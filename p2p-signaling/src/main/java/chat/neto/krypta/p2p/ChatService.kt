package chat.neto.krypta.p2p

import chat.neto.krypta.core.FileStore
import chat.neto.krypta.core.ISignalingService
import chat.neto.krypta.core.KeyExchange
import chat.neto.krypta.core.MessageCipher
import chat.neto.krypta.core.SignalingEvent
import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.core.model.DecodedMessage
import chat.neto.krypta.core.model.Message
import chat.neto.krypta.core.model.MessageContent
import chat.neto.krypta.core.model.MessageStatus
import chat.neto.krypta.core.repository.ContactRepository
import chat.neto.krypta.core.repository.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/** Estado de la conexión WAN (a la DHT vía el nodo bootstrap). */
enum class WanStatus { DISABLED, CONNECTING, CONNECTED, ERROR }

/** Resultado de [ChatService.setBootstrap], para dar feedback inmediato en la UI. */
enum class BootstrapResult { OK, CLEARED, INVALID }

/**
 * Validación *ligera* de un multiaddr de bootstrap: debe empezar por `/` y contener un
 * componente `/p2p/<PeerID>` no vacío (p. ej. `/dns4/krypta.neto.chat/tcp/443/wss/p2p/12D3KooW…`
 * o `/ip4/1.2.3.4/tcp/4001/p2p/12D3KooW…`). No valida el PeerID ni resuelve el host —de eso se
 * encarga `connectDht` al conectar—; solo evita persistir basura obvia y dar feedback inmediato.
 */
fun isValidBootstrapAddr(addr: String): Boolean {
    val a = addr.trim()
    return a.startsWith("/") && a.substringAfter("/p2p/", "").isNotBlank()
}

/**
 * Normaliza una lista de bootstraps (uno por línea): recorta cada línea y descarta las
 * vacías. Devuelve null si alguna línea no vacía es inválida ([isValidBootstrapAddr]).
 * Multi-nodo: el bridge conecta la DHT y reserva relay en TODOS, deposita el buzón en el
 * primero vivo y lo retira de TODOS — un nodo caído deja de ser un punto único de fallo.
 */
fun normalizeBootstrapList(raw: String): String? {
    val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty() || lines.any { !isValidBootstrapAddr(it) }) return null
    return lines.joinToString("\n")
}

/**
 * Orquesta la mensajería de extremo a extremo cerrando el lazo de dominio:
 * cifra (E2EE) → persiste (Room) → envía por la capa de señalización; y a la inversa,
 * recibe → resuelve el contacto por PeerID → persiste. Desde la v8 de la base un `Message`
 * guarda el **sobre en claro** y lo que protege el historial es el cifrado de la base; ver
 * [Message] y `docs/DISENO-ratchet.md` §4 sobre por qué el secreto hacia adelante lo obliga.
 */
@Singleton
class ChatService @Inject constructor(
    private val signaling: ISignalingService,
    private val cipher: MessageCipher,
    private val messages: MessageRepository,
    private val contacts: ContactRepository,
    private val keyExchange: KeyExchange,
    private val rendezvous: RendezvousService,
    private val fileStore: FileStore,
    private val scope: CoroutineScope,
    private val sessions: RatchetSessions,
    private val cpu: CpuContext,
) {
    /**
     * Sin [CpuContext]: el cifrado corre en el hilo de quien llama. Solo para los tests, que así
     * siguen en su reloj virtual; la app usa el constructor de arriba, con `Dispatchers.Default`.
     */
    internal constructor(
        signaling: ISignalingService,
        cipher: MessageCipher,
        messages: MessageRepository,
        contacts: ContactRepository,
        keyExchange: KeyExchange,
        rendezvous: RendezvousService,
        fileStore: FileStore,
        scope: CoroutineScope,
        sessions: RatchetSessions,
    ) : this(signaling, cipher, messages, contacts, keyExchange, rendezvous, fileStore, scope, sessions, CpuContext(EmptyCoroutineContext))


    // --- Piezas (§5 de docs/AUDITORIA-2026-10-12.md) ---------------------------------------
    // ChatService había llegado a 2.000 líneas con siete responsabilidades. Ahora es el lazo
    // cifrar → persistir → enviar/recibir, y delega lo demás. Se construyen aquí, no se inyectan,
    // para que la API pública y los constructores no cambien.

    private val diag = DiagnosticsLog()

    /** Registro de diagnóstico (últimas [LOG_LINES] líneas). */
    val log: StateFlow<List<String>> = diag.lines

    private fun logLine(msg: String) = diag.line(msg)

    private val outbox = Outbox(signaling, cipher, messages, keyExchange, sessions, cpu, diag)
    private val files = FileTransfer(outbox, messages, fileStore, scope, diag)
    private val receipts = ReadReceipts(messages, outbox)
    private val caps = CapabilityNegotiator(contacts, sessions, outbox, scope, diag)

    private val wan = WanLoop(
        signaling, scope, diag,
        object : WanSteps {
            override suspend fun inboxLabels() = this@ChatService.inboxLabels()
            override suspend fun fetchMailbox() = this@ChatService.fetchMailbox()
            override suspend fun logRelayStatus() = this@ChatService.logRelayStatus()
            override suspend fun announceAndFind() = this@ChatService.announceAndFind()
            override suspend fun retryFailed() = this@ChatService.retryFailed()
            override suspend fun announceCapabilities() = caps.announceCapabilities()
        },
    )

    private val contactOps = ContactService(
        contacts, messages, keyExchange, fileStore, sessions, diag,
        object : ContactHooks {
            override suspend fun refreshAllowedPeers() = this@ChatService.refreshAllowedPeers()
            override fun kickWan() = this@ChatService.kickWan()
            override fun forgetPresence(peerId: String) {
                lastFound.remove(peerId)
                _onlinePeers.update { it - peerId }
            }
            override fun content(contact: Contact, message: Message) = this@ChatService.content(contact, message)
        },
    )

    /** Estado de la conexión a la DHT (WAN). */
    val wanStatus: StateFlow<WanStatus> = wan.wanStatus

    private val _onlinePeers = MutableStateFlow<Set<String>>(emptySet())

    /** PeerIDs actualmente conectados (p. ej. encontrados por mDNS en LAN). */
    val onlinePeers: StateFlow<Set<String>> = _onlinePeers.asStateFlow()

    private val _incoming = MutableSharedFlow<Pair<Contact, Message>>(extraBufferCapacity = 64)

    /**
     * Mensajes entrantes ya persistidos (contacto + mensaje). **Observación best-effort**: es
     * un SharedFlow sin replay, así que lo emitido sin suscriptores (o con el búfer lleno) se
     * pierde. Para el **aviso al usuario** no se usa esto sino [setIncomingNotifier], que es
     * un gancho directo y no puede perderse — ver su documentación.
     */
    val incoming: Flow<Pair<Contact, Message>> = _incoming

    @Volatile
    private var incomingNotifier: (suspend (Contact, Message) -> Unit)? = null

    /**
     * Registra el gancho de **aviso de mensaje entrante**, invocado *en el sitio* justo tras
     * persistir cada entrante (mismo patrón que [ISignalingService.setMailboxProcessor]).
     *
     * Existe porque el aviso NO puede depender de que alguien esté coleccionando [incoming]:
     * un `MutableSharedFlow` con `replay = 0` **descarta en silencio** lo emitido sin
     * suscriptores, y eso pasaba de verdad — cuando el OEM mata el proceso y lo revive solo
     * el `HeartbeatReceiver`, el servicio en primer plano (único suscriptor) no existe, el
     * mensaje se retiraba del buzón, se persistía, se confirmaba (borrándolo del nodo) y el
     * aviso se perdía para siempre: "llegó el mensaje pero no sonó nada".
     */
    fun setIncomingNotifier(notifier: suspend (Contact, Message) -> Unit) {
        incomingNotifier = notifier
    }

    /** Publica un entrante ya persistido: flujo de observación + gancho de aviso garantizado. */
    private suspend fun emitIncoming(contact: Contact, message: Message) {
        _incoming.tryEmit(contact to message)
        // El aviso nunca debe tumbar la recepción (y un fallo aquí no debe impedir el ack:
        // el mensaje YA está persistido, que es lo que el buzón confirma).
        runCatching { incomingNotifier?.invoke(contact, message) }
            .onFailure { logLine("aviso no mostrado: ${(it.message ?: "$it").take(60)}") }
    }

    private val _callSignals =
        MutableSharedFlow<Pair<Contact, MessageEnvelope.Decoded.Call>>(extraBufferCapacity = 64)

    /**
     * Señales de llamada entrantes (invite/accept/…), descifradas. Las consume `CallService`,
     * que por eso debe instanciarse al arrancar el proceso (lo hace `KryptaApplication`): sin
     * suscriptor, un `invite` se descarta en silencio y la llamada no suena.
     */
    val callSignals: Flow<Pair<Contact, MessageEnvelope.Decoded.Call>> = _callSignals

    /** Escribe una línea en el panel de diagnóstico desde fuera (p. ej. datos del dispositivo). */
    fun diagnose(msg: String) = logLine(msg)

    init {
        // Sobres del buzón: se procesan AQUÍ, síncronos, y solo se confirma (ack → borrado
        // en el nodo) lo que persistió sin error; un fallo deja el sobre en el buzón y el
        // nodo lo reentrega (dedup por id). Es la garantía que faltaba para los trozos de
        // archivo: antes se ack'eaba antes de persistir y una muerte del proceso los perdía.
        signaling.setMailboxProcessor { peerId, ciphertext, envelopeId, ts, label ->
            // Un sobre ciego no dice de quién viene: lo dice la etiqueta, que se resuelve
            // contra el índice de contactos. Si no se resuelve NO se confirma —el sobre se
            // queda en el nodo y vuelve— porque confirmarlo lo borraría, y un índice
            // momentáneamente desfasado (rotación de semana, contacto recién añadido) habría
            // destruido un mensaje bueno.
            val contact = runCatching { resolveMailboxContact(peerId, label) }.getOrNull()
            if (label.isNotBlank() && contact == null) {
                logLine("buzón: etiqueta ${label.take(8)}… sin contacto conocido (se deja para el próximo ciclo)")
                return@setMailboxProcessor false
            }
            val result = runCatching { onReceived(peerId, ciphertext, envelopeId, ts, contact) }
            result.onSuccess { msg ->
                if (msg != null) logLine("← mensaje de ${short(contact?.peerId ?: peerId)} (buzón)")
            }.onFailure {
                logLine("buzón: sobre ${envelopeId.take(8)} sin persistir (reintentará): ${(it.message ?: "$it").take(60)}")
            }
            result.isSuccess
        }
        // Mensajes por stream directo: también síncronos, porque lo que devuelve es el acuse que
        // se contesta al emisor (`/krypta/msg/1.1.0`). Con `false` el emisor lo deja en el
        // buzón; antes el camino directo no tenía acuse y un fallo al guardar perdía el mensaje
        // (O-7 de docs/AUDITORIA-2026-10-12.md). Un remitente desconocido o bloqueado devuelve
        // null sin error: se acusa como guardado, porque no hay nada que reintentar.
        signaling.setDirectProcessor { peerId, ciphertext ->
            val result = runCatching { onReceived(peerId, ciphertext) }
            result.onSuccess { msg ->
                if (msg != null) logLine("← mensaje de ${short(peerId)}")
            }.onFailure {
                logLine("⚠ entrante directo sin persistir (el emisor lo dejará en el buzón): ${(it.message ?: "$it").take(60)}")
            }
            result.isSuccess
        }
        scope.launch {
            signaling.events.collect { event ->
                when (event) {
                    is SignalingEvent.MessageReceived -> {
                        // runCatching: un fallo puntual (p. ej. Room) no debe matar el
                        // bucle de eventos entero.
                        val msg = runCatching { onReceived(event.fromContactId, event.ciphertext) }
                            .onFailure { logLine("⚠ entrante no persistido: ${(it.message ?: "$it").take(60)}") }
                            .getOrNull()
                        // msg == null puede ser un acuse de lectura (no visible) o un
                        // remitente desconocido; no lo etiquetamos como mensaje.
                        if (msg != null) {
                            logLine("← mensaje de ${short(event.fromContactId)}")
                        }
                    }
                    is SignalingEvent.PeerFound -> {
                        _onlinePeers.update { it + event.contactId }
                        logLine("● en línea: ${short(event.contactId)}")
                    }
                    is SignalingEvent.WakeReceived -> {
                        // El nodo avisa de correo (o el stream de wake reconectó): retirar ya.
                        fetchMailbox()
                    }
                    is SignalingEvent.Failure ->
                        logLine("⚠ ${event.cause.message ?: event.cause}")
                }
            }
        }
    }

    private val startedOnce = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Arranca el nodo y el descubrimiento: host + mDNS (LAN, pruebas) y, si hay un nodo
     * bootstrap configurado, lanza el bucle WAN (DHT + rendezvous, auto-reparable).
     * Idempotente: lo llaman tanto el `ChatViewModel` (UI) como el `KryptaForegroundService`
     * y solo el primero hace el trabajo.
     */
    suspend fun start() {
        if (!startedOnce.compareAndSet(false, true)) return
        // El arranque del host/mDNS es best-effort: si falla (p. ej. en datos móviles, sin
        // interfaz multicast para mDNS) NO debe impedir el WAN, que es el camino principal de
        // Krypta. El bucle WAN es auto-reparable, así que reintentará `connectDht` si hiciera
        // falta. Por eso el WAN se arranca aunque `signaling.start()` haya lanzado.
        runCatching { signaling.start() }.onFailure { logLine("host/mDNS: ${it.message ?: it}") }
        // Cuanto antes, para acortar la ventana en la que el filtro está abierto.
        runCatching { refreshAllowedPeers() }
        runCatching { signaling.bootstrap() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(wan::start)
        // En segundo plano y sin bloquear el arranque: nada depende de que termine, porque el
        // historial se lee igual mientras esté a medias.
        scope.launch { runCatching { unsealHistory() } }
    }

    /**
     * Convierte el historial que aún se guarda cifrado con la clave estática (`encrypted`) al
     * sobre en claro dentro de la base cifrada. Ver [Message] y `docs/DISENO-ratchet.md` §4.1.
     *
     * Va por lotes y **salta lo que no puede convertir** (un contacto que ya no está, una fila
     * corrupta) avanzando el desplazamiento: si no, un solo mensaje ilegible dejaría el resto
     * del historial sin convertir para siempre. Lo saltado se queda como está —y se sigue
     * leyendo igual— y se vuelve a intentar en el siguiente arranque.
     *
     * Es idempotente y barato cuando no queda nada: una consulta que no devuelve filas.
     */
    internal suspend fun unsealHistory(batch: Int = UNSEAL_BATCH) {
        var offset = 0
        var converted = 0
        while (true) {
            val pending = messages.findEncrypted(batch, offset)
            if (pending.isEmpty()) break
            val abiertos = pending.mapNotNull { m ->
                val contact = contacts.findById(m.conversationId) ?: return@mapNotNull null
                val secret = contact.sharedSecret ?: return@mapNotNull null
                runCatching { cipher.decrypt(secret, m.payload) }.getOrNull()
                    ?.let { m.copy(payload = it, encrypted = false) }
            }
            if (abiertos.isNotEmpty()) messages.saveAll(abiertos)
            converted += abiertos.size
            // Los que no se pudieron abrir siguen siendo `encrypted`, así que la siguiente
            // consulta los devolvería otra vez: hay que dejarlos atrás.
            offset += pending.size - abiertos.size
            if (pending.size < batch) break
        }
        if (converted > 0) logLine("🗄 historial convertido: $converted mensaje(s)")
    }

    suspend fun bootstrap(): String? = signaling.bootstrap()

    /**
     * Configura el/los nodos bootstrap WAN (uno por línea) y arranca/reanuda el
     * descubrimiento por rendezvous.
     * - Vacío → modo **solo LAN/mDNS**: persiste `""` y **detiene el bucle WAN en vivo**.
     * - Alguna línea inválida ([normalizeBootstrapList]) → se **rechaza** entera: ni se
     *   persiste ni se arranca.
     * - Válido → persiste la lista normalizada y (re)arranca el WAN, que recoge los nodos
     *   nuevos en su próximo ciclo.
     */
    suspend fun setBootstrap(addr: String): BootstrapResult {
        if (addr.isBlank()) {
            signaling.setBootstrap("")
            wan.stop()
            return BootstrapResult.CLEARED
        }
        val normalized = normalizeBootstrapList(addr)
        if (normalized == null) {
            logLine("bootstrap inválido (esperado /…/p2p/<PeerID>, uno por línea)")
            return BootstrapResult.INVALID
        }
        signaling.setBootstrap(normalized)
        wan.start(normalized)
        return BootstrapResult.OK
    }

    /**
     * Reintenta los mensajes que quedaron **FALLIDOS** (fallaron el envío directo *y* el
     * depósito en el buzón), ahora que hay conexión. Es la "reconciliación al recuperar
     * conexión" que pedía la Fase 4 del plan: hasta ahora un FAILED se quedaba así para
     * siempre y la única salida era que el usuario se diera cuenta y tocara la burbuja.
     *
     * Va al final del ciclo y con tope ([MAX_RETRIES_PER_CYCLE]) a propósito: reintentar no
     * debe competir por el presupuesto con lo que entrega mensajes (el buzón). Reusa
     * [transmit], así que no duplica ids ni vuelve a cifrar.
     */
    internal suspend fun retryFailed() {
        val cutoff = System.currentTimeMillis() - RETRY_MAX_AGE_MS
        val failed = runCatching { messages.findByStatus(MessageStatus.FAILED, MAX_RETRIES_PER_CYCLE) }
            .getOrDefault(emptyList())
            // Solo lo reciente: reenviar solo un mensaje que falló hace semanas sería una
            // sorpresa desagradable (el usuario ya dio la conversación por cerrada). Lo viejo
            // sigue siendo reintentable a mano tocando la burbuja.
            .filter { it.timestamp >= cutoff }
        if (failed.isEmpty()) return
        var sent = 0
        for (message in failed) {
            val contact = contacts.findById(message.conversationId) ?: continue
            if (contact.blocked || contact.sharedSecret == null) continue
            // Un archivo son muchos envíos y no cabe en el presupuesto del paso: va aparte, en
            // el scope, y solo si tiene copia y no se está enviando ya.
            if (files.resendInBackground(contact, message)) continue
            messages.updateStatus(message.id, MessageStatus.PENDING)
            val result = outbox.transmit(contact, message.copy(status = MessageStatus.PENDING), outbox.wireBytes(contact, message))
            if (result.status == MessageStatus.SENT) sent++
        }
        if (sent > 0) logLine("↻ reenviados $sent de ${failed.size} mensaje(s) pendientes")
    }

    /**
     * Un ciclo **puntual y síncrono** (reconecta DHT + retira el buzón), para el "latido" por
     * AlarmManager: en móviles que **suspenden la red en segundo plano** (Transsion/TECNO,
     * etc.) el stream de wake queda dormido; al despertar el sistema el latido descarga el
     * buzón y dispara el aviso. No hace nada si la WAN está desactivada.
     *
     * Arranca antes el host/WAN ([start] es idempotente): si el OEM mató el proceso y lo
     * revivió **solo la alarma**, nadie llamó a `start()`, así que `wan.bootstrapAddr` estaría
     * vacío y el nodo nativo ni existiría — el latido era un no-op justo en el escenario
     * para el que se creó. Tras `start()` se releen los nodos guardados por si acaso.
     */
    suspend fun pollOnce() {
        // Todo bajo [POLL_BUDGET_MS], por debajo de lo que el sistema le da a un receptor de
        // broadcast (~60 s antes de tratarlo como ANR y, en segundo plano, matar el proceso) y
        // del wake lock del latido. Con los plazos del ciclo WAN eran hasta 105 s (O-5 de
        // docs/AUDITORIA-2026-10-12.md): la red de seguridad podía acabar con el proceso que
        // venía a rescatar.
        withTimeoutOrNull(POLL_BUDGET_MS) {
            withTimeoutOrNull(POLL_START_BUDGET_MS) { runCatching { start() } }
            val bootstrap = wan.bootstrapAddr
                ?: runCatching { signaling.bootstrap() }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: return@withTimeoutOrNull
            // Con plazo, y el buzón se retira **pase lo que pase** con el DHT. Antes eran dos
            // llamadas sin plazo y en este orden, así que un dial colgado dejaba al latido sin
            // llegar nunca a `fetchMailbox()`: la red de seguridad fallaba justo en el escenario
            // para el que existe (medido en vivo el 2 sep 2026).
            wan.step("DHT (latido)", POLL_CONNECT_BUDGET_MS) { signaling.connectDht(bootstrap) }
            wan.step("buzón (latido)", POLL_MAILBOX_BUDGET_MS) { fetchMailbox() }
        } ?: logLine("latido: sin terminar en ${POLL_BUDGET_MS / 1000}s, se corta")
    }

    /**
     * Renueva la reserva de Circuit Relay v2 cada ciclo (mantiene el slot activo en el nodo de
     * infra, para que reenvíe conexiones hacia nosotros tras NAT). La dirección /p2p-circuit ya
     * la anuncia el propio host (AddrsFactory en Go). Loguea solo al cambiar el estado.
     */
    private suspend fun logRelayStatus() {
        val res = runCatching { signaling.reserveRelay() }.getOrElse { it.message ?: "error" }
        val state = relayState(res)
        if (state != lastReserveResult) {
            lastReserveResult = state
            logLine("relay: $state")
        }
    }

    @Volatile
    private var lastReserveResult: String? = null

    /**
     * Índice etiqueta(hex) → id de contacto. Se rehace cada vez que se calculan las etiquetas
     * de recepción, o sea en cada ciclo WAN, así que sigue a la rotación semanal y a las altas
     * de contactos sin más ceremonia.
     */
    @Volatile
    private var labelIndex: Map<String, String> = emptyMap()

    /**
     * Etiquetas propias de recepción (una por línea), y de paso refresca [labelIndex].
     *
     * Incluye a los **bloqueados** a propósito: su correo hay que retirarlo para que se borre
     * del nodo —`onReceived` lo descarta sin persistir— en vez de dejarlo ocupando sitio hasta
     * que caduque.
     */
    private suspend fun inboxLabels(): String {
        val me = runCatching { keyExchange.localPeerId() }.getOrNull() ?: return ""
        val all = runCatching { contacts.observeAll().first() }.getOrDefault(emptyList())
            .filter { it.sharedSecret != null }
        val index = HashMap<String, String>(all.size * 2)
        for (c in all) {
            for (label in MailboxLabel.inbox(c.sharedSecret!!, me, c.peerId)) {
                index[MailboxLabel.toHex(label)] = c.id
            }
        }
        labelIndex = index
        return index.keys.joinToString("\n")
    }

    /** Contacto de un sobre del buzón: por etiqueta si es ciego, por PeerID si es del camino viejo. */
    private suspend fun resolveMailboxContact(peerId: String, label: String): Contact? {
        if (label.isBlank()) return contacts.findByPeerId(peerId)
        labelIndex[label]?.let { id -> contacts.findById(id)?.let { return it } }
        // Segundo intento rehaciendo el índice: puede haber rotado la semana o haberse añadido
        // un contacto entre la petición y la respuesta.
        inboxLabels()
        return labelIndex[label]?.let { contacts.findById(it) }
    }

    /** Interno (no privado) para poder ejercitarlo desde los tests del módulo. */
    internal suspend fun announceAndFind() {
        val targets = runCatching { contacts.observeAll().first() }
            .getOrDefault(emptyList())
            // El bloqueado se cae del rendezvous: se deja de anunciar el punto de cita
            // compartido con él, así que ni siquiera puede localizar a este dispositivo.
            .filter { it.sharedSecret != null && !it.blocked }
        // Los mismos contactos que entran en el rendezvous son los que pueden abrirnos
        // conexión. Refrescarlo aquí (cada ciclo WAN) recoge altas, bajas y bloqueos sin
        // depender de que nadie más se acuerde de llamar.
        pushAllowedPeers(targets.map { it.peerId })
        if (targets.isNotEmpty()) logLine("rendezvous: anunciando a ${targets.size} contacto(s)")
        for (contact in targets) {
            // Ventana de solape al cambiar de día (ver RendezvousService.rendezvousWindow):
            // en el cambio de fecha UTC se anuncian y buscan las dos claves contiguas, para
            // que dos móviles que roten con unos segundos de diferencia sigan encontrándose.
            val keys = rendezvous.rendezvousWindow(contact.sharedSecret!!)
            runCatching {
                var hit = false
                for (rdv in keys) {
                    signaling.announce(rdv)
                    if (!hit) hit = signaling.findPeers(rdv).any { it == contact.peerId }
                }
                if (hit != lastFound[contact.peerId]) {
                    lastFound[contact.peerId] = hit
                    logLine(if (hit) "rendezvous: ✓ encontrado ${short(contact.peerId)}" else "rendezvous: aún no encuentro ${short(contact.peerId)}")
                }
            }.onFailure { logLine("rendezvous ${short(contact.peerId)}: ${it.message}") }
        }
    }

    /**
     * PeerID de cada nodo de una lista de bootstrap (uno por línea). Tolera la forma con
     * `/p2p-circuit` detrás, que aparece en las direcciones de relay.
     */
    internal fun bootstrapPeerIds(raw: String): List<String> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                line.substringAfterLast("/p2p/", "").substringBefore("/").ifBlank { null }
            }
            .distinct()
            .toList()

    /**
     * Fija en el transporte quién puede **abrirnos** conexión: estos contactos y los nodos.
     *
     * Los **bloqueados quedan fuera** a propósito: además de no recibir nada, dejan de poder
     * sacar nuestra IP. Y los nodos entran siempre, porque el relay y AutoNAT necesitan poder
     * hablarnos de vuelta. Ver `docs/security-model.md` §5.1.
     */
    private suspend fun pushAllowedPeers(contactIds: List<String>) {
        val nodes = bootstrapPeerIds(
            wan.bootstrapAddr ?: runCatching { signaling.bootstrap() }.getOrNull().orEmpty(),
        )
        val lista = (contactIds + nodes).distinct()
        runCatching { signaling.setAllowedPeers(lista.joinToString("\n")) }
            .onFailure { logLine("filtro de conexiones: ${it.message}") }
        // Se registra solo cuando cambia, para no llenar el panel: el ciclo WAN pasa por aquí
        // cada 30-180 s. Con 0 el filtro está ABIERTO, y eso hay que verlo, no deducirlo.
        if (lista.size != lastAllowedCount) {
            lastAllowedCount = lista.size
            val estado = runCatching { signaling.allowedPeersStatus() }.getOrDefault("")
            logLine("filtro de conexiones: ${lista.size} permitido(s)${if (estado.isNotBlank()) " · $estado" else ""}")
        }
    }

    @Volatile
    private var lastAllowedCount = -1

    /**
     * ¿Descubrimiento en la red local (mDNS) activado? Apagado de serie: anunciarse en la WiFi
     * delata el PeerID a quien comparta la red (`docs/security-model.md` §5.1), y el
     * descubrimiento real de Krypta es WAN por DHT + rendezvous.
     */
    suspend fun lanDiscovery(): Boolean = runCatching { signaling.lanDiscovery() }.getOrDefault(false)

    /** Activa o desactiva el mDNS. Surte efecto en el momento, en los dos sentidos. */
    suspend fun setLanDiscovery(enabled: Boolean) {
        runCatching { signaling.setLanDiscovery(enabled) }
            .onFailure { logLine("descubrimiento LAN: ${it.message}") }
            .onSuccess { logLine(if (enabled) "descubrimiento LAN activado" else "descubrimiento LAN desactivado") }
    }

    /** Recalcula la lista leyendo los contactos (para cuando cambian fuera del ciclo WAN). */
    internal suspend fun refreshAllowedPeers() {
        val ids = runCatching { contacts.observeAll().first() }
            .getOrDefault(emptyList())
            .filter { !it.blocked }
            .map { it.peerId }
        pushAllowedPeers(ids)
    }

    // Concurrente: lo escribe el bucle WAN y lo limpian bloquear y borrar desde la UI (O-10).
    private val lastFound = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Retira los mensajes pendientes del buzón del nodo (entrega offline). Cada mensaje
     * llega por el mismo flujo de eventos que los directos y se persiste allí; aquí solo
     * se dispara la retirada y se loguea el total.
     */
    private suspend fun fetchMailbox() {
        runCatching { signaling.fetchMailbox(inboxLabels()) }
            .onSuccess { n ->
                lastMailboxError = null
                if (n > 0) logLine("buzón: $n mensaje(s) recogido(s)")
            }
            .onFailure {
                // Solo al cambiar, para no llenar el diagnóstico en cada ciclo del wanLoop.
                val msg = it.message?.take(80) ?: it.toString()
                if (msg != lastMailboxError) {
                    lastMailboxError = msg
                    logLine("buzón: $msg")
                }
            }
    }

    @Volatile
    private var lastMailboxError: String? = null

    /**
     * Cifra [plaintext] para [contact], lo persiste (PENDING) y lo envía. Si el envío
     * directo falla (peer offline / NAT sin ruta), cae al **buzón** store-and-forward del
     * nodo (entrega offline) → `SENT`. Solo si el buzón también falla queda **FAILED** —
     * nunca se propaga la excepción, para no tumbar la app. Devuelve el estado final.
     */
    suspend fun send(contact: Contact, plaintext: ByteArray, replyTo: String? = null): Message {
        outbox.requireNotBlocked(contact)
        // Precondición, no valor: quién cifra y con qué depende ya de [seal].
        requireNotNull(contact.sharedSecret) { "contact ${contact.id} has no shared secret" }
        // El ciphertext cifra un SOBRE que lleva el id del mensaje, para que el receptor
        // pueda acusar su lectura citándolo (marca de leído). Si es una respuesta, ese sobre
        // va envuelto en uno de cita, que solo lleva el id del mensaje citado.
        val msgId = UUID.randomUUID().toString()
        val envelope = MessageEnvelope.wrapReply(replyTo, MessageEnvelope.encodeText(msgId, plaintext))
        val out = outbox.sealAndPersist(contact, envelope) {
            Message(
                id = msgId,
                conversationId = contact.id,
                senderId = SELF,
                // Se guarda el **sobre en claro**, no lo que va por la red: ver [Message]. Lo
                // que protege el historial es el cifrado de la base.
                payload = envelope,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.PENDING,
            )
        }
        return outbox.transmit(contact, out.message, out.wire)
    }

    /**
     * Envía una **imagen** (JPEG ya comprimido, ≤ límite del buzón) a [contact]. Igual que
     * [send] pero el sobre es de tipo imagen; viaja por el mismo camino (directo → buzón).
     */
    suspend fun sendImage(contact: Contact, jpeg: ByteArray, replyTo: String? = null): Message {
        outbox.requireNotBlocked(contact)
        // Precondición, no valor: quién cifra y con qué depende ya de [seal].
        requireNotNull(contact.sharedSecret) { "contact ${contact.id} has no shared secret" }
        val msgId = UUID.randomUUID().toString()
        val envelope = MessageEnvelope.wrapReply(replyTo, MessageEnvelope.encodeImage(msgId, jpeg))
        val out = outbox.sealAndPersist(contact, envelope) {
            Message(
                id = msgId,
                conversationId = contact.id,
                senderId = SELF,
                payload = envelope,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.PENDING,
            )
        }
        return outbox.transmit(contact, out.message, out.wire)
    }

    /**
     * Envía una señal de llamada E2EE a [contact] (directo → buzón). [kind] ∈
     * invite/accept/reject/hangup/busy. Lleva timestamp para descartar invites rancios.
     */
    suspend fun sendCallSignal(
        contact: Contact,
        kind: String,
        callId: String,
        key: ByteArray? = null,
        peerNegotiates: Boolean = contact.peerProtocol >= RATCHET_MIN_PROTOCOL,
    ) {
        // La mitad de clave solo viaja hacia quien haya anunciado que la entiende: un cliente
        // anterior parte la cabecera `C` en tres trozos y descartaría la señal entera, con lo
        // que la llamada no llegaría a sonar. Con el resto se sigue por el camino antiguo.
        // [peerNegotiates] lo fuerza quien contesta a un invite que traía mitad: eso ya demuestra
        // que el otro la entiende, conste lo que conste.
        val negociada = key?.takeIf { peerNegotiates }
        outbox.sendRaw(contact, MessageEnvelope.encodeCall(kind, callId, System.currentTimeMillis(), negociada))
    }

    /**
     * Persiste una fila local "📞 Llamada perdida" en el chat de [contact] y la emite, **una sola
     * vez por llamada** (H-7). El id sale de `(contacto, callId)`: un invite rancio que el buzón
     * devuelva días después, o el mismo invite tras colgar mientras sonaba, encuentra la fila hecha
     * y no guarda ni avisa otra vez. Hay que mirar antes de guardar porque `save` es un upsert y la
     * volvería a marcar como no leída. El `callId` lo elige el otro extremo, así que nunca se usa
     * tal cual como id, que es la clave primaria de todos los mensajes (ver [missedCallId]).
     * Límite: vaciar el chat borra la fila y, con ella, esa memoria.
     */
    suspend fun recordMissedCall(contact: Contact, callId: String) {
        contact.sharedSecret ?: return
        val id = missedCallId(contact.id, callId)
        if (messages.findById(id) != null) return
        val message = Message(
            id = id,
            conversationId = contact.id,
            senderId = contact.id,
            payload = MessageEnvelope.encodeText(id, MISSED_CALL_TEXT.toByteArray()),
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.DELIVERED,
        )
        messages.save(message)
        emitIncoming(contact, message)
        logLine("📞 llamada perdida de ${short(contact.peerId)}")
    }

    /**
     * Fila «sesión reiniciada» (R3, `docs/DISENO-ratchet.md` §1.13): [contact] nos hizo adoptar otro
     * linaje en una sesión que ya había salido de la época 0. Lo normal es que haya reinstalado o
     * restaurado; si no, alguien con su identidad o la nuestra forzó una sesión (W-4), y esto es lo
     * único que lo deja ver. Una sola fila por linaje, y sin notificación: no es un mensaje suyo.
     */
    private suspend fun recordSessionRestart(contact: Contact, lineage: Long) {
        val id = sessionRestartId(contact.id, lineage)
        if (messages.findById(id) != null) return
        messages.save(
            Message(
                id = id,
                conversationId = contact.id,
                senderId = contact.id,
                payload = MessageEnvelope.encodeText(id, SESSION_RESTART_TEXT.toByteArray()),
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.DELIVERED,
            ),
        )
        logLine("🔄 sesión con ${short(contact.peerId)} reiniciada (linaje $lineage)")
    }

    /**
     * Sonda del gate de llamadas (Fase 7a): mide el RTT al nodo bootstrap — ida y vuelta a
     * través de Cloudflare ≈ latencia one-way de un frame de audio relayed entre dos
     * móviles — y escribe el resultado en el diagnóstico. Nunca lanza.
     */
    suspend fun latencyProbe(count: Int = 50, intervalMs: Int = 20) {
        logLine("📞 midiendo RTT al nodo ($count pings/${intervalMs} ms)…")
        runCatching { signaling.pingProbe(count, intervalMs) }
            .onSuccess { logLine("📞 RTT nodo: $it") }
            .onFailure { logLine("📞 sonda de latencia falló: ${(it.message ?: "$it").take(80)}") }
    }

    /**
     * Reintenta enviar un mensaje **FALLIDO** (o cualquiera por id): lo vuelve a PENDING y
     * repite el camino directo→buzón→FAILED con el **mismo id** (el receptor deduplica por él,
     * así que no se duplica). Desde la v8 el sobre se guarda en claro, así que reintentar
     * **vuelve a cifrarlo**; una fila anterior, que aún guarda su ciphertext, se reenvía tal
     * cual. Devuelve el mensaje con su estado final, o null si no existe.
     */
    suspend fun retry(contact: Contact, messageId: String): Message? {
        outbox.requireNotBlocked(contact)
        val message = messages.findById(messageId) ?: return null
        // Un archivo no se reintenta reenviando su fila: la fila es el descriptor local.
        files.retry(contact, message)?.let { return it }
        messages.updateStatus(messageId, MessageStatus.PENDING)
        logLine("↻ reintentando a ${short(contact.peerId)}")
        return outbox.transmit(contact, message.copy(status = MessageStatus.PENDING), outbox.wireBytes(contact, message))
    }

    /**
     * Procesa un ciphertext entrante: resuelve el contacto por [peerId], lo abre y ramifica.
     * Un **acuse de lectura** marca nuestros salientes como READ (no crea mensaje visible); un
     * **anuncio de capacidad** apunta qué versión habla el contacto; un **texto** se persiste
     * DELIVERED con el id del sobre (dedup ante reentregas). Devuelve el `Message` persistido,
     * o null si era un acuse, un anuncio, algo ilegible o un remitente desconocido.
     *
     * Acepta **las dos formas**: el sobre de ratchet (v2) y el cifrado con la clave estática
     * (v1). El byte de versión solo decide en qué orden se intentan — quien decide de verdad
     * es el AEAD, porque un ciphertext v1 son bytes arbitrarios y puede empezar igual.
     */
    suspend fun onReceived(
        peerId: String,
        ciphertext: ByteArray,
        mailboxId: String? = null,
        ts: Long? = null,
        resolved: Contact? = null,
    ): Message? {
        // `resolved` llega del buzón ciego, donde el remitente no viaja y quien identifica al
        // contacto es la etiqueta. Por stream directo se sigue resolviendo por PeerID.
        val contact = resolved ?: contacts.findByPeerId(peerId) ?: return null
        // Bloqueado: se descarta **antes de descifrar**, así que no se persiste, no avisa y
        // no llega a CallService (nada de timbre ni de "llamada perdida"). Devolver null es
        // además lo que ack'ea el sobre en el buzón: se borra del nodo en vez de reentregarse
        // en cada ciclo ocupando el cupo del destinatario. El bloqueado no se entera: sus
        // envíos le quedan como enviados, igual que si estuvieras desconectado.
        if (contact.blocked) return null
        if (contact.sharedSecret == null) return null

        val message =
            if (Ratchet.looksLikeRatchet(ciphertext)) openRatchet(contact, ciphertext, mailboxId, ts)
            else openLegacy(contact, ciphertext, mailboxId, ts)
        // El aviso al usuario va FUERA de la transacción del ratchet: dentro alargaría el
        // bloqueo de la base por algo que no tiene nada que ver con persistir.
        message?.let { emitIncoming(contact, it) }
        return message
    }

    /**
     * Camino v2 (ratchet). Persiste **dentro de la misma transacción** que el avance del
     * ratchet (ver [RatchetSessions]); una reentrega ya procesada se reconoce y se descarta en
     * vez de parecer basura, porque su clave ya está gastada.
     *
     * Si no se puede abrir se intenta el camino v1 antes de rendirse: la cabecera es una
     * pista, no una garantía.
     */
    private suspend fun openRatchet(
        contact: Contact,
        ciphertext: ByteArray,
        mailboxId: String?,
        ts: Long?,
    ): Message? {
        // Solo «no se pudo abrir» ([RatchetException], el único error de `Ratchet.decrypt`) cae al
        // camino v1. Cualquier otro —Room al guardar, el staging de un trozo, leer el estado—
        // **se propaga**: así el buzón no confirma y el nodo lo reentrega. Hasta el 12 oct 2026
        // un `runCatching` lo tragaba todo, caía a `openLegacy` (que no abre un sobre de
        // ratchet), devolvía null y el sobre se confirmaba y se perdía (O-1 de
        // docs/AUDITORIA-2026-10-12.md).
        val recibido = try {
            sessions.receive(contact, ciphertext, kdf4(contact)) { plain -> persistEnvelope(contact, plain, mailboxId, ts) }
        } catch (e: RatchetException) {
            return openLegacy(contact, ciphertext, mailboxId, ts, parecíaRatchet = true)
        }
        return when (recibido) {
            is RatchetSessions.Received.Opened -> {
                caps.learnFromRatchet(contact, ciphertext)
                recibido.restartedTo?.let { recordSessionRestart(contact, it) }
                recibido.value
            }
            // Ya procesado: devolver null lo ack'ea en el buzón, que es lo correcto — está
            // entregado y su clave, gastada.
            RatchetSessions.Received.Duplicate -> null
        }
    }

    /** Camino v1: clave estática del contacto. */
    private suspend fun openLegacy(
        contact: Contact,
        ciphertext: ByteArray,
        mailboxId: String?,
        ts: Long?,
        parecíaRatchet: Boolean = false,
    ): Message? {
        val plain = runCatching { cipher.decrypt(requireNotNull(contact.sharedSecret), ciphertext) }
            .getOrNull()
        if (plain == null) {
            // No se puede abrir por ninguna vía. Antes se persistía el ciphertext como si fuera
            // texto legado, lo que pintaba una burbuja de basura que no ayuda a nadie.
            logLine(
                "⚠ mensaje ilegible de ${short(contact.peerId)} (descartado, " +
                    "${if (parecíaRatchet) "venía con cabecera de ratchet" else "sin cabecera de ratchet"})",
            )
            // Si venía con cabecera de ratchet, lo más probable es que sus linajes estén
            // desincronizados y el otro esté escribiendo en uno que aquí ya no vale. Nosotros
            // sí lo sabemos: reengancharlo (ver [rehook]).
            if (parecíaRatchet && !caps.relayIfBehind(contact, ciphertext)) caps.rehook(contact)
            return null
        }
        return persistEnvelope(contact, plain, mailboxId, ts)
    }

    /**
     * Ramifica por el contenido de un sobre **ya descifrado** y persiste lo que corresponda.
     * No avisa al usuario: eso lo hace [onReceived] al salir, fuera de la transacción.
     */
    private suspend fun persistEnvelope(
        contact: Contact,
        plain: ByteArray,
        mailboxId: String?,
        ts: Long?,
    ): Message? {
        val envelope = MessageEnvelope.decode(plain)
        // La cita es un envoltorio: se abre aquí para que el resto ramifique por el contenido
        // real. El id citado solo hace falta en lo que crea burbuja (archivo); el texto y la
        // imagen lo llevan en su propio sobre, que es lo que se persiste.
        val replyTo = (envelope as? MessageEnvelope.Decoded.Reply)?.replyTo
        val decoded = (envelope as? MessageEnvelope.Decoded.Reply)?.inner ?: envelope

        when (decoded) {
            is MessageEnvelope.Decoded.Read -> {
                receipts.markOutgoingRead(contact.id, decoded.ids)
                return null
            }
            is MessageEnvelope.Decoded.Hello -> {
                // Nos dice qué versión habla y qué tiene apuntado de nosotros. No crea burbuja.
                caps.onHello(contact, decoded)
                return null
            }
            is MessageEnvelope.Decoded.FileMeta -> {
                val f = fileStore.onMeta(
                    decoded.fileId,
                    chat.neto.krypta.core.IncomingFileMeta(
                        decoded.name, decoded.mime, decoded.size, decoded.totalChunks, replyTo,
                    ),
                )
                return f?.let { persistFile(contact, decoded.fileId, it) }
            }
            is MessageEnvelope.Decoded.FileChunk -> {
                val f = fileStore.onChunk(decoded.fileId, decoded.index, decoded.bytes)
                return f?.let { persistFile(contact, decoded.fileId, it) }
            }
            is MessageEnvelope.Decoded.Call -> {
                _callSignals.tryEmit(contact to decoded)
                return null
            }
            // Un descriptor es la burbuja **local** de un archivo, nunca algo que viaje: solo lo
            // mandaba el reintento defectuoso de antes del 21 sep 2026, y persistirlo pintaba un
            // archivo que no existe. Además su ruta la elige quien envía, y la UI la usaría para
            // leer del almacén propio. Se descarta (y se confirma, para que no vuelva).
            is MessageEnvelope.Decoded.FileDescriptor -> {
                logLine("⚠ descriptor de archivo de ${short(contact.peerId)} descartado: no trae el archivo")
                return null
            }
            // Sobre de un tipo que esta versión no entiende: ignorar (no crear burbuja) es
            // mejor que persistir algo que no se sabe pintar.
            is MessageEnvelope.Decoded.Unsupported -> return null
            else -> Unit
        }
        val msgId = when (decoded) {
            is MessageEnvelope.Decoded.Text -> decoded.id
            is MessageEnvelope.Decoded.Image -> decoded.id
            else -> null
        } ?: mailboxId ?: UUID.randomUUID().toString()
        // El id lo elige el emisor y es la clave primaria (Room guarda con REPLACE), así que
        // un id que ya existe **no se reescribe nunca**. Cubre tres casos:
        //  - el eco de un mensaje propio (un contacto que apunta a tu propio PeerID): no
        //    sobrescribas tu copia saliente con la versión entrante;
        //  - un id que ya pertenece a OTRA conversación: nadie debe poder pisar, ni por error
        //    ni a propósito, el mensaje de un tercero (auditoría A-9);
        //  - el **mismo** mensaje otra vez: con el acuse de la entrega directa (O-7), uno que
        //    se guardó pero cuyo acuse se perdió vuelve por el buzón. Ya está; reescribirlo lo
        //    volvía a marcar como no leído y volvía a sonar.
        if (messages.findById(msgId) != null) return null
        val message = Message(
            id = msgId,
            conversationId = contact.id,
            senderId = contact.id,
            // El sobre ya descifrado: guardar el ciphertext de la red dejaría de servir en
            // cuanto la clave del mensaje sea de un solo uso (ratchet). Ver [Message].
            payload = plain,
            timestamp = ts ?: System.currentTimeMillis(),
            status = MessageStatus.DELIVERED,
        )
        messages.save(message)
        return message
    }

    /**
     * Persiste un archivo ya reensamblado como Message (descriptor con path). **No avisa**: el
     * aviso lo da [onReceived] con lo que devuelva esta rama, y hacerlo aquí también avisaría
     * dos veces del mismo archivo.
     */
    private suspend fun persistFile(contact: Contact, fileId: String, f: chat.neto.krypta.core.AssembledFile): Message? {
        // Misma guarda que en onReceived: ni el eco de un envío propio ni un id que ya es de
        // otra conversación pueden sobrescribir nada.
        val existing = messages.findById(fileId)
        if (existing != null && (existing.senderId == SELF || existing.conversationId != contact.id)) {
            return null
        }
        val descriptor = MessageEnvelope.wrapReply(
            f.replyTo,
            MessageEnvelope.encodeFileDescriptor(f.name, f.mime, f.size, f.path),
        )
        val message = Message(
            id = fileId,
            conversationId = contact.id,
            senderId = contact.id,
            payload = descriptor,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.DELIVERED,
        )
        messages.save(message)
        logLine("← archivo de ${short(contact.peerId)}: ${f.name}")
        return message
    }

    /** Qué protección lleva hoy lo que se le envía a un contacto. */
    enum class Protection {
        /** Clave estática (v1): la versión del contacto aún no consta. */
        STATIC,
        /** Ratchet por épocas, sin relleno. */
        RATCHET,
        /** Ratchet por épocas y tamaño relleno por tramos. */
        RATCHET_PADDED,
    }

    /**
     * El modo **efectivo** con [contact], para enseñárselo al usuario. La primera negociación es
     * oportunista: un nodo que tire los anuncios iniciales puede dejar a la pareja en v1, y lo
     * único que lo hace visible es esto (A-07 de la auditoría interna del 11 oct 2026, y P9 de la
     * especificación).
     */
    fun protectionOf(contact: Contact): Protection = when {
        pads(contact) -> Protection.RATCHET_PADDED
        usesRatchet(contact) -> Protection.RATCHET
        else -> Protection.STATIC
    }

    fun observeConversation(conversationId: String): Flow<List<Message>> =
        messages.observeConversation(conversationId)

    /** Los [limit] más recientes de la conversación: la ventana que pinta el chat (O-9). */
    fun observeRecent(conversationId: String, limit: Int): Flow<List<Message>> =
        messages.observeRecent(conversationId, limit)

    /** Tamaño de ventana que incluye el mensaje [id] (0 si no es de esa conversación). */
    suspend fun windowToInclude(conversationId: String, id: String): Int =
        messages.countSince(conversationId, id)

    /** Un mensaje de [conversationId] por id, o null (citas fuera de la ventana cargada). */
    suspend fun findMessage(conversationId: String, id: String): Message? =
        messages.findById(id)?.takeIf { it.conversationId == conversationId }

    /** Último mensaje por conversación (lista de conversaciones). */
    fun observeLastMessages(): Flow<List<Message>> = messages.observeLastMessages()

    /** Entrantes sin ver por conversationId (badge de no leídos). */
    fun observeUnreadCounts(): Flow<Map<String, Int>> = messages.observeUnreadCounts()

    /**
     * Descifra el contenido de [message] para mostrarlo (solo aquí aparece el texto plano).
     * Desenvuelve el sobre y devuelve el cuerpo de texto; si el mensaje es previo al sobre
     * (legado), devuelve el texto descifrado tal cual. Para imágenes usa [content].
     */
    fun decrypt(contact: Contact, message: Message): ByteArray {
        val plain = outbox.envelopeOf(contact, message)
        val decoded = MessageEnvelope.decode(plain)
        return when (val d = (decoded as? MessageEnvelope.Decoded.Reply)?.inner ?: decoded) {
            is MessageEnvelope.Decoded.Text -> d.body
            null -> plain // legado: mensaje anterior al sobre
            else -> ByteArray(0)
        }
    }

    /**
     * Descifra [message] y lo clasifica en [MessageContent] (texto o imagen) para pintarlo.
     * Un sobre legado (sin tipo) se trata como texto. Para saber además a qué mensaje
     * responde, usa [decodeMessage].
     */
    fun content(contact: Contact, message: Message): MessageContent =
        decodeMessage(contact, message).content

    /**
     * Descifra [message] y devuelve su contenido **y la cita**, si es una respuesta. Descifra
     * una sola vez: la UI necesita las dos cosas por mensaje al pintar la conversación.
     */
    fun decodeMessage(contact: Contact, message: Message): DecodedMessage {
        val plain = outbox.envelopeOf(contact, message)
        val decoded = MessageEnvelope.decode(plain)
        val replyTo = (decoded as? MessageEnvelope.Decoded.Reply)?.replyTo
        val content = when (val d = (decoded as? MessageEnvelope.Decoded.Reply)?.inner ?: decoded) {
            is MessageEnvelope.Decoded.Text -> MessageContent.Text(String(d.body))
            is MessageEnvelope.Decoded.Image -> MessageContent.Image(d.bytes)
            is MessageEnvelope.Decoded.FileDescriptor ->
                MessageContent.File(d.name, d.mime, d.size, d.path)
            is MessageEnvelope.Decoded.Read, is MessageEnvelope.Decoded.Hello ->
                MessageContent.Text("") // no crean burbuja
            is MessageEnvelope.Decoded.FileMeta, is MessageEnvelope.Decoded.FileChunk,
            is MessageEnvelope.Decoded.Call ->
                MessageContent.Text("") // no deberían persistirse como Message
            is MessageEnvelope.Decoded.Unsupported, is MessageEnvelope.Decoded.Reply ->
                MessageContent.Text(UNSUPPORTED_TEXT)
            null -> MessageContent.Text(String(plain)) // legado
        }
        return DecodedMessage(content, replyTo)
    }

    /** Texto para la notificación de [message] (para imágenes/archivos, un rótulo). */
    fun notificationText(contact: Contact, message: Message): String =
        when (val c = runCatching { content(contact, message) }.getOrNull()) {
            is MessageContent.Image -> "📷 Foto"
            is MessageContent.File -> when {
                c.mime.startsWith("audio/") -> "🎤 Nota de voz"
                // Un GIF viaja por el camino de archivos (para no perder la animación), pero
                // para el usuario no es "un adjunto llamado archivo.gif".
                c.mime in ANIMATED_IMAGE_MIMES -> "🎞 GIF"
                else -> "📎 ${c.name}"
            }
            is MessageContent.Text -> c.text.ifBlank { "Mensaje nuevo" }
            null -> "Mensaje nuevo"
        }

    // --- Delegados: la API de siempre, ahora en sus piezas -----------------------------------

    /**
     * Adelanta el próximo ciclo del bucle WAN (reconexión + rendezvous + buzón) sin esperar
     * los 30 s — p. ej. cuando el sistema notifica un cambio de red (WiFi↔datos). No-op si
     * la WAN está desactivada.
     */
    fun kickWan() = wan.kick()

    /** Ver [CapabilityNegotiator.announceCapabilities]. Interno para los tests del módulo. */
    internal suspend fun announceCapabilities() = caps.announceCapabilities()

    /** Ver [CapabilityNegotiator.reannounceForCall]. */
    fun reannounceForCall(contact: Contact) = caps.reannounceForCall(contact)

    /** Envía un archivo troceado. Ver [FileTransfer.sendFile]. */
    suspend fun sendFile(
        contact: Contact,
        name: String,
        mime: String,
        bytes: ByteArray,
        localPath: String? = null,
        replyTo: String? = null,
    ): Message = files.sendFile(contact, name, mime, bytes, localPath, replyTo)

    /** Acusa la lectura de lo que estaba sin leer. Ver [ReadReceipts.markConversationRead]. */
    suspend fun markConversationRead(contact: Contact) = receipts.markConversationRead(contact)

    fun observeContacts(): Flow<List<Contact>> = contactOps.observeContacts()

    /** PeerID de este dispositivo (para compartir con quien te quiera añadir). */
    fun myPeerId(): String = contactOps.myPeerId()

    /** Contacto guardado con ese PeerID, si lo hay (el PeerID es la clave primaria). */
    suspend fun findContact(peerId: String): Contact? = contactOps.findContact(peerId)

    /** Ver [ContactService.addContact]. */
    suspend fun addContact(displayName: String, peerId: String): Contact = contactOps.addContact(displayName, peerId)

    /** Número de seguridad anti-MITM con [contact]. Ver [ContactService.safetyNumber]. */
    fun safetyNumber(contact: Contact): String = contactOps.safetyNumber(contact)

    /** Marca (o desmarca) [contact] como verificado. */
    suspend fun setVerified(contact: Contact, verified: Boolean) = contactOps.setVerified(contact, verified)

    /** Bloquea (o desbloquea) a [contact]. Ver [ContactService.setBlocked]. */
    suspend fun setBlocked(contact: Contact, blocked: Boolean) = contactOps.setBlocked(contact, blocked)

    /** Vacía el chat de [contact] solo en este dispositivo. Ver [ContactService.clearConversation]. */
    suspend fun clearConversation(contact: Contact) = contactOps.clearConversation(contact)

    /** Elimina [contact] y todo su chat. Ver [ContactService.deleteContact]. */
    suspend fun deleteContact(contact: Contact) = contactOps.deleteContact(contact)

    // `internal` (no `private`): los tests del módulo fijan la versión de protocolo que se
    // anuncia, y una constante de protocolo que nadie comprueba se desincroniza sola.
    internal companion object {
        const val SELF = "self"
        /** Imágenes animadas: viajan como archivo pero se rotulan/pintan como imagen. */
        val ANIMATED_IMAGE_MIMES = setOf("image/gif", "image/webp")
        // Texto de la fila local de llamada perdida (no viaja por la red).
        const val MISSED_CALL_TEXT = "📞 Llamada perdida"

        /**
         * Líneas del Diagnóstico que se guardan (en memoria, se pierden al cerrar la app). Eran 30, y
         * un archivo por trozos o una tanda del buzón las desplazaba en segundos (12 oct 2026).
         */
        const val LOG_LINES = 150

        /** Texto de la fila de R3. Va en primera persona del sistema, no del contacto. */
        const val SESSION_RESTART_TEXT =
            "🔄 La sesión con este contacto se ha reiniciado. Si no ha reinstalado ni restaurado " +
                "Krypta, verifica su identidad."

        /** Id de la fila de R3: una por (contacto, linaje), así una reentrega no la repite. */
        fun sessionRestartId(contactId: String, lineage: Long): String =
            MessageDigest.getInstance("SHA-256")
                .digest("krypta-session-restart-v1\u0000$contactId\u0000$lineage".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /**
         * Id de la fila de llamada perdida de `(contacto, callId)`: el mismo cada vez, y dentro de
         * un hash con el contacto para que un `callId` elegido por el otro no pueda coincidir con
         * el id de ningún otro mensaje, de esta conversación o de otra.
         */
        fun missedCallId(contactId: String, callId: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("krypta-missed-call-v1\u0000$contactId\u0000$callId".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        // Sobre válido de un tipo que esta versión no conoce (cliente más nuevo).
        const val UNSUPPORTED_TEXT = "[mensaje no compatible con esta versión]"
        // Bucle ágil cuando el wake no está (sonda buzón + redescubre). Bien por debajo del
        // corte por inactividad de Cloudflare (~100 s) para sanar la wss de la DHT a tiempo.
        const val REDISCOVER_MS = 30_000L
        // Bucle relajado cuando el wake empuja la entrega: renueva relay (TTL ~1 h) y
        // redescubre cada 3 min; el buzón sigue como red de seguridad. Menos despertares.
        const val WAKE_IDLE_MS = 180_000L
        // Plazos por paso del ciclo WAN. Existen porque el bucle es secuencial: sin ellos una
        // sola llamada de red colgada para la entrega entera (medido en vivo el 2 sep 2026).
        // El presupuesto del ciclo es menor que el intervalo relajado, así que un ciclo malo
        // nunca puede solaparse con el siguiente ni "comerse" varios turnos.
        const val CONNECT_BUDGET_MS = 30_000L
        const val MAILBOX_BUDGET_MS = 45_000L
        const val RELAY_BUDGET_MS = 30_000L
        const val RENDEZVOUS_BUDGET_MS = 45_000L
        const val RETRY_BUDGET_MS = 30_000L
        const val CYCLE_BUDGET_MS = 150_000L

        // Latido (O-5): la suma cabe en [POLL_BUDGET_MS], y ese en el wake lock del receptor
        // (`HeartbeatReceiver.WAKELOCK_MS`) y en el plazo de un broadcast en segundo plano.
        const val POLL_START_BUDGET_MS = 10_000L
        const val POLL_CONNECT_BUDGET_MS = 15_000L
        const val POLL_MAILBOX_BUDGET_MS = 20_000L
        const val POLL_BUDGET_MS = 45_000L
        // Reintentos de FALLIDOS por ciclo: suficiente para vaciar una racha corta sin
        // convertir el ciclo en una tormenta de envíos tras una caída larga.
        const val MAX_RETRIES_PER_CYCLE = 10
        /** Ids por acuse: ~3,7 KB, dentro del tramo de 4 KiB del relleno (O-2). */
        const val RECEIPT_BATCH = 100
        /** Acuses pendientes de reintento por contacto, como mucho. */
        const val MAX_PENDING_RECEIPTS = 1000
        /**
         * Versión de protocolo que habla este cliente y que se anuncia a cada contacto (sobre
         * `V`). La 1 es implícita: no la anuncia nadie, es "lo que había antes".
         *
         * **Esta constante no es el umbral de ninguna capacidad**, y la distinción cuesta un
         * fallo silencioso si se olvida: cuando subió a 3 (relleno por tramos), comparar
         * `peerProtocol >= PROTOCOL_VERSION` habría **apagado el ratchet y la negociación de
         * clave de llamada** con todos los contactos que anunciaron 2 — una regresión de
         * seguridad por añadir una función. Cada capacidad tiene su propio mínimo.
         */
        const val PROTOCOL_VERSION = 4

        /**
         * Mínimo para el **ratchet** y para la **clave de llamada negociada**: las dos llegaron
         * con la v2, así que quien anuncie 2 o más las entiende.
         */
        const val RATCHET_MIN_PROTOCOL = 2

        /**
         * Mínimo para el **relleno por tramos** (ver [Padding]), que llegó con la v3. A quien
         * anuncie 2 se le sigue enviando sin relleno: no sabría quitarlo y se comería el final
         * del mensaje como si fuera contenido.
         */
        const val PADDING_MIN_PROTOCOL = 3

        /**
         * Mínimo para el **KDF de raíz v4** y la **época 0 atada a la propuesta** (W-15 y W-16,
         * auditoría interna del 11 oct 2026). Llegó con la v4.
         */
        const val KDF4_MIN_PROTOCOL = 4

        /** Tope del reengache de sesiones desincronizadas: uno por contacto cada 5 min. */
        internal const val REHOOK_MIN_INTERVAL_MS = 5 * 60 * 1000L

        /**
         * ¿Se **envía** ya con ratchet? **Sí, desde el 10 sep 2026.**
         *
         * A quién se le envía así lo decide `contact.peerProtocol` —lo que cada contacto haya
         * anunciado con el sobre `V`—, no esta constante: con un contacto que aún no lo
         * anuncia se sigue en v1, y eso no cambia por encenderla. Es decir, **esto no empieza
         * a tener efecto hasta que el otro extremo actualiza**.
         *
         * Se encendió **antes** de la prueba con dos móviles que pedía el §10 del diseño, a
         * decisión del autor (10 sep 2026): la colaboradora que presta el segundo móvil no
         * responde y eso tenía el trabajo parado. La prueba sigue pendiente y está en
         * `docs/PRUEBAS-PENDIENTES.md` §16 — conviene hacerla **con un contacto desechable en
         * cuanto los dos móviles tengan este build**, antes de fiarle una conversación real.
         *
         * Sigue siendo `var` y no `const` a propósito: es el **interruptor de emergencia**. Si
         * algo va mal en vivo, ponerlo a `false` devuelve todo a v1 sin perder nada de lo que
         * ya está guardado (lo que se hubiera enviado con ratchet y no se pudiera abrir, sí).
         * Nada del código de producción lo escribe; los tests lo usan para cubrir los dos
         * caminos.
         */
        @Volatile
        internal var RATCHET_SEND = true

        /** Mensajes por lote al convertir el historial a sobre en claro (ver `unsealHistory`). */
        const val UNSEAL_BATCH = 200

        /** Plazo del paso de anuncio de capacidades del ciclo WAN. */
        const val CAPABILITIES_BUDGET_MS = 8_000L

        /**
         * Interruptor del **depósito ciego** (ver `DISENO-buzon-ciego.md`). Encendido el 12 sep
         * 2026; a quién se le aplica lo decide [BLIND_MIN_PROTOCOL], no este valor. `var` por
         * lo mismo que [RATCHET_SEND]: los tests cubren los dos caminos y un `false` en una
         * publicación posterior devuelve todos los depósitos a v1 sin tocar nada más.
         */
        @Volatile
        internal var BLIND_DEPOSIT = true

        /**
         * Mínimo para depositar **a ciegas** a un contacto. La retirada por etiquetas (fase 4
         * del buzón ciego, 9 sep 2026) entró en el cliente **antes** que el anuncio de
         * capacidad (10 sep), así que no existe ningún cliente que anuncie 2 y no sepa retirar
         * por etiquetas. Igual que con el ratchet: una versión nueva no es el umbral de nada,
         * cada capacidad tiene el suyo.
         */
        const val BLIND_MIN_PROTOCOL = 2
        // Antigüedad máxima de un FALLIDO para reintentarlo solo (24 h).
        const val RETRY_MAX_AGE_MS = 24L * 60 * 60 * 1000
        // Tamaño de trozo de archivo: deja aire bajo el límite del buzón (64 KiB) tras el
        // sobre + el cifrado (nonce 12 + tag 16 + cabecera).
        const val CHUNK_SIZE = 48 * 1024
        // Esperas entre intentos de una pieza de archivo (4 intentos, ~22 s en total): lo que
        // tarda un dial caído en volver o un destinatario en línea en vaciar su buzón lleno.
        val PIECE_RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L, 15_000L)
    }
}

/** `1 conn: tcp` / `2 conns: tcp+ws`, tal como lo escribe `connSummary` en Go. */
private val CONN_SUMMARY = Regex("""\d+ conns?: [a-z+]+""")
private val PRUNED_SUMMARY = Regex("""wss redundantes cerradas: \d+""")

/**
 * Estado estable de la reserva de relay a partir del resumen del puente, que cambia en cada
 * ciclo (lleva la hora de expiración). Se conservan solo las partes que importan y no cambian
 * si todo va bien: cuántas conexiones hay con cada nodo y por qué vía (`1 conn: tcp`), y
 * cuántas WebSocket sobrantes se han cerrado (ver `conn_prune.go`). Así una segunda conexión
 * por Caddy, o una poda, se ve en el diagnóstico sin inundarlo.
 */
internal fun relayState(res: String): String {
    if (!res.startsWith("OK")) return res
    val conns = CONN_SUMMARY.findAll(res).map { it.value }.joinToString(" | ")
    val pruned = PRUNED_SUMMARY.find(res)?.value
    return buildString {
        append("OK (alcanzable por circuit")
        if (conns.isNotEmpty()) append("; ").append(conns)
        append(")")
        if (pruned != null) append(" · ").append(pruned)
    }
}

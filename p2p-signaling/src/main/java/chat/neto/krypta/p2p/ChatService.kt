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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import java.util.UUID
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
) {
    private val _onlinePeers = MutableStateFlow<Set<String>>(emptySet())
    /** PeerIDs actualmente conectados (p. ej. encontrados por mDNS en LAN). */
    val onlinePeers: StateFlow<Set<String>> = _onlinePeers.asStateFlow()

    private val _wanStatus = MutableStateFlow(WanStatus.DISABLED)
    /** Estado de la conexión a la DHT (WAN). */
    val wanStatus: StateFlow<WanStatus> = _wanStatus.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    /** Registro de diagnóstico (últimas ~30 líneas). */
    val log: StateFlow<List<String>> = _log.asStateFlow()

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

    private fun logLine(msg: String) {
        val line = "${LocalTime.now().withNano(0)}  $msg"
        runCatching { android.util.Log.i("KryptaDiag", msg) } // a logcat; no-op en tests JVM
        _log.update { (it + line).takeLast(30) }
    }

    /** Escribe una línea en el panel de diagnóstico desde fuera (p. ej. datos del dispositivo). */
    fun diagnose(msg: String) = logLine(msg)

    private fun short(peerId: String) = if (peerId.length > 10) "…${peerId.takeLast(8)}" else peerId

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

    private var wanJob: Job? = null

    @Volatile
    private var bootstrapAddr: String? = null

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
            ?.let(::startWan)
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
            stopWan()
            return BootstrapResult.CLEARED
        }
        val normalized = normalizeBootstrapList(addr)
        if (normalized == null) {
            logLine("bootstrap inválido (esperado /…/p2p/<PeerID>, uno por línea)")
            return BootstrapResult.INVALID
        }
        signaling.setBootstrap(normalized)
        startWan(normalized)
        return BootstrapResult.OK
    }

    private fun startWan(bootstrap: String) {
        bootstrapAddr = bootstrap
        if (wanJob == null) wanJob = scope.launch { wanLoop() }
        // Stream ligero de wake: el nodo avisa al instante cuando hay correo en el buzón.
        scope.launch { runCatching { signaling.startWake(inboxLabels()) } }
    }

    /** Detiene el bucle WAN y vuelve a DISABLED (solo LAN/mDNS). */
    private fun stopWan() {
        wanJob?.cancel()
        wanJob = null
        bootstrapAddr = null
        scope.launch { runCatching { signaling.stopWake() } }
        _wanStatus.value = WanStatus.DISABLED
        logLine("WAN: desactivado (solo LAN)")
    }

    /**
     * Bucle WAN **auto-reparable**: cada ciclo (re)conecta a la DHT vía el nodo bootstrap
     * (`connectDht` es idempotente) — esto sana la conexión wss que Cloudflare recicla
     * (~cada 10 min en plan Free) — y, si hay conexión, anuncia/busca a cada contacto por su
     * rendezvous del día `HKDF(sharedSecret, fecha)`.
     */
    private suspend fun wanLoop() {
        while (true) {
            val bootstrap = bootstrapAddr
            if (bootstrap != null) {
                // Todo el ciclo bajo un plazo máximo. Cada paso lleva además el suyo (ver
                // [step]): sin ellos, el 2 sep 2026 se midieron 37 min sin un solo ciclo, con
                // el buzón lleno y el móvil sin recoger nada — el bucle es secuencial, así que
                // cualquier llamada de red que se cuelgue congela **toda** la entrega.
                val cycled = withTimeoutOrNull(CYCLE_BUDGET_MS) { wanCycle(bootstrap) }
                if (cycled == null) {
                    _wanStatus.value = WanStatus.ERROR
                    logLine("ciclo WAN abortado por tiempo (${CYCLE_BUDGET_MS / 1000}s), reintentando")
                }
            }
            // Intervalo adaptativo (batería): si el stream de wake está abierto, los mensajes
            // llegan al instante empujados por el nodo, así que el bucle puede espaciarse; si no
            // (LAN-only o wake caído), se mantiene ágil para sondear el buzón y redescubrir.
            val wakeUp = runCatching { signaling.wakeConnected() }.getOrDefault(false)
            val interval = if (wakeUp) WAKE_IDLE_MS else REDISCOVER_MS
            if (wakeUp != lastWakeUp) {
                lastWakeUp = wakeUp
                logLine(if (wakeUp) "wake activo: bucle relajado (${WAKE_IDLE_MS / 1000}s)" else "wake inactivo: bucle ágil (${REDISCOVER_MS / 1000}s)")
            }
            // Espera interrumpible: kickWan() (p. ej. al cambiar de red) adelanta el ciclo.
            withTimeoutOrNull(interval) { wanKick.receive() }
        }
    }

    /**
     * Un ciclo del bucle WAN. Cada paso va acotado por [step]: un nodo lento o colgado hace
     * que se salte **ese** paso, no que se pare la entrega. El orden importa: el buzón se
     * retira antes del rendezvous porque es lo que entrega mensajes; descubrir peers puede
     * esperar al siguiente ciclo.
     */
    private suspend fun wanCycle(bootstrap: String) {
        val wasConnected = _wanStatus.value == WanStatus.CONNECTED
        if (!wasConnected) _wanStatus.value = WanStatus.CONNECTING

        val connected = step("DHT", CONNECT_BUDGET_MS) { signaling.connectDht(bootstrap) }
        if (connected) {
            if (!wasConnected) logLine("DHT: conectado")
            _wanStatus.value = WanStatus.CONNECTED
        } else {
            _wanStatus.value = WanStatus.ERROR
        }

        // El wake se re-arma cada ciclo: StartWake es idempotente en Go, y si la primera
        // llamada llegó sin host o sin lista de nodos, esta lo levanta en vez de quedarse
        // sin push para siempre.
        step("wake", CONNECT_BUDGET_MS) { signaling.startWake(inboxLabels()) }

        // Aunque el DHT no haya conectado: el buzón se retira por dial directo a cada nodo,
        // y es la vía que entrega los mensajes. Antes iba dentro del `if` del DHT, así que un
        // fallo al conectar dejaba el correo sin recoger.
        step("buzón", MAILBOX_BUDGET_MS) { fetchMailbox() }

        if (connected) {
            step("relay", RELAY_BUDGET_MS) { logRelayStatus() }
            step("rendezvous", RENDEZVOUS_BUDGET_MS) { announceAndFind() }
            step("reintentos", RETRY_BUDGET_MS) { retryFailed() }
            step("capacidades", CAPABILITIES_BUDGET_MS) { announceCapabilities() }
        }
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
            messages.updateStatus(message.id, MessageStatus.PENDING)
            val result = transmit(contact, message.copy(status = MessageStatus.PENDING), wireBytes(contact, message))
            if (result.status == MessageStatus.SENT) sent++
        }
        if (sent > 0) logLine("↻ reenviados $sent de ${failed.size} mensaje(s) pendientes")
    }

    /**
     * Anuncia a cada contacto qué versión de protocolo habla este cliente (sobre `V`), **una
     * sola vez por contacto y por versión**: al recibir el suyo se apunta en `peerProtocol`, y
     * eso es lo que permitirá encender el ratchet contacto a contacto en vez de esperar a que
     * todo el mundo actualice (ver `docs/DISENO-ratchet.md` §5).
     *
     * Se marca como anunciado **solo si el envío salió** (directo o buzón). Si falla por las
     * dos vías se reintenta en el próximo ciclo; si sale por buzón, ya está dicho y no se
     * vuelve a depositar — repetirlo en cada arranque gastaría el cupo del destinatario.
     *
     * Un cliente anterior recibe el sobre como `Unsupported` y lo ignora sin pintar nada, que
     * es lo que hace seguro empezar a anunciarlo desde ya.
     */
    internal suspend fun announceCapabilities() {
        val pendientes = runCatching { contacts.observeAll().first() }.getOrDefault(emptyList())
            .filter { !it.blocked && it.sharedSecret != null && it.announcedProtocol < PROTOCOL_VERSION }
        var anunciados = 0
        for (contact in pendientes) {
            val enviado = runCatching { sendRaw(contact, MessageEnvelope.encodeHello(PROTOCOL_VERSION)) }
                .isSuccess
            if (enviado) {
                contacts.upsert(contact.copy(announcedProtocol = PROTOCOL_VERSION))
                anunciados++
            }
        }
        // Se registran los dos desenlaces: sin la segunda línea, "no aparece nada" tanto puede
        // significar "ya estaba dicho" como "falla siempre en silencio", y en el diagnóstico
        // eso no se puede distinguir.
        if (anunciados > 0) {
            logLine("↔ protocolo v$PROTOCOL_VERSION anunciado a $anunciados contacto(s)")
        } else if (pendientes.isNotEmpty()) {
            logLine("↔ anuncio de protocolo pendiente para ${pendientes.size} contacto(s)")
        }
    }

    /**
     * Ejecuta un paso del ciclo con plazo y sin dejar que su fallo tumbe el resto. Devuelve
     * `true` solo si terminó a tiempo y sin excepción. Solo loguea al vencer el plazo (un
     * paso lento repetido llenaría el diagnóstico).
     */
    private suspend fun step(name: String, budgetMs: Long, block: suspend () -> Unit): Boolean {
        val done = withTimeoutOrNull(budgetMs) { runCatching { block() }.isSuccess }
        if (done == null) logLine("$name: sin respuesta en ${budgetMs / 1000}s, se salta este ciclo")
        return done == true
    }

    @Volatile
    private var lastWakeUp: Boolean? = null

    private val wanKick = Channel<Unit>(Channel.CONFLATED)

    /**
     * Adelanta el próximo ciclo del bucle WAN (reconexión + rendezvous + buzón) sin esperar
     * los 30 s — p. ej. cuando el sistema notifica un cambio de red (WiFi↔datos). No-op si
     * la WAN está desactivada.
     */
    fun kickWan() {
        wanKick.trySend(Unit)
    }

    /**
     * Un ciclo **puntual y síncrono** (reconecta DHT + retira el buzón), para el "latido" por
     * AlarmManager: en móviles que **suspenden la red en segundo plano** (Transsion/TECNO,
     * etc.) el stream de wake queda dormido; al despertar el sistema el latido descarga el
     * buzón y dispara el aviso. No hace nada si la WAN está desactivada.
     *
     * Arranca antes el host/WAN ([start] es idempotente): si el OEM mató el proceso y lo
     * revivió **solo la alarma**, nadie llamó a `start()`, así que `bootstrapAddr` estaría
     * vacío y el nodo nativo ni existiría — el latido era un no-op justo en el escenario
     * para el que se creó. Tras `start()` se releen los nodos guardados por si acaso.
     */
    suspend fun pollOnce() {
        withTimeoutOrNull(CONNECT_BUDGET_MS) { runCatching { start() } }
        val bootstrap = bootstrapAddr
            ?: runCatching { signaling.bootstrap() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return
        // Con plazo, y el buzón se retira **pase lo que pase** con el DHT. Antes eran dos
        // llamadas sin plazo y en este orden, así que un dial colgado dejaba al latido sin
        // llegar nunca a `fetchMailbox()`: la red de seguridad fallaba justo en el escenario
        // para el que existe (medido en vivo el 2 sep 2026).
        step("DHT (latido)", CONNECT_BUDGET_MS) { signaling.connectDht(bootstrap) }
        step("buzón (latido)", MAILBOX_BUDGET_MS) { fetchMailbox() }
    }


    /**
     * Registra si el host ya tiene una **reserva de relay** (una dirección `/p2p-circuit` entre
     * sus multiaddrs). Es la condición para ser alcanzable tras NAT: sin ella, el otro contacto
     * te encuentra por rendezvous pero "sin direcciones" y el envío falla. Solo loguea al cambiar.
     */
    /**
     * Renueva la reserva de Circuit Relay v2 cada ciclo (mantiene el slot activo en el nodo de
     * infra, para que reenvíe conexiones hacia nosotros tras NAT). La dirección /p2p-circuit ya
     * la anuncia el propio host (AddrsFactory en Go). Loguea solo al cambiar el estado.
     */
    private suspend fun logRelayStatus() {
        val res = runCatching { signaling.reserveRelay() }.getOrElse { it.message ?: "error" }
        val state = if (res.startsWith("OK")) "OK (alcanzable por circuit)" else res
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

    /**
     * Etiqueta bajo la que depositar para [contact], o cadena vacía para usar el camino
     * antiguo (direccionado por PeerID).
     *
     * Hoy devuelve siempre vacío, y es deliberado. Depositar a ciegas solo sirve si **el
     * destinatario** retira por etiquetas: un cliente que aún no lo haga jamás miraría ese
     * buzón y el mensaje se quedaría ahí hasta caducar. Que el *nodo* hable v2 no basta —esa
     * es la parte que el diseño original planteó mal—. Así que el orden correcto es: primero
     * todos los clientes saben **recibir** a ciegas (esta versión), y cuando esa versión esté
     * repartida se enciende el envío cambiando [BLIND_DEPOSIT] a true.
     */
    private fun outboxLabel(contact: Contact): String {
        if (!BLIND_DEPOSIT) return ""
        val secret = contact.sharedSecret ?: return ""
        val me = runCatching { keyExchange.localPeerId() }.getOrNull() ?: return ""
        return MailboxLabel.toHex(MailboxLabel.outbox(secret, me, contact.peerId))
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
            bootstrapAddr ?: runCatching { signaling.bootstrap() }.getOrNull().orEmpty(),
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

    private val lastFound = mutableMapOf<String, Boolean>()

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
     * Salvaguarda de dominio: a un contacto bloqueado no se le envía **nada** (mensajes,
     * trozos de archivo o señales de llamada). La UI ya lo impide, pero el corte vive aquí
     * para que ningún camino de envío se lo salte.
     */
    private fun requireNotBlocked(contact: Contact) {
        require(!contact.blocked) { "${contact.displayName} está bloqueado" }
    }

    /**
     * ¿Se le escribe a este contacto con ratchet? Depende de **él**, no de la versión que haya
     * publicada: solo si ha anunciado que sabe recibirlo ([Contact.peerProtocol], sobre `V`).
     * Así el encendido no necesita que actualice todo el mundo a la vez ni una publicación de
     * seguimiento que cambie una constante — que es lo que sí necesita el depósito ciego.
     *
     * [RATCHET_SEND] queda por encima como interruptor de emergencia.
     */
    private fun usesRatchet(contact: Contact): Boolean =
        RATCHET_SEND && contact.sharedSecret != null && contact.peerProtocol >= PROTOCOL_VERSION

    /** Un envío ya cifrado: el mensaje persistido (si lo hay) y los bytes que van por la red. */
    private class Outgoing(val message: Message, val wire: ByteArray)

    /**
     * Cifra [envelope] con la forma que hable [contact].
     *
     * Con ratchet, **el estado avanzado se guarda antes de que los bytes salgan**. El orden no
     * es un detalle: si se enviara primero y el estado no llegara a guardarse, el siguiente
     * mensaje reutilizaría la misma clave de mensaje —y con ella el mismo nonce de AES-GCM—,
     * que es la forma clásica de romper del todo un cifrado autenticado.
     */
    private suspend fun seal(contact: Contact, envelope: ByteArray): ByteArray {
        if (!usesRatchet(contact)) {
            return cipher.encrypt(requireNotNull(contact.sharedSecret), envelope)
        }
        lateinit var wire: ByteArray
        sessions.send(contact, envelope) { wire = it }
        return wire
    }

    /**
     * Como [seal], pero además persiste el mensaje que construya [build] **en la misma
     * transacción** que el avance del ratchet: o se guardan los dos, o ninguno.
     */
    private suspend fun sealAndPersist(
        contact: Contact,
        envelope: ByteArray,
        build: () -> Message,
    ): Outgoing {
        if (!usesRatchet(contact)) {
            val wire = cipher.encrypt(requireNotNull(contact.sharedSecret), envelope)
            val message = build()
            messages.save(message)
            return Outgoing(message, wire)
        }
        lateinit var wire: ByteArray
        val message = sessions.send(contact, envelope) { ct ->
            wire = ct
            build().also { messages.save(it) }
        }
        return Outgoing(message, wire)
    }

    /**
     * Cifra [plaintext] para [contact], lo persiste (PENDING) y lo envía. Si el envío
     * directo falla (peer offline / NAT sin ruta), cae al **buzón** store-and-forward del
     * nodo (entrega offline) → `SENT`. Solo si el buzón también falla queda **FAILED** —
     * nunca se propaga la excepción, para no tumbar la app. Devuelve el estado final.
     */
    suspend fun send(contact: Contact, plaintext: ByteArray, replyTo: String? = null): Message {
        requireNotBlocked(contact)
        // Precondición, no valor: quién cifra y con qué depende ya de [seal].
        requireNotNull(contact.sharedSecret) { "contact ${contact.id} has no shared secret" }
        // El ciphertext cifra un SOBRE que lleva el id del mensaje, para que el receptor
        // pueda acusar su lectura citándolo (marca de leído). Si es una respuesta, ese sobre
        // va envuelto en uno de cita, que solo lleva el id del mensaje citado.
        val msgId = UUID.randomUUID().toString()
        val envelope = MessageEnvelope.wrapReply(replyTo, MessageEnvelope.encodeText(msgId, plaintext))
        val out = sealAndPersist(contact, envelope) {
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
        return transmit(contact, out.message, out.wire)
    }

    /**
     * Envía una **imagen** (JPEG ya comprimido, ≤ límite del buzón) a [contact]. Igual que
     * [send] pero el sobre es de tipo imagen; viaja por el mismo camino (directo → buzón).
     */
    suspend fun sendImage(contact: Contact, jpeg: ByteArray, replyTo: String? = null): Message {
        requireNotBlocked(contact)
        // Precondición, no valor: quién cifra y con qué depende ya de [seal].
        requireNotNull(contact.sharedSecret) { "contact ${contact.id} has no shared secret" }
        val msgId = UUID.randomUUID().toString()
        val envelope = MessageEnvelope.wrapReply(replyTo, MessageEnvelope.encodeImage(msgId, jpeg))
        val out = sealAndPersist(contact, envelope) {
            Message(
                id = msgId,
                conversationId = contact.id,
                senderId = SELF,
                payload = envelope,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.PENDING,
            )
        }
        return transmit(contact, out.message, out.wire)
    }

    /**
     * Envía un **archivo** troceado a [contact]: anuncia la meta y manda cada trozo (≤ límite
     * del buzón) como mensajes cifrados por el camino normal (directo → buzón). Crea UNA
     * burbuja visible (descriptor). Si algún trozo falla → FAILED (reintentar reenvía todo).
     * [localPath] (si el emisor conserva copia, p. ej. una nota de voz) hace la burbuja
     * propia abrible/reproducible; los archivos del picker van sin copia en v1.
     */
    suspend fun sendFile(
        contact: Contact,
        name: String,
        mime: String,
        bytes: ByteArray,
        localPath: String? = null,
        replyTo: String? = null,
    ): Message {
        requireNotBlocked(contact)
        // Precondición, no valor: quién cifra y con qué depende ya de [seal].
        requireNotNull(contact.sharedSecret) { "contact ${contact.id} has no shared secret" }
        val fileId = UUID.randomUUID().toString()
        val total = (bytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE
        // La cita va en el descriptor local (burbuja propia) y en la meta que viaja: los
        // trozos no la llevan, y la burbuja del receptor no nace hasta tenerlos todos.
        val descriptor = MessageEnvelope.wrapReply(
            replyTo,
            MessageEnvelope.encodeFileDescriptor(name, mime, bytes.size.toLong(), localPath),
        )
        val message = Message(
            id = fileId,
            conversationId = contact.id,
            senderId = SELF,
            payload = descriptor,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.PENDING,
        )
        messages.save(message)
        return try {
            sendRaw(
                contact,
                MessageEnvelope.wrapReply(
                    replyTo,
                    MessageEnvelope.encodeFileMeta(fileId, name, mime, bytes.size.toLong(), total),
                ),
            )
            for (i in 0 until total) {
                val from = i * CHUNK_SIZE
                val to = minOf(from + CHUNK_SIZE, bytes.size)
                sendRaw(contact, MessageEnvelope.encodeFileChunk(fileId, i, bytes.copyOfRange(from, to)))
            }
            messages.updateStatus(fileId, MessageStatus.SENT)
            logLine("→ archivo enviado a ${short(contact.peerId)} ($total trozos)")
            message.copy(status = MessageStatus.SENT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            messages.updateStatus(fileId, MessageStatus.FAILED)
            logLine("✗ archivo no enviado a ${short(contact.peerId)}: ${sendErrorReason(e)}")
            message.copy(status = MessageStatus.FAILED)
        }
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
    ) {
        // La mitad de clave solo viaja hacia quien haya anunciado que la entiende: un cliente
        // anterior parte la cabecera `C` en tres trozos y descartaría la señal entera, con lo
        // que la llamada no llegaría a sonar. Con el resto se sigue por el camino antiguo.
        val negociada = key?.takeIf { contact.peerProtocol >= PROTOCOL_VERSION }
        sendRaw(contact, MessageEnvelope.encodeCall(kind, callId, System.currentTimeMillis(), negociada))
    }

    /** Persiste una fila local "📞 Llamada perdida" en el chat de [contact] y la emite. */
    suspend fun recordMissedCall(contact: Contact) {
        val secret = contact.sharedSecret ?: return
        val id = UUID.randomUUID().toString()
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

    /** Cifra y envía un sobre "en crudo" (meta/trozo) sin crear un Message: directo → buzón. */
    private suspend fun sendRaw(contact: Contact, envelope: ByteArray) {
        requireNotBlocked(contact)
        val ciphertext = seal(contact, envelope)
        try {
            signaling.send(contact, ciphertext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            signaling.sendOffline(contact, ciphertext, outboxLabel(contact)) // fallback; si también falla, propaga
        }
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
        requireNotBlocked(contact)
        val message = messages.findById(messageId) ?: return null
        messages.updateStatus(messageId, MessageStatus.PENDING)
        logLine("↻ reintentando a ${short(contact.peerId)}")
        return transmit(contact, message.copy(status = MessageStatus.PENDING), wireBytes(contact, message))
    }

    /**
     * Bytes listos para la red de un mensaje ya persistido. Una fila de la v8 en adelante
     * guarda el sobre en claro y se cifra aquí; una anterior guarda ya el ciphertext de la
     * clave estática y se reenvía tal cual, que es exactamente lo que se hacía antes.
     */
    private suspend fun wireBytes(contact: Contact, message: Message): ByteArray =
        if (message.encrypted) message.payload else seal(contact, message.payload)

    /** El sobre en claro de un mensaje persistido, descifrando solo si es una fila antigua. */
    private fun envelopeOf(contact: Contact, message: Message): ByteArray =
        if (message.encrypted) cipher.decrypt(requireNotNull(contact.sharedSecret), message.payload)
        else message.payload

    /** Transmite un mensaje ya persistido: directo → buzón (offline) → FAILED. Nunca lanza. */
    private suspend fun transmit(contact: Contact, message: Message, ciphertext: ByteArray): Message =
        try {
            signaling.send(contact, ciphertext)
            messages.updateStatus(message.id, MessageStatus.SENT)
            logLine("→ enviado a ${short(contact.peerId)}")
            message.copy(status = MessageStatus.SENT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sendViaMailbox(contact, message, ciphertext, e)
        }

    /** Fallback offline: deposita el ciphertext en el buzón del nodo → SENT, o FAILED. */
    private suspend fun sendViaMailbox(
        contact: Contact,
        message: Message,
        ciphertext: ByteArray,
        directError: Exception,
    ): Message = try {
        signaling.sendOffline(contact, ciphertext, outboxLabel(contact))
        messages.updateStatus(message.id, MessageStatus.SENT)
        logLine("→ buzón para ${short(contact.peerId)} (offline)")
        message.copy(status = MessageStatus.SENT)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        messages.updateStatus(message.id, MessageStatus.FAILED)
        logLine(
            "✗ no enviado a ${short(contact.peerId)}: ${sendErrorReason(directError)}; " +
                "buzón: ${sendErrorReason(e)}"
        )
        message.copy(status = MessageStatus.FAILED)
    }

    /** Traduce el error de libp2p a algo legible en el panel de diagnóstico. */
    private fun sendErrorReason(e: Throwable): String {
        val msg = e.message ?: e.toString()
        return when {
            "no addresses" in msg || "all dials failed" in msg || "failed to dial" in msg ->
                "sin ruta al contacto (NAT/relay pendiente)"
            else -> msg.take(120)
        }
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
        val recibido = runCatching {
            sessions.receive(contact, ciphertext) { plain -> persistEnvelope(contact, plain, mailboxId, ts) }
        }.getOrElse { return openLegacy(contact, ciphertext, mailboxId, ts) }
        return when (recibido) {
            is RatchetSessions.Received.Opened -> recibido.value
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
    ): Message? {
        val plain = runCatching { cipher.decrypt(requireNotNull(contact.sharedSecret), ciphertext) }
            .getOrNull()
        if (plain == null) {
            // No se puede abrir por ninguna vía. Antes se persistía el ciphertext como si fuera
            // texto legado, lo que pintaba una burbuja de basura que no ayuda a nadie.
            logLine("⚠ mensaje ilegible de ${short(contact.peerId)} (descartado)")
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
                markOutgoingRead(contact.id, decoded.ids)
                return null
            }
            is MessageEnvelope.Decoded.Hello -> {
                // Nos dice qué versión habla. Se apunta y ya está: no crea burbuja.
                if (decoded.protocol != contact.peerProtocol) {
                    contacts.upsert(contact.copy(peerProtocol = decoded.protocol))
                    logLine("↔ ${short(contact.peerId)} habla protocolo v${decoded.protocol}")
                }
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
        // antes de escribir hay que descartar dos colisiones:
        //  - el eco de un mensaje propio (un contacto que apunta a tu propio PeerID): no
        //    sobrescribas tu copia saliente con la versión entrante;
        //  - un id que ya pertenece a OTRA conversación: nadie debe poder pisar, ni por error
        //    ni a propósito, el mensaje de un tercero (auditoría A-9).
        val existing = messages.findById(msgId)
        if (existing != null && (existing.senderId == SELF || existing.conversationId != contact.id)) {
            return null
        }
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

    /**
     * Marca como READ nuestros mensajes salientes **de esa conversación** cuyo id venga en el
     * acuse. Exigir la conversación importa porque los ids del acuse los elige quien lo
     * envía: sin esa condición, un contacto podría marcar como leídos mensajes dirigidos a
     * otro (auditoría A-9).
     */
    private suspend fun markOutgoingRead(conversationId: String, ids: List<String>) {
        for (id in ids) {
            val m = messages.findById(id)
            if (m != null && m.senderId == SELF && m.conversationId == conversationId &&
                m.status != MessageStatus.READ
            ) {
                messages.updateStatus(id, MessageStatus.READ)
            }
        }
    }

    /**
     * Ids ya acusados, para no reenviar el mismo acuse cada vez que se abre el chat. Es una
     * caché **acotada** (LRU): antes crecía sin techo mientras viviera el proceso. Perderla
     * (al morir el proceso, o al desalojar una entrada) solo provoca un acuse repetido, que
     * es inocuo: [markOutgoingRead] ignora el que ya está en READ.
     */
    private val ackedReceipts = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) =
            size > MAX_ACKED_RECEIPTS
    }

    /**
     * Envía un **acuse de lectura** de los mensajes recibidos de [contact] que aún no se han
     * acusado (best-effort: directo, y si falla, buzón). Se llama al abrir/ver la conversación;
     * el emisor pondrá esos mensajes en READ. No persiste nada ni crea burbujas.
     */
    suspend fun markConversationRead(contact: Contact) {
        // Vista local: limpia el badge de no leídos aunque el acuse de red falle.
        runCatching { messages.markIncomingRead(contact.id) }
        // A un bloqueado no se le acusa nada: el historial se puede seguir leyendo aquí, pero
        // él no debe recibir ninguna señal (ni siquiera un ✓✓) de este dispositivo.
        if (contact.blocked) return
        val secret = contact.sharedSecret ?: return
        val received = runCatching { messages.observeConversation(contact.id).first() }
            .getOrDefault(emptyList())
            .filter { it.senderId == contact.id }
            .map { it.id }
        val newIds = received.filter { it !in ackedReceipts.keys }
        if (newIds.isEmpty()) return
        val ciphertext = seal(contact, MessageEnvelope.encodeRead(newIds))
        val ok = runCatching { signaling.send(contact, ciphertext); true }.getOrDefault(false) ||
            runCatching { signaling.sendOffline(contact, ciphertext, outboxLabel(contact)); true }.getOrDefault(false)
        if (ok) newIds.forEach { ackedReceipts[it] = Unit }
    }

    fun observeContacts(): Flow<List<Contact>> = contacts.observeAll()

    /** PeerID de este dispositivo (para compartir con quien te quiera añadir). */
    fun myPeerId(): String = keyExchange.localPeerId()

    /** Contacto guardado con ese PeerID, si lo hay (el PeerID es la clave primaria). */
    suspend fun findContact(peerId: String): Contact? = contacts.findById(peerId)

    /**
     * Da de alta (o actualiza) un contacto. El secreto compartido se calcula por ECDH
     * (X25519) a partir de nuestra identidad y la clave pública embebida en su PeerID, así
     * que basta con el PeerID — sin passphrase ni claves que pegar. Si el PeerID cambia
     * respecto a uno ya guardado, la verificación previa deja de valer (se resetea).
     *
     * Añadirse a **uno mismo** se rechaza: el PeerID propio y el del contacto se copian y
     * pegan por el mismo canal, así que pegar el propio por error es fácil — y el resultado
     * era una conversación con uno mismo indistinguible de un contacto real (pasó en vivo el
     * 23 jul 2026: un contacto nuevo heredó el chat del auto-envío de pruebas porque el
     * PeerID pegado era el del propio teléfono, y los mensajes nunca llegaban a nadie).
     */
    suspend fun addContact(displayName: String, peerId: String): Contact {
        require(peerId != keyExchange.localPeerId()) {
            "Ese es tu propio PeerID: pide a tu contacto el suyo"
        }
        val existing = contacts.findById(peerId)
        val contact = Contact(
            id = peerId, // 1:1: el PeerID identifica la conversación
            displayName = displayName,
            peerId = peerId,
            publicKey = ByteArray(0),
            sharedSecret = keyExchange.sharedSecretWith(peerId),
            verified = existing?.verified ?: false,
        )
        contacts.upsert(contact)
        // Para que pueda marcarnos ya, sin esperar al próximo ciclo WAN.
        refreshAllowedPeers()
        return contact
    }

    /**
     * Número de seguridad anti-MITM con [contact] (estilo Signal): hash simétrico de ambos
     * PeerIDs. Los dos contactos deben ver el mismo; cotejarlo fuera de banda descarta que
     * alguien haya sustituido un PeerID en el canal por el que se compartió. Ver [SafetyNumber].
     */
    fun safetyNumber(contact: Contact): String =
        SafetyNumber.compute(keyExchange.localPeerId(), contact.peerId)

    /** Marca (o desmarca) [contact] como verificado tras cotejar el número de seguridad. */
    suspend fun setVerified(contact: Contact, verified: Boolean) {
        contacts.upsert(contact.copy(verified = verified))
    }

    /**
     * Bloquea (o desbloquea) a [contact]. Todo local y sin cambio de protocolo: se deja de
     * anunciar su rendezvous ([announceAndFind]), lo que llegue de él se descarta sin
     * persistir ni avisar ([onReceived]) y no se le envía nada ([requireNotBlocked]). El
     * bloqueado **no recibe ninguna señal**: sus mensajes le quedan como enviados, igual que
     * si este dispositivo estuviera apagado. El chat y el contacto se conservan — para
     * borrarlos están [clearConversation] y [deleteContact].
     */
    suspend fun setBlocked(contact: Contact, blocked: Boolean) {
        contacts.upsert(contact.copy(blocked = blocked))
        // Un bloqueado sale de la lista en el acto: deja de poder abrirnos conexión.
        refreshAllowedPeers()
        if (blocked) {
            // Sin rendezvous ya no se le va a ver: que el punto verde no se quede pegado.
            lastFound.remove(contact.peerId)
            _onlinePeers.update { it - contact.peerId }
        }
        val what = if (blocked) "🚫 contacto bloqueado" else "contacto desbloqueado"
        logLine("$what: ${short(contact.peerId)}")
    }

    /**
     * Vacía el chat de [contact] **solo en este dispositivo**: borra sus mensajes de Room y
     * los archivos locales asociados (adjuntos recibidos, copias de notas de voz enviadas).
     * El contacto se conserva. Sin cambio de protocolo: al otro lado no le afecta.
     */
    suspend fun clearConversation(contact: Contact) {
        val all = runCatching { messages.observeConversation(contact.id).first() }
            .getOrDefault(emptyList())
        for (m in all) {
            val c = runCatching { content(contact, m) }.getOrNull()
            if (c is MessageContent.File) {
                runCatching { fileStore.deleteLocal(m.id, c.localPath) }
            }
        }
        messages.deleteConversation(contact.id)
        logLine("🗑 chat vaciado (${all.size} mensaje(s)) de ${short(contact.peerId)}")
    }

    /**
     * Elimina [contact] y todo su chat (mensajes + archivos) de este dispositivo. El bucle
     * WAN relee los contactos de Room en cada ciclo ([announceAndFind]), así que el
     * rendezvous del contacto cesa solo, sin reiniciar el host. Re-añadirlo por PeerID
     * vuelve a derivar el mismo secreto (la verificación habrá que repetirla).
     */
    suspend fun deleteContact(contact: Contact) {
        clearConversation(contact)
        // La sesión del ratchet se va con el contacto: volver a añadirlo arranca un linaje
        // nuevo, y quedársela sería guardar material de una conversación que ya no existe.
        // Vaciar el chat, en cambio, NO la toca: vaciar no es romper la sesión.
        runCatching { sessions.forget(contact) }
        contacts.delete(contact.id)
        refreshAllowedPeers()
        lastFound.remove(contact.peerId)
        _onlinePeers.update { it - contact.peerId }
        logLine("🗑 contacto eliminado: ${short(contact.peerId)}")
    }

    fun observeConversation(conversationId: String): Flow<List<Message>> =
        messages.observeConversation(conversationId)

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
        val plain = envelopeOf(contact, message)
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
        val plain = envelopeOf(contact, message)
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

    // `internal` (no `private`): los tests del módulo fijan la versión de protocolo que se
    // anuncia, y una constante de protocolo que nadie comprueba se desincroniza sola.
    internal companion object {
        const val SELF = "self"
        /** Imágenes animadas: viajan como archivo pero se rotulan/pintan como imagen. */
        val ANIMATED_IMAGE_MIMES = setOf("image/gif", "image/webp")
        // Texto de la fila local de llamada perdida (no viaja por la red).
        const val MISSED_CALL_TEXT = "📞 Llamada perdida"
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
        // Reintentos de FALLIDOS por ciclo: suficiente para vaciar una racha corta sin
        // convertir el ciclo en una tormenta de envíos tras una caída larga.
        const val MAX_RETRIES_PER_CYCLE = 10
        // Tope de la caché de acuses ya enviados (ver ackedReceipts).
        const val MAX_ACKED_RECEIPTS = 500
        /**
         * Interruptor del **depósito ciego** (ver [outboxLabel]). Se enciende cuando la versión
         * que sabe recibir por etiquetas esté repartida entre los contactos; hasta entonces,
         * depositar a ciegas sería depositar donde el otro no mira.
         */
        /**
         * Versión de protocolo que habla este cliente y que se anuncia a cada contacto (sobre
         * `V`). La 1 es implícita: no la anuncia nadie, es "lo que había antes".
         */
        const val PROTOCOL_VERSION = 2

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

        const val BLIND_DEPOSIT = false
        // Antigüedad máxima de un FALLIDO para reintentarlo solo (24 h).
        const val RETRY_MAX_AGE_MS = 24L * 60 * 60 * 1000
        // Tamaño de trozo de archivo: deja aire bajo el límite del buzón (64 KiB) tras el
        // sobre + el cifrado (nonce 12 + tag 16 + cabecera).
        const val CHUNK_SIZE = 48 * 1024
    }
}

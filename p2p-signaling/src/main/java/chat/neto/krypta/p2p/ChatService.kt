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
 * recibe → resuelve el contacto por PeerID → persiste. Los `Message` guardan siempre el
 * `ciphertext`; el texto plano solo se obtiene bajo demanda con [decrypt].
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
        signaling.setMailboxProcessor { peerId, ciphertext, envelopeId, ts ->
            val result = runCatching { onReceived(peerId, ciphertext, envelopeId, ts) }
            result.onSuccess { msg ->
                if (msg != null) logLine("← mensaje de ${short(peerId)} (buzón)")
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
        runCatching { signaling.bootstrap() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(::startWan)
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
        scope.launch { runCatching { signaling.startWake() } }
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
        step("wake", CONNECT_BUDGET_MS) { signaling.startWake() }

        // Aunque el DHT no haya conectado: el buzón se retira por dial directo a cada nodo,
        // y es la vía que entrega los mensajes. Antes iba dentro del `if` del DHT, así que un
        // fallo al conectar dejaba el correo sin recoger.
        step("buzón", MAILBOX_BUDGET_MS) { fetchMailbox() }

        if (connected) {
            step("relay", RELAY_BUDGET_MS) { logRelayStatus() }
            step("rendezvous", RENDEZVOUS_BUDGET_MS) { announceAndFind() }
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

    /** Interno (no privado) para poder ejercitarlo desde los tests del módulo. */
    internal suspend fun announceAndFind() {
        val targets = runCatching { contacts.observeAll().first() }
            .getOrDefault(emptyList())
            // El bloqueado se cae del rendezvous: se deja de anunciar el punto de cita
            // compartido con él, así que ni siquiera puede localizar a este dispositivo.
            .filter { it.sharedSecret != null && !it.blocked }
        if (targets.isNotEmpty()) logLine("rendezvous: anunciando a ${targets.size} contacto(s)")
        for (contact in targets) {
            val rdv = rendezvous.rendezvousFor(contact.sharedSecret!!)
            runCatching {
                signaling.announce(rdv)
                val found = signaling.findPeers(rdv)
                val hit = found.any { it == contact.peerId }
                if (hit != lastFound[contact.peerId]) {
                    lastFound[contact.peerId] = hit
                    logLine(if (hit) "rendezvous: ✓ encontrado ${short(contact.peerId)}" else "rendezvous: aún no encuentro ${short(contact.peerId)}")
                }
            }.onFailure { logLine("rendezvous ${short(contact.peerId)}: ${it.message}") }
        }
    }

    private val lastFound = mutableMapOf<String, Boolean>()

    /**
     * Retira los mensajes pendientes del buzón del nodo (entrega offline). Cada mensaje
     * llega por el mismo flujo de eventos que los directos y se persiste allí; aquí solo
     * se dispara la retirada y se loguea el total.
     */
    private suspend fun fetchMailbox() {
        runCatching { signaling.fetchMailbox() }
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
     * Cifra [plaintext] para [contact], lo persiste (PENDING) y lo envía. Si el envío
     * directo falla (peer offline / NAT sin ruta), cae al **buzón** store-and-forward del
     * nodo (entrega offline) → `SENT`. Solo si el buzón también falla queda **FAILED** —
     * nunca se propaga la excepción, para no tumbar la app. Devuelve el estado final.
     */
    suspend fun send(contact: Contact, plaintext: ByteArray, replyTo: String? = null): Message {
        requireNotBlocked(contact)
        val secret = requireNotNull(contact.sharedSecret) { "contact ${contact.id} has no shared secret" }
        // El ciphertext cifra un SOBRE que lleva el id del mensaje, para que el receptor
        // pueda acusar su lectura citándolo (marca de leído). Si es una respuesta, ese sobre
        // va envuelto en uno de cita, que solo lleva el id del mensaje citado.
        val msgId = UUID.randomUUID().toString()
        val ciphertext = cipher.encrypt(
            secret,
            MessageEnvelope.wrapReply(replyTo, MessageEnvelope.encodeText(msgId, plaintext)),
        )
        val message = Message(
            id = msgId,
            conversationId = contact.id,
            senderId = SELF,
            ciphertext = ciphertext,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.PENDING,
        )
        messages.save(message)
        return transmit(contact, message, ciphertext)
    }

    /**
     * Envía una **imagen** (JPEG ya comprimido, ≤ límite del buzón) a [contact]. Igual que
     * [send] pero el sobre es de tipo imagen; viaja por el mismo camino (directo → buzón).
     */
    suspend fun sendImage(contact: Contact, jpeg: ByteArray, replyTo: String? = null): Message {
        requireNotBlocked(contact)
        val secret = requireNotNull(contact.sharedSecret) { "contact ${contact.id} has no shared secret" }
        val msgId = UUID.randomUUID().toString()
        val ciphertext = cipher.encrypt(
            secret,
            MessageEnvelope.wrapReply(replyTo, MessageEnvelope.encodeImage(msgId, jpeg)),
        )
        val message = Message(
            id = msgId,
            conversationId = contact.id,
            senderId = SELF,
            ciphertext = ciphertext,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.PENDING,
        )
        messages.save(message)
        return transmit(contact, message, ciphertext)
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
        val secret = requireNotNull(contact.sharedSecret) { "contact ${contact.id} has no shared secret" }
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
            ciphertext = cipher.encrypt(secret, descriptor),
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
    suspend fun sendCallSignal(contact: Contact, kind: String, callId: String) {
        sendRaw(contact, MessageEnvelope.encodeCall(kind, callId, System.currentTimeMillis()))
    }

    /** Persiste una fila local "📞 Llamada perdida" en el chat de [contact] y la emite. */
    suspend fun recordMissedCall(contact: Contact) {
        val secret = contact.sharedSecret ?: return
        val id = UUID.randomUUID().toString()
        val message = Message(
            id = id,
            conversationId = contact.id,
            senderId = contact.id,
            ciphertext = cipher.encrypt(secret, MessageEnvelope.encodeText(id, MISSED_CALL_TEXT.toByteArray())),
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
        val ciphertext = cipher.encrypt(requireNotNull(contact.sharedSecret), envelope)
        try {
            signaling.send(contact, ciphertext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            signaling.sendOffline(contact, ciphertext) // fallback; si también falla, propaga
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
     * repite el camino directo→buzón→FAILED, reusando su ciphertext ya persistido (mismo id,
     * sin duplicar). Devuelve el mensaje con su estado final, o null si no existe.
     */
    suspend fun retry(contact: Contact, messageId: String): Message? {
        requireNotBlocked(contact)
        val message = messages.findById(messageId) ?: return null
        messages.updateStatus(messageId, MessageStatus.PENDING)
        logLine("↻ reintentando a ${short(contact.peerId)}")
        return transmit(contact, message.copy(status = MessageStatus.PENDING), message.ciphertext)
    }

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
        signaling.sendOffline(contact, ciphertext)
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
     * Procesa un ciphertext entrante: resuelve el contacto por [peerId], descifra el sobre y
     * ramifica: un **acuse de lectura** marca nuestros mensajes salientes como READ (no crea
     * mensaje visible); un **texto** se persiste DELIVERED con el id del sobre (dedup ante
     * reentregas). Si no lleva sobre (mensaje legado), se guarda como texto con [mailboxId] o
     * un id generado. Devuelve el `Message` persistido, o null si es acuse/desconocido.
     */
    suspend fun onReceived(
        peerId: String,
        ciphertext: ByteArray,
        mailboxId: String? = null,
        ts: Long? = null,
    ): Message? {
        val contact = contacts.findByPeerId(peerId) ?: return null
        // Bloqueado: se descarta **antes de descifrar**, así que no se persiste, no avisa y
        // no llega a CallService (nada de timbre ni de "llamada perdida"). Devolver null es
        // además lo que ack'ea el sobre en el buzón: se borra del nodo en vez de reentregarse
        // en cada ciclo ocupando el cupo del destinatario. El bloqueado no se entera: sus
        // envíos le quedan como enviados, igual que si estuvieras desconectado.
        if (contact.blocked) return null
        val secret = contact.sharedSecret ?: return null
        val envelope = runCatching { MessageEnvelope.decode(cipher.decrypt(secret, ciphertext)) }.getOrNull()
        // La cita es un envoltorio: se abre aquí para que el resto ramifique por el contenido
        // real. El id citado solo hace falta en lo que crea burbuja (archivo); el texto y la
        // imagen lo llevan en su propio ciphertext, que es lo que se persiste.
        val replyTo = (envelope as? MessageEnvelope.Decoded.Reply)?.replyTo
        val decoded = (envelope as? MessageEnvelope.Decoded.Reply)?.inner ?: envelope

        when (decoded) {
            is MessageEnvelope.Decoded.Read -> {
                markOutgoingRead(decoded.ids)
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
        // Eco de un mensaje propio (p. ej. un contacto que apunta a tu propio PeerID): no
        // sobrescribas tu copia saliente con la versión entrante.
        val existing = messages.findById(msgId)
        if (existing != null && existing.senderId == SELF) return null
        val message = Message(
            id = msgId,
            conversationId = contact.id,
            senderId = contact.id,
            ciphertext = ciphertext,
            timestamp = ts ?: System.currentTimeMillis(),
            status = MessageStatus.DELIVERED,
        )
        messages.save(message)
        emitIncoming(contact, message)
        return message
    }

    /** Persiste un archivo ya reensamblado como Message (descriptor con path) y lo emite. */
    private suspend fun persistFile(contact: Contact, fileId: String, f: chat.neto.krypta.core.AssembledFile): Message? {
        // No sobrescribas un envío propio con su eco (contacto que apunta a tu PeerID).
        val existing = messages.findById(fileId)
        if (existing != null && existing.senderId == SELF) return null
        val descriptor = MessageEnvelope.wrapReply(
            f.replyTo,
            MessageEnvelope.encodeFileDescriptor(f.name, f.mime, f.size, f.path),
        )
        val message = Message(
            id = fileId,
            conversationId = contact.id,
            senderId = contact.id,
            ciphertext = cipher.encrypt(requireNotNull(contact.sharedSecret), descriptor),
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.DELIVERED,
        )
        messages.save(message)
        emitIncoming(contact, message)
        logLine("← archivo de ${short(contact.peerId)}: ${f.name}")
        return message
    }

    /** Marca como READ nuestros mensajes salientes cuyo id venga en un acuse de lectura. */
    private suspend fun markOutgoingRead(ids: List<String>) {
        for (id in ids) {
            val m = messages.findById(id)
            if (m != null && m.senderId == SELF && m.status != MessageStatus.READ) {
                messages.updateStatus(id, MessageStatus.READ)
            }
        }
    }

    private val ackedReceipts = mutableSetOf<String>()

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
        val newIds = received.filter { it !in ackedReceipts }
        if (newIds.isEmpty()) return
        val ciphertext = cipher.encrypt(secret, MessageEnvelope.encodeRead(newIds))
        val ok = runCatching { signaling.send(contact, ciphertext); true }.getOrDefault(false) ||
            runCatching { signaling.sendOffline(contact, ciphertext); true }.getOrDefault(false)
        if (ok) ackedReceipts.addAll(newIds)
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
        contacts.delete(contact.id)
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
        val plain = cipher.decrypt(requireNotNull(contact.sharedSecret), message.ciphertext)
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
        val plain = cipher.decrypt(requireNotNull(contact.sharedSecret), message.ciphertext)
        val decoded = MessageEnvelope.decode(plain)
        val replyTo = (decoded as? MessageEnvelope.Decoded.Reply)?.replyTo
        val content = when (val d = (decoded as? MessageEnvelope.Decoded.Reply)?.inner ?: decoded) {
            is MessageEnvelope.Decoded.Text -> MessageContent.Text(String(d.body))
            is MessageEnvelope.Decoded.Image -> MessageContent.Image(d.bytes)
            is MessageEnvelope.Decoded.FileDescriptor ->
                MessageContent.File(d.name, d.mime, d.size, d.path)
            is MessageEnvelope.Decoded.Read -> MessageContent.Text("")
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

    private companion object {
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
        const val CYCLE_BUDGET_MS = 150_000L
        // Tamaño de trozo de archivo: deja aire bajo el límite del buzón (64 KiB) tras el
        // sobre + el cifrado (nonce 12 + tag 16 + cabecera).
        const val CHUNK_SIZE = 48 * 1024
    }
}

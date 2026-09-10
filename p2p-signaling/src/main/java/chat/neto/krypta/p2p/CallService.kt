package chat.neto.krypta.p2p

import chat.neto.krypta.core.AudioEngine
import chat.neto.krypta.core.CallStream
import chat.neto.krypta.core.ISignalingService
import chat.neto.krypta.core.MessageCipher
import chat.neto.krypta.core.VideoFrame
import chat.neto.krypta.core.model.Contact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Fase de la llamada actual (una sola llamada a la vez, MVP). */
enum class CallPhase {
    /** Sin llamada. */
    IDLE,
    /** Saliente: invite enviado, esperando respuesta (suena en el otro lado). */
    CALLING,
    /** Entrante: invite recibido, timbrando aquí. */
    RINGING,
    /** Aceptada: abriendo/validando el stream de medios. */
    CONNECTING,
    /** En llamada (audio fluyendo). */
    ACTIVE,
    /** Terminada; [CallState.endReason] dice por qué. Vuelve sola a IDLE en unos segundos. */
    ENDED,
}

data class CallState(
    val phase: CallPhase = CallPhase.IDLE,
    val contact: Contact? = null,
    val callId: String = "",
    val outgoing: Boolean = false,
    /** Unix millis del paso a ACTIVE (para el cronómetro de la UI). */
    val startedAt: Long = 0L,
    val endReason: String? = null,
    /** Fase 7c: estamos enviando nuestra cámara. */
    val videoSending: Boolean = false,
    /** Fase 7c: el otro lado está enviando vídeo (hay frames remotos que pintar). */
    val videoReceiving: Boolean = false,
)

/**
 * Orquesta las llamadas de voz (Fase 7b, Opción A): señalización por sobres E2EE `C`
 * (vía [ChatService], directo → buzón), medios por un stream libp2p `/krypta/call/1.0.0`
 * (directo por DCUtR o relayed), y cada frame de audio cifrado con una **clave por llamada**.
 * El que llama abre el stream tras el accept y manda un "hello" cifrado; el receptor lo valida
 * (autentica la llamada) antes de arrancar el audio.
 *
 * **La clave de la llamada se negocia** (fase 7 del ratchet): cada lado sortea 32 bytes y los
 * manda dentro del sobre `C` —el invite lleva la mitad de quien llama, el accept la del que
 * contesta— y la clave sale de las dos, `HKDF(k_llamante ‖ k_contestador)`. Antes se derivaba
 * de `HKDF(secreto_estático, callId)`, o sea que **quien robara la identidad podía descifrar
 * cualquier llamada que hubiera grabado**, pasada o futura. Ahora hace falta además el sobre
 * de señalización de esa llamada concreta; y en cuanto el envío por ratchet esté encendido,
 * ese sobre tiene secreto hacia adelante y la llamada lo hereda entero, sin tocar el
 * streaming ni un byte.
 *
 * Con un contacto que aún no lo entiende se sigue por el camino antiguo (ver
 * `ChatService.sendCallSignal`): una llamada que suena vale más que una llamada perfecta que
 * el otro no puede recibir.
 */
@Singleton
class CallService @Inject constructor(
    private val chat: ChatService,
    private val signaling: ISignalingService,
    private val cipher: MessageCipher,
    private val audio: AudioEngine,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var callKey: ByteArray? = null

    /** Mitad de clave propia de la llamada en curso (la que se manda en el invite/accept). */
    private var localHalf: ByteArray? = null

    /** Mitad del otro extremo, si la mandó. Null = contacto antiguo, sin negociación. */
    private var remoteHalf: ByteArray? = null

    private val random = java.security.SecureRandom()
    private var stream: CallStream? = null
    private var txFrames: Channel<ByteArray>? = null
    private var mediaJobs: List<Job> = emptyList()
    private var timeoutJob: Job? = null
    private var resetJob: Job? = null

    // Vídeo (7c): streams independientes del audio (uno por sentido, cada lado abre el suyo).
    private var videoOut: CallStream? = null
    private var videoIn: CallStream? = null
    private var videoTx: Channel<ByteArray>? = null
    private var videoJobs: List<Job> = emptyList()

    // Frames de vídeo remotos ya descifrados (H.264 tal como salió del encoder del otro
    // lado). DROP_OLDEST: si el decodificador no da abasto se pierden frames viejos y la
    // imagen se recompone en el siguiente keyframe.
    private val _remoteVideo = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 60,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Frames de vídeo del otro extremo (H.264), para el decodificador de la app. */
    val remoteVideoFrames: kotlinx.coroutines.flow.Flow<ByteArray> = _remoteVideo

    init {
        scope.launch {
            chat.callSignals.collect { (contact, sig) ->
                runCatching { onSignal(contact, sig) }
            }
        }
        scope.launch {
            signaling.incomingCallStreams.collect { (peerId, s) ->
                // El hello puede tardar (red): cada stream entrante en su propia corrutina.
                launch { runCatching { onIncomingStream(peerId, s) } }
            }
        }
        scope.launch {
            signaling.incomingVideoStreams.collect { (peerId, s) ->
                launch { runCatching { onIncomingVideo(peerId, s) } }
            }
        }
    }

    /** Inicia una llamada saliente a [contact]. No-op si ya hay una en curso. */
    suspend fun startCall(contact: Contact) {
        val secret = contact.sharedSecret ?: return
        val started = mutex.withLock {
            if (!idle()) return@withLock false
            val callId = UUID.randomUUID().toString()
            // Clave de respaldo por si el otro extremo no negocia (contacto antiguo). Si
            // negocia, el accept la sustituye antes de que se abra el stream.
            callKey = deriveCallKey(secret, callId)
            localHalf = ByteArray(HALF_BYTES).also(random::nextBytes)
            remoteHalf = null
            _state.value = CallState(CallPhase.CALLING, contact, callId, outgoing = true)
            armTimeout(RING_TIMEOUT_MS) { onOutgoingTimeout() }
            true
        }
        if (!started) return
        chat.diagnose("📞 llamando a ${contact.displayName}…")
        runCatching { chat.sendCallSignal(contact, KIND_INVITE, _state.value.callId, localHalf) }
            .onFailure { endCall("sin conexión", sendHangup = false) }
    }

    /** Acepta la llamada entrante (fase RINGING). El que llama abrirá el stream. */
    suspend fun accept() {
        val st = mutex.withLock {
            val s = _state.value
            if (s.phase != CallPhase.RINGING) return
            _state.value = s.copy(phase = CallPhase.CONNECTING)
            armTimeout(CONNECT_TIMEOUT_MS) { endCall("no se pudo conectar", sendHangup = true) }
            s
        }
        runCatching { chat.sendCallSignal(st.contact!!, KIND_ACCEPT, st.callId, localHalf) }
            .onFailure { endCall("sin conexión", sendHangup = false) }
    }

    /** Rechaza la llamada entrante (RINGING). Vuelve directo a IDLE (fue decisión propia). */
    suspend fun reject() {
        val st = mutex.withLock {
            val s = _state.value
            if (s.phase != CallPhase.RINGING) return
            teardownLocked()
            _state.value = CallState()
            s
        }
        runCatching { chat.sendCallSignal(st.contact!!, KIND_REJECT, st.callId) }
    }

    /** Cuelga (cancela la saliente, o termina la activa). */
    suspend fun hangup() {
        val reason = if (_state.value.phase == CallPhase.CALLING) "cancelada" else "finalizada"
        endCall(reason, sendHangup = true)
    }

    /** Silencia/activa el micro. */
    fun setMuted(muted: Boolean) {
        runCatching { audio.setMuted(muted) }
    }

    /** Altavoz manos-libres on/off. */
    fun setSpeakerphone(on: Boolean) {
        runCatching { audio.setSpeakerphone(on) }
    }

    // --- Señalización entrante -----------------------------------------------------------

    private suspend fun onSignal(contact: Contact, sig: MessageEnvelope.Decoded.Call) {
        when (sig.kind) {
            KIND_INVITE -> onInvite(contact, sig)
            KIND_ACCEPT -> {
                val proceed = mutex.withLock {
                    val s = _state.value
                    (s.phase == CallPhase.CALLING && s.callId == sig.callId && s.contact?.id == contact.id)
                        .also {
                            if (it) {
                                // La otra mitad llega aquí, **antes** de abrir el stream: el
                                // primer byte de medios ya va con la clave negociada.
                                remoteHalf = sig.key
                                negotiatedKey(
                                    contact.sharedSecret, s.callId,
                                    caller = localHalf, callee = sig.key,
                                )?.let { negociada -> callKey = negociada }
                                _state.value = s.copy(phase = CallPhase.CONNECTING)
                            }
                        }
                }
                if (proceed) connectAsCaller()
            }
            KIND_REJECT -> if (currentCall(sig.callId)) endCall("rechazada", sendHangup = false)
            KIND_BUSY -> if (currentCall(sig.callId)) endCall("ocupado", sendHangup = false)
            KIND_HANGUP -> {
                val s = _state.value
                if (s.callId != sig.callId) return
                when (s.phase) {
                    // Colgó antes de que contestáramos → para nosotros es una perdida.
                    CallPhase.RINGING -> {
                        mutex.withLock {
                            teardownLocked()
                            _state.value = CallState()
                        }
                        runCatching { chat.recordMissedCall(contact) }
                    }
                    CallPhase.CALLING, CallPhase.CONNECTING, CallPhase.ACTIVE ->
                        endCall("finalizada", sendHangup = false)
                    else -> Unit
                }
            }
        }
    }

    private suspend fun onInvite(contact: Contact, sig: MessageEnvelope.Decoded.Call) {
        val secret = contact.sharedSecret ?: return
        // Un invite rancio (llegó por buzón mucho después; reloj adelantado cuenta como
        // fresco) ya no debe timbrar: fila de "llamada perdida" y listo.
        if (System.currentTimeMillis() - sig.ts > INVITE_FRESH_MS) {
            runCatching { chat.recordMissedCall(contact) }
            return
        }
        val busy = mutex.withLock {
            if (!idle()) {
                _state.value.callId != sig.callId // otra llamada distinta → ocupado
            } else {
                remoteHalf = sig.key
                localHalf = ByteArray(HALF_BYTES).also(random::nextBytes)
                // Si el otro negoció, la clave sale ya de las dos mitades: quien contesta las
                // tiene ambas desde este momento (la suya la acaba de sortear).
                callKey = negotiatedKey(secret, sig.callId, caller = sig.key, callee = localHalf)
                    ?: deriveCallKey(secret, sig.callId)
                _state.value = CallState(CallPhase.RINGING, contact, sig.callId, outgoing = false)
                armTimeout(RING_TIMEOUT_MS) { onRingingTimeout() }
                chat.diagnose("📞 llamada entrante de ${contact.displayName}")
                false
            }
        }
        if (busy) runCatching { chat.sendCallSignal(contact, KIND_BUSY, sig.callId) }
    }

    // --- Medios ---------------------------------------------------------------------------

    /** Lado que llama: tras el accept, abre el stream y manda el hello cifrado. */
    private suspend fun connectAsCaller() {
        val st = _state.value
        val key = callKey ?: return
        runCatching {
            val s = signaling.openCallStream(st.contact!!)
            s.sendFrame(cipher.encrypt(key, helloPayload(st.callId)))
            s
        }.onSuccess { startMedia(it) }
            .onFailure { endCall("no se pudo conectar", sendHangup = true) }
    }

    /** Lado que recibe: valida que el stream entrante trae el hello de la llamada aceptada. */
    private suspend fun onIncomingStream(peerId: String, s: CallStream) {
        val st = _state.value
        val key = callKey
        if (st.phase != CallPhase.CONNECTING || st.outgoing || st.contact?.peerId != peerId || key == null) {
            s.close()
            return
        }
        val hello = withTimeoutOrNull(HELLO_TIMEOUT_MS) { s.receiveFrame() }
        val valid = hello != null && runCatching {
            cipher.decrypt(key, hello).contentEquals(helloPayload(st.callId))
        }.getOrDefault(false)
        if (!valid) {
            s.close()
            return
        }
        startMedia(s)
    }

    /** Arranca los bombeos TX/RX y el motor de audio; pasa a ACTIVE. */
    private suspend fun startMedia(s: CallStream) {
        val ok = mutex.withLock {
            val st = _state.value
            val key = callKey
            if (st.phase != CallPhase.CONNECTING || key == null) return@withLock false
            stream = s
            timeoutJob?.cancel()
            // El hilo de captura encola sin bloquear (DROP_OLDEST bajo congestión: mejor
            // perder un frame viejo que acumular latencia); un solo TX preserva el orden.
            val tx = Channel<ByteArray>(64, BufferOverflow.DROP_OLDEST)
            txFrames = tx
            mediaJobs = listOf(
                scope.launch {
                    for (frame in tx) {
                        val sent = runCatching { s.sendFrame(cipher.encrypt(key, frame)) }
                        if (sent.isFailure) break
                    }
                },
                scope.launch {
                    while (true) {
                        val frame = s.receiveFrame() ?: break
                        runCatching { audio.onRemoteFrame(cipher.decrypt(key, frame)) }
                    }
                    endCall("finalizada", sendHangup = false) // el otro lado cerró
                },
            )
            _state.value = st.copy(phase = CallPhase.ACTIVE, startedAt = System.currentTimeMillis())
            true
        }
        if (!ok) {
            s.close()
            return
        }
        chat.diagnose("📞 en llamada")
        runCatching { audio.start { frame -> txFrames?.trySend(frame) } }
            .onFailure { endCall("error de audio", sendHangup = true) }
    }

    // --- Vídeo (Fase 7c) --------------------------------------------------------------------

    /**
     * Enciende el envío de cámara durante una llamada ACTIVE: abre el stream de vídeo hacia
     * el contacto, manda el hello cifrado y arranca el bombeo TX. El motor de cámara de la
     * app alimenta con [sendVideoFrame]. Best-effort: si falla, la llamada de voz sigue.
     * Devuelve true si el vídeo quedó activo.
     */
    suspend fun startVideo(): Boolean {
        val (contact, key, callId) = mutex.withLock {
            val s = _state.value
            val k = callKey
            if (s.phase != CallPhase.ACTIVE || s.videoSending || s.contact == null || k == null) return false
            Triple(s.contact, k, s.callId)
        }
        val opened = runCatching {
            val s = signaling.openVideoStream(contact)
            s.sendFrame(cipher.encrypt(key, videoHelloPayload(callId)))
            s
        }.getOrElse {
            chat.diagnose("🎥 no se pudo abrir el vídeo: ${(it.message ?: "$it").take(60)}")
            return false
        }
        mutex.withLock {
            val s = _state.value
            if (s.phase != CallPhase.ACTIVE) { // la llamada murió mientras abríamos
                scope.launch { runCatching { opened.close() } }
                return false
            }
            videoOut = opened
            val tx = Channel<ByteArray>(60, BufferOverflow.DROP_OLDEST)
            videoTx = tx
            videoInFlight.set(0)
            videoAwaitKey = false
            videoJobs = videoJobs + scope.launch {
                for (frame in tx) {
                    val sent = runCatching { opened.sendFrame(cipher.encrypt(key, frame)) }
                    videoInFlight.decrementAndGet()
                    if (sent.isFailure) break
                }
                // El stream murió (o se cerró en stopVideo): reflejarlo en el estado.
                mutex.withLock {
                    if (videoOut === opened) {
                        videoOut = null
                        videoTx = null
                        _state.update { st -> st.copy(videoSending = false) }
                    }
                }
            }
            _state.update { st -> st.copy(videoSending = true) }
        }
        chat.diagnose("🎥 vídeo activado")
        return true
    }

    /** Apaga el envío de cámara (la recepción del otro lado no se toca). */
    suspend fun stopVideo() {
        val out = mutex.withLock {
            val o = videoOut
            videoOut = null
            videoTx?.close(); videoTx = null
            _state.update { st -> st.copy(videoSending = false) }
            o
        }
        out?.let { runCatching { it.close() } }
        if (out != null) chat.diagnose("🎥 vídeo apagado")
    }

    // Frames de vídeo en vuelo (encolados y aún no escritos al stream). Si crece, la red
    // no da abasto: el vídeo debe ceder ANTES de ahogar el túnel — visto en vivo (6 jul):
    // con la cola larga, la voz (que comparte la conexión relayed) se congelaba también.
    private val videoInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var videoAwaitKey = false

    /**
     * Encola un frame de vídeo del encoder local (lo cifra y envía el bombeo TX), con
     * descarte **por grupos** bajo congestión: un frame delta depende del último keyframe,
     * así que tirar uno suelto pixelaría la imagen — si la cola pasa del umbral se descarta
     * todo hasta el próximo keyframe ([VideoFrame.KEY]), que refresca la imagen entera.
     * La rotación y el config ([VideoFrame.CONFIG]) nunca se descartan.
     */
    fun sendVideoFrame(frame: ByteArray) {
        val tx = videoTx ?: return
        val type = frame.firstOrNull()
        val isData = type == VideoFrame.KEY || type == VideoFrame.DELTA
        if (isData) {
            val isKey = type == VideoFrame.KEY
            if (!isKey && videoAwaitKey) return // descartando el resto del grupo roto
            if (!isKey && videoInFlight.get() >= VIDEO_CONGESTION_FRAMES) {
                videoAwaitKey = true // congestión: corta aquí, retoma en el próximo keyframe
                return
            }
            if (isKey) videoAwaitKey = false
        }
        if (tx.trySend(frame).isSuccess) videoInFlight.incrementAndGet()
    }

    /** Stream de vídeo entrante: solo se acepta el de la llamada activa, con hello válido. */
    private suspend fun onIncomingVideo(peerId: String, s: CallStream) {
        val st = _state.value
        val key = callKey
        val inCall = st.phase == CallPhase.ACTIVE || st.phase == CallPhase.CONNECTING
        if (!inCall || st.contact?.peerId != peerId || key == null) {
            s.close()
            return
        }
        val hello = withTimeoutOrNull(HELLO_TIMEOUT_MS) { s.receiveFrame() }
        val valid = hello != null && runCatching {
            cipher.decrypt(key, hello).contentEquals(videoHelloPayload(st.callId))
        }.getOrDefault(false)
        if (!valid) {
            s.close()
            return
        }
        val previous = mutex.withLock {
            val p = videoIn
            videoIn = s
            _state.update { cur -> cur.copy(videoReceiving = true) }
            p
        }
        previous?.let { runCatching { it.close() } } // reemplazo (p. ej. re-encendido)
        chat.diagnose("🎥 vídeo entrante de ${st.contact.displayName}")
        val job = scope.launch {
            while (true) {
                val frame = s.receiveFrame() ?: break
                runCatching { _remoteVideo.tryEmit(cipher.decrypt(key, frame)) }
            }
            mutex.withLock {
                if (videoIn === s) {
                    videoIn = null
                    _state.update { cur -> cur.copy(videoReceiving = false) }
                }
            }
        }
        mutex.withLock { videoJobs = videoJobs + job }
    }

    // --- Fin de llamada --------------------------------------------------------------------

    private suspend fun endCall(reason: String, sendHangup: Boolean) {
        val st = mutex.withLock {
            val s = _state.value
            if (s.phase == CallPhase.IDLE || s.phase == CallPhase.ENDED) return
            teardownLocked()
            _state.value = s.copy(
                phase = CallPhase.ENDED, endReason = reason,
                videoSending = false, videoReceiving = false,
            )
            s
        }
        chat.diagnose("📞 llamada terminada: $reason")
        if (sendHangup) st.contact?.let { runCatching { chat.sendCallSignal(it, KIND_HANGUP, st.callId) } }
        scheduleReset()
    }

    /** Suelta stream/audio/vídeo/jobs. Llamar con [mutex] tomado. */
    private fun teardownLocked() {
        timeoutJob?.cancel(); timeoutJob = null
        mediaJobs.forEach { it.cancel() }; mediaJobs = emptyList()
        txFrames?.close(); txFrames = null
        stream?.let { s -> scope.launch { runCatching { s.close() } } }
        stream = null
        videoJobs.forEach { it.cancel() }; videoJobs = emptyList()
        videoTx?.close(); videoTx = null
        videoOut?.let { s -> scope.launch { runCatching { s.close() } } }
        videoOut = null
        videoIn?.let { s -> scope.launch { runCatching { s.close() } } }
        videoIn = null
        runCatching { audio.stop() }
        callKey = null
        localHalf = null
        remoteHalf = null
    }

    private suspend fun onOutgoingTimeout() {
        if (_state.value.phase == CallPhase.CALLING) endCall("sin respuesta", sendHangup = true)
    }

    /** Nadie contestó aquí: registrar perdida y volver a IDLE sin pantalla de "finalizada". */
    private suspend fun onRingingTimeout() {
        val st = mutex.withLock {
            val s = _state.value
            if (s.phase != CallPhase.RINGING) return
            teardownLocked()
            _state.value = CallState()
            s
        }
        st.contact?.let { runCatching { chat.recordMissedCall(it) } }
    }

    private fun scheduleReset() {
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(ENDED_LINGER_MS)
            mutex.withLock { if (_state.value.phase == CallPhase.ENDED) _state.value = CallState() }
        }
    }

    // --- Utilidades -------------------------------------------------------------------------

    private fun idle(): Boolean =
        _state.value.phase == CallPhase.IDLE || _state.value.phase == CallPhase.ENDED

    private fun currentCall(callId: String): Boolean = _state.value.callId == callId

    private fun armTimeout(ms: Long, action: suspend () -> Unit) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(ms)
            runCatching { action() }
        }
    }

    /** Camino antiguo: la clave es función del secreto estático, así que no tiene PFS. */
    private fun deriveCallKey(sharedSecret: ByteArray, callId: String): ByteArray =
        Hkdf.derive(sharedSecret, CALL_KEY_SALT, callId.toByteArray(Charsets.UTF_8), 32)

    /**
     * Clave negociada de la llamada: `HKDF(k_llamante ‖ k_contestador, salt = secreto)`. Null
     * si falta alguna mitad (contacto que no negocia) → se usa el camino antiguo.
     *
     * El orden es siempre llamante-primero, que ambos extremos conocen sin hablarlo. Y el
     * secreto compartido va de sal para que la clave siga atada a **esa pareja**: sin él, dos
     * mitades interceptadas bastarían.
     */
    private fun negotiatedKey(
        sharedSecret: ByteArray?,
        callId: String,
        caller: ByteArray?,
        callee: ByteArray?,
    ): ByteArray? {
        if (sharedSecret == null || caller == null || callee == null) return null
        return Hkdf.derive(
            ikm = caller + callee,
            salt = sharedSecret,
            info = "krypta-call-key-v2:$callId".toByteArray(Charsets.UTF_8),
            length = 32,
        )
    }

    private fun helloPayload(callId: String): ByteArray =
        "HELLO:$callId".toByteArray(Charsets.UTF_8)

    /** Hello del canal de vídeo: distinto del de audio para que no sean intercambiables. */
    private fun videoHelloPayload(callId: String): ByteArray =
        "VHELLO:$callId".toByteArray(Charsets.UTF_8)

    // `internal` (no `private`): el test que fija que la clave ya NO sale del secreto estático
    // tiene que usar la sal de verdad. Escribiéndola a mano, la aserción pasa por el motivo
    // equivocado — ninguna clave inventada abre nada.
    internal companion object {
        /** Bytes que aporta cada lado a la clave de la llamada. */
        const val HALF_BYTES = 32

        const val KIND_INVITE = "invite"
        const val KIND_ACCEPT = "accept"
        const val KIND_REJECT = "reject"
        const val KIND_HANGUP = "hangup"
        const val KIND_BUSY = "busy"

        /** Cuánto suena una llamada (ambos lados) antes de "sin respuesta"/perdida. */
        const val RING_TIMEOUT_MS = 45_000L
        /** Un invite más viejo que esto (p. ej. del buzón) ya no timbra: perdida. */
        const val INVITE_FRESH_MS = 45_000L
        /** Tras aceptar, cuánto esperar el stream de medios. */
        const val CONNECT_TIMEOUT_MS = 20_000L
        /** Espera del hello en un stream entrante. */
        const val HELLO_TIMEOUT_MS = 10_000L
        /** Cuánto se muestra la pantalla "terminada" antes de volver a IDLE. */
        const val ENDED_LINGER_MS = 3_000L

        /**
         * Frames de vídeo en vuelo a partir de los cuales hay congestión y el vídeo cede
         * (descarta hasta el próximo keyframe). ~1 s a 12 fps.
         */
        const val VIDEO_CONGESTION_FRAMES = 12

        val CALL_KEY_SALT = "krypta-call-v1".toByteArray(Charsets.UTF_8)
    }
}

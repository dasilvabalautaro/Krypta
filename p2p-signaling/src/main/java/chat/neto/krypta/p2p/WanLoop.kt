package chat.neto.krypta.p2p

import chat.neto.krypta.core.ISignalingService
import chat.neto.krypta.p2p.ChatService.Companion.CAPABILITIES_BUDGET_MS
import chat.neto.krypta.p2p.ChatService.Companion.CONNECT_BUDGET_MS
import chat.neto.krypta.p2p.ChatService.Companion.CYCLE_BUDGET_MS
import chat.neto.krypta.p2p.ChatService.Companion.MAILBOX_BUDGET_MS
import chat.neto.krypta.p2p.ChatService.Companion.REDISCOVER_MS
import chat.neto.krypta.p2p.ChatService.Companion.RELAY_BUDGET_MS
import chat.neto.krypta.p2p.ChatService.Companion.RENDEZVOUS_BUDGET_MS
import chat.neto.krypta.p2p.ChatService.Companion.RETRY_BUDGET_MS
import chat.neto.krypta.p2p.ChatService.Companion.WAKE_IDLE_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Lo que hace cada ciclo WAN; lo pone [ChatService], que es quien sabe de buzón y contactos. */
internal interface WanSteps {
    suspend fun inboxLabels(): String
    suspend fun fetchMailbox()
    suspend fun logRelayStatus()
    suspend fun announceAndFind()
    suspend fun retryFailed()
    suspend fun announceCapabilities()
}

/**
 * El **bucle WAN**: conectar a la DHT, mantener el wake, retirar el buzón, renovar el relay,
 * anunciarse por rendezvous, reintentar los fallidos y anunciar capacidades, cada paso con su
 * plazo y el intervalo adaptado a si el wake está en pie. Se separó de `ChatService` el 12 oct
 * 2026 (§5 de docs/AUDITORIA-2026-10-12.md) sin cambiar nada de lo que hace; los pasos siguen
 * siendo de `ChatService` ([WanSteps]).
 */
internal class WanLoop(
    private val signaling: ISignalingService,
    private val scope: CoroutineScope,
    private val log: DiagnosticsLog,
    private val steps: WanSteps,
) {
    private fun logLine(msg: String) = log.line(msg)

    /** Los nodos con los que corre el bucle; null con la WAN apagada (solo LAN). */
    @Volatile
    var bootstrapAddr: String? = null
        private set

    private val _wanStatus = MutableStateFlow(WanStatus.DISABLED)

    /** Estado de la conexión a la DHT (WAN). */
    val wanStatus: StateFlow<WanStatus> = _wanStatus.asStateFlow()

    // Lo tocan `start` (desde la UI, el servicio o el latido) y `setBootstrap` (desde la UI), en
    // hilos distintos: se lee y escribe bajo [wanLock] para no lanzar dos bucles WAN (O-10).
    private var wanJob: Job? = null

    private val wanLock = Any()

    /** Fija los nodos y arranca el bucle si no estaba en marcha. Idempotente. */
    fun start(bootstrap: String) {
        bootstrapAddr = bootstrap
        synchronized(wanLock) { if (wanJob == null) wanJob = scope.launch { loop() } }
        // Stream ligero de wake: el nodo avisa al instante cuando hay correo en el buzón.
        scope.launch { runCatching { signaling.startWake(steps.inboxLabels()) } }
    }

    /** Detiene el bucle WAN y vuelve a DISABLED (solo LAN/mDNS). */
    fun stop() {
        synchronized(wanLock) {
            wanJob?.cancel()
            wanJob = null
        }
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
    private suspend fun loop() {
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
            // Espera interrumpible: [kick] (p. ej. al cambiar de red) adelanta el ciclo.
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
        step("wake", CONNECT_BUDGET_MS) { signaling.startWake(steps.inboxLabels()) }

        // Aunque el DHT no haya conectado: el buzón se retira por dial directo a cada nodo,
        // y es la vía que entrega los mensajes. Antes iba dentro del `if` del DHT, así que un
        // fallo al conectar dejaba el correo sin recoger.
        step("buzón", MAILBOX_BUDGET_MS) { steps.fetchMailbox() }

        if (connected) {
            step("relay", RELAY_BUDGET_MS) { steps.logRelayStatus() }
            step("rendezvous", RENDEZVOUS_BUDGET_MS) { steps.announceAndFind() }
            step("reintentos", RETRY_BUDGET_MS) { steps.retryFailed() }
            step("capacidades", CAPABILITIES_BUDGET_MS) { steps.announceCapabilities() }
        }
    }

    /**
     * Ejecuta un paso del ciclo con plazo y sin dejar que su fallo tumbe el resto. Devuelve
     * `true` solo si terminó a tiempo y sin excepción. Solo loguea al vencer el plazo (un
     * paso lento repetido llenaría el diagnóstico).
     */
    suspend fun step(name: String, budgetMs: Long, block: suspend () -> Unit): Boolean {
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
    fun kick() {
        wanKick.trySend(Unit)
    }

}

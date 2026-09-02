package chat.neto.krypta

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.IBinder
import chat.neto.krypta.p2p.CallPhase
import chat.neto.krypta.p2p.CallService
import chat.neto.krypta.p2p.CallState
import chat.neto.krypta.p2p.ChatService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Servicio en primer plano que mantiene Krypta recibiendo **con la app cerrada** (Fase 5):
 * sostiene el nodo libp2p (host + bucle WAN + stream de wake). Sobrevive al swipe de la UI
 * (`START_STICKY`); junto con el wake integrado del nodo, un depósito en el buzón llega al
 * móvil en segundos.
 *
 * **Los avisos ya no se postean aquí**: los dueños de la notificación de mensaje y del timbre
 * de llamada son [IncomingNotifier] + [KryptaNotifications], enganchados desde
 * [KryptaApplication], porque este servicio puede no existir en un proceso revivido solo por
 * el latido de entrega — y ahí se perdían avisos en silencio.
 */
@AndroidEntryPoint
class KryptaForegroundService : Service() {

    @Inject
    lateinit var chat: ChatService

    // Inyectarlo aquí garantiza que CallService (y sus colectores de señales) viven desde
    // que arranca el servicio: un invite puede llegar con la UI cerrada.
    @Inject
    lateinit var calls: CallService

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Mantiene el WiFi despierto en segundo plano: muchos OEM (Transsion/TECNO, Xiaomi…)
    // apagan el WiFi al apagar la pantalla estando a batería, lo que mata el stream de wake y
    // la recepción. El WifiLock lo contrarresta mientras el servicio vive.
    private var wifiLock: WifiManager.WifiLock? = null

    // Al cambiar la red (WiFi↔datos, recuperar cobertura) adelanta el ciclo WAN: reconecta
    // DHT + relay + wake y retira el buzón al instante en vez de esperar los 30 s.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            chat.kickWan()
        }
    }

    override fun onCreate() {
        super.onCreate()
        KryptaNotifications.ensureChannels(this)
        // specialUse (no dataSync): Android 15 corta los FGS dataSync a las 6 h — fatal
        // para una conexión de mensajería persistente.
        startForeground(
            KryptaNotifications.ONGOING_ID,
            KryptaNotifications.serviceNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        scope.launch { runCatching { chat.start() } }
        // El aviso por mensaje y el timbre los pone IncomingNotifier (ver el KDoc de la clase);
        // aquí solo queda el tipo de FGS, que sí es cosa del servicio.
        scope.launch {
            calls.state.collect { st -> runCatching { onCallState(st) } }
        }
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                .registerDefaultNetworkCallback(networkCallback)
        }
        runCatching {
            @Suppress("DEPRECATION") // FULL_HIGH_PERF: el único modo que mantiene el radio
            // despierto con la pantalla apagada (LOW_LATENCY solo aplica en primer plano).
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "krypta:wifi")
                .apply { setReferenceCounted(false); acquire() }
        }
        // Latido de entrega: red de seguridad para OEM que suspenden la red en 2.º plano.
        HeartbeatReceiver.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * El usuario quitó Krypta de **recientes** (en HiOS, el botón "Cerrar todo"). Eso mata el
     * proceso, el servicio y su aviso: medido en el TECNO el 2 sep 2026, el pid desaparece al
     * instante y con él la conexión.
     *
     * `START_STICKY` no basta —el sistema no rearranca un servicio cuya tarea ha quitado el
     * usuario— así que aquí se pide un latido **inmediato**: es la misma vía que ya resucita
     * la app sola (verificado: el sistema lo permite con `Background started FGS: Allowed`,
     * `code:SYSTEM_ALLOW_LISTED`, por la exención de batería). Sin esto había que esperar al
     * latido normal: se midieron **~160 s a ciegas**, sin recibir nada y sin aviso, que el
     * usuario lee como "Krypta se apagó".
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        HeartbeatReceiver.scheduleNow(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(networkCallback)
        }
        wifiLock?.let { runCatching { if (it.isHeld) it.release() } }
        // OJO: aquí NO se cancela el latido. Nada en la app para este servicio a propósito
        // (no hay un solo stopSelf/stopService), así que si llegamos aquí es porque el
        // sistema o el OEM lo está tumbando — justo cuando el latido es lo ÚNICO que puede
        // devolver la app a la vida. Cancelarlo mataba la red de seguridad en el único
        // escenario para el que existe. Se pide uno inmediato por el mismo motivo.
        HeartbeatReceiver.scheduleNow(this)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // 7d: en llamada el FGS declara también el tipo **microphone**: Android/OEM saben que
    // hay captura de audio activa (el micro sigue vivo con la pantalla apagada por el
    // sensor de proximidad) y el proceso gana prioridad frente a los limpiadores. El tipo
    // `phoneCall` de verdad exige Telecom (ConnectionService) — aplazado a esa integración.
    // El upgrade ocurre al aceptar/conectar (la UI está en primer plano, así que el tipo
    // while-in-use está permitido); si el sistema lo rechaza, seguimos en specialUse.
    private var inCallTypes = false
    private fun updateForegroundType(inCall: Boolean) {
        if (inCall == inCallTypes) return
        val types = if (inCall) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        val ok = runCatching {
            startForeground(
                KryptaNotifications.ONGOING_ID,
                KryptaNotifications.serviceNotification(this),
                types,
            )
        }.isSuccess
        if (ok) inCallTypes = inCall
    }

    /** Solo el tipo de FGS: el timbre y la notificación de llamada los pone [IncomingNotifier]. */
    private fun onCallState(state: CallState) {
        updateForegroundType(state.phase == CallPhase.CONNECTING || state.phase == CallPhase.ACTIVE)
    }

    companion object {
        /** Arranca el servicio (idempotente: si ya corre, Android reutiliza la instancia). */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, KryptaForegroundService::class.java))
        }
    }
}

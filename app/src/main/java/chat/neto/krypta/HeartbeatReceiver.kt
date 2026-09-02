package chat.neto.krypta

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import chat.neto.krypta.p2p.ChatService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Latido" de entrega: un `AlarmManager` despierta la app cada [INTERVAL_MS] con
 * `setAndAllowWhileIdle` (atraviesa Doze; sin límite al estar exenta de batería) y retira el
 * buzón. Es la **red de seguridad** para móviles que **suspenden la red en segundo plano**
 * (Transsion/TECNO, Xiaomi…), donde el stream de wake queda dormido y los mensajes no llegan
 * hasta abrir la app. El wake sigue siendo el camino rápido cuando la red está viva; esto
 * garantiza la entrega (y la notificación) en ≤ ~2 min aunque no lo esté.
 */
@AndroidEntryPoint
class HeartbeatReceiver : BroadcastReceiver() {

    @Inject
    lateinit var chat: ChatService

    override fun onReceive(context: Context, intent: Intent) {
        // goAsync + wakelock: mantiene CPU y ventana de red mientras se retira el buzón.
        val pending = goAsync()
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "krypta:heartbeat")
            .apply { acquire(WAKELOCK_MS) }
        // Si el OEM mató el proceso, esta alarma es lo único que lo revive: relanzar el
        // servicio deja el nodo y el stream de wake otra vez en pie (y no solo hasta el
        // siguiente latido). La alarma `setAndAllowWhileIdle` abre la ventana que permite
        // arrancar un FGS desde segundo plano; si el sistema aun así lo rechaza, el aviso
        // igualmente sale porque IncomingNotifier vive en la Application.
        runCatching { KryptaForegroundService.start(context) }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                chat.pollOnce()
            } finally {
                schedule(context) // reprograma el siguiente latido (la alarma es de un disparo)
                if (wakeLock.isHeld) runCatching { wakeLock.release() }
                pending.finish()
            }
        }
    }

    companion object {
        private const val INTERVAL_MS = 2 * 60 * 1000L
        // Margen para que el sistema termine de desmontar el proceso antes de relanzarlo.
        private const val RESTART_DELAY_MS = 3_000L
        private const val WAKELOCK_MS = 25_000L

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 0, Intent(context, HeartbeatReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** Programa el próximo latido. `setAndAllowWhileIdle` atraviesa Doze. */
        fun schedule(context: Context) = scheduleIn(context, INTERVAL_MS)

        /**
         * Latido **cuanto antes**, para resucitar la app tras una muerte del servicio: lo piden
         * `onTaskRemoved` (el usuario dio a "Cerrar todo" en recientes) y `onDestroy`. Reusa la
         * misma vía que ya funciona sola —el receptor relanza el FGS y retira el buzón—, pero
         * sin esperar los 2 min del ciclo normal, que en el TECNO se midieron como ~160 s con
         * la app a ciegas.
         *
         * No es 0 ms a propósito: al quitar la tarea el sistema aún está desmontando el
         * proceso, y un rearranque instantáneo se pisa con esa limpieza.
         */
        fun scheduleNow(context: Context) = scheduleIn(context, RESTART_DELAY_MS)

        private fun scheduleIn(context: Context, delayMs: Long) {
            runCatching {
                context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + delayMs,
                    pendingIntent(context),
                )
            }
        }
    }
}

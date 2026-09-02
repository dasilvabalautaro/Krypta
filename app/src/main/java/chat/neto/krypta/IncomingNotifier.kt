package chat.neto.krypta

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.core.model.Message
import chat.neto.krypta.p2p.CallPhase
import chat.neto.krypta.p2p.CallService
import chat.neto.krypta.p2p.CallState
import chat.neto.krypta.p2p.ChatService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dueño único de los **avisos al usuario** (mensajes y llamadas entrantes). Lo instancia
 * [KryptaApplication] en `onCreate`, así que existe **siempre que exista el proceso**, venga
 * de donde venga: la Activity, el [KryptaForegroundService] o un simple `BroadcastReceiver`
 * ([HeartbeatReceiver], [BootReceiver]).
 *
 * Ese "siempre" es justo el arreglo. Antes el aviso lo posteaba el servicio en primer plano
 * coleccionando `ChatService.incoming`, un `SharedFlow` sin replay: cuando el OEM
 * (Transsion/TECNO y compañía) mataba el proceso y lo revivía **solo la alarma del latido**,
 * el servicio no existía, nadie coleccionaba, y el mensaje se retiraba del buzón, se
 * persistía, se confirmaba al nodo (borrándolo allí) y el aviso **se descartaba en silencio**.
 * Resultado observado: "el mensaje está, pero no sonó nada" — y al abrir la app aparecía sin
 * haber avisado nunca. Lo mismo con las llamadas: `CallService` solo se instanciaba desde el
 * servicio, así que un `invite` que llegaba por el buzón en un proceso revivido por la alarma
 * se perdía sin timbrar. Aquí se inyecta [calls] por eso: para que el consumidor de señales
 * de llamada exista desde el arranque del proceso.
 *
 * Además decide **cuándo callar**: solo si estás mirando *esa misma* conversación. Antes
 * bastaba con tener la app abierta en cualquier pantalla para que no sonara nada, que es otra
 * fuente de "no me avisó".
 */
@Singleton
class IncomingNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chat: ChatService,
    private val calls: CallService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    /** Visibilidad de la UI, observada en el hilo PRINCIPAL (leerla desde otro no es fiable). */
    @Volatile
    private var uiVisible = false

    /** Conversación abierta ahora mismo (null = ninguna). La fija/limpia la pantalla de chat. */
    @Volatile
    private var visibleConversationId: String? = null

    private var ringtone: Ringtone? = null
    private var attached = false

    /** Llamado una vez desde [KryptaApplication.onCreate]. Idempotente. */
    fun attach() {
        if (attached) return
        attached = true
        KryptaNotifications.ensureChannels(context)
        main.post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    uiVisible = true
                    // Abrir la app limpia la bandeja y el conteo del icono de una vez. Los no
                    // leídos por contacto NO se tocan: viven en Room y solo los borra entrar
                    // en la conversación, así que la lista sigue mostrando su badge.
                    KryptaNotifications.cancelAllMessages(context)
                }

                override fun onStop(owner: LifecycleOwner) {
                    uiVisible = false
                    visibleConversationId = null
                }
            })
        }
        // Gancho directo (no un Flow): se invoca en el sitio tras persistir cada entrante, así
        // que no depende de que haya un suscriptor vivo. Ver ChatService.setIncomingNotifier.
        chat.setIncomingNotifier { contact, message -> onIncoming(contact, message) }
        scope.launch { calls.state.collect { st -> runCatching { onCallState(st) } } }
    }

    /** La pantalla de chat declara qué conversación se está mirando (null al salir). */
    fun setVisibleConversation(contactId: String?) {
        visibleConversationId = contactId
        if (contactId != null) KryptaNotifications.cancel(context, contactId)
    }

    private fun onIncoming(contact: Contact, message: Message) {
        // Solo se calla si tienes ESA conversación delante; con la app abierta en la lista,
        // en ajustes o en otro chat sí avisa (heads-up), como cualquier mensajería.
        if (uiVisible && visibleConversationId == contact.id) return
        val text = runCatching { chat.notificationText(contact, message) }
            .getOrDefault("Mensaje nuevo")
        runCatching {
            KryptaNotifications.notifyMessage(
                context, contact.id, contact.displayName, text, message.timestamp,
            )
        }
    }

    /**
     * Timbre + vibración + notificación mientras la llamada entrante suena; se retiran al
     * salir de RINGING. También levanta el servicio en primer plano: si el proceso lo revivió
     * la alarma, el nodo/audio deben estar vivos para poder contestar.
     */
    private fun onCallState(state: CallState) {
        if (state.phase == CallPhase.RINGING) {
            runCatching { KryptaForegroundService.start(context) }
            startRinging()
            KryptaNotifications.notifyIncomingCall(context, state.contact?.displayName ?: "Contacto")
        } else {
            stopRinging()
            KryptaNotifications.cancelCall(context)
        }
    }

    private fun startRinging() {
        if (ringtone != null) return
        runCatching {
            ringtone = RingtoneManager.getRingtone(
                context,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            )?.apply {
                // Explícito: sin estos atributos el tono puede salir por el stream de música
                // (volumen equivocado, silenciado con el timbre alto).
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                isLooping = true
                runCatching { play() }
            }
        }
        // Vibración en bucle aparte del tono: en silencio/vibración el timbre no suena y la
        // vibración del canal solo daría un pulso al postear la notificación.
        runCatching {
            vibrator()?.vibrate(
                VibrationEffect.createWaveform(VIBRATE_PATTERN, 0),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build(),
            )
        }
    }

    private fun stopRinging() {
        ringtone?.let { runCatching { it.stop() } }
        ringtone = null
        runCatching { vibrator()?.cancel() }
    }

    /**
     * `VibratorManager` es API 31 y el `minSdk` es 30: en Android 11 la referencia a la clase
     * no resuelve, así que ahí hay que caer al `Vibrator` clásico (obsoleto desde la 31, pero
     * es el único que existe en la 30). Sin este reparto, el móvil más antiguo que soportamos
     * se quedaba **sin vibración de llamada** — y en silencio la vibración es el único aviso.
     */
    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    private companion object {
        // espera, vibra, espera, vibra… (se repite desde el índice 0)
        val VIBRATE_PATTERN = longArrayOf(0, 700, 900)
    }
}

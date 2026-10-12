package chat.neto.krypta

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Qué enseña un aviso de mensaje o de llamada. De más a menos revelador. */
enum class NotificationContent { NAME_AND_MESSAGE, NAME_ONLY, NOTHING }

/**
 * Cuánto dice un aviso. Hasta el 12 oct 2026 cada aviso llevaba siempre el nombre del contacto y
 * el **texto descifrado**, y ese texto sale de Krypta: se ve en la pantalla de bloqueo (según el
 * ajuste del sistema), lo lee cualquier app con acceso a notificaciones (relojes, launchers,
 * "asistentes"), queda en el historial de notificaciones si está activado y se ve en la cortina
 * aunque el bloqueo de la app esté puesto (O-4 de docs/AUDITORIA-2026-10-12.md).
 *
 * Mismo patrón que [ThemePreference]: estado en el proceso, preferencia en `krypta_settings`.
 * Por defecto [NotificationContent.NAME_AND_MESSAGE], que es el comportamiento de siempre y el de
 * cualquier mensajería; quien quiera menos lo elige en Ajustes.
 */
object NotificationPrivacy {

    private const val KEY_CONTENT = "notification_content"

    private val _content = MutableStateFlow(NotificationContent.NAME_AND_MESSAGE)
    val content: StateFlow<NotificationContent> = _content

    @Volatile
    private var initialized = false

    private fun settings(context: Context) =
        context.getSharedPreferences("krypta_settings", Context.MODE_PRIVATE)

    /** Carga la preferencia guardada. Idempotente y sin hilo exigido. */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        _content.value = read(settings(context).getString(KEY_CONTENT, null))
    }

    fun setContent(context: Context, value: NotificationContent) {
        settings(context).edit().putString(KEY_CONTENT, value.name).apply()
        _content.value = value
    }

    /** Lo que se muestra de verdad ahora mismo, con el bloqueo de la app tenido en cuenta. */
    fun current(context: Context): NotificationContent {
        init(context)
        return effective(_content.value, AppLock.isEnabled(context))
    }

    /** Tolera un valor ausente o corrupto cayendo al de siempre. */
    internal fun read(stored: String?): NotificationContent =
        runCatching { NotificationContent.valueOf(stored ?: NotificationContent.NAME_AND_MESSAGE.name) }
            .getOrDefault(NotificationContent.NAME_AND_MESSAGE)

    /**
     * Con el **bloqueo de la app** activado nunca se enseña el texto: el bloqueo promete que quien
     * coja el móvil no lee tus conversaciones sin desbloquear, y la cortina se lo saltaba. El
     * nombre sí, salvo que se haya elegido no enseñar nada. Pura, para probarla en la JVM.
     */
    fun effective(chosen: NotificationContent, appLockEnabled: Boolean): NotificationContent =
        if (appLockEnabled && chosen == NotificationContent.NAME_AND_MESSAGE) NotificationContent.NAME_ONLY
        else chosen
}

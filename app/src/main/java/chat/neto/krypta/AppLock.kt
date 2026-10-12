package chat.neto.krypta

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Bloqueo de acceso a la app con la credencial del sistema (huella/cara o el PIN/patrón del
 * propio móvil, vía [BiometricPrompt]). Krypta no guarda ningún secreto de desbloqueo: si el
 * sistema reconoce al usuario, se entra — mismo modelo que Signal/WhatsApp.
 *
 * El estado vive en el proceso (singleton) y la preferencia en `krypta_settings`. El
 * re-bloqueo usa [ProcessLifecycleOwner] (no el ciclo de la Activity): así una rotación o un
 * cambio de configuración no cuentan como "salir de la app", solo pasar de verdad a segundo
 * plano, y al volver se re-bloquea si pasó el período de gracia configurado.
 */
object AppLock {

    /** Períodos de gracia seleccionables (ms tras pasar a 2.º plano antes de re-bloquear). */
    const val GRACE_IMMEDIATE = 0L
    const val GRACE_1_MIN = 60_000L
    const val GRACE_5_MIN = 300_000L

    private const val KEY_ENABLED = "app_lock_enabled"
    private const val KEY_GRACE = "app_lock_grace_ms"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private val _graceMs = MutableStateFlow(GRACE_IMMEDIATE)
    val graceMs: StateFlow<Long> = _graceMs

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked

    private var initialized = false
    private var hiddenAt = 0L // 0 = la app no ha pasado a 2.º plano desde el último frente

    private val visibilityObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (shouldRelock(_enabled.value, hiddenAt, System.currentTimeMillis(), _graceMs.value)) {
                _locked.value = true
            }
            hiddenAt = 0L
        }

        override fun onStop(owner: LifecycleOwner) {
            hiddenAt = System.currentTimeMillis()
        }
    }

    private fun settings(context: Context) =
        context.getSharedPreferences("krypta_settings", Context.MODE_PRIVATE)

    /**
     * Carga la preferencia y arma el observador de visibilidad. Idempotente; llamar en el
     * hilo principal (lo exige [ProcessLifecycleOwner]). En arranque en frío la app nace
     * bloqueada si el bloqueo está activado, sea cual sea el período de gracia.
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = settings(context)
        _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
        _graceMs.value = prefs.getLong(KEY_GRACE, GRACE_IMMEDIATE)
        _locked.value = _enabled.value
        ProcessLifecycleOwner.get().lifecycle.addObserver(visibilityObserver)
    }

    /**
     * ¿Está activado? Vale también en un proceso que no ha pasado por [init] —uno revivido por el
     * latido, donde no hay Activity— leyendo la preferencia. Lo usa [NotificationPrivacy].
     */
    fun isEnabled(context: Context): Boolean =
        if (initialized) _enabled.value else settings(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        settings(context).edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
        if (!value) _locked.value = false
    }

    fun setGraceMs(context: Context, value: Long) {
        settings(context).edit().putLong(KEY_GRACE, value).apply()
        _graceMs.value = value
    }

    fun unlock() {
        _locked.value = false
    }

    /**
     * Comprueba si el móvil puede autenticar con biometría o credencial del dispositivo.
     * Devuelve `null` si puede, o un mensaje explicando qué falta.
     */
    fun availabilityProblem(context: Context): String? {
        val manager = context.getSystemService(BiometricManager::class.java)
            ?: return "Este móvil no soporta la autenticación del sistema."
        val code = runCatching { manager.canAuthenticate(AUTHENTICATORS) }
            .getOrDefault(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE)
        return when (code) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                "Configura primero un bloqueo de pantalla (PIN, patrón o huella) en los " +
                    "ajustes del sistema."
            else -> "La autenticación del sistema no está disponible en este móvil."
        }
    }

    /**
     * Lanza el diálogo nativo de autenticación. `onResult(true)` solo con autenticación
     * correcta; cancelar o agotar intentos da `false` (el prompt gestiona los reintentos de
     * huella fallidos por sí mismo).
     */
    fun authenticate(activity: Activity, title: String, onResult: (Boolean) -> Unit) {
        val launched = runCatching {
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
            prompt.authenticate(
                CancellationSignal(),
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult?,
                    ) = onResult(true)

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) =
                        onResult(false)
                },
            )
        }
        // Que un fallo del prompt (OEM raro, servicio caído) nunca tumbe la app: cuenta
        // como autenticación fallida y el usuario puede reintentar con el botón.
        if (launched.isFailure) onResult(false)
    }

    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /**
     * Decisión pura de re-bloqueo al volver al frente: bloqueado si el bloqueo está activado,
     * la app llegó a pasar a 2.º plano ([hiddenAtMs] > 0) y el tiempo fuera alcanzó el
     * período de gracia. Separada para poder testearla en JVM.
     */
    internal fun shouldRelock(
        enabled: Boolean,
        hiddenAtMs: Long,
        nowMs: Long,
        graceMs: Long,
    ): Boolean = enabled && hiddenAtMs > 0 && nowMs - hiddenAtMs >= graceMs
}

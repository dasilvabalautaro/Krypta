package chat.neto.krypta

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import chat.neto.krypta.ui.KryptaApp
import chat.neto.krypta.ui.theme.KryptaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* opcional */ }

    // Contacto a abrir desde una notificación (deep-link). Es estado Compose: al llegar un
    // onNewIntent con la app viva, la UI recompone y navega a esa conversación.
    private var openContactId by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(KryptaNotifications.EXTRA_OPEN_CONTACT)?.let { openContactId = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nota: el bloqueo de captura (FLAG_SECURE) ya no se pone aquí. Es por pantalla —
        // solo mientras se mira un chat — y lo gestiona `SecureScreenEffect` en la propia
        // pantalla de chat. Ver [ScreenSecurity].
        enableEdgeToEdge()
        // Bloqueo de acceso: cargar la preferencia y armar el observador de visibilidad
        // antes de componer, para que un arranque en frío ya nazca bloqueado.
        AppLock.init(applicationContext)
        // Preferencia de tema (claro/oscuro/sistema); por defecto sigue al sistema.
        ThemePreference.init(applicationContext)
        openContactId = intent.getStringExtra(KryptaNotifications.EXTRA_OPEN_CONTACT)
        // Android 13+ exige permiso en tiempo de ejecución para notificar mensajes nuevos.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Exención de Doze: sin ella el sistema congela el bucle WAN con la pantalla
        // apagada y los mensajes esperan a la ventana de mantenimiento (minutos).
        val power = getSystemService(PowerManager::class.java)
        if (!power.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                @Suppress("BatteryLife") // messenger P2P sin push de Google: caso legítimo
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"),
                    )
                )
            }
        }
        // El servicio mantiene el nodo (y las notificaciones) vivos al cerrar la UI.
        KryptaForegroundService.start(this)
        setContent {
            val themeMode by ThemePreference.mode.collectAsState()
            KryptaTheme(darkTheme = ThemePreference.resolveDark(themeMode, isSystemInDarkTheme())) {
                KryptaApp(
                    openContactId = openContactId,
                    onOpenConsumed = { openContactId = null },
                )
            }
        }
    }
}

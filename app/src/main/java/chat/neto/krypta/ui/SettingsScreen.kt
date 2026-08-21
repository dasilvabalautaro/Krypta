package chat.neto.krypta.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.neto.krypta.AppLock
import chat.neto.krypta.KryptaNotifications
import chat.neto.krypta.ThemeMode
import chat.neto.krypta.ThemePreference
import chat.neto.krypta.p2p.WanStatus

/**
 * Ajustes: identidad (PeerID para compartir), red WAN (bootstrap + estado), recepción en
 * segundo plano y el panel de diagnóstico. Todo lo técnico que antes abarrotaba la pantalla
 * principal vive aquí.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    myPeerId: String,
    bootstrap: String,
    bootstrapError: String?,
    wanStatus: WanStatus,
    diagnostics: List<String>,
    backupMessage: String?,
    restoredPeerId: String?,
    onBack: () -> Unit,
    onOpenHelp: () -> Unit,
    onSetBootstrap: (String) -> Unit,
    onProbeLatency: () -> Unit,
    onExportBackup: (passphrase: String, uri: Uri) -> Unit,
    onImportBackup: (passphrase: String, uri: Uri) -> Unit,
    onBackupMessageShown: () -> Unit,
) {
    val context = LocalContext.current
    var bootstrapDraft by remember(bootstrap) { mutableStateOf(bootstrap) }

    // Ayuda contextual: diálogo (título, texto) que abren los iconos "ⓘ" de ciertas tarjetas.
    var infoDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    // --- Copia de seguridad: lanzadores SAF + diálogos de passphrase --------------------
    var askExportPass by remember { mutableStateOf(false) }
    var exportPass by remember { mutableStateOf("") } // passphrase a la espera del destino SAF
    var importUri by remember { mutableStateOf<Uri?>(null) }
    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null && exportPass.isNotEmpty()) onExportBackup(exportPass, uri)
        exportPass = ""
    }
    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) importUri = uri }

    // Resultado de export/import por toast (los fallos mudos parecen "no hizo nada").
    LaunchedEffect(backupMessage) {
        backupMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            onBackupMessageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(KryptaBackIcon, contentDescription = "Atrás")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenHelp) {
                        Icon(KryptaHelpIcon, contentDescription = "Ayuda")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SettingsCard(
                "Tu identidad",
                onInfo = {
                    infoDialog = "Tu PeerID" to (
                        "Tu PeerID es tu identidad en Krypta: es tu clave pública, no un " +
                            "teléfono ni un correo, y se crea sola en este móvil. Compártela con " +
                            "quien quiera añadirte; con ella pueden escribirte, pero no revela " +
                            "ningún otro dato tuyo. Si pierdes el móvil sin copia de seguridad, " +
                            "pierdes este PeerID."
                        )
                },
            ) {
                Text(
                    "Comparte tu PeerID con quien quiera añadirte; es tu única seña de contacto.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val clipboard = LocalClipboardManager.current
                Text(
                    myPeerId.ifEmpty { "…" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsActionButton(
                        "Copiar", KryptaCopyIcon, myPeerId.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        clipboard.setText(AnnotatedString(myPeerId))
                        Toast.makeText(context, "PeerID copiado", Toast.LENGTH_SHORT).show()
                    }
                    SettingsActionButton(
                        "Compartir", KryptaShareIcon, myPeerId.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, myPeerId)
                        }
                        context.startActivity(Intent.createChooser(send, "Compartir PeerID"))
                    }
                }
            }

            SettingsCard("Copia de seguridad") {
                Text(
                    "Guarda tu identidad y tus contactos en un archivo cifrado con una " +
                        "frase-clave. Sin esta copia, perder el móvil = perder tu PeerID " +
                        "(todos tendrían que volver a añadirte y verificarte).",
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsActionButton(
                        "Exportar", KryptaUploadIcon,
                        modifier = Modifier.weight(1f),
                    ) { askExportPass = true }
                    SettingsActionButton(
                        "Importar", KryptaDownloadIcon,
                        modifier = Modifier.weight(1f),
                    ) { openBackup.launch(arrayOf("*/*")) }
                }
            }

            SettingsCard("Bloqueo de la app") {
                val lockEnabled by AppLock.enabled.collectAsState()
                val graceMs by AppLock.graceMs.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Pedir desbloqueo para entrar",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "Con tu huella, cara o el PIN del móvil. Krypta no guarda " +
                                "ese secreto: lo comprueba el sistema.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = lockEnabled,
                        onCheckedChange = { want ->
                            val activity = context.findActivity() ?: return@Switch
                            if (want) {
                                val problem = AppLock.availabilityProblem(context)
                                if (problem != null) {
                                    Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
                                    return@Switch
                                }
                            }
                            // Autenticar para cambiarlo, en ambos sentidos: al activar
                            // verifica que el desbloqueo funciona (nadie se queda fuera);
                            // al desactivar impide que otro lo apague con el móvil en mano.
                            AppLock.authenticate(
                                activity,
                                if (want) "Activar el bloqueo de Krypta"
                                else "Desactivar el bloqueo de Krypta",
                            ) { ok -> if (ok) AppLock.setEnabled(context, want) }
                        },
                    )
                }
                if (lockEnabled) {
                    Text(
                        "Volver a bloquear al salir de la app:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val options = listOf(
                            AppLock.GRACE_IMMEDIATE to "Al instante",
                            AppLock.GRACE_1_MIN to "Tras 1 min",
                            AppLock.GRACE_5_MIN to "Tras 5 min",
                        )
                        options.forEachIndexed { index, (value, label) ->
                            SegmentedButton(
                                selected = graceMs == value,
                                onClick = { AppLock.setGraceMs(context, value) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = options.size,
                                ),
                            ) {
                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            SettingsCard("Apariencia") {
                Text(
                    "Tema de la app. “Sistema” sigue el modo claro/oscuro del móvil.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val themeMode by ThemePreference.mode.collectAsState()
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    val options = listOf(
                        ThemeMode.SYSTEM to "Sistema",
                        ThemeMode.LIGHT to "Claro",
                        ThemeMode.DARK to "Oscuro",
                    )
                    options.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = themeMode == value,
                            onClick = { ThemePreference.setMode(context, value) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = options.size,
                            ),
                        ) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            SettingsCard("Red") {
                WanStatusLine(wanStatus)
                OutlinedTextField(
                    value = bootstrapDraft,
                    onValueChange = { bootstrapDraft = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Nodos WAN (bootstrap)") },
                    maxLines = 4,
                    isError = bootstrapError != null,
                    supportingText = {
                        Text(
                            bootstrapError
                                ?: "Puntos de entrada a la red (DHT), uno por línea. Ya viene " +
                                "configurado e igual para todos; con varios nodos, si uno cae " +
                                "los demás siguen entregando.",
                        )
                    },
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Spacer(Modifier.weight(1f))
                    SettingsActionButton("Aplicar", KryptaCheckIcon) {
                        onSetBootstrap(bootstrapDraft)
                    }
                }
            }

            SettingsCard(
                "Recepción en segundo plano",
                onInfo = {
                    infoDialog = "Recepción en segundo plano" to (
                        "Para recibir mensajes con la app cerrada, Krypta mantiene una conexión " +
                            "ligera en segundo plano. Muchos móviles la cortan para ahorrar " +
                            "batería, y entonces los avisos llegan tarde o solo al abrir la app. " +
                            "Pulsa “Ajustes del sistema” y permite a Krypta: batería sin " +
                            "restricciones, inicio automático y notificaciones."
                        )
                },
            ) {
                Text(
                    "Si los avisos no suenan o llegan tarde, prueba el aviso y revisa los " +
                        "permisos de batería/notificaciones del sistema (imprescindible en " +
                        "algunos móviles).",
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsActionButton(
                        "Probar aviso", KryptaBellIcon,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        KryptaNotifications.ensureChannels(context)
                        KryptaNotifications.notifyMessage(
                            context, "diag-test", "Prueba de aviso",
                            "Si ves esto con sonido y banner, ¡listo!",
                        )
                    }
                    SettingsActionButton(
                        "Ajustes del sistema", KryptaSettingsIcon,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    }
                }
            }

            SettingsCard("Diagnóstico") {
                SettingsActionButton(
                    "Sonda de latencia de llamadas", KryptaPhoneIcon,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onProbeLatency,
                )
                if (diagnostics.isEmpty()) {
                    Text(
                        "Sin eventos todavía.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    DiagnosticsPanel(diagnostics)
                }
            }
        }
    }

    infoDialog?.let { (title, body) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { infoDialog = null },
            title = { Text(title) },
            text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { infoDialog = null }) { Text("Entendido") } },
        )
    }

    if (askExportPass) {
        PassphraseDialog(
            title = "Exportar copia de seguridad",
            text = "Elige una frase-clave para cifrar la copia; te la pedirá al importarla. " +
                "Guárdala bien: sin ella la copia es irrecuperable.",
            confirmLabel = "Elegir destino",
            onDismiss = { askExportPass = false },
            onConfirm = { pass ->
                askExportPass = false
                exportPass = pass
                createBackup.launch("krypta-identidad.krbk")
            },
        )
    }

    importUri?.let { uri ->
        PassphraseDialog(
            title = "Importar copia de seguridad",
            text = "Introduce la frase-clave de la copia. Al reiniciar Krypta tu identidad " +
                "actual será SUSTITUIDA por la de la copia (contactos incluidos).",
            confirmLabel = "Importar",
            onDismiss = { importUri = null },
            onConfirm = { pass ->
                importUri = null
                onImportBackup(pass, uri)
            },
        )
    }

    if (restoredPeerId != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {}, // sin identidad recargada no hay vuelta atrás limpia
            title = { Text("Identidad restaurada") },
            text = {
                Text(
                    "PeerID importado:\n$restoredPeerId\n\nKrypta debe reiniciarse para " +
                        "usarla. Se cerrará ahora; vuelve a abrirla.",
                )
            },
            confirmButton = {
                TextButton(onClick = { kotlin.system.exitProcess(0) }) { Text("Cerrar Krypta") }
            },
        )
    }
}

/** Diálogo de frase-clave para exportar/importar la copia de seguridad. */
@Composable
private fun PassphraseDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pass by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Frase-clave") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = pass.length >= 4, onClick = { onConfirm(pass) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/**
 * Tarjeta de ajustes. `surfaceContainerHigh` + un borde sutil la separan claramente del fondo
 * (`surface`/`background`) — con `surfaceContainerLow` (el valor anterior) ambos tonos eran casi
 * idénticos y las tarjetas se confundían con la pantalla, tanto en claro como en oscuro.
 */
@Composable
private fun SettingsCard(
    title: String,
    onInfo: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = (MaterialTheme.typography.titleMedium.fontSize.value + 2f).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).padding(bottom = 8.dp),
                )
                if (onInfo != null) {
                    IconButton(onClick = onInfo, modifier = Modifier.size(28.dp)) {
                        Icon(
                            KryptaInfoIcon,
                            contentDescription = "Más información",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            content()
        }
    }
}

/**
 * Botón de acción único para toda la pantalla de Ajustes (icono + texto, `FilledTonalButton`):
 * antes convivían botones solo-icono, icono+texto y solo-texto en la misma pantalla.
 */
@Composable
private fun SettingsActionButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

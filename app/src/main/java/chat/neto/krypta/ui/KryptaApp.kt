package chat.neto.krypta.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.widget.Toast
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import chat.neto.krypta.AppLock
import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.p2p.ChatService
import chat.neto.krypta.p2p.WanStatus

// Navegación de la app (`KryptaApp`) y los componentes que comparten varias pantallas. Se separó
// de ChatScreens.kt el 12 oct 2026 (§5 de docs/AUDITORIA-2026-10-12.md).

@Composable
fun KryptaApp(
    viewModel: ChatViewModel = hiltViewModel(),
    callViewModel: CallViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    openContactId: String? = null,
    onOpenConsumed: () -> Unit = {},
) {
    var current by remember { mutableStateOf<Contact?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val contacts by viewModel.contacts.collectAsState()

    // Deep-link desde una notificación: al llegar (o al cargar los contactos) abre esa
    // conversación. Se consume una sola vez para no re-abrirla si el usuario navega atrás.
    LaunchedEffect(openContactId, contacts) {
        val id = openContactId ?: return@LaunchedEffect
        contacts.find { it.id == id }?.let {
            current = it
            onOpenConsumed()
        }
    }
    val myPeerId by settingsViewModel.myPeerId.collectAsState()
    val error by viewModel.error.collectAsState()
    val online by viewModel.onlinePeers.collectAsState()
    val bootstrap by settingsViewModel.bootstrap.collectAsState()
    val bootstrapError by settingsViewModel.bootstrapError.collectAsState()
    val wanStatus by viewModel.wanStatus.collectAsState()
    val diagnostics by settingsViewModel.diagnostics.collectAsState()

    // Una llamada en curso (cualquier fase ≠ IDLE) toma toda la pantalla.
    val callState by callViewModel.callState.collectAsState()
    val callMuted by callViewModel.callMuted.collectAsState()
    val callSpeaker by callViewModel.callSpeaker.collectAsState()
    val videoWanted by callViewModel.videoWanted.collectAsState()
    val remoteRotation by callViewModel.remoteVideoRotation.collectAsState()
    val localRotation by callViewModel.localVideoRotation.collectAsState()
    val callError by callViewModel.error.collectAsState()
    val toastContext = LocalContext.current
    LaunchedEffect(callError) {
        callError?.let {
            Toast.makeText(toastContext, it, Toast.LENGTH_SHORT).show()
            callViewModel.clearError()
        }
    }
    if (callState.phase != chat.neto.krypta.p2p.CallPhase.IDLE) {
        CallScreen(
            state = callState,
            muted = callMuted,
            speaker = callSpeaker,
            videoWanted = videoWanted,
            remoteRotation = remoteRotation,
            localRotation = localRotation,
            onAccept = callViewModel::acceptCall,
            onReject = callViewModel::rejectCall,
            onHangup = callViewModel::hangupCall,
            onToggleMute = callViewModel::toggleCallMute,
            onToggleSpeaker = callViewModel::toggleCallSpeaker,
            onToggleVideo = callViewModel::toggleVideo,
            onSwitchCamera = callViewModel::switchCamera,
            onPreviewSurface = callViewModel::onPreviewSurfaceReady,
            onRemoteSurface = callViewModel::setRemoteVideoSurface,
        )
        return
    }

    // Bloqueo de acceso: cubre toda la app salvo la llamada en curso (arriba) — una llamada
    // entrante debe poder atenderse sin desbloquear, igual que en el teléfono nativo.
    val locked by AppLock.locked.collectAsState()
    if (locked) {
        val activity = LocalContext.current.findActivity()
        LockScreen(
            onRequestUnlock = {
                activity?.let { act ->
                    AppLock.authenticate(act, "Desbloquear Krypta") { ok ->
                        if (ok) AppLock.unlock()
                    }
                }
            },
        )
        return
    }

    // Reenlaza al contacto vivo de la lista: así la insignia de verificado y el nombre en el
    // chat reflejan cambios (verificar, renombrar) sin depender de la instantánea guardada.
    val openContact = current?.let { c -> contacts.find { it.id == c.id } ?: c }
    // Los adjuntos van cifrados en reposo: quien los lea (burbuja de nota de voz, GIF, abrir
    // con otra app) los pide por aquí en vez de tocar el fichero.
    CompositionLocalProvider(LocalAttachmentReader provides viewModel::readAttachment) {
    when {
        openContact != null -> {
            BackHandler { current = null }
            ChatScreen(
                viewModel = viewModel,
                contact = openContact,
                online = openContact.peerId in online && !openContact.blocked,
                myPeerId = myPeerId,
                onBack = { current = null },
                onStartCall = callViewModel::startCall,
            )
        }
        // La Ayuda va antes que Ajustes: abierta desde Ajustes se muestra encima, y "atrás"
        // vuelve a Ajustes (showSettings sigue true debajo); abierta desde la lista, vuelve a
        // la lista.
        showHelp -> {
            BackHandler { showHelp = false }
            HelpScreen(onBack = { showHelp = false })
        }
        showSettings -> {
            BackHandler { showSettings = false }
            val backupMessage by settingsViewModel.backupMessage.collectAsState()
            val restoredPeerId by settingsViewModel.restoredPeerId.collectAsState()
            val lanDiscovery by settingsViewModel.lanDiscovery.collectAsState()
            SettingsScreen(
                myPeerId = myPeerId,
                bootstrap = bootstrap,
                bootstrapError = bootstrapError,
                wanStatus = wanStatus,
                diagnostics = diagnostics,
                backupMessage = backupMessage,
                restoredPeerId = restoredPeerId,
                onBack = { showSettings = false },
                onOpenHelp = { showHelp = true },
                onSetBootstrap = settingsViewModel::setBootstrap,
                lanDiscovery = lanDiscovery,
                onSetLanDiscovery = settingsViewModel::setLanDiscovery,
                onProbeLatency = settingsViewModel::probeCallLatency,
                onExportBackup = settingsViewModel::exportBackup,
                onImportBackup = settingsViewModel::importBackup,
                onBackupMessageShown = settingsViewModel::clearBackupMessage,
            )
        }
        else -> {
            val conversations by viewModel.conversations.collectAsState()
            ConversationsScreen(
                conversations = conversations,
                online = online,
                wanStatus = wanStatus,
                error = error,
                onOpen = { current = it },
                onOpenSettings = { showSettings = true },
                onOpenHelp = { showHelp = true },
                onAddContact = viewModel::addContact,
                onClearError = viewModel::clearError,
                onClearChat = viewModel::clearChat,
                onDeleteContact = viewModel::deleteContact,
                onSetBlocked = viewModel::setBlocked,
            )
        }
    }
    }
}

/** Botón-icono minimalista con tooltip (texto en pulsación larga) y `contentDescription`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TooltipIconButton(
    tooltip: String,
    icon: ImageVector,
    enabled: Boolean,
    tint: Color = LocalContentColor.current,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = tooltip, tint = tint)
        }
    }
}

@Composable
internal fun AddContactDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var peerId by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo contacto") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
                OutlinedTextField(
                    peerId, { peerId = it },
                    label = { Text("PeerID del contacto") }, singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, peerId) },
                enabled = name.isNotBlank() && peerId.isNotBlank(),
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Confirmación de una acción destructiva (vaciar chat / eliminar contacto). */
@Composable
internal fun ConfirmDeleteDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
internal fun WanStatusLine(status: WanStatus) {
    val (label, color) = when (status) {
        WanStatus.DISABLED -> "sin configurar" to MaterialTheme.colorScheme.onSurfaceVariant
        WanStatus.CONNECTING -> "conectando…" to MaterialTheme.colorScheme.tertiary
        WanStatus.CONNECTED -> "conectado" to MaterialTheme.colorScheme.primary
        WanStatus.ERROR -> "error" to MaterialTheme.colorScheme.error
    }
    Text(
        "WAN (DHT): $label",
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
}

/** Líneas del Diagnóstico que se ven en Ajustes: todas las que guarda `ChatService`. */
private const val DIAGNOSTICS_VISIBLE_LINES = 150

@Composable
internal fun DiagnosticsPanel(lines: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp)
    ) {
        Text("Diagnóstico", style = MaterialTheme.typography.labelSmall)
        // Más recientes abajo. Antes solo se veían 8: lo que pasaba durante una prueba con dos
        // móviles ya no estaba cuando se miraba (12 oct 2026). La pantalla de Ajustes desplaza.
        lines.takeLast(DIAGNOSTICS_VISIBLE_LINES).forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

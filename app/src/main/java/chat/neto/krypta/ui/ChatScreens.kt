package chat.neto.krypta.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Popup
import androidx.hilt.navigation.compose.hiltViewModel
import chat.neto.krypta.AppLock
import chat.neto.krypta.ScreenSecurity
import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.core.model.MessageStatus
import chat.neto.krypta.p2p.WanStatus
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun KryptaApp(
    viewModel: ChatViewModel = hiltViewModel(),
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
    val myPeerId by viewModel.myPeerId.collectAsState()
    val error by viewModel.error.collectAsState()
    val online by viewModel.onlinePeers.collectAsState()
    val bootstrap by viewModel.bootstrap.collectAsState()
    val bootstrapError by viewModel.bootstrapError.collectAsState()
    val wanStatus by viewModel.wanStatus.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()

    // Una llamada en curso (cualquier fase ≠ IDLE) toma toda la pantalla.
    val callState by viewModel.callState.collectAsState()
    val callMuted by viewModel.callMuted.collectAsState()
    val callSpeaker by viewModel.callSpeaker.collectAsState()
    val videoWanted by viewModel.videoWanted.collectAsState()
    val remoteRotation by viewModel.remoteVideoRotation.collectAsState()
    val localRotation by viewModel.localVideoRotation.collectAsState()
    if (callState.phase != chat.neto.krypta.p2p.CallPhase.IDLE) {
        CallScreen(
            state = callState,
            muted = callMuted,
            speaker = callSpeaker,
            videoWanted = videoWanted,
            remoteRotation = remoteRotation,
            localRotation = localRotation,
            onAccept = viewModel::acceptCall,
            onReject = viewModel::rejectCall,
            onHangup = viewModel::hangupCall,
            onToggleMute = viewModel::toggleCallMute,
            onToggleSpeaker = viewModel::toggleCallSpeaker,
            onToggleVideo = viewModel::toggleVideo,
            onSwitchCamera = viewModel::switchCamera,
            onPreviewSurface = viewModel::onPreviewSurfaceReady,
            onRemoteSurface = viewModel::setRemoteVideoSurface,
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
    when {
        openContact != null -> {
            BackHandler { current = null }
            ChatScreen(
                viewModel = viewModel,
                contact = openContact,
                online = openContact.peerId in online && !openContact.blocked,
                onBack = { current = null },
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
            val backupMessage by viewModel.backupMessage.collectAsState()
            val restoredPeerId by viewModel.restoredPeerId.collectAsState()
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
                onSetBootstrap = viewModel::setBootstrap,
                onProbeLatency = viewModel::probeCallLatency,
                onExportBackup = viewModel::exportBackup,
                onImportBackup = viewModel::importBackup,
                onBackupMessageShown = viewModel::clearBackupMessage,
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

/**
 * Marca la ventana como segura (`FLAG_SECURE`) mientras esta pantalla esté compuesta y la
 * desmarca al salir. Con una sola Activity el flag es de toda la ventana, así que ponerlo y
 * quitarlo aquí es lo que lo convierte en "solo el chat": ni capturas del sistema, ni
 * grabación, ni miniatura en "recientes" mientras se mira una conversación, y captura normal
 * en el resto de la app. La captura propia (⋮ › Capturar pantalla) sigue funcionando porque
 * dibuja las vistas, no la superficie.
 */
@Composable
private fun SecureScreenEffect() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.let { ScreenSecurity.setSecure(it, true) }
        onDispose { activity?.let { ScreenSecurity.setSecure(it, false) } }
    }
}

// ExperimentalFoundationApi: `Modifier.contentReceiver` (contenido enriquecido del teclado).
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChatScreen(
    viewModel: ChatViewModel,
    contact: Contact,
    online: Boolean,
    onBack: () -> Unit,
) {
    val messages by viewModel.messages(contact).collectAsState(initial = emptyList())
    // TextFieldState (API de estado) y no `value/onValueChange`: solo el campo basado en
    // estado enchufa `Modifier.contentReceiver`, que es lo que habilita GIF/stickers/emoji
    // grandes del teclado (ver más abajo). `draft` es la vista de texto para el resto.
    val draftState = rememberTextFieldState()
    val draft = draftState.text.toString()
    var showVerify by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var captureRequested by remember { mutableStateOf(false) }
    // Respuesta en curso: el mensaje citado que se adjuntará al siguiente envío, sea del tipo
    // que sea (texto, foto, archivo o nota de voz). Se limpia al enviar o al descartar.
    var replyingTo by remember(contact.id) { mutableStateOf<DisplayMessage?>(null) }
    // Mensaje al que se acaba de saltar desde una cita: se resalta un momento para localizarlo.
    var highlightId by remember(contact.id) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // Solo aquí se bloquea la captura del sistema: lo que hay que proteger es el contenido de
    // las conversaciones. Al salir del chat el flag se quita y el resto de la app (lista,
    // ajustes, ayuda) se puede capturar con normalidad.
    SecureScreenEffect()

    // Captura propia (la del sistema está bloqueada por FLAG_SECURE). Se espera un par de
    // fotogramas para que el menú ⋮ ya haya desaparecido: si no, sale él en la imagen.
    if (captureRequested) {
        val activity = context.findActivity()
        LaunchedEffect(Unit) {
            withFrameNanos {}
            withFrameNanos {}
            val message = if (activity == null) {
                "No se pudo capturar la pantalla"
            } else {
                ScreenSecurity.captureToGallery(activity).fold(
                    onSuccess = { "Captura guardada en Galería › Krypta" },
                    onFailure = { "No se pudo guardar la captura" },
                )
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            captureRequested = false
        }
    }

    // Photo picker del sistema (sin permiso de almacenamiento): al elegir, comprime y envía.
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.sendImage(contact, uri, replyingTo?.id)
            replyingTo = null
        }
    }

    // File picker genérico (cualquier tipo): al elegir, se trocea y envía.
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            viewModel.sendFile(contact, uri, replyingTo?.id)
            replyingTo = null
        }
    }

    // Llamada de voz: el micro se pide antes de llamar (AudioRecord lo necesita ya concedido).
    val callPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.startCall(contact) }
    val startCall = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.startCall(contact) else callPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Errores del ViewModel (imagen/archivo/nota de voz): en esta pantalla no hay banner,
    // así que se avisan por toast — un fallo mudo parece "el botón no hizo nada".
    val vmError by viewModel.error.collectAsState()
    LaunchedEffect(vmError) {
        vmError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    // Nota de voz: grabadora + permiso de micro pedido en el primer uso. Dos modos:
    // **FIJADA** (toque corto → barra con Cancelar/Enviar, como hasta ahora) y **MANTENIDA**
    // (mantener pulsado el micro y **soltar para enviar**, la costumbre WhatsApp; una
    // pulsación de <1 s se descarta como accidental). start() lanza si el micro no está
    // disponible (p. ej. aún ocupado tras una llamada); sin el toast el fallo era invisible
    // (visto en vivo el 5 jul).
    val recorder = remember { AudioRecorder(context) }
    var recordMode by remember { mutableStateOf(RecordMode.NONE) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    var recordStartedAt by remember { mutableLongStateOf(0L) }
    val tryStartRecording = { mode: RecordMode ->
        val ok = runCatching { recorder.start() }.isSuccess
        if (ok) {
            recordMode = mode
            recordStartedAt = System.currentTimeMillis()
        } else {
            Toast.makeText(context, "No se pudo grabar: micrófono no disponible", Toast.LENGTH_LONG).show()
        }
        ok
    }
    // Tras conceder el permiso el dedo ya no está en el micro → arranca en modo fijado.
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) tryStartRecording(RecordMode.LOCKED) }
    val startRecording = { mode: RecordMode ->
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) tryStartRecording(mode) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Termina la grabación en curso: la envía (stop) o la descarta (cancel).
    val finishRecording = { send: Boolean ->
        recordMode = RecordMode.NONE
        if (send) {
            val file = recorder.stop()
            if (file != null) {
                viewModel.sendVoiceNote(contact, file, replyingTo?.id)
                replyingTo = null
            } else {
                Toast.makeText(context, "Grabación vacía o fallida: no se envió", Toast.LENGTH_LONG).show()
            }
        } else {
            recorder.cancel()
        }
    }
    LaunchedEffect(recordMode) {
        recordSeconds = 0
        while (recordMode != RecordMode.NONE) {
            delay(1_000)
            recordSeconds++
        }
    }
    // Salir del chat a media grabación la cancela (y libera el micro).
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    // Bloquear con una grabación fijada en curso (toque corto en el micro → ⋮ → Bloquear):
    // la barra de entrada desaparece, así que sin esto el micro se quedaría tomado sin
    // ningún control en pantalla para soltarlo.
    LaunchedEffect(contact.blocked) {
        if (contact.blocked) {
            if (recordMode != RecordMode.NONE) finishRecording(false)
            replyingTo = null // la cita en curso ya no se puede enviar a nadie
        }
    }

    // Declara qué conversación se está mirando: limpia su notificación (aunque hayas entrado
    // por el icono de la app) y silencia SOLO los mensajes de este contacto mientras el chat
    // esté delante — los de cualquier otro sí avisan. Al salir se vuelve a avisar de todo.
    DisposableEffect(contact.id) {
        viewModel.onConversationVisible(contact.id)
        onDispose { viewModel.onConversationVisible(null) }
    }

    // Acusa la lectura al abrir y cada vez que llega un mensaje mientras miras el chat →
    // el emisor verá sus mensajes como "leído". Dedup interno evita reenviar acuses.
    LaunchedEffect(contact.id, messages.size) {
        viewModel.markRead(contact)
    }

    // Filas del chat: mensajes + separadores de día, con agrupación de consecutivos del
    // mismo lado (la burbuja solo "abre" esquina en el primero y último del grupo).
    val rows = remember(messages) { buildChatRows(messages) }

    // Auto-scroll al último mensaje. Instantáneo la primera vez que llega el historial (para
    // aterrizar bien al abrir); animado en los mensajes siguientes. Espera un frame a que la
    // lista esté compuesta antes de saltar, si no el índice aún no existe.
    var didInitialScroll by remember(contact.id) { mutableStateOf(false) }
    LaunchedEffect(rows.size) {
        if (rows.isEmpty()) return@LaunchedEffect
        withFrameNanos {}
        if (didInitialScroll) {
            listState.animateScrollToItem(rows.lastIndex)
        } else {
            listState.scrollToItem(rows.lastIndex)
            didInitialScroll = true
        }
    }
    // El teclado (imePadding, más abajo) encoge la lista al abrirse; sin esto el último
    // mensaje quedaba fuera de vista al abrir el teclado (y al cerrarlo, y no reaccionaba
    // mientras el teclado seguía abierto). Se sigue el alto del IME fotograma a fotograma
    // (no solo abierto/cerrado) para que la lista se reacomode también durante la animación
    // de apertura/cierre, no solo al terminar.
    val latestRows = rememberUpdatedState(rows)
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    LaunchedEffect(Unit) {
        snapshotFlow { imeInsets.getBottom(density) }
            .collect {
                val r = latestRows.value
                if (r.isNotEmpty()) listState.scrollToItem(r.lastIndex)
            }
    }

    // Tocar una cita salta al mensaje citado y lo resalta un momento (si sigue en el chat).
    val scope = rememberCoroutineScope()
    val jumpTo = { targetId: String ->
        val index = rows.indexOfFirst { it is ChatRow.Msg && it.message.id == targetId }
        if (index >= 0) {
            highlightId = targetId
            scope.launch { listState.animateScrollToItem(index) }
        } else {
            Toast.makeText(context, "Ese mensaje ya no está en este chat", Toast.LENGTH_SHORT).show()
        }
        Unit
    }
    LaunchedEffect(highlightId) {
        if (highlightId != null) {
            delay(1_500)
            highlightId = null
        }
    }
    // Atrás con una respuesta en curso la descarta, en vez de salir del chat: la costumbre
    // en mensajería, y evita mandar por error una respuesta a un mensaje que ya no querías.
    BackHandler(enabled = replyingTo != null) { replyingTo = null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            androidx.compose.material3.TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(KryptaBackIcon, contentDescription = "Atrás")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ContactAvatar(contact.displayName, contact.peerId, online = online, size = 38.dp)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(
                                contact.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (online) {
                                Text(
                                    "en línea",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                },
                actions = {
                    // A un bloqueado no se le llama (ChatService lo cortaría igualmente al
                    // enviar el invite; aquí se ve por qué el botón no responde).
                    TooltipIconButton(
                        tooltip = if (contact.blocked) "Contacto bloqueado" else "Llamar",
                        icon = KryptaPhoneIcon,
                        enabled = !contact.blocked,
                        onClick = { startCall() },
                    )
                    TooltipIconButton(
                        tooltip = if (contact.verified) "Identidad verificada" else "Verificar identidad",
                        icon = KryptaShieldIcon,
                        enabled = true,
                        onClick = { showVerify = true },
                        tint = if (contact.verified) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(KryptaMoreIcon, contentDescription = "Más opciones")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            DropdownMenuItem(
                                text = { Text("Capturar pantalla") },
                                leadingIcon = { Icon(KryptaImageIcon, contentDescription = null) },
                                onClick = { showMenu = false; captureRequested = true },
                            )
                            DropdownMenuItem(
                                text = { Text(if (contact.blocked) "Desbloquear" else "Bloquear") },
                                leadingIcon = { Icon(KryptaBlockIcon, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    viewModel.setBlocked(contact, !contact.blocked)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Vaciar chat") },
                                leadingIcon = { Icon(KryptaDeleteIcon, contentDescription = null) },
                                onClick = { showMenu = false; confirmClear = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Eliminar contacto") },
                                leadingIcon = {
                                    Icon(
                                        KryptaDeleteIcon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = { showMenu = false; confirmDelete = true },
                            )
                        }
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            )
        },
    ) { padding ->
        // imePadding: la barra de entrada sube sobre el teclado en vez de quedar tapada.
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (!contact.verified) {
                UnverifiedBanner(onClick = { showVerify = true })
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(
                    rows,
                    key = { row ->
                        when (row) {
                            is ChatRow.Day -> "day-${row.label}"
                            is ChatRow.Msg -> row.message.id
                        }
                    },
                ) { row ->
                    when (row) {
                        is ChatRow.Day -> DaySeparator(row.label)
                        is ChatRow.Msg -> MessageBubble(
                            row.message,
                            first = row.first,
                            onRetry = { viewModel.retry(contact, row.message.id) },
                            onReply = { replyingTo = row.message },
                            onQuoteClick = { targetId -> jumpTo(targetId) },
                            highlighted = highlightId == row.message.id,
                        )
                    }
                }
            }
            // Cita en curso: qué mensaje se está respondiendo, con ✕ para descartarla. Va
            // pegada encima de la barra de entrada (mismo tono), como en cualquier mensajero.
            replyingTo?.let { target ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(start = 12.dp, end = 4.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QuotedPreview(
                        quoted = QuotedMessage(
                            id = target.id,
                            author = if (target.mine) "Tú" else contact.displayName,
                            preview = replyPreview(target),
                        ),
                        onBg = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { replyingTo = null }) {
                        Icon(KryptaCloseIcon, contentDescription = "Descartar la respuesta")
                    }
                }
            }
            // Contacto bloqueado: no hay barra de entrada. El historial se sigue leyendo
            // (bloquear no borra nada), pero no se le puede escribir, ni adjuntar, ni mandar
            // una nota de voz — el corte de verdad está en ChatService; esto es lo que hace
            // que se entienda y ofrece la salida (desbloquear) donde el usuario la busca.
            if (contact.blocked) {
                BlockedInputBar(
                    name = contact.displayName,
                    onUnblock = { viewModel.setBlocked(contact, false) },
                )
            } else {
                // Barra de entrada. OJO: mientras se graba en modo MANTENIDO el dedo sigue sobre
                // el micro, así que el Box del micro debe **permanecer en composición** (si se
                // sustituyera por otra barra, su pointerInput se cancela y la soltada — el
                // "enviar" — nunca llega; visto en vivo el 12 jul). Por eso la fila cambia sus
                // tramos izquierdos pero el micro es siempre el mismo nodo.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (recordMode == RecordMode.NONE) {
                        TooltipIconButton("Adjuntar", KryptaAttachIcon, enabled = true) {
                            showAttach = true
                        }
                        // Campo con relleno propio y esquinas redondeadas a juego con las
                        // burbujas (antes un OutlinedTextField transparente, con solo el borde
                        // de foco como fondo, se confundía con la barra que lo rodea).
                        TextField(
                            state = draftState,
                            // Contenido enriquecido del teclado: GIF, stickers y emoji grandes.
                            // Sin este modificador el campo solo anuncia `text/*` en su EditorInfo
                            // y el teclado responde "la app no admite insertar aquí" — era el caso.
                            // Con él anuncia `*/*`, y Compose ya pide el permiso de lectura de la
                            // URI (`InputContentInfoCompat.requestPermission`) antes de entregarla.
                            modifier = Modifier
                                .weight(1f)
                                .contentReceiver { transferable ->
                                    // Devuelve lo NO consumido: el texto plano se deja al campo.
                                    transferable.consume { item ->
                                        val uri = item.uri
                                        val isImage = uri != null &&
                                            context.contentResolver.getType(uri)
                                                ?.startsWith("image/") == true
                                        if (isImage) {
                                            viewModel.sendImage(contact, uri!!, replyingTo?.id)
                                            replyingTo = null
                                        }
                                        isImage
                                    }
                                },
                            placeholder = { Text("Mensaje cifrado…") },
                            shape = RoundedCornerShape(24.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                        )
                    } else {
                        Icon(
                            KryptaMicIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            "Grabando… ${formatSeconds(recordSeconds)}" +
                                if (recordMode == RecordMode.HELD) " · suelta para enviar" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        if (recordMode == RecordMode.LOCKED) {
                            TextButton(onClick = { finishRecording(false) }) { Text("Cancelar") }
                            Button(
                                onClick = { finishRecording(true) },
                                modifier = Modifier.padding(start = 4.dp, end = 4.dp),
                            ) { Text("Enviar") }
                        }
                    }
                    if (draft.isBlank() || recordMode != RecordMode.NONE) {
                        // Sin texto, el hueco de enviar es el micro (nota de voz), estilo mensajería.
                        // Sin TooltipBox: la costumbre WhatsApp es MANTENER pulsado el micro, y el
                        // tooltip se comía esa pulsación larga sin grabar nada (visto en vivo:
                        // "aprieto y no pasa nada", 6 jul). Toque corto = grabación fijada (con
                        // Cancelar/Enviar); mantener pulsado = graba y **al soltar se envía**
                        // (<1 s se descarta como pulsación accidental).
                        val haptics = LocalHapticFeedback.current
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .pointerInput(contact.id) {
                                    detectTapGestures(
                                        onTap = {
                                            if (recordMode == RecordMode.NONE) startRecording(RecordMode.LOCKED)
                                        },
                                        onLongPress = {
                                            if (recordMode == RecordMode.NONE) {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                startRecording(RecordMode.HELD)
                                            }
                                        },
                                        onPress = {
                                            tryAwaitRelease()
                                            if (recordMode == RecordMode.HELD) {
                                                val longEnough =
                                                    System.currentTimeMillis() - recordStartedAt >= 1_000
                                                if (!longEnough) {
                                                    Toast.makeText(
                                                        context,
                                                        "Mantén pulsado para grabar y suelta para enviar",
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                                finishRecording(longEnough)
                                            }
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                KryptaMicIcon,
                                contentDescription = "Grabar nota de voz",
                                tint = if (recordMode == RecordMode.HELD) MaterialTheme.colorScheme.primary
                                else LocalContentColor.current,
                            )
                        }
                    } else Button(
                        onClick = {
                            viewModel.send(contact, draft, replyingTo?.id)
                            draftState.clearText()
                            replyingTo = null
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("Enviar") }
                }
            }
        }
    }

    // Hoja inferior de adjuntos: un solo botón-clip en la barra abre las opciones.
    if (showAttach) {
        ModalBottomSheet(
            onDismissRequest = { showAttach = false },
            // El contenedor por defecto (surfaceContainerLow) apenas se distinguía del
            // fondo de la pantalla de chat (mismo problema ya visto en las tarjetas de
            // Ajustes); surfaceContainerHigh sí contrasta con lo que queda debajo.
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            AttachOption(
                icon = KryptaImageIcon,
                title = "Foto o imagen",
                subtitle = "De la galería; se comprime para enviarla al instante",
            ) {
                showAttach = false
                pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }
            AttachOption(
                icon = KryptaAttachIcon,
                title = "Archivo",
                subtitle = "Cualquier tipo, hasta 8 MB",
            ) {
                showAttach = false
                pickFile.launch("*/*")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showVerify) {
        val myPeerId by viewModel.myPeerId.collectAsState()
        // Lanzador del escáner de QR (zxing-android-embedded). Al escanear el QR del otro,
        // comparamos el PeerID leído con el guardado para este contacto: si coincide, nadie
        // sustituyó el PeerID → verificado; si no, avisamos de posible suplantación.
        val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
            val scanned = result.contents ?: return@rememberLauncherForActivityResult
            val scannedPeerId = QrCode.parseVerifyPayload(scanned)
            when (scannedPeerId) {
                contact.peerId -> {
                    viewModel.setVerified(contact, true)
                    Toast.makeText(context, "✓ Identidad verificada", Toast.LENGTH_SHORT).show()
                }
                null -> Toast.makeText(context, "Ese QR no es de Krypta", Toast.LENGTH_SHORT).show()
                else -> Toast.makeText(
                    context,
                    "⚠ El QR NO coincide con ${contact.displayName} (posible suplantación)",
                    Toast.LENGTH_LONG,
                ).show()
            }
            showVerify = false
        }
        VerifyIdentityDialog(
            contact = contact,
            myPeerId = myPeerId,
            safetyNumber = viewModel.safetyNumber(contact),
            onDismiss = { showVerify = false },
            onScan = {
                scanLauncher.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Escanea el QR de ${contact.displayName}")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false),
                )
            },
            onSetVerified = { verified ->
                viewModel.setVerified(contact, verified)
                showVerify = false
            },
        )
    }

    if (confirmClear) {
        ConfirmDeleteDialog(
            title = "¿Vaciar el chat?",
            text = "Se borrarán los mensajes y archivos de este chat solo en este dispositivo. " +
                "Esta acción no se puede deshacer.",
            confirmLabel = "Vaciar",
            onConfirm = {
                confirmClear = false
                viewModel.clearChat(contact)
            },
            onDismiss = { confirmClear = false },
        )
    }
    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = "¿Eliminar a ${contact.displayName}?",
            text = "Se eliminarán el contacto y todos sus mensajes de este dispositivo. " +
                "Podrás volver a añadirlo con su PeerID (y verificarlo de nuevo).",
            confirmLabel = "Eliminar",
            onConfirm = {
                confirmDelete = false
                viewModel.deleteContact(contact)
                onBack()
            },
            onDismiss = { confirmDelete = false },
        )
    }
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

/**
 * Diálogo de verificación de identidad (anti-MITM). Dos vías equivalentes para confirmar que
 * el PeerID del contacto no fue sustituido en el canal por el que se compartió:
 *  - **Número de seguridad**: 60 dígitos idénticos en ambos móviles, para cotejar de viva voz.
 *  - **QR**: cada uno muestra su QR (su propio PeerID) y escanea el del otro; la comparación
 *    la hace la app. Es la vía cómoda; el número es el respaldo manual.
 */
@Composable
private fun VerifyIdentityDialog(
    contact: Contact,
    myPeerId: String,
    safetyNumber: String,
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onSetVerified: (Boolean) -> Unit,
) {
    var showQr by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Verificar identidad") },
        text = {
            Column {
                if (showQr) {
                    Text(
                        "Muéstrale este QR a ${contact.displayName} para que lo escanee, y escanea " +
                            "el suyo.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val qr = remember(myPeerId) {
                        if (myPeerId.isBlank()) null else QrCode.encode(QrCode.verifyPayload(myPeerId))
                    }
                    if (qr != null) {
                        Image(
                            bitmap = qr,
                            contentDescription = "Mi QR de verificación",
                            modifier = Modifier
                                .padding(vertical = 16.dp)
                                .fillMaxWidth()
                                .aspectRatio(1f),
                        )
                    }
                    TextButton(onClick = onScan) { Text("Escanear el QR de ${contact.displayName}") }
                } else {
                    Text(
                        "Compara este número con el de ${contact.displayName} (en persona o por una " +
                            "llamada). Si coincide en ambos móviles, nadie está en medio. También " +
                            "puedes usar el QR.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        safetyNumber,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showQr = !showQr }) {
                        Text(if (showQr) "Ver número" else "Usar QR")
                    }
                    if (contact.verified) {
                        Text(
                            "  ✓ verificado",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSetVerified(!contact.verified) }) {
                Text(if (contact.verified) "Quitar verificación" else "Coinciden, verificar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
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
        // Más recientes abajo; mostramos las últimas 8 líneas.
        lines.takeLast(8).forEach { line ->
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

/**
 * Aviso discreto en el chat cuando el contacto **no está verificado** (anti-MITM). Empuja a
 * cotejar el número/QR; al tocarlo abre el diálogo de verificación. Desaparece al verificar.
 */
/**
 * Sustituye a la barra de entrada cuando el contacto está bloqueado: dice por qué no se
 * puede escribir y ofrece deshacerlo ahí mismo (la otra vía es ⋮ → Desbloquear).
 */
@Composable
private fun BlockedInputBar(name: String, onUnblock: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            KryptaBlockIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            "Has bloqueado a $name. No recibirás sus mensajes ni sus llamadas.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        TextButton(onClick = onUnblock) { Text("Desbloquear") }
    }
}

@Composable
private fun UnverifiedBanner(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            KryptaShieldIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(18.dp),
        )
        Text(
            "Identidad sin verificar · toca para comprobar el número o el QR",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun MessageBubble(
    message: DisplayMessage,
    first: Boolean,
    onRetry: () -> Unit,
    onReply: () -> Unit = {},
    onQuoteClick: (String) -> Unit = {},
    highlighted: Boolean = false,
) {
    val align = if (message.mine) Alignment.CenterEnd else Alignment.CenterStart
    val failed = message.mine && message.status == MessageStatus.FAILED
    // Solo hay algo que copiar en las burbujas de texto: en imagen, archivo, audio y GIF el
    // `text` va vacío (lo pone ChatViewModel al mapear el contenido).
    val copyable = message.text.isNotBlank()
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    // Deslizar para responder: el gesto de toda la mensajería. Se arrastra hacia la derecha
    // (en las dos orillas, como WhatsApp) y al pasar el umbral vibra y abre la respuesta; al
    // soltar, la burbuja vuelve animada a su sitio. `detectHorizontalDragGestures` solo
    // consume tras superar el slop horizontal, así que el scroll vertical de la lista sigue
    // funcionando igual.
    val scope = rememberCoroutineScope()
    val dragX = remember(message.id) { Animatable(0f) }
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { SWIPE_REPLY_THRESHOLD.toPx() }
    // Un color de fondo por rol con su pareja "on" correcta. La burbuja **propia** usa
    // `primary` (color de marca saturado), no `primaryContainer`: primary es oscuro en tema
    // claro y brillante en oscuro, así queda con luminosidad **opuesta** a la recibida (un
    // gris neutro) en ambos temas — el contraste que necesita quien tiene baja visión para
    // distinguir de un vistazo quién escribió cada mensaje. Con `primaryContainer` (menta muy
    // claro) y la recibida (gris muy claro) tenían casi la misma luminosidad en tema claro.
    val (bg, onBg) = when {
        failed -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        message.mine -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurface
    }
    // Destello al saltar aquí desde una cita: el color se mezcla con el terciario y vuelve
    // solo. Sin él, tras el scroll no se sabe cuál de los mensajes a la vista era el citado.
    val bubbleBg by animateColorAsState(
        if (highlighted) lerp(bg, MaterialTheme.colorScheme.tertiary, 0.35f) else bg,
        label = "bubble-highlight",
    )
    // Esquina "cola" (menos redondeada) hacia el lado del emisor; en un grupo de mensajes
    // consecutivos solo el primero abre la esquina superior de ese lado.
    val big = 18.dp
    val small = 4.dp
    val shape = if (message.mine) {
        RoundedCornerShape(big, if (first) big else small, small, big)
    } else {
        RoundedCornerShape(if (first) big else small, big, big, small)
    }

    // BoxWithConstraints para conocer el ancho disponible de la fila: la burbuja se topa a
    // una fracción de él (ver BUBBLE_MAX_WIDTH_RATIO). Sin tope, la **silueta la decidía el
    // contenido**: un texto largo, una imagen ancha o —desde que hay respuestas— la cita de
    // un mensaje largo estiraban la burbuja de lado a lado, y dos mensajes seguidos salían
    // con anchos muy distintos. Con el tope, el contenido se ajusta dentro de una forma
    // estable en vez de definirla.
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(top = if (first) 6.dp else 0.dp)
            .pointerInput(message.id) {
                var passedThreshold = false
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragX.value >= swipeThresholdPx) onReply()
                        passedThreshold = false
                        scope.launch { dragX.animateTo(0f) }
                    },
                    onDragCancel = {
                        passedThreshold = false
                        scope.launch { dragX.animateTo(0f) }
                    },
                ) { change, amount ->
                    // Solo hacia la derecha, y con tope: el gesto es un tirón corto, no
                    // arrastrar la burbuja por la pantalla.
                    val next = (dragX.value + amount).coerceIn(0f, swipeThresholdPx * 1.3f)
                    change.consume()
                    scope.launch { dragX.snapTo(next) }
                    // Vibra al cruzar el umbral (una vez): confirma que soltando ya responde.
                    if (!passedThreshold && next >= swipeThresholdPx) {
                        passedThreshold = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
            },
        contentAlignment = align,
    ) {
        // Flecha que asoma detrás de la burbuja conforme se arrastra: la pista visual de que
        // el gesto va a responder (aparece solo mientras se desliza).
        if (dragX.value > 0f) {
            Icon(
                KryptaReplyIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(22.dp)
                    .alpha((dragX.value / swipeThresholdPx).coerceIn(0f, 1f)),
            )
        }
        Column(
            Modifier
                .widthIn(max = maxWidth * BUBBLE_MAX_WIDTH_RATIO)
                .offset { IntOffset(dragX.value.roundToInt(), 0) }
                // Sombra sutil: sin ella, dos mensajes seguidos del mismo lado (mismo color
                // exacto, solo 2dp de separación) se leían como un único bloque en vez de
                // burbujas distintas — el propio contraste "entre ellas" que hacía falta.
                .shadow(1.5.dp, shape, clip = false)
                .clip(shape)
                .background(bubbleBg)
                // Las burbujas recibidas son un gris neutro cercano al fondo; un borde fino
                // les da un segundo nivel de contraste con la pantalla (mismo criterio que
                // las tarjetas de Ajustes).
                .then(
                    if (!message.mine && !failed) {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
                    } else Modifier,
                )
                // Un toque reintenta si el mensaje falló; la pulsación larga abre las
                // acciones (responder/copiar). Toque y pulsación larga van en un solo
                // `combinedClickable` porque encadenar dos modificadores de gesto haría que
                // el segundo no llegue a ver el evento (el deslizar-para-responder sí puede
                // ir aparte: vive en el Box de fuera, no en este mismo nodo).
                // Se cablea en TODAS las burbujas, no solo en las de texto: copiar sigue
                // siendo cosa del texto, pero **responder** vale para foto, audio o archivo.
                .combinedClickable(
                    onClick = { if (failed) onRetry() },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            CompositionLocalProvider(LocalContentColor provides onBg) {
                message.quoted?.let { quoted ->
                    QuotedPreview(
                        quoted = quoted,
                        onBg = onBg,
                        modifier = Modifier.padding(bottom = 4.dp),
                        onClick = if (quoted.available) {
                            { onQuoteClick(quoted.id) }
                        } else null,
                    )
                }
                when {
                    message.image != null -> {
                        val bmp = remember(message.id) { ImageCodec.decode(message.image) }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = "Imagen",
                                modifier = Modifier
                                    .heightIn(max = 240.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                        } else {
                            Text("📷 [imagen no disponible]", color = onBg)
                        }
                    }
                    message.file != null ->
                        // Con copia en disco: un audio se reproduce en la burbuja (nota de voz)
                        // y un GIF/WebP se anima. Sin copia local (o mime que no sea de esos)
                        // cae a la burbuja de archivo genérica; AnimatedImage también cae ahí
                        // si el archivo ya no se puede decodificar.
                        if (message.file.localPath != null && message.file.mime.startsWith("audio/")) {
                            AudioNote(message.file)
                        } else if (message.file.localPath != null && message.file.mime in ANIMATED_IMAGE_MIMES) {
                            AnimatedImage(message.file.localPath) { FileAttachment(message.file) }
                        } else {
                            FileAttachment(message.file)
                        }
                    else -> Text(rememberLinkifiedText(message.text, onBg), color = onBg)
                }
                // Pie de burbuja: hora y, en las propias, el estado como icono (reloj/✓/✓✓).
                if (failed) {
                    Text(
                        "No enviado · toca para reintentar",
                        style = MaterialTheme.typography.labelSmall,
                        color = onBg,
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                    ) {
                        if (message.timestamp > 0) {
                            Text(
                                bubbleTime(message.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = onBg.copy(alpha = 0.75f),
                            )
                        }
                        if (message.mine) {
                            // Sobre el fondo `primary` de la burbuja propia: "leído" opaco
                            // (máximo contraste posible ahí) y el resto al 60%, para que
                            // ✓✓ leído siga distinguiéndose de ✓✓ entregado.
                            MessageStatusIcon(
                                message.status,
                                modifier = Modifier.padding(start = 3.dp),
                                mutedTint = onBg.copy(alpha = 0.6f),
                                readTint = onBg,
                            )
                        }
                    }
                }
            }
        }
        // Acciones en botones de icono, no en un menú. Un DropdownMenu ocupa el ancho mínimo
        // de menú de M3 (112 dp) más su relleno, y para una o dos acciones resultaba un cartel
        // enorme encima del mensaje. Aquí es una píldora con los iconos, pegada al lado de la
        // burbuja que se pulsó. Copiar solo aparece si hay texto que copiar.
        //
        // Color: la píldora se solapa con la burbuja que se pulsó, así que tiene que
        // distinguirse de las DOS orillas — el teal saturado de la propia y el gris neutro de
        // la recibida. Antes era `surfaceContainerHighest`, que es EXACTAMENTE el fondo de la
        // burbuja recibida (contraste 1.0: invisible sobre un mensaje recibido).
        //
        // Ningún relleno plano lo resuelve: en tema oscuro la burbuja propia es muy clara
        // (#53DBC9) y la recibida muy oscura (#303635), y el mejor color equidistante posible
        // se queda en 2.84:1 contra ambas — por debajo del 3:1 que pide WCAG para elementos
        // no textuales. Por eso el contraste va en **dos capas**: relleno `inverseSurface` +
        // **aro** `inverseOnSurface`, que son tonos inversos entre sí. Sobre la burbuja
        // oscura contrasta el relleno (9.5:1) y sobre la clara contrasta el aro (7.7:1); en
        // tema claro se intercambian los papeles (10.7:1 y 5.7:1). Siempre hay una de las dos
        // capas que separa la píldora del fondo, sea cual sea la burbuja de debajo.
        if (menuOpen) {
            Popup(
                alignment = if (message.mine) Alignment.TopEnd else Alignment.TopStart,
                offset = IntOffset(0, with(LocalDensity.current) { (-6).dp.roundToPx() }),
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.inverseOnSurface),
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Con el texto fuera, la descripción es lo único que queda para
                        // TalkBack: sin ella los botones serían iconos mudos.
                        IconButton(
                            onClick = {
                                menuOpen = false
                                onReply()
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                KryptaReplyIcon,
                                contentDescription = "Responder a este mensaje",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        if (copyable) {
                            IconButton(
                                onClick = {
                                    menuOpen = false
                                    copyMessageText(context, message.text)
                                },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    KryptaCopyIcon,
                                    contentDescription = "Copiar mensaje",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Cita de un mensaje anterior: franja de color + autor + resumen de una línea. Se pinta
 * dentro de la burbuja que responde y, si el citado sigue en el chat, es tocable para saltar
 * a él. El fondo es [onBg] muy transparente para que funcione sobre cualquier burbuja (la
 * propia es de color de marca y la recibida gris) sin fijar un color por rol.
 */
@Composable
private fun QuotedPreview(
    quoted: QuotedMessage,
    onBg: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            // Ocupa todo el ancho de la burbuja: así la cita es una cabecera del mensaje y no
            // un recuadro flotante que deja el interior desigual. La burbuja ya tiene su
            // propio tope de ancho, así que esto no la deforma.
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(onBg.copy(alpha = 0.12f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            // Altura intrínseca mínima: la franja de color puede así estirarse hasta la
            // altura real del texto. Con una altura fija se quedaba corta cuando el resumen
            // ocupaba dos líneas, y la cita se veía descuadrada.
            .height(IntrinsicSize.Min)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(onBg.copy(alpha = 0.7f)),
        )
        Column(Modifier.padding(start = 8.dp, top = 5.dp, bottom = 5.dp)) {
            if (quoted.author.isNotBlank()) {
                Text(
                    quoted.author,
                    style = MaterialTheme.typography.labelMedium,
                    color = onBg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                quoted.preview.ifBlank { "Mensaje" },
                style = MaterialTheme.typography.bodySmall,
                color = onBg.copy(alpha = if (quoted.available) 0.8f else 0.6f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Burbuja de archivo adjunto: nombre + tamaño; tocable para abrir si está en disco. */
@Composable
private fun FileAttachment(file: FileInfo) {
    val context = LocalContext.current
    val openable = file.localPath != null
    Row(
        modifier = Modifier
            .then(
                if (openable) Modifier.clickable { FilePicker.open(context, file.localPath!!, file.mime) }
                else Modifier,
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            KryptaAttachIcon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = LocalContentColor.current,
        )
        Column(Modifier.padding(start = 8.dp)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                humanSize(file.size) + if (openable) " · toca para abrir" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Burbuja de nota de voz: play/pausa + progreso + duración, reproducida desde la copia local
 * con un MediaPlayer propio de la burbuja (se libera al salir de pantalla).
 */
@Composable
private fun AudioNote(file: FileInfo) {
    val path = file.localPath!!
    var playing by remember(path) { mutableStateOf(false) }
    var progress by remember(path) { mutableFloatStateOf(0f) }
    var prepared by remember(path) { mutableStateOf(false) }
    var durationMs by remember(path) { mutableIntStateOf(0) }
    val player = remember(path) { android.media.MediaPlayer() }
    DisposableEffect(path) { onDispose { runCatching { player.release() } } }

    // Barra y contador avanzan solo mientras suena.
    LaunchedEffect(playing) {
        while (playing) {
            if (durationMs > 0) progress = player.currentPosition / durationMs.toFloat()
            delay(200)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        IconButton(onClick = {
            runCatching {
                if (playing) {
                    player.pause()
                    playing = false
                } else {
                    if (!prepared) {
                        player.setDataSource(path)
                        player.prepare()
                        durationMs = player.duration
                        player.setOnCompletionListener {
                            playing = false
                            progress = 1f
                        }
                        prepared = true
                    }
                    player.start()
                    playing = true
                }
            }
        }) {
            Icon(
                if (playing) KryptaPauseIcon else KryptaPlayIcon,
                contentDescription = if (playing) "Pausar" else "Reproducir",
                tint = LocalContentColor.current,
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.weight(1f, fill = false).padding(horizontal = 4.dp).size(width = 120.dp, height = 4.dp),
        )
        Text(
            if (durationMs > 0) formatSeconds(durationMs / 1000) else humanSize(file.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

private fun formatSeconds(s: Int): String = "%d:%02d".format(s / 60, s % 60)

private fun humanSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** Etiqueta legible del estado de un mensaje propio. */
internal fun statusLabel(status: MessageStatus): String = when (status) {
    MessageStatus.PENDING -> "enviando…"
    MessageStatus.SENT -> "enviado"
    MessageStatus.DELIVERED -> "entregado"
    MessageStatus.READ -> "leído"
    MessageStatus.FAILED -> "no enviado"
}

/** Modo de la grabación de nota de voz en curso (ver el micro de [ChatScreen]). */
private enum class RecordMode { NONE, LOCKED, HELD }

/** Fila pintable del chat: separador de día o mensaje (con su posición dentro del grupo). */
private sealed interface ChatRow {
    data class Day(val label: String) : ChatRow

    /** [first]: el mensaje abre grupo (cambia el lado, el día, o pasó el hueco de tiempo). */
    data class Msg(val message: DisplayMessage, val first: Boolean) : ChatRow
}

/** Hueco máximo entre mensajes consecutivos del mismo lado para agruparlos como uno. */
private const val GROUP_WINDOW_MS = 3 * 60_000L

/** Arrastre horizontal a partir del cual soltar la burbuja abre la respuesta. */
private val SWIPE_REPLY_THRESHOLD = 56.dp

/**
 * Ancho máximo de una burbuja como fracción del ancho de la lista. Es lo que mantiene la
 * **forma al margen del contenido**: sin él, un texto largo, una imagen ancha o la cita de un
 * mensaje largo estiraban la burbuja hasta el borde y cada mensaje salía con una silueta
 * distinta. También deja siempre visible un margen del lado contrario, que es lo que hace
 * legible de un vistazo quién escribió cada mensaje.
 */
private const val BUBBLE_MAX_WIDTH_RATIO = 0.78f

/**
 * Resumen de una línea del mensaje que se va a responder, para la barra de cita. Duplica a
 * propósito muy poco de [ChatViewModel]: aquí el mensaje ya viene descifrado y solo hay que
 * rotular los tipos sin texto.
 */
private fun replyPreview(m: DisplayMessage): String = when {
    m.image != null -> "📷 Foto"
    m.file != null && m.file.mime.startsWith("audio/") -> "🎤 Nota de voz"
    m.file != null && m.file.mime in ANIMATED_IMAGE_MIMES -> "🎞 GIF"
    m.file != null -> "📎 ${m.file.name}"
    else -> m.text
}

private fun buildChatRows(messages: List<DisplayMessage>): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    messages.forEachIndexed { i, m ->
        val prev = messages.getOrNull(i - 1)
        val newDay = prev == null || !sameDay(prev.timestamp, m.timestamp)
        if (newDay && m.timestamp > 0) rows += ChatRow.Day(dayLabel(m.timestamp))
        val first = prev == null || newDay || prev.mine != m.mine ||
            m.timestamp - prev.timestamp > GROUP_WINDOW_MS
        rows += ChatRow.Msg(m, first)
    }
    return rows
}

@Composable
private fun DaySeparator(label: String) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** Opción de la hoja de adjuntos: icono en círculo tonal + título + subtítulo. */
@Composable
private fun AttachOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun bubbleTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
        ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

/** Rótulo del separador de día: "Hoy", "Ayer", "sábado 11 de julio" (o con año si es otro). */
private fun dayLabel(timestamp: Long): String {
    val now = System.currentTimeMillis()
    if (sameDay(timestamp, now)) return "Hoy"
    if (sameDay(timestamp, now - 86_400_000L)) return "Ayer"
    val thisYear = Calendar.getInstance().get(Calendar.YEAR) ==
        Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.YEAR)
    val pattern = if (thisYear) "EEEE d 'de' MMMM" else "d 'de' MMMM 'de' yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}

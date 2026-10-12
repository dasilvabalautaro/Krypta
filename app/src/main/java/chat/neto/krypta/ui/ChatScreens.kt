package chat.neto.krypta.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TooltipBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.neto.krypta.ScreenSecurity
import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.p2p.ChatService
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
internal fun ChatScreen(
    viewModel: ChatViewModel,
    contact: Contact,
    online: Boolean,
    myPeerId: String,
    onBack: () -> Unit,
    onStartCall: (Contact) -> Unit,
) {
    // `remember`: viewModel.messages(contact) construye un Flow NUEVO en cada llamada, y
    // collectAsState está keyed por la instancia — sin esto, cada recomposición (escribir en el
    // campo de texto recompone esta pantalla) reiniciaba la colección: la lista parpadeaba a
    // vacío y se volvía a descifrar la conversación entera en cada pulsación de tecla.
    // Ventana: cuántos de los mensajes más recientes se cargan. Crece de [CHAT_PAGE] en
    // [CHAT_PAGE] al llegar arriba (O-9 de docs/AUDITORIA-2026-10-12.md).
    val window = remember(contact.id) { MutableStateFlow(CHAT_PAGE) }
    val messageFlow = remember(contact.id) { viewModel.messages(contact, window) }
    val messages by messageFlow.collectAsState(initial = emptyList())
    // El último mensaje, no el tamaño: con la ventana llena, uno nuevo empuja fuera al más
    // antiguo y el tamaño no cambia — y al cargar el tramo anterior cambia sin que llegue nada.
    val lastMessageId = messages.lastOrNull()?.id
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
    ) { granted -> if (granted) onStartCall(contact) }
    val startCall = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) onStartCall(contact) else callPermission.launch(Manifest.permission.RECORD_AUDIO)
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
    LaunchedEffect(contact.id, lastMessageId) {
        viewModel.markRead(contact)
    }

    // Filas del chat: mensajes + separadores de día, con agrupación de consecutivos del
    // mismo lado (la burbuja solo "abre" esquina en el primero y último del grupo).
    val rows = remember(messages) { buildChatRows(messages) }

    // Auto-scroll al último mensaje. Instantáneo la primera vez que llega el historial (para
    // aterrizar bien al abrir); animado en los mensajes siguientes. Espera un frame a que la
    // lista esté compuesta antes de saltar, si no el índice aún no existe.
    var didInitialScroll by remember(contact.id) { mutableStateOf(false) }
    LaunchedEffect(lastMessageId) {
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

    // Al llegar arriba con la ventana llena, se carga el tramo anterior. Solo tras el primer
    // salto al final: antes de él la lista está arriba del todo sin que nadie haya subido.
    val loadedCount = rememberUpdatedState(messages.size)
    LaunchedEffect(contact.id) {
        snapshotFlow { didInitialScroll && listState.firstVisibleItemIndex == 0 }
            .distinctUntilChanged()
            .collect { atTop ->
                if (atTop && loadedCount.value >= window.value) window.value += CHAT_PAGE
            }
    }

    // Tocar una cita salta al mensaje citado y lo resalta un momento (si sigue en el chat). Si
    // quedó fuera de la ventana, se agranda hasta incluirlo y el salto se hace al llegar.
    val scope = rememberCoroutineScope()
    var pendingJump by remember(contact.id) { mutableStateOf<String?>(null) }
    val jumpTo = { targetId: String ->
        val index = rows.indexOfFirst { it is ChatRow.Msg && it.message.id == targetId }
        if (index >= 0) {
            highlightId = targetId
            scope.launch { listState.animateScrollToItem(index) }
        } else {
            scope.launch {
                val needed = viewModel.windowToInclude(contact, targetId)
                if (needed > 0) {
                    pendingJump = targetId
                    window.value = maxOf(window.value, needed + CHAT_JUMP_MARGIN)
                } else {
                    Toast.makeText(context, "Ese mensaje ya no está en este chat", Toast.LENGTH_SHORT).show()
                }
            }
        }
        Unit
    }
    LaunchedEffect(rows, pendingJump) {
        val target = pendingJump ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it is ChatRow.Msg && it.message.id == target }
        if (index >= 0) {
            pendingJump = null
            highlightId = target
            withFrameNanos {}
            listState.animateScrollToItem(index)
        }
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
            protection = viewModel.protection(contact),
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
    protection: ChatService.Protection,
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
                ProtectionLine(contact.displayName, protection, contact.peerProtocol)
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

/**
 * Qué protección lleva hoy lo que se le envía a este contacto. Existe porque la primera
 * negociación de versión es oportunista: si los anuncios iniciales no llegan, la pareja sigue con
 * la clave fija sin que nada falle, y esto es lo que lo deja ver (A-07 de la auditoría interna del
 * 11 oct 2026).
 */
@Composable
private fun ProtectionLine(name: String, protection: ChatService.Protection, peerProtocol: Int) {
    val (texto, color) = when (protection) {
        ChatService.Protection.STATIC ->
            "Clave fija: la app de $name todavía no ha anunciado la versión nueva. Quien obtenga " +
                "una de las dos identidades podría descifrar lo que haya grabado de la red." to
                MaterialTheme.colorScheme.error
        ChatService.Protection.RATCHET ->
            "Clave que cambia (ratchet por épocas)." to MaterialTheme.colorScheme.onSurfaceVariant
        ChatService.Protection.RATCHET_PADDED ->
            "Clave que cambia (ratchet por épocas) y tamaño relleno." to
                MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        "Protección con $name: $texto",
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(top = 4.dp),
    )
    // La versión que consta del contacto. «Ratchet y relleno» es igual en v3 y en v4, y sin esto no
    // había forma de saber desde el móvil si una pareja ya va por el protocolo v4 (12 oct 2026).
    Text(
        if (peerProtocol > 0) "Su app anuncia el protocolo v$peerProtocol."
        else "Su app todavía no ha anunciado qué versión del protocolo habla.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

/** Mensajes por tramo del chat: los que se cargan al abrirlo y los que se añaden al subir (O-9). */
private const val CHAT_PAGE = 100

/** Mensajes de más por encima de una cita antigua al saltar a ella, para que tenga contexto. */
private const val CHAT_JUMP_MARGIN = 20

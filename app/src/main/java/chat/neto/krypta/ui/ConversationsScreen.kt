package chat.neto.krypta.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.core.model.MessageStatus
import chat.neto.krypta.p2p.WanStatus
import chat.neto.krypta.ui.theme.AvatarColors
import chat.neto.krypta.ui.theme.avatarShapeFor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Pantalla principal: lista de conversaciones (avatar + último mensaje + hora + no leídos),
 * FAB para añadir contacto y acceso a Ajustes. Los controles técnicos (PeerID, bootstrap,
 * diagnóstico…) viven en [SettingsScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    conversations: List<ConversationItem>,
    online: Set<String>,
    wanStatus: WanStatus,
    error: String?,
    onOpen: (Contact) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onAddContact: (String, String) -> Unit,
    onClearError: () -> Unit,
    onClearChat: (Contact) -> Unit,
    onDeleteContact: (Contact) -> Unit,
    onSetBlocked: (Contact, Boolean) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    // Pulsación larga sobre una conversación → menú de acciones → confirmación destructiva.
    var actionsFor by remember { mutableStateOf<Contact?>(null) }
    var confirmClear by remember { mutableStateOf<Contact?>(null) }
    var confirmDelete by remember { mutableStateOf<Contact?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Krypta", style = MaterialTheme.typography.titleLarge)
                        WanStatusSubtitle(wanStatus)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenHelp) {
                        Icon(KryptaHelpIcon, contentDescription = "Ayuda")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(KryptaSettingsIcon, contentDescription = "Ajustes")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onClearError(); showAdd = true },
                icon = { Icon(KryptaAddIcon, contentDescription = null) },
                text = { Text("Nuevo contacto") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (conversations.isEmpty()) {
                EmptyConversations()
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(conversations, key = { it.contact.id }) { item ->
                        ConversationRow(
                            item = item,
                            // Un bloqueado no se anuncia ni se busca: aunque quedara en
                            // el set de conectados, pintarlo "en línea" sería mentira.
                            isOnline = item.contact.peerId in online && !item.contact.blocked,
                            onClick = { onOpen(item.contact) },
                            onLongClick = { actionsFor = item.contact },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddContactDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, peerId ->
                onAddContact(name, peerId)
                showAdd = false
            },
        )
    }

    actionsFor?.let { contact ->
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(contact.displayName) },
            text = {
                Column {
                    DialogOption("Vaciar chat") {
                        actionsFor = null
                        confirmClear = contact
                    }
                    // Bloquear es reversible y no destruye nada, así que no pide confirmación
                    // (a diferencia de vaciar/eliminar).
                    DialogOption(if (contact.blocked) "Desbloquear" else "Bloquear") {
                        actionsFor = null
                        onSetBlocked(contact, !contact.blocked)
                    }
                    DialogOption("Eliminar contacto", color = MaterialTheme.colorScheme.error) {
                        actionsFor = null
                        confirmDelete = contact
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionsFor = null }) { Text("Cancelar") } },
        )
    }
    confirmClear?.let { contact ->
        ConfirmDeleteDialog(
            title = "¿Vaciar el chat?",
            text = "Se borrarán los mensajes y archivos del chat con ${contact.displayName} " +
                "solo en este dispositivo. Esta acción no se puede deshacer.",
            confirmLabel = "Vaciar",
            onConfirm = {
                confirmClear = null
                onClearChat(contact)
            },
            onDismiss = { confirmClear = null },
        )
    }
    confirmDelete?.let { contact ->
        ConfirmDeleteDialog(
            title = "¿Eliminar a ${contact.displayName}?",
            text = "Se eliminarán el contacto y todos sus mensajes de este dispositivo. " +
                "Podrás volver a añadirlo con su PeerID (y verificarlo de nuevo).",
            confirmLabel = "Eliminar",
            onConfirm = {
                confirmDelete = null
                onDeleteContact(contact)
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

/** Opción de un diálogo-menú (fila clicable a lo ancho, estilo lista). */
@Composable
private fun DialogOption(
    label: String,
    color: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

/** Estado de la conexión WAN como subtítulo discreto de la barra (punto + etiqueta). */
@Composable
private fun WanStatusSubtitle(status: WanStatus) {
    val (label, color) = when (status) {
        WanStatus.DISABLED -> "solo red local" to MaterialTheme.colorScheme.onSurfaceVariant
        WanStatus.CONNECTING -> "conectando…" to MaterialTheme.colorScheme.tertiary
        WanStatus.CONNECTED -> "conectado" to MaterialTheme.colorScheme.primary
        WanStatus.ERROR -> "sin conexión" to MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    item: ConversationItem,
    isOnline: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val contact = item.contact
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(contact.displayName, contact.peerId, online = isOnline, size = 52.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            // La fecha va en su propia celda tras una fila con weight(1f): así su borde
            // derecho siempre coincide con el borde de la fila (antes un Spacer(weight(1f))
            // sin fillMaxWidth en el Row dejaba la fecha flotando según el ancho del nombre,
            // desalineada entre contactos con nombres de distinta longitud).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        contact.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (item.unread > 0) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (contact.verified) {
                        Icon(
                            KryptaShieldIcon,
                            contentDescription = "Identidad verificada",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp).size(15.dp),
                        )
                    }
                    if (contact.blocked) {
                        Icon(
                            KryptaBlockIcon,
                            contentDescription = "Contacto bloqueado",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 4.dp).size(15.dp),
                        )
                    }
                }
                if (item.timestamp != null) {
                    Text(
                        relativeTime(item.timestamp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (item.unread > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 3.dp),
            ) {
                if (item.previewMine && item.previewStatus != null) {
                    MessageStatusIcon(item.previewStatus, modifier = Modifier.padding(end = 4.dp))
                }
                Text(
                    // Sin mensajes aún: enseña el PeerID (identifica al contacto recién añadido).
                    item.preview ?: contact.peerId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (item.unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.unread > 0) {
                    Badge(modifier = Modifier.padding(start = 8.dp)) {
                        Text(if (item.unread > 99) "99+" else "${item.unread}")
                    }
                }
            }
        }
    }
}

/**
 * Icono pequeño del estado de un mensaje propio (para la vista previa y las burbujas):
 * reloj = enviando, ✓ = enviado, ✓✓ = entregado, ✓✓ en primary = leído, ! = fallido.
 */
@Composable
fun MessageStatusIcon(
    status: MessageStatus,
    modifier: Modifier = Modifier,
    // Tintes por defecto para la lista de conversaciones (sobre el fondo). Dentro de una
    // burbuja propia (fondo `primary`) se pasan tintes derivados de `onBg`, porque el teal
    // fijo de "leído" quedaría teal-sobre-teal (invisible).
    mutedTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    readTint: Color = MaterialTheme.colorScheme.primary,
    failedTint: Color = MaterialTheme.colorScheme.error,
) {
    val (icon, tint) = when (status) {
        MessageStatus.PENDING -> KryptaClockIcon to mutedTint
        MessageStatus.SENT -> KryptaCheckIcon to mutedTint
        MessageStatus.DELIVERED -> KryptaDoubleCheckIcon to mutedTint
        MessageStatus.READ -> KryptaDoubleCheckIcon to readTint
        MessageStatus.FAILED -> null to failedTint
    }
    if (icon != null) {
        Icon(icon, contentDescription = statusLabel(status), tint = tint, modifier = modifier.size(15.dp))
    } else {
        Text(
            "!",
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            modifier = modifier,
        )
    }
}

/**
 * Avatar del contacto: inicial sobre un color y una **forma** estables derivados del PeerID
 * (círculo, hexágono, pentágono…), con punto verde de "en línea". Color + forma juntos son
 * una huella visual de identidad y rompen la monotonía de tener solo círculos. El punto de
 * "en línea" se coloca hacia el interior inferior-derecho (no en la esquina) para que quede
 * sobre el cuerpo de cualquier forma — en un hexágono/pentágono la esquina es aire.
 */
@Composable
fun ContactAvatar(name: String, peerId: String, online: Boolean, size: Dp = 44.dp) {
    val color = remember(peerId) { AvatarColors[abs(peerId.hashCode()) % AvatarColors.size] }
    val shape = remember(peerId) { avatarShapeFor(peerId) }
    val initial = remember(name) { name.trim().take(1).uppercase().ifEmpty { "?" } }
    Box(Modifier.size(size)) {
        Box(
            Modifier.size(size).clip(shape).background(color),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initial,
                color = Color.White,
                // La inicial escala con el avatar (52 dp en la lista, 96 dp en llamada).
                style = MaterialTheme.typography.titleLarge.copy(fontSize = (size.value * 0.42f).sp),
                fontWeight = FontWeight.Bold,
            )
        }
        if (online) {
            // Centrado en (0.70, 0.84) del recuadro: dentro del cuerpo de todas las formas.
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset(x = size * 0.55f, y = size * 0.69f)
                    .size(size * 0.30f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(size * 0.20f)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50)),
                )
            }
        }
    }
}

@Composable
private fun EmptyConversations() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            KryptaChatBubbleIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Aún no tienes contactos", style = MaterialTheme.typography.titleMedium)
        Text(
            "1. En Ajustes, comparte tu PeerID con la persona que quieres agregar y pídele el suyo.\n" +
                "2. Toca \"Nuevo contacto\", escribe su nombre y pega su PeerID.\n" +
                "3. Abre el chat y verifica la conexión con esa persona.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Hora del último mensaje al estilo mensajería: hoy → "14:05", ayer → "ayer", esta semana
 * → "lun.", más viejo → "12/7/26".
 */
private fun relativeTime(timestamp: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    val daysAgo = ((now.timeInMillis - timestamp) / 86_400_000L).toInt()
    return when {
        daysAgo < 2 -> "ayer"
        daysAgo < 7 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("d/M/yy", Locale.getDefault()).format(Date(timestamp))
    }
}

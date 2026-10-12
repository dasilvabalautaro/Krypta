package chat.neto.krypta.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.neto.krypta.KryptaNotifications
import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.core.model.MessageContent
import chat.neto.krypta.core.model.MessageStatus
import chat.neto.krypta.p2p.ChatService
import chat.neto.krypta.p2p.WanStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Mensaje listo para pintar: contenido ya descifrado + de quién es. Prioridad de tipo:
 * [file] no nulo → burbuja de archivo; [image] no nulo → imagen; si no, [text].
 * [quoted] no nulo = el mensaje responde a otro y se pinta con su cita encima.
 */
data class DisplayMessage(
    val id: String,
    val text: String,
    val image: ByteArray? = null,
    val file: FileInfo? = null,
    val mine: Boolean,
    val status: MessageStatus,
    val timestamp: Long = 0L,
    val quoted: QuotedMessage? = null,
)

/**
 * Cita de un mensaje anterior, ya resuelta contra la conversación local. [available] false =
 * el citado ya no está (chat vaciado, o aún no ha llegado): por la red viaja solo su id, no
 * una copia, así que no hay nada que pintar más allá del aviso.
 */
data class QuotedMessage(
    val id: String,
    val author: String,
    val preview: String,
    val available: Boolean = true,
)

/** Datos de un archivo adjunto para la burbuja (abrir requiere [localPath]). */
data class FileInfo(val name: String, val mime: String, val size: Long, val localPath: String?)

/**
 * Fila de la lista de conversaciones: contacto + resumen del último mensaje ya descifrado
 * ([preview] null = conversación sin mensajes) + badge de no leídos.
 */
data class ConversationItem(
    val contact: Contact,
    val preview: String?,
    val previewMine: Boolean,
    val previewStatus: MessageStatus?,
    val timestamp: Long?,
    val unread: Int,
)

/**
 * La **lista de conversaciones** y el **chat**: contactos, mensajes, envíos y acciones sobre un
 * contacto. Hasta el 12 oct 2026 servía también a la llamada y a Ajustes; eso está ahora en
 * [CallViewModel] y [SettingsViewModel] (§5 de docs/AUDITORIA-2026-10-12.md).
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chat: ChatService,
    private val notifier: chat.neto.krypta.IncomingNotifier,
    private val files: chat.neto.krypta.core.FileStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /**
     * Bytes **en claro** de un adjunto. Desde que `krypta_files/` va cifrado en reposo, la UI
     * no lee ficheros: los pide aquí y descifra el almacén.
     */
    suspend fun readAttachment(path: String): ByteArray? = runCatching { files.read(path) }.getOrNull()

    val contacts: StateFlow<List<Contact>> =
        chat.observeContacts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Lista de conversaciones: contactos + último mensaje descifrado + no leídos, ordenada
     * por actividad reciente (los contactos sin mensajes al final, por nombre).
     */
    val conversations: StateFlow<List<ConversationItem>> =
        combine(
            chat.observeContacts(),
            chat.observeLastMessages(),
            chat.observeUnreadCounts(),
        ) { contacts, lastMessages, unreadCounts ->
            val lastByConversation = lastMessages.associateBy { it.conversationId }
            contacts.map { c ->
                val last = lastByConversation[c.id]
                ConversationItem(
                    contact = c,
                    preview = last?.let { chat.notificationText(c, it) },
                    previewMine = last != null && last.senderId != c.id,
                    previewStatus = last?.status,
                    timestamp = last?.timestamp,
                    unread = unreadCounts[c.id] ?: 0,
                )
            }.sortedWith(
                compareByDescending<ConversationItem> { it.timestamp ?: Long.MIN_VALUE }
                    .thenBy { it.contact.displayName.lowercase() },
            )
        }
            .flowOn(Dispatchers.Default) // el preview descifra AES por conversación
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** PeerIDs conectados ahora mismo (mDNS LAN en pruebas; DHT/rendezvous en WAN). */
    val onlinePeers: StateFlow<Set<String>> = chat.onlinePeers

    /** Diagnóstico: estado de la conexión WAN y registro de eventos en vivo. */
    val wanStatus: StateFlow<WanStatus> = chat.wanStatus

    init {
        // Arranca host + descubrimiento (LAN + WAN si hay bootstrap). Idempotente: el servicio en
        // primer plano y el latido también lo llaman.
        viewModelScope.launch(Dispatchers.IO) { runCatching { chat.start() } }
    }

    /**
     * Flujo de mensajes de la conversación, descifrados para mostrar. En dos pasadas: primero
     * cada mensaje, después las **citas**, que se resuelven contra los mensajes ya mapeados
     * (por la red viaja solo el id del citado). Como la fuente es un Flow, una cita a un mensaje
     * que aún no había llegado se completa sola en cuanto llega.
     *
     * **Por ventanas** ([window] = cuántos de los más recientes; O-9 de
     * docs/AUDITORIA-2026-10-12.md): Room reemite la consulta con **cualquier** cambio de la
     * tabla —un mensaje de otro chat, un ✓✓—, y cargar la conversación entera, con las fotos en
     * línea, en cada reemisión hacía que el coste creciera con la historia del chat. La pantalla
     * agranda la ventana al llegar arriba. Una cita a un mensaje que quedó fuera se resuelve
     * aparte, por id.
     *
     * Dos cosas importantes más, ambas de la auditoría (A-2):
     *  - **`flowOn(Dispatchers.Default)`**: cada mensaje es un decodificado de sobre (y, en
     *    las filas anteriores a la v8, además un descifrado AES-GCM). Sin esto se ejecutaba en
     *    el hilo del colector, que es el principal.
     *  - **caché por mensaje** ([decodedCache]): sin ella cada reemisión volvía a decodificar
     *    toda la ventana. El contenido guardado de un id no cambia nunca, así que la caché es
     *    segura y basta con la clave (id + huella del payload).
     *
     * La llamada desde Compose debe ir dentro de un `remember`: este método construye un Flow
     * nuevo en cada invocación y `collectAsState` reinicia la colección con cada instancia.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun messages(contact: Contact, window: Flow<Int>): Flow<List<DisplayMessage>> =
        window.distinctUntilChanged()
            .flatMapLatest { limit -> chat.observeRecent(contact.id, limit) }
            .map { list ->
                val decoded = list.map { m -> toDisplay(contact, m) }
                val byId = decoded.associate { (msg, _) -> msg.id to msg }
                decoded.map { (msg, replyTo) ->
                    if (replyTo == null) msg
                    else msg.copy(quoted = quoteOf(contact, replyTo, byId[replyTo] ?: outsideWindow(contact, replyTo)))
                }
            }
            .flowOn(Dispatchers.Default)

    /** Tamaño de ventana que incluye el mensaje [id] (saltar a una cita antigua); 0 si no está. */
    suspend fun windowToInclude(contact: Contact, id: String): Int =
        runCatching { chat.windowToInclude(contact.id, id) }.getOrDefault(0)

    /** Un mensaje citado que quedó fuera de la ventana cargada, buscado por id. */
    private suspend fun outsideWindow(contact: Contact, id: String): DisplayMessage? =
        runCatching { chat.findMessage(contact.id, id) }.getOrNull()?.let { toDisplay(contact, it).first }

    /** Un mensaje guardado, listo para pintar, y el id del que cita (si es una respuesta). */
    private fun toDisplay(
        contact: Contact,
        m: chat.neto.krypta.core.model.Message,
    ): Pair<DisplayMessage, String?> {
        val mine = m.senderId != contact.id
        val d = decodeCached(contact, m)
        val display = when (val c = d?.content) {
            is MessageContent.Image ->
                DisplayMessage(
                    m.id, text = "", image = c.jpeg, mine = mine, status = m.status,
                    timestamp = m.timestamp,
                )
            is MessageContent.File ->
                DisplayMessage(
                    m.id, text = "", mine = mine, status = m.status,
                    file = FileInfo(c.name, c.mime, c.size, c.localPath),
                    timestamp = m.timestamp,
                )
            is MessageContent.Text ->
                DisplayMessage(
                    m.id, text = c.text, mine = mine, status = m.status,
                    timestamp = m.timestamp,
                )
            null ->
                DisplayMessage(
                    m.id, text = "[cifrado]", mine = mine, status = m.status,
                    timestamp = m.timestamp,
                )
        }
        return display to d?.replyTo
    }

    /**
     * Decodifica [m] reutilizando el resultado anterior si ya se decodificó ese mismo mensaje.
     *
     * Solo se cachean texto y archivo: la imagen en línea lleva sus bytes dentro, y guardar
     * cientos en memoria sería peor que volver a decodificarla. La clave incluye una huella del
     * payload para que un id reutilizado con otro contenido nunca devuelva lo anterior.
     */
    private fun decodeCached(
        contact: Contact,
        m: chat.neto.krypta.core.model.Message,
    ): chat.neto.krypta.core.model.DecodedMessage? {
        val key = "${m.id}:${m.payload.contentHashCode()}"
        decodedCache[key]?.let { return it }
        val decoded = runCatching { chat.decodeMessage(contact, m) }.getOrNull() ?: return null
        if (decoded.content !is MessageContent.Image) decodedCache[key] = decoded
        return decoded
    }

    private val decodedCache =
        object : LinkedHashMap<String, chat.neto.krypta.core.model.DecodedMessage>(128, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, chat.neto.krypta.core.model.DecodedMessage>?,
            ) = size > MAX_DECODED_CACHE
        }

    /** Cita pintable de [target] (el mensaje citado), o el aviso de que ya no está. */
    private fun quoteOf(contact: Contact, replyTo: String, target: DisplayMessage?): QuotedMessage =
        if (target == null) {
            QuotedMessage(replyTo, author = "", preview = "Mensaje no disponible", available = false)
        } else {
            QuotedMessage(
                id = target.id,
                author = if (target.mine) "Tú" else contact.displayName,
                preview = previewOf(target),
            )
        }

    /** Resumen de una línea de [m] para pintarlo dentro de una cita. */
    private fun previewOf(m: DisplayMessage): String = when {
        m.image != null -> "📷 Foto"
        m.file != null && m.file.mime.startsWith("audio/") -> "🎤 Nota de voz"
        m.file != null && m.file.mime in ANIMATED_MIMES -> "🎞 GIF"
        m.file != null -> "📎 ${m.file.name}"
        else -> m.text
    }

    /** [replyTo]: id del mensaje citado si se está respondiendo a uno (null = mensaje suelto). */
    fun send(contact: Contact, text: String, replyTo: String? = null) {
        if (text.isBlank()) return
        // ChatService.send ya no propaga fallos de envío (marca FAILED); el runCatching es
        // cinturón extra para que nada (p. ej. cifrado) pueda tumbar la app.
        viewModelScope.launch { runCatching { chat.send(contact, text.toByteArray(), replyTo) } }
    }

    /**
     * Envía una imagen desde su [uri] (photo picker o contenido del teclado).
     *
     * Bifurca por tipo: un **GIF va entero por el camino troceado** ([sendAnimation]) para que
     * conserve la animación; el resto se comprime a una sola pieza en línea. Comprimir un GIF
     * con `ImageCodec` lo dejaba en su primer fotograma — llegaba congelado.
     */
    fun sendImage(contact: Contact, uri: android.net.Uri, replyTo: String? = null) {
        viewModelScope.launch {
            val mime = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.getType(uri) }.getOrNull()
            }
            if (mime in ANIMATED_MIMES) {
                sendAnimation(contact, uri, mime!!, replyTo)
                return@launch
            }
            runCatching {
                val jpeg = withContext(Dispatchers.IO) { ImageCodec.compress(context, uri) }
                if (jpeg != null) chat.sendImage(contact, jpeg, replyTo)
                else _error.value = "No se pudo procesar la imagen"
            }
        }
    }

    /**
     * Envía una imagen animada **sin recodificar**: los bytes originales viajan por el camino
     * de archivos troceados (48 KiB por trozo, con staging en disco y reentrega del buzón), que
     * es el único que admite más de los ~58 KiB del sobre de imagen en línea — un GIF de
     * teclado pesa entre cientos de KiB y varios MB.
     *
     * Guarda además una **copia local** (igual que las notas de voz) y la pasa como `localPath`,
     * para que la burbuja propia del emisor también se anime; sin ella solo la vería el que
     * recibe.
     */
    private suspend fun sendAnimation(
        contact: Contact,
        uri: android.net.Uri,
        mime: String,
        replyTo: String? = null,
    ) {
        val picked = withContext(Dispatchers.IO) {
            runCatching { FilePicker.read(context, uri, MAX_ANIMATION_BYTES) }.getOrNull()
        }
        if (picked == null) {
            _error.value = "GIF demasiado grande (máx ${MAX_ANIMATION_BYTES / (1024 * 1024)} MB) o ilegible"
            return
        }
        val extension = if (mime == "image/webp") "webp" else "gif"
        val name = picked.name.takeIf { it.contains('.') } ?: "animacion.$extension"
        // La copia propia va por el almacén, que la deja cifrada en reposo como el resto.
        val localPath = files.saveSent("${java.util.UUID.randomUUID()}.$extension", picked.bytes)
        runCatching { chat.sendFile(contact, name, mime, picked.bytes, localPath, replyTo) }
            .onFailure { _error.value = "No se pudo enviar el GIF" }
    }

    /** Lee y envía un archivo desde su [uri] (file picker), troceado. Límite v1: 8 MB. */
    fun sendFile(contact: Contact, uri: android.net.Uri, replyTo: String? = null) {
        viewModelScope.launch {
            runCatching {
                val info = withContext(Dispatchers.IO) { FilePicker.read(context, uri, MAX_FILE_BYTES) }
                when {
                    info == null -> _error.value = "No se pudo leer el archivo"
                    else -> chat.sendFile(contact, info.name, info.mime, info.bytes, replyTo = replyTo)
                }
            }.onFailure { _error.value = "Archivo demasiado grande (máx 8 MB) o ilegible" }
        }
    }

    /**
     * Envía una nota de voz ya grabada (archivo .m4a en disco). Viaja como archivo troceado
     * (mime de audio) y conserva la copia local para que la burbuja propia sea reproducible.
     */
    fun sendVoiceNote(contact: Contact, file: java.io.File, replyTo: String? = null) {
        viewModelScope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                when {
                    bytes.isEmpty() -> _error.value = "Nota de voz vacía"
                    bytes.size > MAX_FILE_BYTES -> _error.value = "Nota de voz demasiado larga"
                    else -> {
                        // MediaRecorder solo sabe escribir en claro, así que graba en la caché
                        // y la copia que se queda pasa por el almacén, ya cifrada. El
                        // temporal se borra en cuanto está guardado.
                        val guardada = files.saveSent(file.name, bytes)
                        withContext(Dispatchers.IO) { file.delete() }
                        chat.sendFile(contact, file.name, "audio/mp4", bytes, guardada, replyTo)
                    }
                }
            }.onFailure { _error.value = "No se pudo enviar la nota de voz" }
        }
    }

    private companion object {
        /** Mensajes descifrados que se conservan en memoria por conversación abierta. */
        const val MAX_DECODED_CACHE = 500
        const val MAX_FILE_BYTES = 8 * 1024 * 1024
        /**
         * Tope de una imagen animada. Por debajo del cupo del buzón por destinatario (5 MiB),
         * para que un GIF siga entregándose aunque el contacto esté desconectado.
         */
        const val MAX_ANIMATION_BYTES = 4 * 1024 * 1024
        /** Formatos que se envían **tal cual** (recodificarlos mataría la animación). */
        val ANIMATED_MIMES = setOf("image/gif", "image/webp")
    }

    /** Reintenta un mensaje FALLIDO (tocándolo en el chat). */
    fun retry(contact: Contact, messageId: String) {
        viewModelScope.launch {
            // El único fallo que se lanza es un archivo sin copia local (enviado antes del
            // 21 sep 2026): el mensaje dice qué hacer. El resto acaba en FAILED sin lanzar.
            runCatching { chat.retry(contact, messageId) }
                .onFailure { _error.value = it.message ?: "No se pudo reenviar" }
        }
    }

    /** Acusa la lectura de los mensajes recibidos de [contact] (al abrir/ver el chat). */
    fun markRead(contact: Contact) {
        viewModelScope.launch { runCatching { chat.markConversationRead(contact) } }
    }

    /**
     * Declara la conversación que se está mirando (null al salir del chat): limpia su
     * notificación y hace que [chat.neto.krypta.IncomingNotifier] silencie **solo** ese
     * contacto. Antes bastaba con tener la app abierta en cualquier pantalla para no recibir
     * ningún aviso, así que un mensaje de otro contacto llegaba sin sonar.
     */
    fun onConversationVisible(contactId: String?) {
        notifier.setVisibleConversation(contactId)
    }

    private val _error = MutableStateFlow<String?>(null)

    /** Último error de usuario (p. ej. PeerID inválido al añadir contacto). */
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() { _error.value = null }

    /**
     * Alta de contacto. Dos avisos importantes porque el PeerID se pega a mano: añadirse a
     * uno mismo se rechaza (con el motivo exacto que da [ChatService.addContact]), y si el
     * PeerID ya estaba guardado con otro nombre se avisa del renombrado — si no, el alta
     * hereda en silencio el chat del contacto anterior y parece un contacto nuevo.
     */
    fun addContact(displayName: String, peerId: String) {
        viewModelScope.launch {
            val id = peerId.trim()
            val previous = runCatching { chat.findContact(id) }.getOrNull()
            runCatching { chat.addContact(displayName.trim(), id) }
                .onSuccess { added ->
                    if (previous != null && previous.displayName != added.displayName) {
                        _error.value = "Ese PeerID ya estaba guardado como “${previous.displayName}”: " +
                            "se renombró a “${added.displayName}” y conserva su historial."
                    }
                }
                .onFailure { e ->
                    _error.value = (e as? IllegalArgumentException)?.message
                        ?: "PeerID inválido o no soportado"
                }
        }
    }

    /** Vacía el chat de [contact] (mensajes + archivos locales); el contacto se conserva. */
    fun clearChat(contact: Contact) {
        viewModelScope.launch {
            runCatching { chat.clearConversation(contact) }
                .onFailure { _error.value = "No se pudo vaciar el chat" }
            // Sin mensajes ya no hay nada que anunciar de esta conversación.
            KryptaNotifications.cancel(context, contact.id)
        }
    }

    /** Elimina [contact] y todo su chat de este dispositivo. */
    fun deleteContact(contact: Contact) {
        viewModelScope.launch {
            runCatching { chat.deleteContact(contact) }
                .onFailure { _error.value = "No se pudo eliminar el contacto" }
            KryptaNotifications.cancel(context, contact.id)
        }
    }

    /** Número de seguridad anti-MITM con [contact] (idéntico en ambos móviles). */
    fun safetyNumber(contact: Contact): String = chat.safetyNumber(contact)

    /** Protección efectiva de lo que se le envía a [contact] (clave fija o ratchet). */
    fun protection(contact: Contact): ChatService.Protection = chat.protectionOf(contact)

    /** Marca/desmarca [contact] como verificado tras cotejar el número de seguridad. */
    fun setVerified(contact: Contact, verified: Boolean) {
        viewModelScope.launch { runCatching { chat.setVerified(contact, verified) } }
    }

    /**
     * Bloquea/desbloquea a [contact]. Al bloquear se retira además su notificación pendiente:
     * a partir de ese momento no vuelve a sonar nada suyo, y dejar la anterior en la bandeja
     * sería contradictorio.
     */
    fun setBlocked(contact: Contact, blocked: Boolean) {
        viewModelScope.launch {
            runCatching { chat.setBlocked(contact, blocked) }
                .onFailure { _error.value = "No se pudo cambiar el bloqueo" }
            if (blocked) KryptaNotifications.cancel(context, contact.id)
        }
    }
}

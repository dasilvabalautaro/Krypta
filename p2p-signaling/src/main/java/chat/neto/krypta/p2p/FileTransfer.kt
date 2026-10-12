package chat.neto.krypta.p2p

import chat.neto.krypta.core.FileStore
import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.core.model.Message
import chat.neto.krypta.core.model.MessageStatus
import chat.neto.krypta.core.repository.MessageRepository
import chat.neto.krypta.p2p.ChatService.Companion.CHUNK_SIZE
import chat.neto.krypta.p2p.ChatService.Companion.PIECE_RETRY_DELAYS_MS
import chat.neto.krypta.p2p.ChatService.Companion.SELF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * **Envío de archivos troceados** (también notas de voz y GIF): la meta `F` y los trozos `K` de
 * 48 KiB, la copia propia que permite reenviarlos de verdad, y el reintento por pieza. Se separó
 * de `ChatService` el 12 oct 2026 (§5 de docs/AUDITORIA-2026-10-12.md) sin cambiar nada de lo que
 * hace. La recepción sigue en `ChatService`, que es quien abre los sobres.
 */
internal class FileTransfer(
    private val outbox: Outbox,
    private val messages: MessageRepository,
    private val fileStore: FileStore,
    private val scope: CoroutineScope,
    private val log: DiagnosticsLog,
) {
    private fun logLine(msg: String) = log.line(msg)

    /**
     * Envía un **archivo** troceado a [contact]: anuncia la meta y manda cada trozo (≤ límite
     * del buzón) como mensajes cifrados por el camino normal (directo → buzón). Crea UNA
     * burbuja visible (descriptor). Si algún trozo falla pese a sus reintentos → FAILED.
     *
     * **El emisor conserva siempre una copia** (cifrada en reposo, en `krypta_files/sent/`):
     * [localPath] si ya la trae (nota de voz, GIF) o una que se guarda aquí. Es lo que permite
     * que reintentar ([retry], [retryFailed]) **reenvíe el archivo de verdad**. Hasta el 21 sep
     * 2026 los archivos del selector no guardaban copia y reintentar reenviaba solo el
     * descriptor local: al otro lado aparecía una burbuja con nombre y tamaño y **sin archivo**,
     * y aquí quedaba como enviado (pasó en vivo con un PDF de 6,5 MB).
     */
    suspend fun sendFile(
        contact: Contact,
        name: String,
        mime: String,
        bytes: ByteArray,
        localPath: String? = null,
        replyTo: String? = null,
    ): Message {
        outbox.requireNotBlocked(contact)
        // Precondición, no valor: quién cifra y con qué depende ya de [seal].
        requireNotNull(contact.sharedSecret) { "contact ${contact.id} has no shared secret" }
        val fileId = UUID.randomUUID().toString()
        val ownCopy = localPath
            ?: runCatching { fileStore.saveSent("$fileId-$name", bytes) }.getOrNull()
        if (ownCopy == null) logLine("⚠ sin copia local de $name: si falla, habrá que volver a adjuntarlo")
        // La cita va en el descriptor local (burbuja propia) y en la meta que viaja: los
        // trozos no la llevan, y la burbuja del receptor no nace hasta tenerlos todos.
        val descriptor = MessageEnvelope.wrapReply(
            replyTo,
            MessageEnvelope.encodeFileDescriptor(name, mime, bytes.size.toLong(), ownCopy),
        )
        val message = Message(
            id = fileId,
            conversationId = contact.id,
            senderId = SELF,
            payload = descriptor,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.PENDING,
        )
        messages.save(message)
        filesInFlight.add(fileId)
        try {
            return transmitFile(contact, message, name, mime, bytes, replyTo)
        } finally {
            filesInFlight.remove(fileId)
        }
    }

    /** Archivos que se están enviando ahora mismo: un reintento no debe solaparse con ellos. */
    private val filesInFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Archivos fallidos sin copia local: reintentarlos solos en cada ciclo no sirve de nada. */
    private val filesWithoutCopy: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Manda la meta y todos los trozos del archivo de [message] (su id **es** el `fileId`) y
     * deja la fila en SENT o FAILED. Reenviar un archivo con el mismo id es seguro: el
     * receptor guarda cada trozo por índice en su staging (idempotente), así que lo que ya
     * tenía de un intento anterior se reescribe y lo que faltaba completa el archivo.
     */
    private suspend fun transmitFile(
        contact: Contact,
        message: Message,
        name: String,
        mime: String,
        bytes: ByteArray,
        replyTo: String?,
    ): Message {
        val fileId = message.id
        val total = (bytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE
        return try {
            sendPiece(
                contact,
                MessageEnvelope.wrapReply(
                    replyTo,
                    MessageEnvelope.encodeFileMeta(fileId, name, mime, bytes.size.toLong(), total),
                ),
            )
            for (i in 0 until total) {
                val from = i * CHUNK_SIZE
                val to = minOf(from + CHUNK_SIZE, bytes.size)
                sendPiece(contact, MessageEnvelope.encodeFileChunk(fileId, i, bytes.copyOfRange(from, to)))
            }
            messages.updateStatus(fileId, MessageStatus.SENT)
            logLine("→ archivo enviado a ${short(contact.peerId)} ($total trozos)")
            message.copy(status = MessageStatus.SENT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            messages.updateStatus(fileId, MessageStatus.FAILED)
            logLine("✗ archivo no enviado a ${short(contact.peerId)}: ${outbox.sendErrorReason(e)}")
            message.copy(status = MessageStatus.FAILED)
        }
    }

    /**
     * Entrega **una** pieza de un archivo (meta o trozo), con reintentos antes de rendirse.
     * Un archivo son decenas o cientos de envíos seguidos y bastaba con que **uno** fallara
     * por las dos vías —un dial que se cae un instante, el buzón del destinatario lleno a
     * mitad de la ráfaga hasta que lo vacía— para que el archivo entero quedara FALLIDO.
     *
     * Se cifra **una vez** y se reenvían los mismos bytes: si un intento llegó aunque diera
     * error, el receptor lo descarta como repetido (dedup del ratchet) en vez de gastar otra
     * clave; y el trozo, por índice, es idempotente en su staging.
     */
    private suspend fun sendPiece(contact: Contact, envelope: ByteArray) {
        outbox.requireNotBlocked(contact)
        val wire = outbox.seal(contact, envelope)
        var last: Exception? = null
        for (attempt in 0..PIECE_RETRY_DELAYS_MS.size) {
            if (attempt > 0) kotlinx.coroutines.delay(PIECE_RETRY_DELAYS_MS[attempt - 1])
            try {
                outbox.deliver(contact, wire)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw requireNotNull(last)
    }

    /** El descriptor de un archivo **propio** persistido (y su cita), o null si no lo es. */
    private fun ownFileOf(contact: Contact, message: Message): Pair<MessageEnvelope.Decoded.FileDescriptor, String?>? {
        if (message.senderId != SELF) return null
        val envelope = runCatching { MessageEnvelope.decode(outbox.envelopeOf(contact, message)) }.getOrNull()
        val replyTo = (envelope as? MessageEnvelope.Decoded.Reply)?.replyTo
        val inner = (envelope as? MessageEnvelope.Decoded.Reply)?.inner ?: envelope
        return (inner as? MessageEnvelope.Decoded.FileDescriptor)?.let { it to replyTo }
    }

    /**
     * Reenvía un archivo propio desde su copia local: meta + todos los trozos, mismo id.
     * **Nunca** manda el descriptor: es la burbuja local, no el archivo. Sin copia (un envío
     * de antes del 21 sep 2026) no hay nada que reenviar y se dice, en vez de fingir.
     */
    private suspend fun resendFile(
        contact: Contact,
        message: Message,
        file: MessageEnvelope.Decoded.FileDescriptor,
        replyTo: String?,
    ): Message {
        val bytes = file.path?.let { runCatching { fileStore.read(it) }.getOrNull() }
        if (bytes == null) {
            filesWithoutCopy.add(message.id)
            messages.updateStatus(message.id, MessageStatus.FAILED)
            logLine("✗ no se puede reenviar ${file.name}: no hay copia local")
            throw IllegalStateException("No se guardó copia de «${file.name}»: vuelve a adjuntarlo")
        }
        if (!filesInFlight.add(message.id)) return message // ya se está enviando
        try {
            messages.updateStatus(message.id, MessageStatus.PENDING)
            logLine("↻ reenviando ${file.name} a ${short(contact.peerId)}")
            return transmitFile(contact, message.copy(status = MessageStatus.PENDING), file.name, file.mime, bytes, replyTo)
        } finally {
            filesInFlight.remove(message.id)
        }
    }

    /**
     * Si [message] es un archivo propio, lanza su reenvío en segundo plano —si tiene copia y no se
     * está enviando ya— y devuelve true; si no es un archivo, false. Lo usa la reconciliación del
     * ciclo WAN: un archivo son muchos envíos y no cabe en el presupuesto de su paso.
     */
    fun resendInBackground(contact: Contact, message: Message): Boolean {
        val file = ownFileOf(contact, message) ?: return false
        if (message.id !in filesWithoutCopy && message.id !in filesInFlight) {
            scope.launch { runCatching { resendFile(contact, message, file.first, file.second) } }
        }
        return true
    }

    /**
     * Reintento a mano de un archivo propio: meta y todos los trozos con el mismo id. Null si
     * [message] no es un archivo propio. **Nunca** se reenvía su fila: es el descriptor local.
     */
    suspend fun retry(contact: Contact, message: Message): Message? =
        ownFileOf(contact, message)?.let { (file, replyTo) -> resendFile(contact, message, file, replyTo) }
}

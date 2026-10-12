package chat.neto.krypta.p2p

import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.core.repository.MessageRepository
import chat.neto.krypta.p2p.ChatService.Companion.MAX_PENDING_RECEIPTS
import chat.neto.krypta.p2p.ChatService.Companion.RECEIPT_BATCH
import chat.neto.krypta.p2p.ChatService.Companion.SELF
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **Acuses de lectura**, en los dos sentidos: mandar los de lo que se acaba de leer y aplicar los
 * que llegan. Se separó de `ChatService` el 12 oct 2026 (§5 de docs/AUDITORIA-2026-10-12.md),
 * junto con el arreglo de O-2 del mismo informe.
 */
internal class ReadReceipts(
    private val messages: MessageRepository,
    private val outbox: Outbox,
) {
    /**
     * Marca como READ nuestros mensajes salientes **de esa conversación** cuyo id venga en el
     * acuse. Exigir la conversación importa porque los ids del acuse los elige quien lo
     * envía: sin esa condición, un contacto podría marcar como leídos mensajes dirigidos a
     * otro (auditoría A-9). Una sentencia por lote: antes era una consulta por id, dentro de la
     * transacción del ratchet (O-2).
     */
    suspend fun markOutgoingRead(conversationId: String, ids: List<String>) {
        messages.markOutgoingRead(conversationId, SELF, ids)
    }

    /**
     * Acuses que no salieron por ninguna vía, por contacto, para mandarlos con el siguiente.
     * Vive en memoria y con tope: perderlo al morir el proceso solo deja algún ✓✓ sin llegar al
     * otro extremo, que es lo mismo que pasaría con el móvil apagado.
     */
    private val pendingReceipts = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /** Serializa los acuses: la UI los pide al abrir el chat y con cada mensaje nuevo. */
    private val receiptLock = Mutex()

    /**
     * Envía un **acuse de lectura** de lo que estaba sin leer en la conversación de [contact]
     * (best-effort: directo, y si falla, buzón). Se llama al abrir/ver la conversación; el
     * emisor pondrá esos mensajes en READ. No persiste nada ni crea burbujas.
     *
     * Cita **solo lo que acaba de pasar de no leído a leído** ([MessageRepository.markIncomingRead]),
     * en lotes de [RECEIPT_BATCH]. Hasta el 12 oct 2026 citaba todos los ids recibidos del
     * historial menos los de una caché de 500 que moría con el proceso: con 2.000 mensajes eran
     * 74 KB al abrir el chat —más que el blob máximo del buzón—, 55 KB con cada mensaje nuevo, y
     * su tamaño le decía al nodo cuántos mensajes llevaba la conversación (O-2 de
     * docs/AUDITORIA-2026-10-12.md).
     */
    suspend fun markConversationRead(contact: Contact) = receiptLock.withLock {
        // Vista local: limpia el badge de no leídos aunque el acuse de red falle.
        val unread = runCatching { messages.markIncomingRead(contact.id) }.getOrDefault(emptyList())
        // A un bloqueado no se le acusa nada: el historial se puede seguir leyendo aquí, pero
        // él no debe recibir ninguna señal (ni siquiera un ✓✓) de este dispositivo.
        if (contact.blocked) {
            pendingReceipts.remove(contact.id)
            return@withLock
        }
        contact.sharedSecret ?: return@withLock
        val ids = (pendingReceipts.remove(contact.id).orEmpty() + unread).distinct()
        if (ids.isEmpty()) return@withLock
        val failed = mutableListOf<String>()
        for (lote in ids.chunked(RECEIPT_BATCH)) {
            val ok = runCatching { outbox.deliver(contact, outbox.seal(contact, MessageEnvelope.encodeRead(lote))) }.isSuccess
            if (!ok) failed += lote
        }
        if (failed.isNotEmpty()) pendingReceipts[contact.id] = failed.takeLast(MAX_PENDING_RECEIPTS)
    }
}

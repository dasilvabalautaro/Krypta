package chat.neto.krypta.data.repository

import androidx.room.withTransaction
import chat.neto.krypta.core.model.Message
import chat.neto.krypta.core.model.MessageStatus
import chat.neto.krypta.core.repository.MessageRepository
import chat.neto.krypta.data.KryptaDatabase
import chat.neto.krypta.data.dao.MessageDao
import chat.neto.krypta.data.entity.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class RoomMessageRepository @Inject constructor(
    private val dao: MessageDao,
    private val database: KryptaDatabase,
) : MessageRepository {

    override fun observeConversation(conversationId: String): Flow<List<Message>> =
        dao.observeConversation(conversationId).map { list -> list.map(::toDomain) }

    override fun observeRecent(conversationId: String, limit: Int): Flow<List<Message>> =
        dao.observeRecent(conversationId, limit).map { list -> list.map(::toDomain) }

    override suspend fun countSince(conversationId: String, id: String): Int =
        dao.countSince(conversationId, id)

    override fun observeLastMessages(): Flow<List<Message>> =
        dao.observeLastMessages().map { list -> list.map(::toDomain) }

    override fun observeUnreadCounts(): Flow<Map<String, Int>> =
        dao.observeUnreadCounts(MessageStatus.DELIVERED)
            .map { rows -> rows.associate { it.conversationId to it.count } }

    override suspend fun save(message: Message) = dao.upsert(message.toEntity())

    override suspend fun saveAll(messages: List<Message>) = dao.upsertAll(messages.map { it.toEntity() })

    override suspend fun findEncrypted(limit: Int, offset: Int): List<Message> =
        dao.findEncrypted(limit, offset).map(::toDomain)

    override suspend fun findById(id: String): Message? = dao.findById(id)?.let(::toDomain)

    override suspend fun findByStatus(status: MessageStatus, limit: Int): List<Message> =
        dao.findByStatus(status, limit).map(::toDomain)

    override suspend fun updateStatus(id: String, status: MessageStatus) =
        dao.updateStatus(id, status)

    // En una transacción: un entrante que llegue entre leer los ids y marcarlos no puede quedar
    // marcado como leído sin estar en la lista (no se acusaría nunca).
    override suspend fun markIncomingRead(conversationId: String): List<String> =
        database.withTransaction {
            val ids = dao.unreadIncomingIds(conversationId, MessageStatus.DELIVERED)
            dao.markIncomingRead(conversationId, MessageStatus.READ)
            ids
        }

    override suspend fun markOutgoingRead(conversationId: String, senderId: String, ids: List<String>) {
        // Por lotes: SQLite limita los parámetros de una sentencia.
        for (lote in ids.distinct().chunked(MAX_IDS_PER_STATEMENT)) {
            dao.markReadByIds(conversationId, senderId, lote, MessageStatus.READ)
        }
    }

    override suspend fun deleteConversation(conversationId: String) =
        dao.deleteConversation(conversationId)

    private fun toDomain(e: MessageEntity) = Message(
        id = e.id,
        conversationId = e.conversationId,
        senderId = e.senderId,
        payload = e.payload,
        encrypted = e.encrypted,
        timestamp = e.timestamp,
        status = e.status,
    )

    private fun Message.toEntity() = MessageEntity(
        id = id,
        conversationId = conversationId,
        senderId = senderId,
        payload = payload,
        encrypted = encrypted,
        timestamp = timestamp,
        status = status,
    )

    private companion object {
        const val MAX_IDS_PER_STATEMENT = 500
    }
}

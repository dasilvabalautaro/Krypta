package chat.neto.krypta.data.repository

import chat.neto.krypta.core.model.Message
import chat.neto.krypta.core.model.MessageStatus
import chat.neto.krypta.core.repository.MessageRepository
import chat.neto.krypta.data.dao.MessageDao
import chat.neto.krypta.data.entity.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class RoomMessageRepository @Inject constructor(
    private val dao: MessageDao,
) : MessageRepository {

    override fun observeConversation(conversationId: String): Flow<List<Message>> =
        dao.observeConversation(conversationId).map { list -> list.map(::toDomain) }

    override fun observeLastMessages(): Flow<List<Message>> =
        dao.observeLastMessages().map { list -> list.map(::toDomain) }

    override fun observeUnreadCounts(): Flow<Map<String, Int>> =
        dao.observeUnreadCounts(MessageStatus.DELIVERED)
            .map { rows -> rows.associate { it.conversationId to it.count } }

    override suspend fun save(message: Message) = dao.upsert(message.toEntity())

    override suspend fun findById(id: String): Message? = dao.findById(id)?.let(::toDomain)

    override suspend fun findByStatus(status: MessageStatus, limit: Int): List<Message> =
        dao.findByStatus(status, limit).map(::toDomain)

    override suspend fun updateStatus(id: String, status: MessageStatus) =
        dao.updateStatus(id, status)

    override suspend fun markIncomingRead(conversationId: String) =
        dao.markIncomingRead(conversationId, MessageStatus.READ)

    override suspend fun deleteConversation(conversationId: String) =
        dao.deleteConversation(conversationId)

    private fun toDomain(e: MessageEntity) = Message(
        id = e.id,
        conversationId = e.conversationId,
        senderId = e.senderId,
        ciphertext = e.ciphertext,
        timestamp = e.timestamp,
        status = e.status,
    )

    private fun Message.toEntity() = MessageEntity(
        id = id,
        conversationId = conversationId,
        senderId = senderId,
        ciphertext = ciphertext,
        timestamp = timestamp,
        status = status,
    )
}

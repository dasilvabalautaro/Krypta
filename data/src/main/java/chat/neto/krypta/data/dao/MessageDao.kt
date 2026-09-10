package chat.neto.krypta.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import chat.neto.krypta.core.model.MessageStatus
import chat.neto.krypta.data.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/** Recuento de mensajes entrantes sin ver de una conversación (badge de no leídos). */
data class UnreadCountRow(val conversationId: String, val count: Int)

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeConversation(conversationId: String): Flow<List<MessageEntity>>

    // GROUP BY + MAX(timestamp): SQLite garantiza que las columnas sueltas salen de la
    // fila que da el máximo → el mensaje más reciente de cada conversación.
    @Query(
        "SELECT id, conversationId, senderId, payload, encrypted, MAX(timestamp) AS timestamp, status " +
            "FROM messages GROUP BY conversationId"
    )
    fun observeLastMessages(): Flow<List<MessageEntity>>

    // Entrante ⇔ senderId = conversationId (los salientes llevan senderId = "self").
    @Query(
        "SELECT conversationId, COUNT(*) AS count FROM messages " +
            "WHERE senderId = conversationId AND status = :status GROUP BY conversationId"
    )
    fun observeUnreadCounts(status: MessageStatus): Flow<List<UnreadCountRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    // Conversión del historial: filas que aún guardan ciphertext de la clave estática.
    @Query("SELECT * FROM messages WHERE encrypted = 1 LIMIT :limit OFFSET :offset")
    suspend fun findEncrypted(limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun findById(id: String): MessageEntity?

    // Reconciliación: los FALLIDOS más recientes primero, para reintentarlos al reconectar.
    @Query("SELECT * FROM messages WHERE status = :status ORDER BY timestamp DESC LIMIT :limit")
    suspend fun findByStatus(status: MessageStatus, limit: Int): List<MessageEntity>

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: MessageStatus)

    @Query(
        "UPDATE messages SET status = :read " +
            "WHERE conversationId = :conversationId AND senderId = :conversationId AND status != :read"
    )
    suspend fun markIncomingRead(conversationId: String, read: MessageStatus)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteConversation(conversationId: String)
}

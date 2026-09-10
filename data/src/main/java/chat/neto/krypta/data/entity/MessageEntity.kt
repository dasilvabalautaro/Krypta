package chat.neto.krypta.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import chat.neto.krypta.core.model.MessageStatus

@Entity(
    tableName = "messages",
    // Compuesto (conversationId, timestamp): cubre el WHERE conversationId + ORDER BY timestamp
    // de observeConversation, y también las búsquedas solo por conversationId (prefijo izq.).
    indices = [Index("conversationId", "timestamp")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderId: String,
    val payload: ByteArray,
    /** El [payload] sigue siendo ciphertext de la clave estática (fila anterior a la v8). */
    @ColumnInfo(defaultValue = "1") val encrypted: Boolean,
    val timestamp: Long,
    val status: MessageStatus,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEntity) return false
        return id == other.id &&
            conversationId == other.conversationId &&
            senderId == other.senderId &&
            payload.contentEquals(other.payload) &&
            encrypted == other.encrypted &&
            timestamp == other.timestamp &&
            status == other.status
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + conversationId.hashCode()
        result = 31 * result + senderId.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + encrypted.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + status.hashCode()
        return result
    }
}

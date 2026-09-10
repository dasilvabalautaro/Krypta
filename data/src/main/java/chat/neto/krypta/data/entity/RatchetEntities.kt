package chat.neto.krypta.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Estado del ratchet de una conversación, como blob opaco (lo serializa `:p2p-signaling`).
 * Una fila por contacto; se borra al borrar el contacto, no al vaciar el chat — vaciar no es
 * romper la sesión.
 */
@Entity(tableName = "ratchet_sessions")
data class RatchetSessionEntity(
    @PrimaryKey val conversationId: String,
    val state: ByteArray,
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RatchetSessionEntity) return false
        return conversationId == other.conversationId &&
            state.contentEquals(other.state) &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = conversationId.hashCode()
        result = 31 * result + state.contentHashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

/**
 * Huella de un ciphertext ya procesado, para reconocer las reentregas del buzón **antes** de
 * intentar descifrarlas (la clave de un mensaje se borra al usarla). La huella va en hexadecimal
 * y no como BLOB para que la clave primaria compuesta no dependa de cómo compare cada SQLite los
 * blobs. Se poda: solo interesan las recientes.
 */
@Entity(
    tableName = "ratchet_seen",
    primaryKeys = ["conversationId", "digest"],
    indices = [Index("conversationId", "seenAt")],
)
data class RatchetSeenEntity(
    val conversationId: String,
    val digest: String,
    val seenAt: Long,
)

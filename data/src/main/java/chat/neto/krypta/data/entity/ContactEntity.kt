package chat.neto.krypta.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contacts",
    indices = [Index("peerId")],
)
data class ContactEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val peerId: String,
    val publicKey: ByteArray,
    val sharedSecret: ByteArray?,
    val verified: Boolean = false,
    val blocked: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactEntity) return false
        return id == other.id &&
            displayName == other.displayName &&
            peerId == other.peerId &&
            publicKey.contentEquals(other.publicKey) &&
            (sharedSecret?.contentEquals(other.sharedSecret) ?: (other.sharedSecret == null)) &&
            verified == other.verified &&
            blocked == other.blocked
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + peerId.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + (sharedSecret?.contentHashCode() ?: 0)
        result = 31 * result + verified.hashCode()
        result = 31 * result + blocked.hashCode()
        return result
    }
}

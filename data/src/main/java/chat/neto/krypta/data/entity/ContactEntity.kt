package chat.neto.krypta.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Contacto **tal como se guarda**. Ojo a lo que NO está: el secreto compartido. Se guardaba
 * aquí en claro, y como la clave de cada mensaje sale de él por HKDF, el fichero `krypta.db`
 * bastaba por sí solo para descifrar el historial entero —sin la identidad y sin ejecutar nada
 * dentro de la app—. Como es una función pura de la identidad y del PeerID del contacto
 * (`KeyExchange.sharedSecretWith`, ECDH X25519), no hay ninguna razón para persistirlo: se
 * deriva al vuelo al leer. Así la clave vuelve a depender de la identidad, que desde el
 * 8 sep 2026 vive envuelta en el Android Keystore.
 */
@Entity(
    tableName = "contacts",
    indices = [Index("peerId")],
)
data class ContactEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val peerId: String,
    val publicKey: ByteArray,
    val verified: Boolean = false,
    val blocked: Boolean = false,
    /** Versión de protocolo anunciada por el contacto (0 = desconocida). */
    @ColumnInfo(defaultValue = "0") val peerProtocol: Int = 0,
    /** Versión que ya le anunciamos (0 = nunca), para no repetir el anuncio cada arranque. */
    @ColumnInfo(defaultValue = "0") val announcedProtocol: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactEntity) return false
        return id == other.id &&
            displayName == other.displayName &&
            peerId == other.peerId &&
            publicKey.contentEquals(other.publicKey) &&
            verified == other.verified &&
            blocked == other.blocked &&
            peerProtocol == other.peerProtocol &&
            announcedProtocol == other.announcedProtocol
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + peerId.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + verified.hashCode()
        result = 31 * result + blocked.hashCode()
        result = 31 * result + peerProtocol
        result = 31 * result + announcedProtocol
        return result
    }
}

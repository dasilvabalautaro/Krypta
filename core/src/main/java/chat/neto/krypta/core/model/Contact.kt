package chat.neto.krypta.core.model

/**
 * Contacto. `sharedSecret` se obtiene tras intercambiar claves públicas y es la semilla
 * del rendezvous diario (HKDF(sharedSecret, fecha)) y de la clave de cifrado E2EE; es null
 * mientras no se haya completado el intercambio. `peerId` es la identidad libp2p del
 * contacto (a quién dirigir los streams). `verified` = el usuario cotejó el número de
 * seguridad fuera de banda (anti-MITM). `blocked` = el usuario lo bloqueó: no se anuncia su
 * rendezvous, lo que llegue de él se descarta sin persistir ni avisar, y no se le envía nada.
 */
data class Contact(
    val id: String,
    val displayName: String,
    val peerId: String,
    val publicKey: ByteArray,
    val sharedSecret: ByteArray?,
    val verified: Boolean = false,
    val blocked: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Contact) return false
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

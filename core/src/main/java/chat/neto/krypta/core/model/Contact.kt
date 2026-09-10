package chat.neto.krypta.core.model

/**
 * Contacto. `sharedSecret` es la semilla del rendezvous diario (HKDF(sharedSecret, fecha)) y
 * de la clave de cifrado E2EE; es null mientras no se haya podido derivar. **No se guarda en
 * disco**: se deriva por ECDH de nuestra identidad y del PeerID del contacto cada vez que se
 * lee el contacto, porque persistirlo hacía que la base de datos por sí sola abriera todo el
 * historial. `peerId` es la identidad libp2p del
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
    /**
     * Versión de protocolo que el contacto **ha anunciado** (0 = aún no ha dicho nada, o su
     * cliente es anterior al anuncio). Es lo que permite encender el ratchet contacto a
     * contacto en vez de esperar a que todo el mundo actualice.
     */
    val peerProtocol: Int = 0,
    /** Versión que ya le hemos anunciado nosotros, para no repetirlo en cada arranque. */
    val announcedProtocol: Int = 0,
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
            blocked == other.blocked &&
            peerProtocol == other.peerProtocol &&
            announcedProtocol == other.announcedProtocol
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + peerId.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + (sharedSecret?.contentHashCode() ?: 0)
        result = 31 * result + verified.hashCode()
        result = 31 * result + blocked.hashCode()
        result = 31 * result + peerProtocol
        result = 31 * result + announcedProtocol
        return result
    }
}

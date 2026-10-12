package chat.neto.krypta.core.repository

import chat.neto.krypta.core.model.Contact
import kotlinx.coroutines.flow.Flow

interface ContactRepository {
    fun observeAll(): Flow<List<Contact>>

    /**
     * Da de alta o sustituye el contacto, con una excepción: **`peerProtocol` nunca baja**.
     *
     * Lo que un contacto anunció que sabe hacer no se olvida porque alguien guarde una copia
     * del contacto leída antes de que llegara su anuncio (verificar, bloquear, renombrar,
     * importar un `.krbk`, el propio anuncio de capacidades del ciclo WAN). Antes sí: el
     * contacto anuncia **una sola vez por versión**, así que esa copia vieja lo devolvía a v1
     * **para siempre** — sin ratchet, sin relleno, sin depósito ciego y sin clave de llamada
     * negociada, y sin que nada lo avisara. Es el hallazgo H-2 de
     * `docs/REVISION-protocolo-2026-09-14.md`; el H-3 (un anuncio forjado con una versión menor)
     * se cierra con la misma regla. Lo único que lo devuelve a 0 es borrar el contacto.
     */
    suspend fun upsert(contact: Contact)

    suspend fun findById(id: String): Contact?

    /** Resuelve un contacto por su PeerID libp2p (para mapear mensajes entrantes). */
    suspend fun findByPeerId(peerId: String): Contact?

    /** Elimina el contacto (sus mensajes se borran aparte, vía [MessageRepository]). */
    suspend fun delete(id: String)

    /**
     * Sube la versión anunciada por el contacto [id] a [protocol] si es mayor que la apuntada;
     * nunca la baja y no toca ningún otro campo.
     *
     * La implementación por defecto (leer y reescribir) sirve a los dobles de test. La de Room
     * lo hace en **una sola sentencia**, sin ventana entre leer y escribir: el anuncio llega por
     * el hilo del buzón mientras la UI puede estar guardando el mismo contacto.
     */
    suspend fun raisePeerProtocol(id: String, protocol: Int) {
        val current = findById(id) ?: return
        if (protocol > current.peerProtocol) upsert(current.copy(peerProtocol = protocol))
    }

    // Escrituras de **un solo campo** (O-3 de docs/AUDITORIA-2026-10-12.md). [upsert] reescribe
    // la fila entera tal como venga la copia, y una copia leída antes de un cambio lo deshacía:
    // el anuncio de capacidades del ciclo WAN guardaba su instantánea después de enviar, y si en
    // esos segundos el usuario verificaba o bloqueaba al contacto, la verificación o el bloqueo
    // se perdían sin aviso. Cada operación toca ahora solo lo suyo. Sin cuerpo por defecto a
    // propósito: un "leer y reescribir" aquí reintroduciría la misma ventana.

    /** Marca (o desmarca) el contacto como verificado. No toca nada más. */
    suspend fun setVerified(id: String, verified: Boolean)

    /** Bloquea (o desbloquea) el contacto. No toca nada más. */
    suspend fun setBlocked(id: String, blocked: Boolean)

    /** Apunta que ya le anunciamos [protocol]; como `peerProtocol`, nunca baja. */
    suspend fun markAnnounced(id: String, protocol: Int)
}

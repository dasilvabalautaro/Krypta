package chat.neto.krypta.data.repository

import chat.neto.krypta.core.KeyExchange
import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.core.repository.ContactRepository
import chat.neto.krypta.data.dao.ContactDao
import chat.neto.krypta.data.entity.ContactEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Contactos en Room. El **secreto compartido no se persiste**: se deriva aquí, al mapear a
 * dominio, con [KeyExchange] (ECDH X25519 entre nuestra identidad y el PeerID del contacto).
 * Es determinista, así que el valor es el mismo de siempre; lo que cambia es que ya no está
 * escrito en el disco, donde bastaba para descifrar todas las conversaciones.
 */
class RoomContactRepository @Inject constructor(
    private val dao: ContactDao,
    private val keyExchange: KeyExchange,
) : ContactRepository {

    override fun observeAll(): Flow<List<Contact>> =
        dao.observeAll().map { list -> list.map(::toDomain) }

    override suspend fun upsert(contact: Contact) = dao.upsert(contact.toEntity())

    override suspend fun findById(id: String): Contact? = dao.findById(id)?.let(::toDomain)

    override suspend fun findByPeerId(peerId: String): Contact? =
        dao.findByPeerId(peerId)?.let(::toDomain)

    override suspend fun delete(id: String) = dao.delete(id)

    private fun toDomain(e: ContactEntity) = Contact(
        id = e.id,
        displayName = e.displayName,
        peerId = e.peerId,
        publicKey = e.publicKey,
        // Derivado, no leído: si fallara (PeerID ilegible, identidad no disponible) queda a
        // null, que es el caso que el dominio ya trata como "sin intercambio de claves".
        sharedSecret = runCatching { keyExchange.sharedSecretWith(e.peerId) }.getOrNull(),
        verified = e.verified,
        blocked = e.blocked,
        peerProtocol = e.peerProtocol,
        announcedProtocol = e.announcedProtocol,
    )

    private fun Contact.toEntity() = ContactEntity(
        id = id,
        displayName = displayName,
        peerId = peerId,
        publicKey = publicKey,
        verified = verified,
        blocked = blocked,
        peerProtocol = peerProtocol,
        announcedProtocol = announcedProtocol,
    )
}

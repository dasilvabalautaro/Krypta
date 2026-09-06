package chat.neto.krypta.data.repository

import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.core.repository.ContactRepository
import chat.neto.krypta.data.dao.ContactDao
import chat.neto.krypta.data.entity.ContactEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class RoomContactRepository @Inject constructor(
    private val dao: ContactDao,
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
        sharedSecret = e.sharedSecret,
        verified = e.verified,
        blocked = e.blocked,
    )

    private fun Contact.toEntity() = ContactEntity(
        id = id,
        displayName = displayName,
        peerId = peerId,
        publicKey = publicKey,
        sharedSecret = sharedSecret,
        verified = verified,
        blocked = blocked,
    )
}

package chat.neto.krypta.data.repository

import chat.neto.krypta.core.KeyExchange
import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.data.dao.ContactDao
import chat.neto.krypta.data.entity.ContactEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El contrato del secreto compartido tras sacarlo del disco: se **deriva** al leer y **no** se
 * escribe nunca. Es lo que impide que copiar `krypta.db` baste para descifrar el historial.
 */
class RoomContactRepositoryTest {

    private class FakeDao : ContactDao {
        val store = linkedMapOf<String, ContactEntity>()
        override fun observeAll(): Flow<List<ContactEntity>> = flowOf(store.values.toList())
        override suspend fun upsert(contact: ContactEntity) { store[contact.id] = contact }
        override suspend fun findById(id: String) = store[id]
        override suspend fun findByPeerId(peerId: String) = store.values.find { it.peerId == peerId }
        override suspend fun delete(id: String) { store.remove(id) }
    }

    /** Determinista y distinto por PeerID, como el ECDH real. */
    private class FakeKeyExchange(val failFor: String? = null) : KeyExchange {
        var calls = 0
        override fun localPeerId() = "12D3KooWSelf"
        override fun sharedSecretWith(peerId: String): ByteArray {
            calls++
            if (peerId == failFor) error("PeerID ilegible")
            return ByteArray(32) { (peerId.hashCode() + it).toByte() }
        }
    }

    private val contact = Contact(
        id = "12D3KooWBob",
        displayName = "Bob",
        peerId = "12D3KooWBob",
        publicKey = ByteArray(0),
        sharedSecret = ByteArray(32) { 1 },
        verified = true,
    )

    @Test
    fun `guardar un contacto no escribe el secreto compartido`() = runTest {
        val dao = FakeDao()
        val repo = RoomContactRepository(dao, FakeKeyExchange())

        repo.upsert(contact)

        // La entidad persistida ya no tiene dónde guardarlo: lo que queda en disco es solo
        // identidad pública y estado del contacto.
        val stored = dao.store.getValue(contact.id)
        assertEquals("Bob", stored.displayName)
        assertTrue(stored.verified)
        val campos = ContactEntity::class.java.declaredFields.map { it.name }
        assertTrue("la entidad no debe tener columna de secreto: $campos", "sharedSecret" !in campos)
    }

    @Test
    fun `leer un contacto deriva el secreto en vez de leerlo`() = runTest {
        val dao = FakeDao()
        val keys = FakeKeyExchange()
        val repo = RoomContactRepository(dao, keys)
        repo.upsert(contact)

        val leido = repo.findById(contact.id)!!

        assertArrayEquals(keys.sharedSecretWith(contact.peerId), leido.sharedSecret)
        assertTrue("debe haberse derivado", keys.calls > 0)
    }

    @Test
    fun `si la derivacion falla el contacto queda sin secreto, no revienta`() = runTest {
        val dao = FakeDao()
        val repo = RoomContactRepository(dao, FakeKeyExchange(failFor = contact.peerId))
        repo.upsert(contact)

        // null es el caso que el dominio ya trata como "sin intercambio de claves": no se le
        // envía nada y se cae del rendezvous, en vez de tumbar la lista de conversaciones.
        assertNull(repo.findById(contact.id)!!.sharedSecret)
    }

    @Test
    fun `el secreto derivado es el mismo por todas las vias de lectura`() = runTest {
        val dao = FakeDao()
        val repo = RoomContactRepository(dao, FakeKeyExchange())
        repo.upsert(contact)

        val porId = repo.findById(contact.id)!!.sharedSecret
        val porPeerId = repo.findByPeerId(contact.peerId)!!.sharedSecret
        val enLista = repo.observeAll().let { flow ->
            var out: ByteArray? = null
            flow.collect { out = it.single().sharedSecret }
            out
        }

        assertArrayEquals(porId, porPeerId)
        assertArrayEquals(porId, enLista)
    }
}

package chat.neto.krypta.p2p

import chat.neto.krypta.core.KeyExchange
import chat.neto.krypta.core.model.Contact
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RatchetSessions]: la persistencia del ratchet y, sobre todo, **su atomicidad**. Lo que se
 * prueba aquí no es la criptografía (eso es `RatchetTest`) sino que nunca pueda quedar guardado
 * el avance del ratchet sin el mensaje que lo provocó — porque entonces la reentrega del buzón
 * sería indescifrable y el mensaje se perdería para siempre.
 */
class RatchetSessionsTest {

    private val alice = "12D3KooWAaaa"
    private val bob = "12D3KooWBbbb"
    private val secret = ByteArray(32) { (it * 3 + 5).toByte() }

    /** Un extremo con su propio almacén, para simular dos móviles en el mismo test. */
    private class Endpoint(me: String, peer: String, secret: ByteArray) {
        val store = FakeRatchetStore()
        val sessions = testSessions(
            keyExchange = object : KeyExchange {
                override fun localPeerId() = me
                override fun sharedSecretWith(peerId: String) = secret
            },
            store = store,
        )
        val contact = Contact(
            id = peer, displayName = peer, peerId = peer,
            publicKey = ByteArray(0), sharedSecret = secret,
        )
    }

    private fun endpoints() = Endpoint(alice, bob, secret) to Endpoint(bob, alice, secret)

    @Test
    fun `el estado sobrevive entre llamadas y la conversacion avanza`() = runTest {
        val (a, b) = endpoints()
        val entregados = mutableListOf<String>()

        repeat(3) { i ->
            val wire = a.sessions.send(a.contact, "hola $i".toByteArray()) { it }
            b.sessions.receive(b.contact, wire) { entregados += String(it) }
            val vuelta = b.sessions.send(b.contact, "eco $i".toByteArray()) { it }
            a.sessions.receive(a.contact, vuelta) { entregados += String(it) }
        }

        assertEquals(listOf("hola 0", "eco 0", "hola 1", "eco 1", "hola 2", "eco 2"), entregados)
        // Y el estado guardado ha ido avanzando de época, no repitiéndose.
        val estado = RatchetState.decode(b.store.sessions.getValue(b.contact.id))
        assertTrue("debería haber salido de la época 0", estado.epoch > 0)
    }

    @Test
    fun `una reentrega del buzon se reconoce en vez de parecer basura`() = runTest {
        val (a, b) = endpoints()
        val wire = a.sessions.send(a.contact, "una vez".toByteArray()) { it }

        val primera = b.sessions.receive(b.contact, wire) { String(it) }
        assertEquals(RatchetSessions.Received.Opened("una vez"), primera)

        var reentregado = false
        val segunda = b.sessions.receive(b.contact, wire) { reentregado = true }
        assertEquals(RatchetSessions.Received.Duplicate, segunda)
        assertTrue("el mensaje duplicado no debe volver a persistirse", !reentregado)
    }

    @Test
    fun `si el guardado del mensaje falla, el ratchet no avanza y el mensaje se puede reintentar`() = runTest {
        val (a, b) = endpoints()
        val wire = a.sessions.send(a.contact, "importante".toByteArray()) { it }

        // Primer intento: el proceso se cae al persistir (disco lleno, muerte del proceso…).
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                b.sessions.receive(b.contact, wire) { error("el proceso se muere aquí") }
            }
        }
        // Nada quedó escrito: ni el estado ni la huella del visto.
        assertNull(b.store.sessions[b.contact.id])
        assertTrue(b.store.seen.isEmpty())

        // Y por eso la reentrega del buzón sigue siendo legible. Ese es todo el punto.
        val segunda = b.sessions.receive(b.contact, wire) { String(it) }
        assertEquals(RatchetSessions.Received.Opened("importante"), segunda)
    }

    @Test
    fun `un estado ilegible reengancha la conversacion en vez de romperla`() = runTest {
        val (a, b) = endpoints()
        val wire = a.sessions.send(a.contact, "antes".toByteArray()) { it }
        b.sessions.receive(b.contact, wire) { String(it) }
        val anterior = b.store.sessions.getValue(b.contact.id)

        // Un blob corrupto (o de una versión futura del formato) no puede dejar mudo al contacto.
        b.store.sessions[b.contact.id] = byteArrayOf(0x7F, 0x00, 0x01)
        val despues = b.sessions.send(b.contact, "sigo aquí".toByteArray()) { it }
        assertEquals(
            RatchetSessions.Received.Opened("sigo aquí"),
            a.sessions.receive(a.contact, despues) { String(it) },
        )
        assertNotEquals(anterior.toList(), b.store.sessions.getValue(b.contact.id).toList())
    }

    @Test
    fun `olvidar la sesion borra estado y huellas`() = runTest {
        val (a, b) = endpoints()
        val wire = a.sessions.send(a.contact, "hola".toByteArray()) { it }
        b.sessions.receive(b.contact, wire) { String(it) }
        assertTrue(b.store.sessions.isNotEmpty() && b.store.seen.isNotEmpty())

        b.sessions.forget(b.contact)
        assertTrue(b.store.sessions.isEmpty() && b.store.seen.isEmpty())
    }

}

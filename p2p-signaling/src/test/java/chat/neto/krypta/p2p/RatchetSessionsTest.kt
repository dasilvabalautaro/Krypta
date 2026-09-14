package chat.neto.krypta.p2p

import chat.neto.krypta.core.KeyExchange
import chat.neto.krypta.core.model.Contact
import kotlinx.coroutines.launch
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

    /**
     * **H-0** de `docs/REVISION-protocolo-2026-09-14.md`. Cargar el estado, cifrar y guardar el
     * avanzado son tres pasos, y **leer el estado suspende** (en Room es una consulta). Si dos
     * operaciones sobre la misma conversación se cruzan en esa ventana —el acuse de lectura al
     * abrir un chat mientras el buzón entrega, dos trozos de archivo, un reengache lanzado—, las
     * dos parten del mismo estado y cifran con **la misma clave de mensaje y el mismo nonce de
     * AES-GCM**, que es la forma clásica de romper del todo un cifrado autenticado.
     */
    @Test
    fun `envios simultaneos a la misma conversacion no repiten clave de mensaje`() = runTest {
        val interno = FakeRatchetStore()
        val lento = object : chat.neto.krypta.core.RatchetStore by interno {
            override suspend fun load(conversationId: String): ByteArray? {
                // Como Room: la consulta ya ha leído cuando la corrutina se reanuda, así que la
                // ventana está entre leer y guardar, que es donde se cuela la otra operación.
                val leido = interno.load(conversationId)
                kotlinx.coroutines.yield()
                return leido
            }
        }
        val sessions = RatchetSessions(
            ratchet = Ratchet(JdkCurve25519()),
            store = lento,
            transactions = RollbackTransactionRunner(interno),
            keyExchange = object : KeyExchange {
                override fun localPeerId() = alice
                override fun sharedSecretWith(peerId: String) = secret
            },
        )
        val contact = Contact(id = bob, displayName = bob, peerId = bob, publicKey = ByteArray(0), sharedSecret = secret)
        // Sesión ya existente: lo que se prueba es el cerrojo, no el arranque.
        sessions.send(contact, "primero".toByteArray()) { it }

        val wires = mutableListOf<ByteArray>()
        kotlinx.coroutines.coroutineScope {
            repeat(8) { i ->
                launch { wires += sessions.send(contact, "simultáneo $i".toByteArray()) { it } }
            }
        }

        val ternas = wires.map { w -> Ratchet.Header.decode(w)!!.let { Triple(it.lineage, it.epoch, it.n) } }
        assertEquals(
            "cada mensaje necesita su propia clave y su propio nonce; salieron: $ternas",
            ternas.size,
            ternas.toSet().size,
        )
    }

    /**
     * La otra cara del mismo cerrojo: una **recepción** que se cruza con un envío puede guardar
     * su estado encima del del envío y **devolver el contador de envío hacia atrás**, con lo que
     * el mensaje siguiente repetiría la clave del que acaba de salir.
     */
    @Test
    fun `una recepcion que se cruza con un envio no hace retroceder el contador`() = runTest {
        val (a, b) = endpoints()
        // B necesita algo que recibir por una cadena ya retirada (no avanza de época al abrirlo):
        // A manda dos, B abre el segundo y avanza; el primero llega tarde, cruzado con un envío.
        val tarde = a.sessions.send(a.contact, "uno".toByteArray()) { it }
        val pronto = a.sessions.send(a.contact, "dos".toByteArray()) { it }
        b.sessions.receive(b.contact, pronto) { }

        val lentoB = object : chat.neto.krypta.core.RatchetStore by b.store {
            override suspend fun load(conversationId: String): ByteArray? {
                val leido = b.store.load(conversationId)
                kotlinx.coroutines.yield()
                return leido
            }
        }
        val sesionesB = RatchetSessions(
            ratchet = Ratchet(JdkCurve25519()),
            store = lentoB,
            transactions = RollbackTransactionRunner(b.store),
            keyExchange = object : KeyExchange {
                override fun localPeerId() = bob
                override fun sharedSecretWith(peerId: String) = secret
            },
        )

        val enviados = mutableListOf<ByteArray>()
        kotlinx.coroutines.coroutineScope {
            launch { enviados += sesionesB.send(b.contact, "cruzado".toByteArray()) { it } }
            launch { sesionesB.receive(b.contact, tarde) { } }
        }
        enviados += sesionesB.send(b.contact, "siguiente".toByteArray()) { it }

        val ternas = enviados.map { w -> Ratchet.Header.decode(w)!!.let { Triple(it.lineage, it.epoch, it.n) } }
        assertEquals("salieron: $ternas", ternas.size, ternas.toSet().size)
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

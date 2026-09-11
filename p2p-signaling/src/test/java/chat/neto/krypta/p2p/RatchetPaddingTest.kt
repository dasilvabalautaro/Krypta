package chat.neto.krypta.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El relleno por tramos **atravesando el ratchet** ([Padding] + `Ratchet.FLAG_PADDED`).
 * [PaddingTest] cubre la pieza suelta; aquí importa lo que solo se ve montado:
 *
 * - que el bit de la cabecera viaje y se respete en **los dos** caminos de descifrado (el
 *   normal y el de una época ya retirada, que es código aparte);
 * - que rellenar sea **por mensaje** y no por sesión, porque durante la transición un mismo
 *   contacto puede recibir mensajes de las dos clases;
 * - que nadie pueda **tocar el bit** por el camino.
 */
class RatchetPaddingTest {

    private val ratchet = Ratchet(JdkCurve25519())
    private val secret = ByteArray(32) { (it * 7 + 1).toByte() }
    private val alice = "12D3KooWAaaa"
    private val bob = "12D3KooWBbbb"

    private fun sessions(): Pair<RatchetState, RatchetState> =
        ratchet.initial(secret, alice, bob, 1_000L) to ratchet.initial(secret, bob, alice, 1_000L)

    @Test
    fun `un mensaje relleno va y vuelve, y por la red mide un tramo`() {
        var (a, b) = sessions()
        val texto = "hola"

        val sealed = ratchet.encrypt(a, texto.toByteArray(), pad = true)
        a = sealed.state

        // Lo que ve el nodo: cabecera + tag + un tramo de 160, nada del tamaño real.
        assertEquals(Ratchet.HEADER_BYTES + 16 + 160, sealed.ciphertext.size)
        assertTrue(Ratchet.looksLikeRatchet(sealed.ciphertext))

        val opened = ratchet.decrypt(b, secret, sealed.ciphertext)
        assertEquals(texto, String(opened.plaintext))
    }

    /**
     * Dos mensajes de tamaños bien distintos tienen que salir **iguales de tamaño** por la red.
     * Esta es la propiedad que se compra con todo lo demás.
     */
    @Test
    fun `un acuse de lectura y un texto corto son indistinguibles por tamano`() {
        val (a, _) = sessions()
        val acuse = MessageEnvelope.encodeRead(listOf("11111111-2222-3333-4444-555555555555"))
        val texto = MessageEnvelope.encodeText("11111111-2222-3333-4444-555555555555", "vale".toByteArray())

        assertTrue("el test no probaría nada si ya midieran igual", acuse.size != texto.size)
        assertEquals(
            ratchet.encrypt(a, acuse, pad = true).ciphertext.size,
            ratchet.encrypt(a, texto, pad = true).ciphertext.size,
        )
    }

    /**
     * Durante la transición un contacto puede mandar unos mensajes rellenos y otros no (p. ej.
     * si se actualiza a mitad de conversación). El bit es por mensaje, así que los dos se abren
     * en la misma sesión sin que haya que saber de antemano cuál es cuál.
     */
    @Test
    fun `relleno y sin relleno se mezclan en la misma sesion`() {
        var (a, b) = sessions()

        val conRelleno = ratchet.encrypt(a, "relleno".toByteArray(), pad = true)
        a = conRelleno.state
        val sinRelleno = ratchet.encrypt(a, "pelado".toByteArray())
        a = sinRelleno.state

        val uno = ratchet.decrypt(b, secret, conRelleno.ciphertext)
        b = uno.state
        val dos = ratchet.decrypt(b, secret, sinRelleno.ciphertext)

        assertEquals("relleno", String(uno.plaintext))
        assertEquals("pelado", String(dos.plaintext))
    }

    /**
     * Un mensaje relleno que llega **tarde**, cuando su época ya está retirada, se abre por
     * `openOld` — que es un camino distinto del normal. Sin quitar el relleno también ahí, el
     * mensaje llegaría con basura pegada justo en el caso que más cuesta reproducir: la
     * reentrega del buzón después de una conversación.
     */
    @Test
    fun `un mensaje relleno de una epoca retirada tambien se abre limpio`() {
        var (a, b) = sessions()

        // Sale en la época 0 y se queda en vuelo (el buzón lo reentregará más tarde).
        val enVuelo = ratchet.encrypt(a, "el que se quedó atrás".toByteArray(), pad = true)
        a = enVuelo.state

        // Mientras, la conversación sigue y las épocas avanzan.
        repeat(2) {
            val ida = ratchet.encrypt(a, "ida".toByteArray(), pad = true)
            a = ida.state
            b = ratchet.decrypt(b, secret, ida.ciphertext).state
            val vuelta = ratchet.encrypt(b, "vuelta".toByteArray(), pad = true)
            b = vuelta.state
            a = ratchet.decrypt(a, secret, vuelta.ciphertext).state
        }
        assertTrue("la prueba necesita que las épocas hayan avanzado", b.epoch > 0)

        val tarde = ratchet.decrypt(b, secret, enVuelo.ciphertext)
        assertEquals("el que se quedó atrás", String(tarde.plaintext))
    }

    /**
     * El bit va en la cabecera, que es el AAD: **tocarlo rompe el AEAD**. Eso es lo que impide
     * las dos manipulaciones que tendrían sentido — apagarlo para que se entregue el relleno
     * como contenido, o encenderlo para que se coma el final de un mensaje que no lo lleva.
     */
    @Test
    fun `tocar el bit de relleno rompe la autenticacion`() {
        val (a, b) = sessions()

        val relleno = ratchet.encrypt(a, "con relleno".toByteArray(), pad = true)
        val apagado = relleno.ciphertext.copyOf().also { it[1] = 0 }
        assertThrows(RatchetException::class.java) { ratchet.decrypt(b, secret, apagado) }

        val pelado = ratchet.encrypt(a, "sin relleno".toByteArray())
        val encendido = pelado.ciphertext.copyOf().also { it[1] = Ratchet.FLAG_PADDED.toByte() }
        assertThrows(RatchetException::class.java) { ratchet.decrypt(b, secret, encendido) }
    }

    /** Un trozo de archivo relleno sigue abriéndose byte a byte igual que salió. */
    @Test
    fun `un trozo de archivo relleno vuelve identico`() {
        var (a, b) = sessions()
        val trozo = ByteArray(ChatService.CHUNK_SIZE) { (it % 251).toByte() }
        val sobre = MessageEnvelope.encodeFileChunk("f-1", 7, trozo)

        val sealed = ratchet.encrypt(a, sobre, pad = true)
        a = sealed.state
        val opened = ratchet.decrypt(b, secret, sealed.ciphertext)

        assertArrayEquals(sobre, opened.plaintext)
        val decoded = MessageEnvelope.decode(opened.plaintext) as MessageEnvelope.Decoded.FileChunk
        assertArrayEquals(trozo, decoded.bytes)
    }
}

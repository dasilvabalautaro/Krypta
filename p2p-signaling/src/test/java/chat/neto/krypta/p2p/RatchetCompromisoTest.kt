package chat.neto.krypta.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Lo que puede hacer quien se lleva el **estado** del ratchet de un móvil, reconstruido desde la
 * especificación (§5.2) y no desde el código: así es como lo haría un atacante, y así el test no
 * hereda un error del propio `Ratchet`.
 *
 * Los tests «en v3» **fijan debilidades que siguen existiendo** por el camino v3 (W-15 y W-16), que
 * es el que se sigue usando con los contactos que no han anunciado la v4. Sus parejas «con v4»
 * comprueban que el protocolo v4 las cierra ([docs/DISENO-ratchet.md] §1.12). Que esas parejas
 * prueban algo se comprobó rompiendo el arreglo a propósito: con las cadenas v4 derivadas otra vez
 * de la raíz, el test de W-15 con v4 falla.
 */
class RatchetCompromisoTest {

    private val ratchet = Ratchet(JdkCurve25519())
    private val secret = ByteArray(32) { (it * 7 + 1).toByte() }
    private val alice = "12D3KooWAaaa"
    private val bob = "12D3KooWBbbb"

    /**
     * La raíz de la época vigente se guarda, y las dos cadenas de esa época salen de ella desde
     * N = 0. Quien tenga el estado de A después de enviar puede volver a derivar la cadena de envío
     * desde el principio y abrir lo que A ya mandó **en esa época**, aunque la clave del mensaje
     * se haya descartado. El doble ratchet de Signal no permite esto: su raíz y su cadena salen del
     * KDF de raíz como salidas independientes.
     *
     * Como la época avanza por mensaje recibido, lo expuesto es lo que un móvil envió desde el
     * último mensaje que recibió: poco en una conversación, y una ráfaga entera (un archivo por
     * trozos, varios textos seguidos sin respuesta) si no la hay.
     */
    @Test
    fun `en v3 con el estado guardado se reabre lo ya enviado en la epoca vigente`() {
        var a = ratchet.initial(secret, alice, bob, 1_000L)
        var b = ratchet.initial(secret, bob, alice, 1_000L)
        // Una ida y vuelta lleva a A a una época ≥ 1, fuera de la época 0 que ya es derivable de S.
        val m1 = ratchet.encrypt(a, "uno".toByteArray()); a = m1.state
        b = ratchet.decrypt(b, secret, m1.ciphertext).state
        val m2 = ratchet.encrypt(b, "dos".toByteArray()); b = m2.state
        a = ratchet.decrypt(a, secret, m2.ciphertext).state
        assertTrue("la prueba tiene que estar fuera de la época 0", a.epoch >= 1)

        // A escribe dos veces sin recibir nada: los dos mensajes quedan en la misma época.
        val secretos = listOf("primero sin respuesta", "segundo sin respuesta")
        val grabados = secretos.map { texto ->
            ratchet.encrypt(a, texto.toByteArray()).also { a = it.state }.ciphertext
        }
        grabados.forEach { b = ratchet.decrypt(b, secret, it).state }

        // Compromiso: el estado de A tal como queda en disco tras enviar. Ni S, ni privadas, ni
        // claves saltadas: solo la raíz, la época y la dirección.
        val robado = RatchetState.decode(a.encode())
        var cadena = hkdf(robado.rootKey, "krypta-rtc-chain:${robado.epoch}:${robado.sendDir}")

        grabados.forEachIndexed { i, wire ->
            assertEquals(secretos[i], String(abrir(hmac(cadena, 0x01), wire)))
            cadena = hmac(cadena, 0x02)
        }
    }

    /**
     * La época 0 depende solo de `S`, el linaje y la dirección. Dos sesiones creadas con el mismo
     * linaje cifran su primer mensaje con la **misma clave y el mismo nonce**, aunque cada una
     * lleve propuestas efímeras nuevas (van en la cabecera, que es AAD, y no entran en la clave).
     * Con AES-GCM eso entrega el XOR de los dos textos y rompe la autenticidad.
     *
     * La precondición es repetir un linaje que la identidad ya usó, al milisegundo: un reloj que
     * vuelve exactamente atrás, o restaurar o clonar un estado. No se ha encontrado cómo forzarlo
     * desde la red. Es lo que condiciona P3 en la especificación.
     */
    @Test
    fun `en v3 repetir un linaje repite clave y nonce en la epoca 0`() {
        val antes = ratchet.initial(secret, alice, bob, 5_000L)
        val despues = ratchet.initial(secret, alice, bob, 5_000L)
        val t1 = "mensaje antes de perder el estado".toByteArray()
        val t2 = "mensaje despues de perder el estad".toByteArray()

        val c1 = ratchet.encrypt(antes, t1).ciphertext.drop(Ratchet.HEADER_BYTES)
        val c2 = ratchet.encrypt(despues, t2).ciphertext.drop(Ratchet.HEADER_BYTES)

        val xorCifrados = ByteArray(t1.size) { (c1[it].toInt() xor c2[it].toInt()).toByte() }
        val xorTextos = ByteArray(t1.size) { (t1[it].toInt() xor t2[it].toInt()).toByte() }
        assertArrayEquals(xorTextos, xorCifrados)
    }

    /**
     * W-15 cerrada: con v4, la raíz guardada ya no lleva a las cadenas. Se intentan las dos
     * derivaciones que un atacante probaría con el estado robado —la v3 desde la raíz y el KDF v4
     * con la raíz como material— y ninguna abre lo ya enviado. Lo que queda en el estado es la
     * cadena **ya avanzada**, y HMAC no se deshace.
     */
    @Test
    fun `con v4 el estado guardado ya no reabre lo enviado en la epoca vigente`() {
        var a = ratchet.initial(secret, alice, bob, 1_000L, v4 = true)
        var b = ratchet.initial(secret, bob, alice, 1_000L, v4 = true)
        val m1 = ratchet.encrypt(a, "uno".toByteArray(), v4 = true); a = m1.state
        b = ratchet.decrypt(b, secret, m1.ciphertext, v4 = true).state
        val m2 = ratchet.encrypt(b, "dos".toByteArray(), v4 = true); b = m2.state
        a = ratchet.decrypt(a, secret, m2.ciphertext, v4 = true).state
        assertTrue(a.epoch >= 1)
        assertTrue("la época de A tiene que ser v4", a.epochV4)

        val grabado = ratchet.encrypt(a, "sin respuesta".toByteArray(), v4 = true).also { a = it.state }.ciphertext
        assertEquals("sin respuesta", String(ratchet.decrypt(b, secret, grabado, v4 = true).plaintext))

        val robado = RatchetState.decode(a.encode())
        val candidatas = listOf(
            hkdf(robado.rootKey, "krypta-rtc-chain:${robado.epoch}:${robado.sendDir}"),
            hkdf(robado.rootKey, "krypta-rtc-root4:${robado.epoch}", 96).copyOfRange(32 * (1 + robado.sendDir), 32 * (2 + robado.sendDir)),
        )
        for (cadena in candidatas) {
            assertTrue(
                "una derivación desde la raíz no puede abrir lo ya enviado",
                runCatching { abrir(hmac(cadena, 0x01), grabado) }.isFailure,
            )
        }
    }

    /**
     * W-16 cerrada: con v4, dos sesiones con el mismo linaje atan su cadena de la época 0 a su
     * propuesta, que es nueva en cada una, así que ya no comparten flujo. Y el receptor abre las dos:
     * la segunda como «otra sesión» del mismo linaje, sin tocar su estado.
     */
    @Test
    fun `con v4 repetir un linaje ya no repite clave y el receptor abre las dos`() {
        val antes = ratchet.initial(secret, alice, bob, 5_000L, v4 = true)
        val despues = ratchet.initial(secret, alice, bob, 5_000L, v4 = true)
        val t1 = "mensaje antes de perder el estado".toByteArray()
        val t2 = "mensaje despues de perder el estad".toByteArray()
        val w1 = ratchet.encrypt(antes, t1, v4 = true).ciphertext
        val w2 = ratchet.encrypt(despues, t2, v4 = true).ciphertext

        val c1 = w1.drop(Ratchet.HEADER_BYTES)
        val c2 = w2.drop(Ratchet.HEADER_BYTES)
        val xorCifrados = ByteArray(t1.size) { (c1[it].toInt() xor c2[it].toInt()).toByte() }
        val xorTextos = ByteArray(t1.size) { (t1[it].toInt() xor t2[it].toInt()).toByte() }
        assertTrue("ya no puede salir el XOR de los textos", !xorCifrados.contentEquals(xorTextos))

        var b = ratchet.initial(secret, bob, alice, 5_000L, v4 = true)
        val r1 = ratchet.decrypt(b, secret, w1, v4 = true); b = r1.state
        assertArrayEquals(t1, r1.plaintext)
        assertArrayEquals("la otra sesión del mismo linaje también se abre", t2, ratchet.decrypt(b, secret, w2, v4 = true).plaintext)
    }

    /**
     * Un cliente que aún no sabe que el otro es v4 (sus propuestas van sin marcar) conversa con uno
     * que sí: la época 0 atada del segundo se abre, y las épocas siguientes se quedan en v3 hasta
     * que las dos propuestas sean v4. Cuando el primero se entera, la conversación pasa sola.
     */
    @Test
    fun `una pareja en la que solo uno sabe del v4 conversa y pasa sola cuando el otro se entera`() {
        var a = ratchet.initial(secret, alice, bob, 1_000L, v4 = true)
        var b = ratchet.initial(secret, bob, alice, 1_000L, v4 = false)
        var bSabe = false
        repeat(6) { ronda ->
            if (ronda == 3) bSabe = true
            val m = ratchet.encrypt(a, "a$ronda".toByteArray(), v4 = true); a = m.state
            val ab = ratchet.decrypt(b, secret, m.ciphertext, v4 = bSabe); b = ab.state
            assertEquals("a$ronda", String(ab.plaintext))
            val r = ratchet.encrypt(b, "b$ronda".toByteArray(), v4 = bSabe); b = r.state
            val ba = ratchet.decrypt(a, secret, r.ciphertext, v4 = true); a = ba.state
            assertEquals("b$ronda", String(ba.plaintext))
            if (ronda < 3) assertTrue("mientras B no sepa, sus épocas son v3", !b.epochV4 || b.epoch == 0)
        }
        assertTrue("al enterarse B, la conversación pasa a v4", a.epochV4 && b.epochV4)
    }

    // --- §5.2 de la especificación, a mano -------------------------------------------------

    private fun hkdf(ikm: ByteArray, info: String, length: Int = 32): ByteArray =
        Hkdf.derive(ikm = ikm, salt = ByteArray(0), info = info.toByteArray(Charsets.UTF_8), length = length)

    private fun hmac(key: ByteArray, seed: Int): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal(byteArrayOf(seed.toByte()))

    private fun abrir(mk: ByteArray, wire: ByteArray): ByteArray {
        val kn = hkdf(mk, "krypta-rtc-msg", 44)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(kn.copyOfRange(0, 32), "AES"), GCMParameterSpec(128, kn.copyOfRange(32, 44)))
            updateAAD(wire.copyOfRange(0, Ratchet.HEADER_BYTES))
        }.doFinal(wire.copyOfRange(Ratchet.HEADER_BYTES, wire.size))
    }

    /**
     * El caso extremo de W-4 (A-03 de la auditoría interna): un linaje forjado a `Long.MAX_VALUE`
     * se adopta, y como la regla es «estrictamente mayor», **ningún** linaje posterior —ni el reloj
     * de nadie— puede superarlo. Exige `S`. Fija la debilidad hasta el rediseño del linaje.
     */
    @Test
    fun `un linaje forjado al maximo se adopta y ningun reloj lo supera`() {
        var b = ratchet.initial(secret, bob, alice, 1_000L)
        val mallory = ratchet.initial(secret, alice, bob, lineage = Long.MAX_VALUE)
        val forjado = ratchet.encrypt(mallory, "hola".toByteArray())
        b = ratchet.decrypt(b, secret, forjado.ciphertext).state
        assertEquals(Long.MAX_VALUE, b.lineage)

        val alicia = ratchet.initial(secret, alice, bob, lineage = System.currentTimeMillis())
        b = ratchet.decrypt(b, secret, ratchet.encrypt(alicia, "soy yo".toByteArray()).ciphertext).state
        assertEquals("el linaje legítimo no se adopta: es menor", Long.MAX_VALUE, b.lineage)
    }
}

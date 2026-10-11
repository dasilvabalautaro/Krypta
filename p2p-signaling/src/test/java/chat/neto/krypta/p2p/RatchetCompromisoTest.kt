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
 * Estos tests **fijan debilidades que hoy existen** (W-15 y la condición de W-6 en la
 * especificación). No describen lo que se quiere, sino lo que hay: cuando se arreglen, tienen que
 * fallar y darse la vuelta, igual que el del linaje forjado en [RatchetTest].
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
    fun `con el estado guardado se reabre lo ya enviado en la epoca vigente`() {
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
    fun `repetir un linaje repite clave y nonce en la epoca 0`() {
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
}

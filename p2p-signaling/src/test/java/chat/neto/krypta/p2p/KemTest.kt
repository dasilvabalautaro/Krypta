package chat.neto.krypta.p2p

import chat.neto.krypta.core.Kem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La primitiva post-cuántica (ML-KEM-768) vista por la interfaz [Kem], con la implementación del
 * JDK. Fase 1 de [docs/DISENO-postcuantico.md]: **no hay nada del protocolo aquí**, solo que la
 * primitiva hace lo que dice y con los formatos del cable.
 *
 * **Necesita un JDK 25** (ML-KEM no existe antes de la 24), y por eso `build.gradle.kts` fija la
 * JVM de los tests de este módulo: Gradle escogía por autodetección un Temurin 21 y esto fallaba
 * con `NoSuchAlgorithmException`, que parece un algoritmo ausente y en realidad era otro JDK.
 *
 * Lo que se prueba no es «encapsular y desencapsular funciona» —eso lo garantiza el JDK— sino
 * las tres cosas que este proyecto puede romper por su cuenta:
 *
 * 1. **Los tamaños del cable**, porque de ellos depende el presupuesto entero del diseño (y que
 *    un trozo de archivo siga cabiendo en el blob del buzón).
 * 2. **La conversión del prefijo SPKI**, que es la verruga de esta implementación: el JDK habla
 *    X.509 y el cable quiere la clave cruda.
 * 3. **El rechazo implícito**, que es la trampa conceptual de ML-KEM y la razón de que el
 *    KDoc de [Kem.decapsulate] avise: un ciphertext manipulado **no da error**, da otro secreto.
 */
class KemTest {

    private val kem = JdkKem()

    @Test
    fun `los tamanos son los del cable`() {
        val pair = kem.generateKeyPair()
        assertEquals("la pública es lo que va en la cabecera", Kem.PUBLIC_KEY_BYTES, pair.publicKey.size)

        val sealed = kem.encapsulate(pair.publicKey)
        assertEquals(Kem.CIPHERTEXT_BYTES, sealed.ciphertext.size)
        assertEquals(Kem.SECRET_BYTES, sealed.sharedSecret.size)

        // El presupuesto del §1 del diseño: 2272 B por ronda. Si esto cambia, cambia el diseño.
        assertEquals(2272, Kem.PUBLIC_KEY_BYTES + Kem.CIPHERTEXT_BYTES)
    }

    @Test
    fun `ida y vuelta - el que encapsula y el que desencapsula sacan el mismo secreto`() {
        val pair = kem.generateKeyPair()
        val sealed = kem.encapsulate(pair.publicKey)
        val recovered = kem.decapsulate(pair.privateKey, sealed.ciphertext)
        assertArrayEquals(sealed.sharedSecret, recovered)
    }

    /**
     * La clave sale **cruda** y vuelve a entrar cruda: es el camino que recorre de verdad, porque
     * al otro lado puede haber la implementación de Go. Si el quitar/poner el prefijo estuviera
     * mal, esto fallaría aquí en vez de en el dispositivo.
     */
    @Test
    fun `una publica cruda vuelve a servir para encapsular`() {
        val pair = kem.generateKeyPair()
        val cruda = pair.publicKey.copyOf() // tal como viajaría
        val sealed = kem.encapsulate(cruda)
        assertArrayEquals(sealed.sharedSecret, kem.decapsulate(pair.privateKey, sealed.ciphertext))
    }

    /**
     * El prefijo SPKI está fijado a mano en [JdkKem.PREFIX] porque el JDK no lo expone. Si un
     * JDK futuro cambiara la codificación, `generateKeyPair` ya lanzaría — pero este test lo dice
     * en voz alta, con el valor medido el 11 sep 2026 y verificado contra Go.
     */
    @Test
    fun `el prefijo SPKI es el medido, 22 bytes`() {
        assertEquals(22, JdkKem.PREFIX.size)
        assertEquals(
            "308204b2300b0609608648016503040402038204a100",
            JdkKem.PREFIX.joinToString("") { "%02x".format(it) },
        )
        // Y que de verdad es el que emite este JDK: si no, generar el par lanza.
        kem.generateKeyPair()
    }

    /**
     * **Rechazo implícito**: un ciphertext manipulado devuelve un secreto *distinto*, no un
     * error. Quien detecta el engaño es el AEAD que use ese secreto. Está aquí para que nadie
     * escriba después un `runCatching` alrededor de `decapsulate` creyendo que así valida algo.
     */
    @Test
    fun `un ciphertext manipulado no da error - da otro secreto`() {
        val pair = kem.generateKeyPair()
        val sealed = kem.encapsulate(pair.publicKey)

        val tocado = sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val otro = kem.decapsulate(pair.privateKey, tocado)

        assertEquals(Kem.SECRET_BYTES, otro.size)
        assertFalse(
            "ML-KEM es de rechazo implícito: el secreto tiene que salir distinto, no igual",
            otro.contentEquals(sealed.sharedSecret),
        )
    }

    @Test
    fun `dos encapsulados contra la misma clave dan secretos distintos`() {
        val pair = kem.generateKeyPair()
        val a = kem.encapsulate(pair.publicKey)
        val b = kem.encapsulate(pair.publicKey)
        assertFalse(a.sharedSecret.contentEquals(b.sharedSecret))
        assertFalse(a.ciphertext.contentEquals(b.ciphertext))
    }

    @Test
    fun `dos pares distintos no se abren entre si`() {
        val uno = kem.generateKeyPair()
        val otro = kem.generateKeyPair()
        val sealed = kem.encapsulate(uno.publicKey)
        assertFalse(
            kem.decapsulate(otro.privateKey, sealed.ciphertext).contentEquals(sealed.sharedSecret),
        )
    }

    /** Longitudes mal: se rechazan en la puerta, no dentro del JDK con un error opaco. */
    @Test
    fun `longitudes invalidas se rechazan`() {
        val pair = kem.generateKeyPair()
        assertThrows(IllegalArgumentException::class.java) { kem.encapsulate(ByteArray(32)) }
        assertThrows(IllegalArgumentException::class.java) {
            kem.encapsulate(pair.publicKey + byteArrayOf(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            kem.decapsulate(pair.privateKey, ByteArray(Kem.CIPHERTEXT_BYTES - 1))
        }
    }

    /** Que la privada sea opaca es parte del contrato: aquí es la expandida del JDK. */
    @Test
    fun `la privada es opaca y solo la entiende quien la creo`() {
        val pair = kem.generateKeyPair()
        assertTrue(
            "el JDK entrega la clave expandida en PKCS#8; Go entrega 64 B de semilla",
            pair.privateKey.size > Kem.PUBLIC_KEY_BYTES,
        )
    }
}

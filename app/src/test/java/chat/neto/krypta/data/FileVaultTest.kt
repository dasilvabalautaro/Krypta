package chat.neto.krypta.data

import chat.neto.krypta.data.crypto.KeyPrefs
import chat.neto.krypta.data.crypto.KeyVault
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileVaultTest {

    /** Envoltura de mentira (XOR): la de verdad es el Keystore, que no existe en la JVM. */
    private class FakeVault(private val mask: Byte = 0x5A) : KeyVault {
        var falla = false
        override fun wrap(plain: ByteArray) = plain.map { (it.toInt() xor mask.toInt()).toByte() }.toByteArray()
        override fun unwrap(wrapped: ByteArray): ByteArray {
            if (falla) error("Keystore no disponible")
            return wrap(wrapped)
        }
    }

    private class FakePrefs : KeyPrefs {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    @Test
    fun `un adjunto va y vuelve, y en disco no se parece al original`() {
        val vault = FileVault(FakePrefs(), FakeVault())
        val original = "esto es una nota de voz".toByteArray()

        val enDisco = vault.seal(original)

        assertFalse("no puede quedar el contenido a la vista", enDisco.contentEquals(original))
        assertTrue(vault.isSealed(enDisco))
        assertArrayEquals(original, vault.open(enDisco))
    }

    @Test
    fun `un adjunto de antes, sin cifrar, se sigue abriendo`() {
        val vault = FileVault(FakePrefs(), FakeVault())
        val viejo = "JPEG de toda la vida".toByteArray()
        assertFalse(vault.isSealed(viejo))
        assertArrayEquals(viejo, vault.open(viejo))
    }

    @Test
    fun `la clave se conserva entre instancias`() {
        val prefs = FakePrefs()
        val enDisco = FileVault(prefs, FakeVault()).seal("hola".toByteArray())
        // Otra instancia (otro arranque de la app) tiene que poder abrirlo.
        assertArrayEquals("hola".toByteArray(), FileVault(prefs, FakeVault()).open(enDisco))
    }

    /**
     * Si la clave guardada no se puede abrir, **no** se genera otra: hacerlo dejaría todos los
     * adjuntos anteriores ilegibles para siempre y sin aviso. Mejor fallar a la vista.
     */
    @Test
    fun `una clave ilegible falla en vez de estrenar otra`() {
        val prefs = FakePrefs()
        val bueno = FileVault(prefs, FakeVault())
        val enDisco = bueno.seal("importante".toByteArray())

        val roto = FakeVault().apply { falla = true }
        assertThrows(IllegalStateException::class.java) { FileVault(prefs, roto).open(enDisco) }
    }

    @Test
    fun `un fichero cifrado manipulado no se abre a medias`() {
        val vault = FileVault(FakePrefs(), FakeVault())
        val enDisco = vault.seal("intacto".toByteArray()).copyOf()
        enDisco[enDisco.size - 1] = (enDisco[enDisco.size - 1] + 1).toByte()
        assertThrows(javax.crypto.AEADBadTagException::class.java) { vault.open(enDisco) }
    }
}

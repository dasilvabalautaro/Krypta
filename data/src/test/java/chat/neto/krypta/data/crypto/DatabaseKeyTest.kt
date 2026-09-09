package chat.neto.krypta.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La frase-clave de la base cifrada tiene un listón más alto que la identidad: la identidad se
 * restaura de un `.krbk`, pero **el historial de mensajes no tiene copia de ninguna clase**.
 * Estos tests fijan que nunca se genere una clave nueva encima de una base que ya existe.
 */
class DatabaseKeyTest {

    private class FakePrefs(initial: Map<String, String> = emptyMap()) : KeyPrefs {
        val values = initial.toMutableMap()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
    }

    private class FakeVault(val roto: Boolean = false, val mentiroso: Boolean = false) : KeyVault {
        override fun wrap(plain: ByteArray): ByteArray {
            if (roto) error("Keystore no disponible")
            return byteArrayOf(0x2A) + plain.reversedArray()
        }
        override fun unwrap(wrapped: ByteArray): ByteArray {
            if (roto) error("clave invalidada")
            if (mentiroso) return ByteArray(DatabaseKey.KEY_BYTES * 2) // devuelve algo, pero no lo suyo
            return wrapped.drop(1).toByteArray().reversedArray()
        }
    }

    @Test
    fun `la primera vez crea una frase-clave de 32 bytes y la guarda envuelta`() {
        val prefs = FakePrefs()
        val key = DatabaseKey(prefs, FakeVault())

        val passphrase = key.passphrase()

        assertEquals("32 bytes de azar en hexadecimal", DatabaseKey.KEY_BYTES * 2, passphrase.size)
        assertTrue(
            "debe ser hexadecimal ASCII: viaja dentro de una sentencia SQL en el cifrado inicial",
            String(passphrase, Charsets.US_ASCII).all { it in "0123456789abcdef" },
        )
        assertNotNull(prefs.values[DatabaseKey.KEY_WRAPPED])
        assertFalse("la frase-clave no puede quedar en claro", prefs.values.values.any { it.contains(String(passphrase)) })
    }

    @Test
    fun `en los arranques siguientes devuelve la misma`() {
        val prefs = FakePrefs()
        val primera = DatabaseKey(prefs, FakeVault()).passphrase()
        val segunda = DatabaseKey(prefs, FakeVault()).passphrase()
        assertArrayEquals("otra clave dejaria la base ilegible", primera, segunda)
    }

    @Test
    fun `si la clave guardada no se puede abrir, falla en vez de crear otra`() {
        val prefs = FakePrefs()
        DatabaseKey(prefs, FakeVault()).passphrase() // deja una guardada

        val error = runCatching { DatabaseKey(prefs, FakeVault(roto = true)).passphrase() }.exceptionOrNull()

        // Crear otra dejaria el historial cifrado con una clave perdida y estrenaria una base
        // vacia sin avisar. Mejor romper a la vista.
        assertNotNull("debe fallar", error)
        assertTrue(error!!.message!!.contains("no se puede abrir"))
    }

    @Test
    fun `no se fia del almacen de claves sin comprobarlo`() {
        // Un Keystore que guarda una cosa y devuelve otra dejaria la base inservible al
        // reabrirla. Se detecta al crearla, cuando todavia no hay nada que perder.
        val error = runCatching { DatabaseKey(FakePrefs(), FakeVault(mentiroso = true)).passphrase() }
            .exceptionOrNull()
        assertNotNull("debe detectarse en el momento de crearla", error)
    }
}

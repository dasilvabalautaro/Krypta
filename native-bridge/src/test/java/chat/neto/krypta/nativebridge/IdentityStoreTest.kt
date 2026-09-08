package chat.neto.krypta.nativebridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La identidad Ed25519 es lo único verdaderamente irreemplazable de la app: si se pierde,
 * todos los contactos se quedan apuntando a un PeerID muerto. Estos tests fijan las dos
 * propiedades de [IdentityStore]: **envolver** con el almacén del sistema, y **no perderla
 * nunca** aunque ese almacén falle.
 */
class IdentityStoreTest {

    private class FakePrefs(initial: Map<String, String> = emptyMap()) : IdentityPrefs {
        val values = initial.toMutableMap()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }

    /** Envoltura falsa pero real en forma: invertir los bytes con un prefijo reconocible. */
    private class FakeWrapper(val fail: Boolean = false, val corruptOnUnwrap: Boolean = false) : KeyWrapper {
        override fun wrap(plain: ByteArray): ByteArray {
            if (fail) error("Keystore no disponible")
            return byteArrayOf(0x7F) + plain.reversedArray()
        }
        override fun unwrap(wrapped: ByteArray): ByteArray {
            if (fail || corruptOnUnwrap) error("clave invalidada")
            require(wrapped.firstOrNull() == 0x7F.toByte()) { "no es una envoltura" }
            return wrapped.drop(1).toByteArray().reversedArray()
        }
    }

    private val identity = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `una identidad nueva se guarda envuelta y nunca en claro`() {
        val prefs = FakePrefs()
        val store = IdentityStore(prefs, FakeWrapper(), generate = { identity })

        assertArrayEquals(identity, store.load())
        assertNotNull(prefs.values[IdentityStore.KEY_WRAPPED])
        assertNull("la copia en claro no debe existir", prefs.values[IdentityStore.KEY_PLAIN])
        // Y se recupera igual en el siguiente arranque, sin volver a generar.
        val again = IdentityStore(prefs, FakeWrapper(), generate = { error("no debía generar otra") })
        assertArrayEquals(identity, again.load())
    }

    @Test
    fun `la identidad en claro de una version anterior se migra y se borra`() {
        val plain = java.util.Base64.getEncoder().encodeToString(identity)
        val prefs = FakePrefs(mapOf(IdentityStore.KEY_PLAIN to plain))
        val store = IdentityStore(prefs, FakeWrapper(), generate = { error("no debía generar otra") })

        assertArrayEquals("la identidad debe conservarse tal cual", identity, store.load())
        assertNull("tras migrar, la copia en claro sobra", prefs.values[IdentityStore.KEY_PLAIN])
        assertNotNull(prefs.values[IdentityStore.KEY_WRAPPED])
    }

    @Test
    fun `si el almacen de claves falla la identidad se conserva en claro`() {
        val plain = java.util.Base64.getEncoder().encodeToString(identity)
        val prefs = FakePrefs(mapOf(IdentityStore.KEY_PLAIN to plain))
        val store = IdentityStore(prefs, FakeWrapper(fail = true), generate = { error("no debía generar otra") })

        assertArrayEquals(identity, store.load())
        assertEquals("no se toca lo que hay si no se pudo envolver", plain, prefs.values[IdentityStore.KEY_PLAIN])
    }

    @Test
    fun `sin almacen de claves se sigue funcionando en claro`() {
        val prefs = FakePrefs()
        val store = IdentityStore(prefs, wrapper = null, generate = { identity })

        assertArrayEquals(identity, store.load())
        assertNotNull(prefs.values[IdentityStore.KEY_PLAIN])
    }

    @Test
    fun `una envoltura ilegible no genera identidad nueva si queda la copia en claro`() {
        val prefs = FakePrefs(
            mapOf(
                IdentityStore.KEY_WRAPPED to "Zm9vYmFy",
                IdentityStore.KEY_PLAIN to java.util.Base64.getEncoder().encodeToString(identity),
            ),
        )
        var generated = false
        val store = IdentityStore(
            prefs,
            FakeWrapper(corruptOnUnwrap = true),
            generate = { generated = true; ByteArray(32) },
        )

        assertArrayEquals("debe caer a la copia en claro", identity, store.load())
        assertTrue("no debe generar una identidad nueva", !generated)
    }

    @Test
    fun `importar un respaldo sustituye la identidad y la deja envuelta`() {
        val prefs = FakePrefs(
            mapOf(IdentityStore.KEY_PLAIN to java.util.Base64.getEncoder().encodeToString(identity)),
        )
        val store = IdentityStore(prefs, FakeWrapper(), generate = { error("no debía generar otra") })
        val imported = ByteArray(32) { (100 + it).toByte() }

        store.save(imported)

        assertNull(prefs.values[IdentityStore.KEY_PLAIN])
        assertArrayEquals(imported, store.load())
    }

    @Test
    fun `si la envoltura no se puede abrir y no hay copia en claro, falla en vez de rotar la identidad`() {
        // El peor desenlace imaginable sería generar una identidad nueva sin decir nada: el
        // PeerID cambiaría y todos los contactos seguirían escribiendo a un móvil inexistente.
        val prefs = FakePrefs(mapOf(IdentityStore.KEY_WRAPPED to "Zm9vYmFy"))
        var generated = false
        val store = IdentityStore(
            prefs,
            FakeWrapper(corruptOnUnwrap = true),
            generate = { generated = true; ByteArray(32) },
        )

        val error = runCatching { store.load() }.exceptionOrNull()

        assertNotNull("debe fallar a la vista", error)
        assertTrue("no debe generar una identidad nueva", !generated)
        assertTrue(
            "el mensaje debe orientar a restaurar el respaldo",
            error!!.message!!.contains(".krbk"),
        )
    }
}

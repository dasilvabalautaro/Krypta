package chat.neto.krypta.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityBackupTest {

    private val identity = ByteArray(64) { (it * 7).toByte() }
    private val contacts = listOf(
        IdentityBackup.BackupContact("Lucía Ñandú 😀", "12D3KooWLucia", verified = true),
        IdentityBackup.BackupContact("Sin|Raro\nNombre", "12D3KooWRaro", verified = false),
    )

    @Test
    fun `round-trip conserva identidad y contactos`() {
        val blob = IdentityBackup.encode(
            "correcta caballo batería grapa".toCharArray(),
            IdentityBackup.Data(identity, contacts),
        )
        val out = IdentityBackup.decode("correcta caballo batería grapa".toCharArray(), blob)
        assertArrayEquals(identity, out.identity)
        assertEquals(contacts, out.contacts)
    }

    /**
     * El bloqueo viaja en el respaldo: restaurar en un móvil nuevo no debe traer de vuelta,
     * desbloqueado, a alguien de quien el usuario se quitó de encima.
     */
    @Test
    fun `round-trip conserva el bloqueo`() {
        val conBloqueado = contacts +
            IdentityBackup.BackupContact("Spam", "12D3KooWSpam", verified = false, blocked = true)
        val blob = IdentityBackup.encode(
            "passphrase".toCharArray(),
            IdentityBackup.Data(identity, conBloqueado),
        )
        val out = IdentityBackup.decode("passphrase".toCharArray(), blob)
        assertEquals(conBloqueado, out.contacts)
        assertEquals(listOf("12D3KooWSpam"), out.contacts.filter { it.blocked }.map { it.peerId })
    }

    /**
     * Un respaldo de una versión anterior (sin ninguna línea `b=`) se lee igual y sus
     * contactos quedan **desbloqueados**: el bloqueo se añadió en líneas propias, no como un
     * cuarto campo de `c=`, para no romper la compatibilidad en ninguno de los dos sentidos.
     */
    @Test
    fun `un respaldo sin lineas de bloqueo se lee con todos desbloqueados`() {
        val blob = IdentityBackup.encode(
            "passphrase".toCharArray(),
            IdentityBackup.Data(identity, contacts), // ninguno bloqueado → payload sin `b=`
        )
        val out = IdentityBackup.decode("passphrase".toCharArray(), blob)
        assertTrue(out.contacts.none { it.blocked })
        assertEquals(contacts, out.contacts)
    }

    @Test
    fun `passphrase incorrecta falla, no devuelve basura`() {
        val blob = IdentityBackup.encode(
            "buena".toCharArray(),
            IdentityBackup.Data(identity, emptyList()),
        )
        assertThrows(IdentityBackup.InvalidBackup::class.java) {
            IdentityBackup.decode("mala".toCharArray(), blob)
        }
    }

    @Test
    fun `un byte alterado invalida el respaldo (GCM autentica)`() {
        val blob = IdentityBackup.encode(
            "clave".toCharArray(),
            IdentityBackup.Data(identity, contacts),
        )
        blob[blob.size - 5] = (blob[blob.size - 5] + 1).toByte()
        assertThrows(IdentityBackup.InvalidBackup::class.java) {
            IdentityBackup.decode("clave".toCharArray(), blob)
        }
    }

    @Test
    fun `un archivo ajeno o truncado se rechaza como no-Krypta`() {
        assertThrows(IdentityBackup.InvalidBackup::class.java) {
            IdentityBackup.decode("x".toCharArray(), "no soy un backup".toByteArray())
        }
        assertThrows(IdentityBackup.InvalidBackup::class.java) {
            IdentityBackup.decode("x".toCharArray(), ByteArray(3))
        }
    }

    @Test
    fun `el blob no contiene la identidad ni los nombres en claro`() {
        val blob = IdentityBackup.encode(
            "clave".toCharArray(),
            IdentityBackup.Data(identity, contacts),
        )
        val asText = String(blob, Charsets.ISO_8859_1)
        assertTrue(!asText.contains("12D3KooWLucia"))
        assertTrue(!asText.contains("Lucía"))
    }
}

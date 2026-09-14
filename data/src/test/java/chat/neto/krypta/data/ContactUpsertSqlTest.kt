package chat.neto.krypta.data

import chat.neto.krypta.data.dao.ContactDao
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * El alta de contactos **sin bajar nunca `peerProtocol`** (hallazgos H-2 y H-3 de
 * `docs/REVISION-protocolo-2026-09-14.md`).
 *
 * Antes era un `@Insert(REPLACE)` que escribía la fila tal como llegara. Verificar o bloquear a
 * un contacto desde una copia leída **antes** de que llegara su anuncio de versión lo devolvía a
 * v1; y como el anuncio sale una sola vez por versión, se quedaba así para siempre: sin ratchet,
 * sin relleno, sin depósito ciego y sin clave de llamada negociada, sin que nada lo avisara.
 *
 * Corre en la JVM contra SQLite de verdad y ejecuta **las mismas cadenas** que van en los
 * `@Query` ([ContactDao.UPSERT_SQL], [ContactDao.RAISE_PEER_PROTOCOL_SQL]), por la misma razón
 * que `RatchetSeenPruneSqlTest`: si la lógica es SQL, se prueba donde se pueda repetir.
 */
class ContactUpsertSqlTest {

    private lateinit var db: Connection
    private val id = "12D3KooWBob"

    @Before
    fun abrir() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        // El `createSql` de `contacts` en data/schemas/…/9.json, tal cual.
        db.createStatement().use { st ->
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `contacts` (`id` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                    "`peerId` TEXT NOT NULL, `publicKey` BLOB NOT NULL, `verified` INTEGER NOT NULL, " +
                    "`blocked` INTEGER NOT NULL, `peerProtocol` INTEGER NOT NULL DEFAULT 0, " +
                    "`announcedProtocol` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS `index_contacts_peerId` ON `contacts` (`peerId`)")
        }
    }

    @After
    fun cerrar() = db.close()

    /**
     * Ejecuta [sql] enlazando sus parámetros **con nombre** en orden de aparición, como hace Room
     * (el alta usa `:id` dos veces: en la fila y en la subconsulta que lee la versión anterior).
     */
    private fun ejecutar(sql: String, params: Map<String, Any>) {
        val nombres = Regex(":(\\w+)").findAll(sql).map { it.groupValues[1] }.toList()
        db.prepareStatement(sql.replace(Regex(":\\w+"), "?")).use { st ->
            nombres.forEachIndexed { i, n -> st.setObject(i + 1, params.getValue(n)) }
            st.executeUpdate()
        }
    }

    private fun guardar(
        peerProtocol: Int,
        nombre: String = "Bob",
        verified: Boolean = false,
        blocked: Boolean = false,
        announced: Int = 0,
        contactId: String = id,
    ) = ejecutar(
        ContactDao.UPSERT_SQL,
        mapOf(
            "id" to contactId, "displayName" to nombre, "peerId" to contactId,
            "publicKey" to byteArrayOf(1, 2, 3),
            // Room enlaza los Boolean como 1/0.
            "verified" to if (verified) 1 else 0, "blocked" to if (blocked) 1 else 0,
            "peerProtocol" to peerProtocol, "announcedProtocol" to announced,
        ),
    )

    private fun subir(protocol: Int) =
        ejecutar(ContactDao.RAISE_PEER_PROTOCOL_SQL, mapOf("id" to id, "protocol" to protocol))

    private data class Fila(
        val nombre: String, val verified: Int, val blocked: Int,
        val peerProtocol: Int, val announced: Int, val publicKey: ByteArray,
    )

    private fun fila(contactId: String = id): Fila =
        db.prepareStatement(
            "SELECT displayName, verified, blocked, peerProtocol, announcedProtocol, publicKey FROM contacts WHERE id = ?",
        ).use { st ->
            st.setString(1, contactId)
            st.executeQuery().use { rs ->
                check(rs.next()) { "no hay fila para $contactId" }
                Fila(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getBytes(6))
            }
        }

    private fun filas(): Int =
        db.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM contacts").use { it.next(); it.getInt(1) } }

    /** El caso real: el anuncio llegó (v3) y la UI guarda la verificación con la copia de antes (v0). */
    @Test
    fun `una copia vieja del contacto no rebaja la version que anuncio`() {
        guardar(peerProtocol = 3)
        guardar(peerProtocol = 0, nombre = "Bob renombrado", verified = true)

        val f = fila()
        assertEquals("la versión anunciada no baja", 3, f.peerProtocol)
        // Todo lo demás sí se sustituye: lo que se quería guardar se guarda.
        assertEquals("Bob renombrado", f.nombre)
        assertEquals(1, f.verified)
        assertArrayEquals(byteArrayOf(1, 2, 3), f.publicKey)
        assertEquals("sigue siendo una sola fila", 1, filas())
    }

    @Test
    fun `el alta sube la version cuando la nueva es mayor`() {
        guardar(peerProtocol = 2)
        guardar(peerProtocol = 3)
        assertEquals(3, fila().peerProtocol)
    }

    /** Un contacto nuevo empieza con lo que traiga: la subconsulta no encuentra fila y cuenta 0. */
    @Test
    fun `un contacto nuevo empieza con la version que traiga`() {
        guardar(peerProtocol = 0)
        guardar(peerProtocol = 3, contactId = "12D3KooWOtro")
        assertEquals(0, fila().peerProtocol)
        assertEquals(3, fila("12D3KooWOtro").peerProtocol)
    }

    /** Subir la versión es solo eso: ni baja, ni toca la verificación, el bloqueo o el anuncio propio. */
    @Test
    fun `subir la version nunca baja y no toca nada mas`() {
        guardar(peerProtocol = 2, verified = true, blocked = true, announced = 3)

        subir(1)
        assertEquals("un anuncio menor no baja", 2, fila().peerProtocol)

        subir(3)
        val f = fila()
        assertEquals(3, f.peerProtocol)
        assertEquals(1, f.verified)
        assertEquals(1, f.blocked)
        assertEquals(3, f.announced)
    }

    /** Lo único que la devuelve a 0 es borrar el contacto: entonces sí se empieza de cero. */
    @Test
    fun `borrar el contacto es lo unico que la devuelve a cero`() {
        guardar(peerProtocol = 3)
        db.prepareStatement("DELETE FROM contacts WHERE id = ?").use { it.setString(1, id); it.executeUpdate() }
        guardar(peerProtocol = 0)
        assertEquals(0, fila().peerProtocol)
    }
}

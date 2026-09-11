package chat.neto.krypta.data

import chat.neto.krypta.data.dao.RatchetDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * La poda de `ratchet_seen`, que es **la única cosa** que impide reproducir un mensaje de la
 * época 0: esa época se re-deriva del secreto compartido, así que el ratchet la vuelve a abrir y
 * quien tiene que decir "esto ya lo vi" es esta tabla (lo encontró `RatchetPropertyTest`, ver
 * `docs/DISENO-ratchet.md` §1.9).
 *
 * La regla es la **unión** de dos: se conserva una huella si es reciente **en el tiempo** (8 días,
 * margen sobre el TTL de 7 del buzón) **o** si está entre las 500 últimas. Antes solo había la de
 * cantidad, y se comportaba al revés de lo que hace falta: en una pareja muy activa 500 mensajes
 * pueden ser medio día, así que dejaba de proteger justo a quien más habla.
 *
 * **Por qué en la JVM y no instrumentado.** El primer intento fue un test instrumentado, y
 * verificar cuatro líneas de SQL costó dos corridas y horas de reloj: la primera murió porque el
 * móvil se desconectó del USB (Gradle esperó 1h 21m y reportó "FAILED" sin mensaje), y la segunda
 * mató el proceso de test en el dispositivo. Esto corre en segundos, sin cable y en cualquier
 * máquina. Ejecuta **la misma cadena** que va en el `@Query` ([RatchetDao.PRUNE_SEEN_SQL]), no una
 * copia: si alguien cambia el SQL del DAO, este test se entera.
 */
class RatchetSeenPruneSqlTest {

    private lateinit var db: Connection
    private val conv = "12D3KooWConversacion"
    private val day = 24L * 60 * 60 * 1000
    private val keep = 500
    private val retention = 8 * day

    @Before
    fun abrir() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        // Mismo esquema que la entidad RatchetSeenEntity (clave primaria compuesta e índice).
        db.createStatement().use { st ->
            st.executeUpdate(
                "CREATE TABLE ratchet_seen (conversationId TEXT NOT NULL, digest TEXT NOT NULL, " +
                    "seenAt INTEGER NOT NULL, PRIMARY KEY (conversationId, digest))",
            )
            st.executeUpdate("CREATE INDEX idx_seen ON ratchet_seen (conversationId, seenAt)")
        }
    }

    @After
    fun cerrar() = db.close()

    private fun marcar(digest: String, at: Long) {
        db.prepareStatement("INSERT OR REPLACE INTO ratchet_seen VALUES (?, ?, ?)").use { st ->
            st.setString(1, conv); st.setString(2, digest); st.setLong(3, at)
            st.executeUpdate()
        }
    }

    /** Ejecuta el SQL del DAO, traduciendo sus parámetros con nombre a posicionales. */
    private fun podar(cutoff: Long) {
        val sql = RatchetDao.PRUNE_SEEN_SQL
            .replace(":conversationId", "?")
            .replace(":cutoff", "?")
            .replace(":keep", "?")
        // El orden de los `?` es el de aparición en la cadena: conversationId, cutoff,
        // conversationId (la subconsulta), keep.
        db.prepareStatement(sql).use { st ->
            st.setString(1, conv); st.setLong(2, cutoff); st.setString(3, conv); st.setInt(4, keep)
            st.executeUpdate()
        }
    }

    private fun existe(digest: String): Boolean =
        db.prepareStatement("SELECT 1 FROM ratchet_seen WHERE conversationId = ? AND digest = ?").use { st ->
            st.setString(1, conv); st.setString(2, digest)
            st.executeQuery().use { it.next() }
        }

    private fun cuantas(): Int =
        db.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM ratchet_seen").use { it.next(); it.getInt(1) }
        }

    /**
     * El caso que motivó el cambio: una pareja muy activa. Con 600 huellas del mismo día, la
     * poda por cantidad habría borrado 100 — y con ellas la protección contra reproducir esos
     * mensajes, el mismo día en que se enviaron.
     */
    @Test
    fun `lo reciente sobrevive aunque pase del tope por cantidad`() {
        val ahora = System.currentTimeMillis()
        repeat(600) { i -> marcar("huella-$i", ahora - i * 1000L) }

        podar(ahora - retention)

        assertEquals("no debería borrarse nada: todas son de hoy", 600, cuantas())
        assertTrue(existe("huella-599"))
        assertTrue(existe("huella-0"))
    }

    /** Dentro del plazo del buzón se conserva aunque quede fuera de las 500 más nuevas. */
    @Test
    fun `dentro del plazo del buzon no se poda aunque sobre por cantidad`() {
        val ahora = System.currentTimeMillis()
        marcar("de-hace-tres-dias", ahora - 3 * day)
        repeat(600) { i -> marcar("reciente-$i", ahora - i * 1000L) }

        podar(ahora - retention)

        assertTrue(
            "una huella de hace 3 días entra en el TTL del buzón: tiene que sobrevivir",
            existe("de-hace-tres-dias"),
        )
    }

    /** Y lo viejo se va cuando además sobra por cantidad: el buzón ya no puede reentregarlo. */
    @Test
    fun `lo viejo se poda cuando ademas sobra por cantidad`() {
        val ahora = System.currentTimeMillis()
        marcar("muy-vieja", ahora - 30 * day)
        repeat(500) { i -> marcar("reciente-$i", ahora - i * 1000L) }

        podar(ahora - retention)

        assertFalse("una huella de hace 30 días ya no hace falta", existe("muy-vieja"))
        assertTrue(existe("reciente-0"))
        assertEquals(500, cuantas())
    }

    /**
     * Lo viejo **no** se poda si aún cabe en el tope por cantidad: es la otra mitad de la unión,
     * y la que protege a una conversación tranquila (donde 500 mensajes pueden ser meses).
     */
    @Test
    fun `lo viejo se conserva si todavia cabe en el tope por cantidad`() {
        val ahora = System.currentTimeMillis()
        marcar("muy-vieja", ahora - 30 * day)
        repeat(10) { i -> marcar("reciente-$i", ahora - i * 1000L) }

        podar(ahora - retention)

        assertTrue(
            "con 11 filas no sobra nada por cantidad, así que ni lo viejo se toca",
            existe("muy-vieja"),
        )
    }
}

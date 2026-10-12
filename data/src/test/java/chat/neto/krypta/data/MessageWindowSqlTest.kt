package chat.neto.krypta.data

import chat.neto.krypta.data.dao.MessageDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * La ventana del chat (O-9 de `docs/AUDITORIA-2026-10-12.md`): el chat ya no carga la
 * conversación entera, sino los N más recientes, y crece al subir. Corre en la JVM contra SQLite
 * de verdad con **las mismas cadenas** de los `@Query`, como `ContactUpsertSqlTest`.
 */
class MessageWindowSqlTest {

    private lateinit var db: Connection

    @Before
    fun abrir() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        // El `createSql` de `messages` en data/schemas/…/10.json, tal cual.
        db.createStatement().use { st ->
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, " +
                    "`senderId` TEXT NOT NULL, `payload` BLOB NOT NULL, `encrypted` INTEGER NOT NULL DEFAULT 1, " +
                    "`timestamp` INTEGER NOT NULL, `status` TEXT NOT NULL, PRIMARY KEY(`id`))",
            )
            st.executeUpdate(
                "CREATE INDEX IF NOT EXISTS `index_messages_conversationId_timestamp` " +
                    "ON `messages` (`conversationId`, `timestamp`)",
            )
        }
        // 250 mensajes en "ana" y 5 en "bob", con las marcas de tiempo desordenadas al insertar.
        for (k in (0 until 250).shuffled(java.util.Random(7))) insertar("a-$k", "ana", k * 10L)
        for (k in 0 until 5) insertar("b-$k", "bob", 10_000L + k)
    }

    @After
    fun cerrar() = db.close()

    private fun insertar(id: String, conversation: String, ts: Long) =
        db.prepareStatement(
            "INSERT INTO messages (id, conversationId, senderId, payload, encrypted, timestamp, status) " +
                "VALUES (?, ?, ?, ?, 0, ?, 'DELIVERED')",
        ).use {
            it.setString(1, id); it.setString(2, conversation); it.setString(3, conversation)
            it.setBytes(4, byteArrayOf(1)); it.setLong(5, ts); it.executeUpdate()
        }

    /** Enlaza los parámetros con nombre en orden de aparición, como Room. */
    private fun <T> consultar(sql: String, params: Map<String, Any>, leer: (java.sql.ResultSet) -> T): List<T> {
        val nombres = Regex(":(\\w+)").findAll(sql).map { it.groupValues[1] }.toList()
        return db.prepareStatement(sql.replace(Regex(":\\w+"), "?")).use { st ->
            nombres.forEachIndexed { i, n -> st.setObject(i + 1, params.getValue(n)) }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(leer(rs)) } }
        }
    }

    private fun ventana(conversation: String, limit: Int): List<String> =
        consultar(MessageDao.OBSERVE_RECENT_SQL, mapOf("conversationId" to conversation, "limit" to limit)) {
            it.getString("id")
        }

    private fun desde(conversation: String, id: String): Int =
        consultar(MessageDao.COUNT_SINCE_SQL, mapOf("conversationId" to conversation, "id" to id)) { it.getInt(1) }
            .single()

    @Test
    fun `la ventana son los mas recientes en orden cronologico`() {
        val v = ventana("ana", 100)
        assertEquals(100, v.size)
        assertEquals("el más antiguo de la ventana", "a-150", v.first())
        assertEquals("el último, abajo", "a-249", v.last())
        assertEquals((150 until 250).map { "a-$it" }, v)
    }

    @Test
    fun `una ventana mayor que la conversacion la trae entera y solo esa`() {
        assertEquals((0 until 5).map { "b-$it" }, ventana("bob", 100))
    }

    @Test
    fun `contar desde un mensaje da la ventana que lo incluye`() {
        val n = desde("ana", "a-120")
        assertEquals(130, n)
        assertEquals("a-120", ventana("ana", n).first())
    }

    /** Un id de otra conversación no da ventana: nadie salta a un mensaje ajeno. */
    @Test
    fun `contar desde un id ajeno o inexistente da cero`() {
        assertEquals(0, desde("ana", "b-1"))
        assertEquals(0, desde("ana", "no-existe"))
    }
}

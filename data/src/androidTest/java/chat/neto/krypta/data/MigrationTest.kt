package chat.neto.krypta.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migraciones de Room, verificadas de verdad.
 *
 * Hasta ahora no había ni una prueba de `:data`: las cuatro versiones de esquema en producción
 * se habían validado a mano en el teléfono del autor, y una migración cuyo SQL no reproduzca
 * exactamente la entidad **lanza en tiempo de ejecución** — con la base de datos del usuario ya
 * abierta. Esto es el hallazgo §7 de la auditoría.
 *
 * Se cubren 4→5 y 5→6: los esquemas exportados empiezan en la v4 (`data/schemas/`), y escribir
 * a mano los de v2/v3 sería inventarse el patrón contra el que se compara.
 *
 * Es instrumentado a propósito (Room necesita un SQLite real), pero **no toca la app**: el APK
 * de prueba es `chat.neto.krypta.data.test`, así que —a diferencia de
 * `:app:connectedDebugAndroidTest`— no desinstala Krypta ni borra la identidad del móvil.
 *
 *     ./gradlew :data:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KryptaDatabase::class.java,
    )

    /**
     * v4→v5 añade `contacts.blocked`. Lo que importa no es la columna en sí, sino que los
     * datos del usuario sobrevivan: el contacto y su estado de verificación tienen que seguir
     * ahí después de migrar (es lo que se comprobó a mano en el TECNO el 6 sep 2026).
     */
    @Test
    fun migracion4a5ConservaLosContactos() {
        helper.createDatabase(DB, 4).use { db ->
            db.execSQL(
                "INSERT INTO contacts (id, displayName, peerId, publicKey, sharedSecret, verified) " +
                    "VALUES ('12D3KooWX', 'Ana', '12D3KooWX', X'00', X'0102', 1)",
            )
            db.execSQL(
                "INSERT INTO messages (id, conversationId, senderId, ciphertext, timestamp, status) " +
                    "VALUES ('m1', '12D3KooWX', 'self', X'0A0B', 123, 'SENT')",
            )
        }

        val db = helper.runMigrationsAndValidate(DB, 5, true, MIGRATION_4_5)

        db.query("SELECT displayName, verified, blocked FROM contacts WHERE id = '12D3KooWX'").use { c ->
            assertTrue("el contacto debe seguir existiendo tras migrar", c.moveToFirst())
            assertEquals("Ana", c.getString(0))
            assertEquals("la verificación no se pierde", 1, c.getInt(1))
            assertEquals("blocked arranca a 0", 0, c.getInt(2))
        }
        db.query("SELECT COUNT(*) FROM messages").use { c ->
            c.moveToFirst()
            assertEquals("los mensajes no se tocan", 1, c.getInt(0))
        }
    }

    /**
     * v5→v6 quita `contacts.sharedSecret`, que se guardaba en claro y abría todo el historial
     * a quien copiara el fichero. Lo que hay que comprobar es que la operación —tabla nueva,
     * copia, borrado y renombrado— **no se lleva por delante los contactos**, y que la columna
     * desaparece de verdad.
     */
    @Test
    fun migracion5a6QuitaElSecretoYConservaLosContactos() {
        helper.createDatabase(DB, 5).use { db ->
            db.execSQL(
                "INSERT INTO contacts (id, displayName, peerId, publicKey, sharedSecret, verified, blocked) " +
                    "VALUES ('12D3KooWX', 'Ana', '12D3KooWX', X'00', X'DEADBEEF', 1, 0)",
            )
            db.execSQL(
                "INSERT INTO contacts (id, displayName, peerId, publicKey, sharedSecret, verified, blocked) " +
                    "VALUES ('12D3KooWY', 'Beto', '12D3KooWY', X'00', X'CAFE', 0, 1)",
            )
        }

        val db = helper.runMigrationsAndValidate(DB, 6, true, MIGRATION_5_6)

        db.query("SELECT id, displayName, verified, blocked FROM contacts ORDER BY id").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("12D3KooWX", c.getString(0))
            assertEquals("Ana", c.getString(1))
            assertEquals("la verificación se conserva", 1, c.getInt(2))
            assertTrue(c.moveToNext())
            assertEquals("Beto", c.getString(1))
            assertEquals("el bloqueo se conserva", 1, c.getInt(3))
        }
        // La columna ya no existe: preguntar por ella tiene que fallar.
        db.query("PRAGMA table_info(contacts)").use { c ->
            val columnas = generateSequence { if (c.moveToNext()) c.getString(1) else null }.toList()
            assertTrue("sharedSecret sigue en la tabla: $columnas", "sharedSecret" !in columnas)
            assertTrue("peerId debe seguir", "peerId" in columnas)
        }
        // Y el índice de peerId, que se va con la tabla vieja, tiene que estar recreado.
        db.query("PRAGMA index_list(contacts)").use { c ->
            val indices = generateSequence { if (c.moveToNext()) c.getString(1) else null }.toList()
            assertTrue("falta el índice de peerId: $indices", indices.any { it.contains("peerId") })
        }
    }

    /**
     * v6→v7 añade las tablas del ratchet. Es aditiva, así que lo que hay que comprobar es
     * justo eso: que **no toca nada** de lo del usuario y que las tablas quedan usables (una
     * sesión y una huella se escriben y se leen). `runMigrationsAndValidate` ya compara el
     * esquema resultante con el exportado, que es donde se cazan las diferencias de SQL.
     */
    @Test
    fun migracion6a7AnadeElRatchetSinTocarLosDatos() {
        helper.createDatabase(DB, 6).use { db ->
            db.execSQL(
                "INSERT INTO contacts (id, displayName, peerId, publicKey, verified, blocked) " +
                    "VALUES ('12D3KooWX', 'Ana', '12D3KooWX', X'00', 1, 0)",
            )
            db.execSQL(
                "INSERT INTO messages (id, conversationId, senderId, ciphertext, timestamp, status) " +
                    "VALUES ('m1', '12D3KooWX', 'self', X'0A0B', 123, 'SENT')",
            )
        }

        val db = helper.runMigrationsAndValidate(DB, 7, true, MIGRATION_6_7)

        db.query("SELECT displayName FROM contacts WHERE id = '12D3KooWX'").use { c ->
            assertTrue("el contacto sigue ahí", c.moveToFirst())
            assertEquals("Ana", c.getString(0))
        }
        db.query("SELECT COUNT(*) FROM messages").use { c ->
            c.moveToFirst()
            assertEquals("los mensajes no se tocan", 1, c.getInt(0))
        }

        db.execSQL(
            "INSERT INTO ratchet_sessions (conversationId, state, updatedAt) " +
                "VALUES ('12D3KooWX', X'0102', 1)",
        )
        db.execSQL(
            "INSERT INTO ratchet_seen (conversationId, digest, seenAt) " +
                "VALUES ('12D3KooWX', 'abcd', 1)",
        )
        db.query("SELECT COUNT(*) FROM ratchet_sessions").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM ratchet_seen WHERE digest = 'abcd'").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
    }

    /**
     * v7→v8: el historial deja de guardarse cifrado con la clave estática. Lo que hay que
     * comprobar es que **el dato no se mueve**: `ciphertext` pasa a llamarse `payload` con los
     * mismos bytes, y las filas viejas quedan marcadas `encrypted = 1` para que se sigan
     * leyendo (y se conviertan luego en segundo plano). El historial no tiene copia de
     * seguridad de ninguna clase, así que esta es la migración que menos margen tiene.
     */
    @Test
    fun migracion7a8RenombraSinPerderElContenido() {
        helper.createDatabase(DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO messages (id, conversationId, senderId, ciphertext, timestamp, status) " +
                    "VALUES ('m1', '12D3KooWX', 'self', X'DEADBEEF', 123, 'SENT')",
            )
            db.execSQL(
                "INSERT INTO messages (id, conversationId, senderId, ciphertext, timestamp, status) " +
                    "VALUES ('m2', '12D3KooWX', '12D3KooWX', X'C0FFEE', 124, 'DELIVERED')",
            )
        }

        val db = helper.runMigrationsAndValidate(DB, 8, true, MIGRATION_7_8)

        db.query("SELECT id, payload, encrypted, status FROM messages ORDER BY timestamp").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("m1", c.getString(0))
            assertEquals(
                "los bytes tienen que ser exactamente los mismos",
                listOf(0xDE, 0xAD, 0xBE, 0xEF).map { it.toByte() },
                c.getBlob(1).toList(),
            )
            assertEquals("una fila vieja sigue siendo ciphertext", 1, c.getInt(2))
            assertEquals("SENT", c.getString(3))
            assertTrue(c.moveToNext())
            assertEquals("m2", c.getString(0))
            assertEquals(1, c.getInt(2))
        }
    }

    /**
     * v8→v9 añade el anuncio de capacidad por contacto. Aditiva: lo que hay que ver es que los
     * contactos siguen ahí y que las dos columnas arrancan en 0 = «no se sabe / nunca».
     */
    @Test
    fun migracion8a9AnadeLasVersionesDeProtocolo() {
        helper.createDatabase(DB, 8).use { db ->
            db.execSQL(
                "INSERT INTO contacts (id, displayName, peerId, publicKey, verified, blocked) " +
                    "VALUES ('12D3KooWX', 'Ana', '12D3KooWX', X'00', 1, 0)",
            )
        }

        val db = helper.runMigrationsAndValidate(DB, 9, true, MIGRATION_8_9)

        db.query("SELECT displayName, verified, peerProtocol, announcedProtocol FROM contacts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Ana", c.getString(0))
            assertEquals("la verificación no se toca", 1, c.getInt(1))
            assertEquals("un contacto de antes no ha anunciado nada", 0, c.getInt(2))
            assertEquals("ni le hemos anunciado nada", 0, c.getInt(3))
        }
    }

    private companion object {
        const val DB = "krypta-migration-test.db"
    }
}

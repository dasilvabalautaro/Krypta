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
 * Solo se puede cubrir 4→5: los esquemas exportados empiezan en la v4 (`data/schemas/`), y
 * escribir a mano los de v2/v3 sería inventarse el patrón contra el que se compara.
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

    private companion object {
        const val DB = "krypta-migration-test.db"
    }
}

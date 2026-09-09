package chat.neto.krypta.data.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * El cifrado de la base existente, que es la parte con datos reales de por medio: si sale mal,
 * el usuario pierde su historial y no hay copia de seguridad que lo devuelva.
 *
 * Instrumentado porque SQLCipher es código nativo. Como el resto de `:data`, corre en su propio
 * paquete (`chat.neto.krypta.data.test`) y no toca la app instalada.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val passphrase = ByteArray(32) { (it + 1).toByte() }
    private lateinit var db: File

    @Before
    fun preparar() {
        db = File(ctx.cacheDir, "prueba-cifrado.db")
        // Ojo al `-journal`: un diario caliente que quede de una ejecución interrumpida bloquea
        // la apertura en solo-lectura de la siguiente, y el test se queda colgado sin decir por
        // qué. Pasó de verdad mientras se escribía esto.
        listOf("", "-wal", "-shm", "-journal").forEach { File(db.path + it).delete() }
        listOf("", "-wal", "-shm", "-journal").forEach { File(db.path + ".cipher.tmp" + it).delete() }
    }

    /** Crea una base **en claro** con datos y una versión de esquema, como la de antes. */
    private fun crearEnClaro() {
        val plain = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(db, null)
        plain.execSQL("CREATE TABLE contacts (id TEXT PRIMARY KEY, displayName TEXT)")
        plain.execSQL("INSERT INTO contacts VALUES ('12D3KooWX', 'Ana')")
        plain.execSQL("INSERT INTO contacts VALUES ('12D3KooWY', 'Beto')")
        plain.version = 6
        plain.close()
    }

    @Test
    fun cifraLaBaseConservandoDatosYVersion() {
        crearEnClaro()
        assertTrue("de partida debe estar en claro", DatabaseEncryption.needsEncrypting(db))
        // Y sus datos se leen con un simple grep del fichero, que es justo el problema.
        assertTrue(db.readBytes().toString(Charsets.ISO_8859_1).contains("Ana"))

        DatabaseEncryption.encryptInPlace(db, passphrase)

        assertFalse("ya no debe ser una base en claro", DatabaseEncryption.needsEncrypting(db))
        assertFalse(
            "los nombres no pueden seguir legibles en el fichero",
            db.readBytes().toString(Charsets.ISO_8859_1).contains("Ana"),
        )

        System.loadLibrary("sqlcipher")
        val cifrada = net.zetetic.database.sqlcipher.SQLiteDatabase
            .openOrCreateDatabase(db, passphrase, null, null)
        try {
            // `sqlcipher_export` NO copia user_version: si no se copia a mano, Room ve una base
            // "recién creada" y podría recrear las tablas encima de los datos del usuario.
            assertEquals("la versión de esquema debe conservarse", 6, cifrada.version)
            cifrada.rawQuery("SELECT displayName FROM contacts ORDER BY id", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Ana", c.getString(0))
                assertTrue(c.moveToNext())
                assertEquals("Beto", c.getString(0))
                assertFalse("no debe haber filas de más", c.moveToNext())
            }
        } finally {
            cifrada.close()
        }
    }

    @Test
    fun esIdempotente() {
        crearEnClaro()
        DatabaseEncryption.encryptInPlace(db, passphrase)
        val despuesDelPrimero = db.readBytes()

        // Un segundo arranque no debe volver a tocarla (ni, sobre todo, cifrar lo ya cifrado).
        DatabaseEncryption.encryptInPlace(db, passphrase)

        assertArrayEqualsPrefijo(despuesDelPrimero, db.readBytes())
        assertFalse(File(db.path + ".cipher.tmp").exists())
    }

    @Test
    fun noHaceNadaSiNoHayBase() {
        assertFalse(DatabaseEncryption.needsEncrypting(db))
        DatabaseEncryption.encryptInPlace(db, passphrase) // no debe lanzar
        assertFalse(db.exists())
    }

    /** Compara la cabecera: el resto del fichero puede variar por el diario de SQLite. */
    private fun assertArrayEqualsPrefijo(esperado: ByteArray, real: ByteArray) {
        val n = minOf(64, esperado.size, real.size)
        assertTrue(
            "la base ya cifrada no debía volver a transformarse",
            esperado.copyOfRange(0, n).contentEquals(real.copyOfRange(0, n)),
        )
    }
}

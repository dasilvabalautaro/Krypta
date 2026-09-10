package chat.neto.krypta.data.crypto

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regresión del 9 sep 2026: **la app arrancaba una vez y no volvía a arrancar**.
 *
 * `System.loadLibrary("sqlcipher")` estaba dentro de `DatabaseEncryption.encryptInPlace`, detrás
 * de su salida temprana. En el arranque que convertía la base en claro se cargaba la nativa y
 * todo iba bien; en el siguiente —ya cifrada— no se cargaba, y Room moría al abrirla con
 * `UnsatisfiedLinkError … nativeOpen`. Es decir, el fallo solo aparecía en el **segundo**
 * arranque, que es exactamente lo que una verificación hecha justo tras la conversión no ve.
 *
 * Esto reproduce ese segundo arranque: pedir la base **sin que nadie cargue la nativa a mano**.
 *
 * Nota de honestidad sobre el alcance: si otra clase de prueba ya cargó la biblioteca en este
 * mismo proceso, el test pasa sin demostrar nada. Corriéndolo solo —que es como hay que correr
 * las pruebas instrumentadas de `:data`— sí reproduce:
 *
 *     adb shell am instrument -w -e class chat.neto.krypta.data.crypto.SqlCipherTest \
 *         chat.neto.krypta.data.test/androidx.test.runner.AndroidJUnitRunner
 *
 * La garantía de verdad no es este test sino la forma de [SqlCipher]: la fábrica solo se puede
 * obtener por `openHelperFactory`, que carga antes de construirla.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherTest {

    @Test
    fun laBaseCifradaSeAbreSinCargarLaNativaAMano() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "sqlcipher-load-test.db"
        listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(name + it).delete() }

        val passphrase = "0123456789abcdef".toByteArray()
        val helper = SqlCipher.openHelperFactory(passphrase).create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE t (v TEXT NOT NULL)")
                        db.execSQL("INSERT INTO t (v) VALUES ('hola')")
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )

        helper.writableDatabase.query("SELECT v FROM t").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("hola", c.getString(0))
        }
        helper.close()

        // Y el fichero que ha quedado está cifrado de verdad (no es un SQLite en claro).
        assertTrue(
            "la base debería estar cifrada",
            !DatabaseEncryption.needsEncrypting(context.getDatabasePath(name)),
        )
    }
}

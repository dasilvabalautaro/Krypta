package chat.neto.krypta.data.crypto

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.neto.krypta.data.KryptaDatabase
import chat.neto.krypta.data.entity.ContactEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * O-8 de `docs/AUDITORIA-2026-10-12.md`: construir Room **no** debe desenvolver la clave ni
 * convertir la base, porque se construye al inyectar cualquier repositorio, también desde
 * `Application.onCreate` en el hilo principal. Lo caro tiene que ocurrir en la primera consulta,
 * una sola vez, y la base tiene que seguir funcionando como antes (cifrada y con sus datos).
 *
 * Se corre sola, como el resto de instrumentadas de `:data`:
 *
 *     adb shell am instrument -w -e class chat.neto.krypta.data.crypto.DeferredOpenHelperFactoryTest \
 *         chat.neto.krypta.data.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class DeferredOpenHelperFactoryTest {

    @Test
    fun laClaveSeDesenvuelveEnLaPrimeraConsultaYSoloUnaVez() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "deferred-open-test.db"
        listOf("", "-wal", "-shm", "-journal").forEach { context.getDatabasePath(name + it).delete() }
        val prepared = AtomicInteger()
        val passphrase = "0123456789abcdef0123456789abcdef".toByteArray()

        fun abrir() = Room.databaseBuilder(context, KryptaDatabase::class.java, name)
            .openHelperFactory(
                DeferredOpenHelperFactory {
                    prepared.incrementAndGet()
                    SqlCipher.openHelperFactory(passphrase)
                },
            )
            .build()

        val db = abrir()
        assertEquals("construir Room no debe preparar la base", 0, prepared.get())

        runBlocking {
            db.contactDao().upsert("id", "Bob", "id", ByteArray(0), false, false, 0, 0)
            assertEquals("Bob", db.contactDao().findById("id")?.displayName)
            db.contactDao().findById("id")
        }
        assertEquals("se prepara una sola vez", 1, prepared.get())
        db.close()

        // Reabrir: cifrada y con sus datos, que es la prueba de que el helper real es el de siempre.
        val again = abrir()
        val bob: ContactEntity? = runBlocking { again.contactDao().findById("id") }
        assertEquals("Bob", bob?.displayName)
        again.close()
        val header = context.getDatabasePath(name).inputStream().use { it.readNBytes(16) }
        check(!String(header, Charsets.US_ASCII).startsWith("SQLite format 3")) { "la base quedó en claro" }
    }
}

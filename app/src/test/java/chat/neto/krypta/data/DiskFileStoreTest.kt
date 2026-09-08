package chat.neto.krypta.data

import chat.neto.krypta.core.IncomingFileMeta
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * El contrato v2 del staging en disco: los trozos sobreviven a la muerte del proceso (se
 * simula creando OTRA instancia sobre el mismo directorio), las reentregas son inocuas y
 * al completarse el archivo el staging se limpia. Es la mitad local de la garantía
 * "ack-tras-persistir" (la otra mitad, no confirmar sobres fallidos, está en ChatServiceTest).
 */
class DiskFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = DiskFileStore(File(tmp.root, "krypta_files"))

    private val meta = IncomingFileMeta("doc.pdf", "application/pdf", 10L, 3)
    private val chunks = listOf("0123".toByteArray(), "4567".toByteArray(), "89".toByteArray())

    @Test
    fun `assembles when meta and all chunks arrive in any order`() = runBlocking {
        val s = store()
        assertNull(s.onChunk("f1", 2, chunks[2]))
        assertNull(s.onMeta("f1", meta))
        assertNull(s.onChunk("f1", 0, chunks[0]))
        val f = s.onChunk("f1", 1, chunks[1])!!

        assertEquals("doc.pdf", f.name)
        assertArrayEquals("0123456789".toByteArray(), File(f.path).readBytes())
        // staging limpiado al completar
        assertFalse(File(tmp.root, "krypta_files/staging/f1").exists())
    }

    @Test
    fun `partial transfer survives process death and completes on redelivery`() = runBlocking {
        val first = store()
        assertNull(first.onMeta("f2", meta))
        assertNull(first.onChunk("f2", 0, chunks[0]))
        assertNull(first.onChunk("f2", 1, chunks[1]))

        // "Muere el proceso": una instancia nueva sobre el mismo directorio (v1 perdía todo
        // aquí porque los trozos vivían en memoria y los sobres ya estaban ack'd/borrados).
        val second = store()
        val f = second.onChunk("f2", 2, chunks[2])!!
        assertArrayEquals("0123456789".toByteArray(), File(f.path).readBytes())
    }

    @Test
    fun `redelivered chunk is idempotent`() = runBlocking {
        val s = store()
        assertNull(s.onMeta("f3", meta))
        assertNull(s.onChunk("f3", 0, chunks[0]))
        assertNull(s.onChunk("f3", 0, chunks[0])) // reentrega (ack perdido): inocua
        assertNull(s.onChunk("f3", 1, chunks[1]))
        val f = s.onChunk("f3", 2, chunks[2])!!
        assertArrayEquals("0123456789".toByteArray(), File(f.path).readBytes())
    }

    /**
     * La cita (responder con una foto o una nota de voz) llega en la meta, mucho antes de que
     * el archivo esté completo, así que tiene que sobrevivir en el staging igual que los
     * trozos — incluido el caso "muere el proceso a mitad de transferencia".
     */
    @Test
    fun `the quoted id travels in the staged meta and survives process death`() = runBlocking {
        val first = store()
        assertNull(first.onMeta("f-cita", meta.copy(replyTo = "id-citado")))
        assertNull(first.onChunk("f-cita", 0, chunks[0]))

        val second = store()
        assertNull(second.onChunk("f-cita", 1, chunks[1]))
        val f = second.onChunk("f-cita", 2, chunks[2])!!
        assertEquals("id-citado", f.replyTo)
    }

    /** Una meta ya en staging de una versión anterior no trae la línea de cita: no rompe. */
    @Test
    fun `a staged meta without the quote line still assembles`() = runBlocking {
        val s = store()
        assertNull(s.onMeta("f-vieja", meta))
        // Se reescribe la meta como la escribía la versión anterior (4 líneas, sin cita).
        val metaFile = File(tmp.root, "krypta_files/staging/f-vieja/meta.txt")
        assertTrue(metaFile.isFile)
        metaFile.writeText(metaFile.readLines().take(4).joinToString("\n", postfix = "\n"))

        assertNull(s.onChunk("f-vieja", 0, chunks[0]))
        assertNull(s.onChunk("f-vieja", 1, chunks[1]))
        val f = s.onChunk("f-vieja", 2, chunks[2])!!
        assertNull(f.replyTo)
        assertArrayEquals("0123456789".toByteArray(), File(f.path).readBytes())
    }

    @Test
    fun `sanitizes hostile file names and ids`() = runBlocking {
        val s = store()
        val evil = IncomingFileMeta("../../evil.sh", "text/plain", 4L, 1)
        assertNull(s.onMeta("../f4", evil))
        val f = s.onChunk("../f4", 0, "data".toByteArray())!!

        val base = File(tmp.root, "krypta_files").canonicalFile
        assertTrue(File(f.path).canonicalPath.startsWith(base.path)) // no escapa del dir
        assertArrayEquals("data".toByteArray(), File(f.path).readBytes())
    }

    @Test
    fun `meta with single chunk assembles immediately after chunk`() = runBlocking {
        val s = store()
        val one = IncomingFileMeta("nota.m4a", "audio/mp4", 3L, 1)
        assertNull(s.onChunk("f5", 0, "abc".toByteArray()))
        val f = s.onMeta("f5", one)!!
        assertEquals("audio/mp4", f.mime)
        assertArrayEquals("abc".toByteArray(), File(f.path).readBytes())
    }

    @Test
    fun `deleteLocal removes assembled file, pending staging and own copy inside the store`() = runBlocking {
        val s = store()
        // Archivo completo (ensamblado) + otro a medias (solo staging).
        assertNull(s.onMeta("f6", meta))
        assertNull(s.onChunk("f6", 0, chunks[0]))
        assertNull(s.onChunk("f6", 1, chunks[1]))
        val done = s.onChunk("f6", 2, chunks[2])!!
        assertNull(s.onMeta("f7", meta))
        assertNull(s.onChunk("f7", 0, chunks[0]))
        // Copia propia (p. ej. nota de voz enviada) dentro del almacén.
        val sent = File(tmp.root, "krypta_files/sent/nota.m4a").apply {
            parentFile!!.mkdirs(); writeBytes("audio".toByteArray())
        }

        s.deleteLocal("f6", done.path)
        s.deleteLocal("f7", null)
        s.deleteLocal("nota-id", sent.absolutePath)

        assertFalse(File(done.path).exists())
        assertFalse(File(tmp.root, "krypta_files/staging/f7").exists())
        assertFalse(sent.exists())
        // Idempotente: repetir un borrado no lanza.
        s.deleteLocal("f6", done.path)
    }

    @Test
    fun `deleteLocal never touches files outside the store`() = runBlocking {
        val s = store()
        val outside = File(tmp.root, "fuera.txt").apply { writeBytes("intocable".toByteArray()) }

        // Un path malicioso/corrupto en un descriptor no puede borrar fuera del almacén.
        s.deleteLocal("fx", outside.absolutePath)

        assertTrue(outside.exists())
    }

    // --- Topes de recepción y limpieza del staging (hallazgo A-3) ---

    @Test
    fun `una meta que anuncia un archivo desmesurado se ignora`() = runBlocking {
        val s = store()
        val enorme = IncomingFileMeta("bomba.bin", "application/octet-stream", 5L * 1024 * 1024 * 1024, 100_000)

        assertNull(s.onMeta("f-bomba", enorme))
        assertFalse(
            "una meta fuera de rango no debe dejar nada en disco",
            File(tmp.root, "krypta_files/staging/f-bomba").exists(),
        )
    }

    @Test
    fun `una meta incoherente con su numero de trozos se ignora`() = runBlocking {
        // 2 trozos no pueden traer 5 MB: el tope de un trozo es el del buzón (64 KiB).
        val s = store()
        assertNull(s.onMeta("f-inc", IncomingFileMeta("x.bin", "application/octet-stream", 5L * 1024 * 1024, 2)))
        assertFalse(File(tmp.root, "krypta_files/staging/f-inc").exists())
    }

    @Test
    fun `los trozos con indice imposible o tamano excesivo se descartan`() = runBlocking {
        val s = store()
        assertNull(s.onChunk("f2", -1, "x".toByteArray()))
        assertNull(s.onChunk("f2", 10_000, "x".toByteArray()))
        assertNull(s.onChunk("f2", 0, ByteArray(128 * 1024)))
        assertFalse(
            "un trozo rechazado no debe crear ni el directorio",
            File(tmp.root, "krypta_files/staging/f2").exists(),
        )
    }

    @Test
    fun `un trozo fuera del total anunciado se descarta`() = runBlocking {
        val s = store()
        s.onMeta("f3", meta) // 3 trozos
        assertNull(s.onChunk("f3", 7, "x".toByteArray()))
        assertFalse(File(tmp.root, "krypta_files/staging/f3/7.chunk").exists())
    }

    @Test
    fun `el staging abandonado se limpia y el reciente no`() = runBlocking {
        val s = store()
        s.onMeta("viejo", meta)
        s.onChunk("viejo", 0, chunks[0])
        s.onMeta("nuevo", meta)

        val viejo = File(tmp.root, "krypta_files/staging/viejo")
        val nuevo = File(tmp.root, "krypta_files/staging/nuevo")
        val hace48h = System.currentTimeMillis() - 48L * 60 * 60 * 1000
        viejo.listFiles()!!.forEach { it.setLastModified(hace48h) }
        viejo.setLastModified(hace48h)

        s.sweepStaging()

        assertFalse("una transferencia abandonada no debe quedarse para siempre", viejo.exists())
        assertTrue("una transferencia en curso no se toca", nuevo.exists())
    }
}

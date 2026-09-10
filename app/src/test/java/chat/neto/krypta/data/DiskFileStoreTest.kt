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

    /**
     * Almacén **con cifrado en reposo**, que es como corre en el móvil. La envoltura de
     * mentira (XOR) sustituye al Keystore, que no existe en la JVM; el AES-GCM del almacén es
     * el de verdad.
     */
    private fun store() = DiskFileStore(File(tmp.root, "krypta_files"), vault())

    /**
     * Las preferencias son **una sola para todo el test**, como en el móvil: la clave de los
     * adjuntos vive entre arranques. Con unas nuevas por instancia, un almacén no podría abrir
     * lo que escribió el anterior — que es justo el caso de "muere el proceso" de más abajo.
     */
    private val prefs = object : chat.neto.krypta.data.crypto.KeyPrefs {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    private fun vault() = FileVault(
        prefs = prefs,
        vault = object : chat.neto.krypta.data.crypto.KeyVault {
            override fun wrap(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
            override fun unwrap(wrapped: ByteArray) = wrap(wrapped)
        },
    )

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
        assertArrayEquals("0123456789".toByteArray(), s.read(f.path))
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
        assertArrayEquals("0123456789".toByteArray(), second.read(f.path))
    }

    @Test
    fun `redelivered chunk is idempotent`() = runBlocking {
        val s = store()
        assertNull(s.onMeta("f3", meta))
        assertNull(s.onChunk("f3", 0, chunks[0]))
        assertNull(s.onChunk("f3", 0, chunks[0])) // reentrega (ack perdido): inocua
        assertNull(s.onChunk("f3", 1, chunks[1]))
        val f = s.onChunk("f3", 2, chunks[2])!!
        assertArrayEquals("0123456789".toByteArray(), s.read(f.path))
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
        // Se reescribe **en claro** y con 4 líneas: cubre a la vez el formato anterior (sin
        // cita) y un staging escrito antes de que los adjuntos se cifraran.
        val enClaro = String(vault().open(metaFile.readBytes())).lines()
        metaFile.writeText(enClaro.take(4).joinToString("\n", postfix = "\n"))

        assertNull(s.onChunk("f-vieja", 0, chunks[0]))
        assertNull(s.onChunk("f-vieja", 1, chunks[1]))
        val f = s.onChunk("f-vieja", 2, chunks[2])!!
        assertNull(f.replyTo)
        assertArrayEquals("0123456789".toByteArray(), s.read(f.path))
    }

    @Test
    fun `sanitizes hostile file names and ids`() = runBlocking {
        val s = store()
        val evil = IncomingFileMeta("../../evil.sh", "text/plain", 4L, 1)
        assertNull(s.onMeta("../f4", evil))
        val f = s.onChunk("../f4", 0, "data".toByteArray())!!

        val base = File(tmp.root, "krypta_files").canonicalFile
        assertTrue(File(f.path).canonicalPath.startsWith(base.path)) // no escapa del dir
        assertArrayEquals("data".toByteArray(), s.read(f.path))
    }

    @Test
    fun `meta with single chunk assembles immediately after chunk`() = runBlocking {
        val s = store()
        val one = IncomingFileMeta("nota.m4a", "audio/mp4", 3L, 1)
        assertNull(s.onChunk("f5", 0, "abc".toByteArray()))
        val f = s.onMeta("f5", one)!!
        assertEquals("audio/mp4", f.mime)
        assertArrayEquals("abc".toByteArray(), s.read(f.path))
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

    // --- cifrado en reposo (fase 8 del ratchet) ---

    @Test
    fun `el archivo ensamblado no queda en claro en el disco`() = runBlocking {
        val s = store()
        s.onMeta("f-cifrado", meta)
        s.onChunk("f-cifrado", 0, chunks[0])
        s.onChunk("f-cifrado", 1, chunks[1])
        val f = s.onChunk("f-cifrado", 2, chunks[2])!!

        val enDisco = File(f.path).readBytes()
        assertFalse(
            "el contenido no puede leerse del fichero tal cual",
            String(enDisco).contains("0123456789"),
        )
        assertTrue("debe llevar la marca del almacén", vault().isSealed(enDisco))
        // Y lo que devuelve el almacén sigue siendo el archivo original.
        assertArrayEquals("0123456789".toByteArray(), s.read(f.path))
    }

    @Test
    fun `los trozos y la meta del staging tampoco quedan en claro`() = runBlocking {
        val s = store()
        s.onMeta("f-staging", meta)
        s.onChunk("f-staging", 0, "0123".toByteArray())

        val dir = File(tmp.root, "krypta_files/staging/f-staging")
        assertTrue(vault().isSealed(File(dir, "meta.txt").readBytes()))
        val trozo = File(dir, "0.chunk").readBytes()
        assertTrue(vault().isSealed(trozo))
        assertFalse(String(trozo).contains("0123"))
    }

    /** Un adjunto de antes del cifrado sigue abriéndose: no se pierde nada al actualizar. */
    @Test
    fun `un adjunto anterior, en claro, se sigue leyendo`() = runBlocking {
        val s = store()
        val viejo = File(tmp.root, "krypta_files/f-viejo/foto.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes("JPEG de toda la vida".toByteArray())
        }
        assertArrayEquals("JPEG de toda la vida".toByteArray(), s.read(viejo.absolutePath))
    }

    @Test
    fun `la copia del emisor se guarda cifrada y se puede volver a leer`() = runBlocking {
        val s = store()
        val path = s.saveSent("nota-voz.m4a", "audio crudo".toByteArray())!!

        assertFalse(String(File(path).readBytes()).contains("audio crudo"))
        assertArrayEquals("audio crudo".toByteArray(), s.read(path))
    }

    /** `read` no puede convertirse en un lector de cualquier fichero del dispositivo. */
    @Test
    fun `read no sale del almacen`() = runBlocking {
        val s = store()
        val fuera = File(tmp.root, "secreto.txt").apply { writeBytes("nada que ver".toByteArray()) }
        assertNull(s.read(fuera.absolutePath))
    }
}

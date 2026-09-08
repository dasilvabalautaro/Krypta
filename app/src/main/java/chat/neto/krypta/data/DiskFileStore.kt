package chat.neto.krypta.data

import android.content.Context
import chat.neto.krypta.core.AssembledFile
import chat.neto.krypta.core.FileStore
import chat.neto.krypta.core.IncomingFileMeta
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reensambla archivos recibidos por trozos. v2: **staging en disco** — cada trozo y la meta
 * se escriben en `<base>/staging/<fileId>/` al llegar, así que una transferencia a medias
 * **sobrevive a la muerte del proceso** (la fragilidad v1 acumulaba en memoria: si el
 * proceso moría con los sobres ya ack'd, el archivo era irrecuperable). Combinado con el
 * ack-tras-persistir del buzón, un trozo perdido se reentrega y el archivo se completa.
 * Cuando están la meta y todos los trozos, se concatena en `<base>/<fileId>/<name>`, se
 * borra el staging y se devuelve el descriptor. Idempotente ante reentregas (reescribir un
 * trozo ya staged es inocuo; un fileId ya ensamblado se reensambla igual si reaparece).
 */
@Singleton
class DiskFileStore(private val baseDir: File) : FileStore {

    @Inject constructor(@ApplicationContext context: Context) :
        this(File(context.filesDir, "krypta_files"))

    private val mutex = Mutex()
    private val stagingRoot get() = File(baseDir, "staging")

    /**
     * Registra la meta de un archivo entrante. **Valida los límites**: el emisor se acota en
     * la UI (8 MB), pero eso no vale de nada aquí — lo que llega viene de otro dispositivo y
     * puede anunciar el tamaño que quiera. Sin este filtro, un contacto (o un cliente con un
     * fallo) podía hacer que el teléfono reservara disco sin techo. Una meta fuera de rango
     * se ignora: no se escribe nada y no se ensambla nunca.
     */
    override suspend fun onMeta(fileId: String, meta: IncomingFileMeta): AssembledFile? = mutex.withLock {
        if (!isSaneMeta(meta)) return@withLock null
        sweepStagingIfDue()
        val dir = stagingDir(fileId)
        writeAtomic(File(dir, META_FILE), encodeMeta(meta))
        tryAssemble(fileId)
    }

    /**
     * Guarda un trozo. Se descartan los índices imposibles y los trozos desmesurados: el
     * índice viene del otro extremo y antes se aceptaba cualquiera ≥ 0, así que un solo
     * `fileId` podía sembrar el disco de ficheros. Si ya hay meta, el índice tiene además que
     * caber en el número de trozos anunciado.
     */
    override suspend fun onChunk(fileId: String, index: Int, bytes: ByteArray): AssembledFile? = mutex.withLock {
        if (index < 0 || index >= MAX_CHUNKS || bytes.size > MAX_CHUNK_BYTES) return@withLock null
        // Lectura de la meta SIN crear el directorio: un trozo que se va a rechazar no debe
        // dejar rastro en disco.
        val announced = runCatching { decodeMeta(File(stagingPath(fileId), META_FILE).readBytes()) }.getOrNull()
        if (announced != null && index >= announced.totalChunks) return@withLock null
        sweepStagingIfDue()
        val dir = stagingDir(fileId)
        writeAtomic(File(dir, "$index$CHUNK_EXT"), bytes)
        tryAssemble(fileId)
    }

    /** ¿La meta anunciada cabe en los límites de lo que Krypta puede enviar? */
    private fun isSaneMeta(meta: IncomingFileMeta): Boolean =
        meta.size > 0 && meta.size <= MAX_FILE_BYTES &&
            meta.totalChunks in 1..MAX_CHUNKS &&
            // Un trozo no pasa del límite del buzón, así que N trozos no pueden traer más de
            // N·64 KiB: una meta que prometa más es incoherente.
            meta.size <= meta.totalChunks.toLong() * MAX_CHUNK_BYTES

    /**
     * Borra el staging de transferencias que se quedaron a medias hace mucho. Una transferencia
     * que nunca se completa (el emisor desaparece, o manda una meta y ningún trozo) dejaba sus
     * trozos en disco **para siempre**. Se ejecuta como mucho una vez por hora, aprovechando
     * que ya llegó algo (no hace falta un temporizador propio).
     */
    internal fun sweepStaging(now: Long = System.currentTimeMillis()) {
        val dirs = stagingRoot.listFiles() ?: return
        for (dir in dirs) {
            if (dir.isDirectory && now - newestMtime(dir) > STAGING_TTL_MS) dir.deleteRecursively()
        }
    }

    /** mtime del fichero más reciente del staging de un archivo (0 si está vacío). */
    private fun newestMtime(dir: File): Long =
        dir.listFiles()?.maxOfOrNull { it.lastModified() } ?: dir.lastModified()

    private var lastSweep = 0L

    private fun sweepStagingIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastSweep < SWEEP_EVERY_MS) return
        lastSweep = now
        runCatching { sweepStaging(now) }
    }

    override suspend fun deleteLocal(fileId: String, path: String?) = mutex.withLock {
        File(stagingRoot, sanitize(fileId)).deleteRecursively()
        File(baseDir, sanitize(fileId)).deleteRecursively()
        // La copia local (p. ej. nota de voz en sent/): solo si vive dentro del almacén —
        // el path viene de un descriptor persistido y no debe poder borrar nada externo.
        path?.let { File(it) }
            ?.takeIf { it.canonicalPath.startsWith(baseDir.canonicalPath + File.separator) }
            ?.delete()
        Unit
    }

    /** Si hay meta y todos los trozos en el staging, concatena, limpia y devuelve el archivo. */
    private fun tryAssemble(fileId: String): AssembledFile? {
        val dir = stagingPath(fileId)
        val meta = runCatching { decodeMeta(File(dir, META_FILE).readBytes()) }.getOrNull() ?: return null
        val chunks = (0 until meta.totalChunks).map { File(dir, "$it$CHUNK_EXT") }
        if (!chunks.all { it.isFile }) return null
        val outDir = File(baseDir, sanitize(fileId)).apply { mkdirs() }
        val out = File(outDir, sanitize(meta.name))
        val tmp = File(outDir, ".${out.name}.tmp")
        tmp.outputStream().use { os ->
            for (chunk in chunks) chunk.inputStream().use { it.copyTo(os) }
        }
        check(tmp.renameTo(out) || (out.delete() && tmp.renameTo(out))) { "no se pudo escribir ${out.name}" }
        dir.deleteRecursively()
        return AssembledFile(meta.name, meta.mime, meta.size, out.absolutePath, meta.replyTo)
    }

    /** Ruta del staging de un archivo, **sin** crearla. */
    private fun stagingPath(fileId: String): File = File(stagingRoot, sanitize(fileId))

    private fun stagingDir(fileId: String): File = stagingPath(fileId).apply { mkdirs() }

    /** Escritura atómica (tmp + rename): nunca queda un trozo/meta a medio escribir. */
    private fun writeAtomic(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeBytes(bytes)
        check(tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))) {
            "no se pudo persistir ${target.name}"
        }
    }

    // Meta como texto por líneas (name/mime en Base64 por si traen saltos/raros). Se usa
    // java.util.Base64 (no android.util) para que la clase corra en unit tests JVM.
    private fun encodeMeta(meta: IncomingFileMeta): ByteArray = buildString {
        appendLine(java.util.Base64.getEncoder().encodeToString(meta.name.toByteArray()))
        appendLine(java.util.Base64.getEncoder().encodeToString(meta.mime.toByteArray()))
        appendLine(meta.size)
        appendLine(meta.totalChunks)
        appendLine(meta.replyTo.orEmpty())
    }.toByteArray()

    private fun decodeMeta(bytes: ByteArray): IncomingFileMeta {
        val lines = String(bytes).lines()
        return IncomingFileMeta(
            name = String(java.util.Base64.getDecoder().decode(lines[0])),
            mime = String(java.util.Base64.getDecoder().decode(lines[1])),
            size = lines[2].toLong(),
            totalChunks = lines[3].toInt(),
            // 5ª línea (cita) añadida después: una meta ya en staging de una versión anterior
            // no la trae, y una transferencia a medias debe seguir completándose.
            replyTo = lines.getOrNull(4)?.ifBlank { null },
        )
    }

    /** Evita rutas fuera del directorio (path traversal) desde el nombre/fileId remoto. */
    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\]"), "_")
            .let { if (it.isBlank() || it == "." || it == "..") "archivo" else it }

    private companion object {
        const val META_FILE = "meta.txt"
        const val CHUNK_EXT = ".chunk"
        /** Tope de recepción, con margen sobre el de envío (ChatViewModel.MAX_FILE_BYTES = 8 MB). */
        const val MAX_FILE_BYTES = 12L * 1024 * 1024
        /** Un trozo nunca pasa del blob máximo del buzón (64 KiB); los de Krypta son de 48 KiB. */
        const val MAX_CHUNK_BYTES = 64 * 1024
        /** Trozos por archivo: 8 MB a 48 KiB son 171; 512 deja aire de sobra. */
        const val MAX_CHUNKS = 512
        /** Un staging sin tocar durante este tiempo es basura de una transferencia abortada. */
        const val STAGING_TTL_MS = 24L * 60 * 60 * 1000
        const val SWEEP_EVERY_MS = 60L * 60 * 1000
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FileStoreModule {
    @Binds
    abstract fun bindFileStore(impl: DiskFileStore): FileStore
}

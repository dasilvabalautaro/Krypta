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

    override suspend fun onMeta(fileId: String, meta: IncomingFileMeta): AssembledFile? = mutex.withLock {
        val dir = stagingDir(fileId)
        writeAtomic(File(dir, META_FILE), encodeMeta(meta))
        tryAssemble(fileId)
    }

    override suspend fun onChunk(fileId: String, index: Int, bytes: ByteArray): AssembledFile? = mutex.withLock {
        require(index >= 0) { "índice de trozo negativo" }
        val dir = stagingDir(fileId)
        writeAtomic(File(dir, "$index$CHUNK_EXT"), bytes)
        tryAssemble(fileId)
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
        val dir = stagingDir(fileId)
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

    private fun stagingDir(fileId: String): File =
        File(stagingRoot, sanitize(fileId)).apply { mkdirs() }

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
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FileStoreModule {
    @Binds
    abstract fun bindFileStore(impl: DiskFileStore): FileStore
}

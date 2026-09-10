package chat.neto.krypta.ui

import android.content.Context
import android.media.MediaDataSource
import androidx.compose.runtime.staticCompositionLocalOf
import java.io.File

/**
 * Lector de adjuntos para la UI. Desde que `krypta_files/` va **cifrado en reposo**, ninguna
 * pantalla puede abrir el fichero por su cuenta: tiene que pedir los bytes aquí, y quien
 * descifra es el almacén (`FileStore`).
 *
 * Va como `CompositionLocal` porque las burbujas de nota de voz y de GIF están enterradas en el
 * árbol de composición y pasarles el lector por parámetro a través de media pantalla no aporta
 * nada. Lo provee `ChatScreen` desde el ViewModel.
 */
val LocalAttachmentReader = staticCompositionLocalOf<suspend (String) -> ByteArray?> {
    { null }
}

/**
 * Fuente de datos en memoria para `MediaPlayer`.
 *
 * Una nota de voz ya no se puede reproducir con `setDataSource(path)`: el fichero está cifrado
 * y `MediaPlayer` no sabría qué hacer con él. Con `MediaDataSource` (API 23+) se le sirven los
 * bytes ya descifrados sin dejar una copia en claro en disco, que es justo lo que este trabajo
 * viene a evitar. Caben en memoria de sobra: una nota de voz de un minuto son ~360 KB.
 */
class BytesMediaSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= bytes.size) return -1
        val n = minOf(size.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        return n
    }

    override fun getSize(): Long = bytes.size.toLong()

    override fun close() = Unit
}

/**
 * Deja un adjunto **en claro** en la caché para poder abrirlo con otra aplicación, y devuelve
 * el fichero.
 *
 * No hay forma de evitarlo: abrir un archivo con un visor externo significa dárselo en claro,
 * así que el cifrado en reposo protege el almacén de Krypta, no lo que el usuario decida sacar
 * de él. Se escribe en `cacheDir/krypta_abrir/`, que [clearOpened] vacía al volver a la app, y
 * el sistema puede borrar por su cuenta.
 */
fun stageForExternalApp(context: Context, name: String, bytes: ByteArray): File? = runCatching {
    val dir = File(context.cacheDir, OPEN_DIR).apply { mkdirs() }
    File(dir, name.replace(Regex("[/\\\\]"), "_")).apply { writeBytes(bytes) }
}.getOrNull()

/** Vacía las copias en claro que se dejaron para abrir con otra app. */
fun clearOpened(context: Context) {
    runCatching { File(context.cacheDir, OPEN_DIR).deleteRecursively() }
}

private const val OPEN_DIR = "krypta_abrir"

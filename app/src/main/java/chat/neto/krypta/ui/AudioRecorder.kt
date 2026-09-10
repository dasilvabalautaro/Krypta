package chat.neto.krypta.ui

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Grabadora de notas de voz (MediaRecorder → AAC en contenedor MP4).
 *
 * Graba en la **caché**, no en el almacén: `MediaRecorder` escribe en claro y no hay forma de
 * interponerse, así que la grabación vive ahí lo que tarda en enviarse y `ChatViewModel` la
 * mueve al almacén ya cifrada, borrando el temporal. Si se cancela o falla, el archivo se
 * borra. Una instancia = una grabación.
 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var output: File? = null

    /** Empieza a grabar. Lanza si el micro no está disponible (la UI lo captura). */
    fun start() {
        check(recorder == null) { "ya grabando" }
        val dir = File(context.cacheDir, "krypta_rec").apply { mkdirs() }
        val file = File(dir, "nota-voz-${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(48_000) // voz: ~21 MB/h, de sobra bajo el límite de 8 MB
            r.setMaxFileSize(MAX_BYTES)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            file.delete()
            throw e
        }
        recorder = r
        output = file
    }

    /** Para y devuelve el archivo grabado, o null si quedó vacío/corrupto (p. ej. <1s). */
    fun stop(): File? {
        val r = recorder ?: return null
        val file = output
        recorder = null
        output = null
        val ok = runCatching { r.stop() }.isSuccess // stop() lanza si no llegó a grabar nada
        r.release()
        if (!ok || file == null || file.length() == 0L) {
            file?.delete()
            return null
        }
        return file
    }

    /** Cancela la grabación y borra el archivo. */
    fun cancel() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        r.release()
        output?.delete()
        output = null
    }

    private companion object {
        // Margen bajo el límite de envío de 8 MB (ChatViewModel.MAX_FILE_BYTES).
        const val MAX_BYTES = 7L * 1024 * 1024
    }
}

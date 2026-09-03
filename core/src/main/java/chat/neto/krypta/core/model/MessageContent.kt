package chat.neto.krypta.core.model

/**
 * Contenido de un mensaje ya descifrado, listo para pintar. Distingue el tipo (texto vs
 * imagen…) sin exponer el formato de transporte. Preparado para más tipos (archivo, audio).
 */
sealed interface MessageContent {
    data class Text(val text: String) : MessageContent
    /** Imagen JPEG (bytes ya descifrados). */
    data class Image(val jpeg: ByteArray) : MessageContent
    /**
     * Archivo adjunto. [localPath] apunta al fichero ya ensamblado en disco (null si aún no
     * está disponible localmente, p. ej. la copia del emisor). Abrir requiere [localPath].
     */
    data class File(
        val name: String,
        val mime: String,
        val size: Long,
        val localPath: String?,
    ) : MessageContent
}

/**
 * Mensaje descifrado listo para pintar: su [content] y, si es una **respuesta**, el id del
 * mensaje citado en [replyTo]. La cita va fuera de [MessageContent] porque es ortogonal al
 * tipo: se puede responder con texto, con una foto o con una nota de voz.
 *
 * [replyTo] es solo un id: quien pinta resuelve la cita contra su propia base de mensajes
 * (si ya no está —chat vaciado, o aún no ha llegado— se pinta como no disponible).
 */
data class DecodedMessage(val content: MessageContent, val replyTo: String? = null)

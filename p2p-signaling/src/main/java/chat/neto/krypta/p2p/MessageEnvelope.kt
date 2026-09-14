package chat.neto.krypta.p2p

/**
 * Sobre de aplicación que viaja **dentro** del cifrado E2EE (el nodo/relay nunca lo ve). Lleva
 * el **id del mensaje del emisor**, para que el receptor pueda acusar recibo/lectura citándolo
 * (marca de leído), y un **tipo**, que abre la puerta a futuros contenidos (media). Formato
 * texto plano por líneas (va cifrado, así que su estructura no filtra nada):
 *
 *   TEXT:  "T\n<id>\n" ++ <cuerpo en bytes>     (el cuerpo puede contener cualquier byte)
 *   READ:  "R\n<id1>\n<id2>\n…"                 (acuse de lectura de esos ids)
 *   IMAGE: "I\n<id>\n" ++ <bytes JPEG>          (imagen comprimida en línea)
 *   FMETA: "F\n<fileId>\n<total>\n<size>\n<mime>\n<name>"  (anuncia un archivo troceado)
 *   FCHNK: "K\n<fileId>\n<index>\n" ++ <bytes del trozo>   (un trozo del archivo)
 *   FDESC: "D\n<size>\n<mime>\n<path>\n<name>"  (descriptor LOCAL del archivo; nunca se envía)
 *   CALL:  "C\n<kind>\n<callId>\n<ts>[\n<mitad de clave en hex>]"  (señalización de llamada:
 *          invite/accept/reject/hangup/busy; ts = unix millis del emisor, para descartar
 *          invites rancios). La quinta línea es **opcional**: la mitad aleatoria de la clave
 *          de esa llamada (ver `CallService`). Solo se le manda a quien haya anunciado que la
 *          entiende — un cliente anterior parte esta cabecera en 3 y descartaría la señal
 *          entera, o sea que la llamada ni sonaría.
 *   REPLY: "Y\n<replyToId>\n" ++ <sobre interior>   (cita: envuelve a T/I/F/D)
 *   HELLO: "V\n<versión>[\n<la versión que tengo apuntada de ti>]"  (anuncio de capacidad)
 *
 * REPLY es un **envoltorio**, no un tipo de contenido: cita el mensaje [replyToId] y dentro
 * lleva el sobre normal del mensaje que responde. Así responder funciona con cualquier
 * contenido (texto, foto, archivo, nota de voz) sin duplicar un tipo por cada uno. Viaja
 * **solo el id**, nunca una copia del mensaje citado: los dos extremos guardan cada mensaje
 * con el mismo id (el que va en el sobre), así que cada uno resuelve la cita contra su propia
 * base — y una cita no puede resucitar contenido que el otro ya borró.
 *
 * Decodificar tolera bytes sin sobre (mensajes previos a esta versión) devolviendo null, para
 * que la capa superior los trate como texto legado. Un sobre con un tipo **desconocido** (una
 * versión futura) devuelve [Decoded.Unsupported] en vez de null, para no pintar su cabecera
 * cruda como si fuera texto.
 */
object MessageEnvelope {

    private const val NL = '\n'.code.toByte()

    sealed interface Decoded {
        /** Mensaje de chat: [id] del emisor + [body] (texto/plano en bytes). */
        data class Text(val id: String, val body: ByteArray) : Decoded
        /** Acuse de lectura de los mensajes [ids]. */
        data class Read(val ids: List<String>) : Decoded
        /** Imagen: [id] del emisor + [bytes] JPEG. */
        data class Image(val id: String, val bytes: ByteArray) : Decoded
        /** Meta de un archivo troceado. */
        data class FileMeta(
            val fileId: String, val name: String, val mime: String,
            val size: Long, val totalChunks: Int,
        ) : Decoded
        /** Un trozo de un archivo. */
        data class FileChunk(val fileId: String, val index: Int, val bytes: ByteArray) : Decoded
        /** Descriptor LOCAL de un archivo ya recibido/enviado (no viaja por la red). */
        data class FileDescriptor(
            val name: String, val mime: String, val size: Long, val path: String?,
        ) : Decoded
        /**
         * Señal de llamada: [kind] ∈ invite/accept/reject/hangup/busy, [ts] = unix millis.
         * [key] es la mitad de la clave de esa llamada que aporta quien envía la señal (solo
         * en invite y accept, y solo hacia clientes que la entienden); null = camino antiguo,
         * con la clave derivada del secreto estático.
         */
        data class Call(
            val kind: String,
            val callId: String,
            val ts: Long,
            val key: ByteArray? = null,
        ) : Decoded {
            override fun equals(other: Any?) = other is Call && kind == other.kind &&
                callId == other.callId && ts == other.ts &&
                (key?.contentEquals(other.key) ?: (other.key == null))

            override fun hashCode(): Int {
                var r = kind.hashCode()
                r = 31 * r + callId.hashCode()
                r = 31 * r + ts.hashCode()
                r = 31 * r + (key?.contentHashCode() ?: 0)
                return r
            }
        }
        /**
         * Cita: [inner] es el mensaje que se envía, [replyTo] el id del mensaje citado.
         * Nunca anida otra [Reply] (decodificar una respuesta dentro de otra da null).
         */
        data class Reply(val replyTo: String, val inner: Decoded) : Decoded
        /**
         * Anuncio de capacidad: el contacto dice qué versión de protocolo habla. Sirve para
         * encender el ratchet **por contacto** en vez de por publicación (ver
         * `docs/DISENO-ratchet.md` §5): un cliente anterior lo recibe como [Unsupported] y lo
         * ignora sin pintar nada, que es justo lo que hace falta para poder anunciarlo ya.
         *
         * [knows] es la versión que el que lo envía **tiene apuntada de nosotros** (null = un
         * cliente anterior al 14 sep 2026, que no la manda). Existe por el hallazgo H-1 de
         * `docs/REVISION-protocolo-2026-09-14.md`: el anuncio sale una sola vez por versión, así
         * que quien lo pierde (borró el contacto y lo volvió a añadir, importó un `.krbk`) no
         * tenía forma de volver a saberlo, y el otro no tenía forma de enterarse de que hacía
         * falta repetirlo. Un cliente anterior lee solo la primera línea, así que no le afecta.
         */
        data class Hello(val protocol: Int, val knows: Int? = null) : Decoded

        /** Sobre bien formado de un tipo que esta versión no conoce (cliente más nuevo). */
        data object Unsupported : Decoded
    }

    fun encodeText(id: String, body: ByteArray): ByteArray =
        "T\n$id\n".toByteArray(Charsets.UTF_8) + body

    fun encodeRead(ids: List<String>): ByteArray =
        ("R\n" + ids.joinToString("\n")).toByteArray(Charsets.UTF_8)

    fun encodeImage(id: String, jpeg: ByteArray): ByteArray =
        "I\n$id\n".toByteArray(Charsets.UTF_8) + jpeg

    fun encodeFileMeta(fileId: String, name: String, mime: String, size: Long, totalChunks: Int): ByteArray =
        "F\n$fileId\n$totalChunks\n$size\n$mime\n$name".toByteArray(Charsets.UTF_8)

    fun encodeFileChunk(fileId: String, index: Int, bytes: ByteArray): ByteArray =
        "K\n$fileId\n$index\n".toByteArray(Charsets.UTF_8) + bytes

    fun encodeFileDescriptor(name: String, mime: String, size: Long, path: String?): ByteArray =
        "D\n$size\n$mime\n${path ?: ""}\n$name".toByteArray(Charsets.UTF_8)

    /**
     * Anuncia a un contacto qué versión de protocolo habla este cliente y, si se pasa [knows],
     * qué versión tenemos apuntada de él (ver [Decoded.Hello.knows]).
     */
    fun encodeHello(protocol: Int, knows: Int? = null): ByteArray =
        ("V\n$protocol" + (knows?.let { "\n$it" } ?: "")).toByteArray(Charsets.UTF_8)

    fun encodeCall(kind: String, callId: String, ts: Long, key: ByteArray? = null): ByteArray =
        ("C\n$kind\n$callId\n$ts" + (key?.let { "\n" + it.toHex() } ?: ""))
            .toByteArray(Charsets.UTF_8)

    /** Envuelve [inner] (un sobre ya codificado) como respuesta al mensaje [replyTo]. */
    fun encodeReply(replyTo: String, inner: ByteArray): ByteArray =
        "Y\n$replyTo\n".toByteArray(Charsets.UTF_8) + inner

    /** [inner] envuelto como respuesta si [replyTo] no es nulo/vacío; si no, tal cual. */
    fun wrapReply(replyTo: String?, inner: ByteArray): ByteArray =
        if (replyTo.isNullOrBlank()) inner else encodeReply(replyTo, inner)

    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size < 2 || bytes[1] != NL) return null
        return when (bytes[0].toInt().toChar()) {
            'T' -> decodeIdAndBody(bytes)?.let { (id, body) -> Decoded.Text(id, body) }
            'I' -> decodeIdAndBody(bytes)?.let { (id, body) -> Decoded.Image(id, body) }
            'R' -> {
                val rest = String(bytes, 2, bytes.size - 2, Charsets.UTF_8)
                Decoded.Read(rest.split("\n").filter { it.isNotBlank() })
            }
            'F' -> {
                // F\n<fileId>\n<total>\n<size>\n<mime>\n<name>  (todo texto; name puede llevar '\n')
                val parts = String(bytes, 2, bytes.size - 2, Charsets.UTF_8).split("\n", limit = 5)
                if (parts.size < 5) return null
                val total = parts[1].toIntOrNull() ?: return null
                val size = parts[2].toLongOrNull() ?: return null
                Decoded.FileMeta(parts[0], parts[4], parts[3], size, total)
            }
            'K' -> {
                // K\n<fileId>\n<index>\n<bytes>  (fileId/index texto; bytes binarios)
                val idEnd = bytes.indexOf(NL, from = 2)
                if (idEnd < 0) return null
                val idxEnd = bytes.indexOf(NL, from = idEnd + 1)
                if (idxEnd < 0) return null
                val fileId = String(bytes, 2, idEnd - 2, Charsets.UTF_8)
                val index = String(bytes, idEnd + 1, idxEnd - idEnd - 1, Charsets.UTF_8).toIntOrNull() ?: return null
                Decoded.FileChunk(fileId, index, bytes.copyOfRange(idxEnd + 1, bytes.size))
            }
            'V' -> {
                // V\n<versión>[\n<la que tengo apuntada de ti>]. La versión es la primera línea
                // y es lo único obligatorio: un cliente anterior solo lee esa. Lo que no se
                // entienda en la segunda (o lo que añada una versión futura detrás) se ignora
                // sin que el sobre deje de entenderse.
                val lines = String(bytes, 2, bytes.size - 2, Charsets.UTF_8).split('\n')
                val protocol = lines[0].trim().toIntOrNull() ?: return null
                Decoded.Hello(protocol, knows = lines.getOrNull(1)?.trim()?.toIntOrNull())
            }
            'C' -> {
                // C\n<kind>\n<callId>\n<ts>[\n<clave hex>]
                val parts = String(bytes, 2, bytes.size - 2, Charsets.UTF_8).split("\n", limit = 4)
                if (parts.size < 3) return null
                val ts = parts[2].toLongOrNull() ?: return null
                if (parts[0].isBlank() || parts[1].isBlank()) return null
                // Una clave ilegible no invalida la señal: se trata como si no viniera y la
                // llamada cae al camino antiguo, que es preferible a no timbrar.
                Decoded.Call(parts[0], parts[1], ts, parts.getOrNull(3)?.let(::fromHex))
            }
            'D' -> {
                // D\n<size>\n<mime>\n<path>\n<name>
                val parts = String(bytes, 2, bytes.size - 2, Charsets.UTF_8).split("\n", limit = 4)
                if (parts.size < 4) return null
                val size = parts[0].toLongOrNull() ?: return null
                Decoded.FileDescriptor(parts[3], parts[1], size, parts[2].ifBlank { null })
            }
            'Y' -> {
                // Y\n<replyToId>\n<sobre interior>. Una respuesta no puede envolver a otra
                // (ni a un sobre ilegible): sin esto la recursión no tendría fondo.
                val idEnd = bytes.indexOf(NL, from = 2)
                if (idEnd < 0) return null
                val replyTo = String(bytes, 2, idEnd - 2, Charsets.UTF_8)
                if (replyTo.isBlank()) return null
                val inner = decode(bytes.copyOfRange(idEnd + 1, bytes.size))
                if (inner == null || inner is Decoded.Reply) return null
                Decoded.Reply(replyTo, inner)
            }
            // Sobre con forma válida pero tipo que esta versión no conoce (p. ej. un cliente
            // más nuevo): no es texto legado, así que no se pinta su cabecera cruda.
            in 'A'..'Z' -> Decoded.Unsupported
            else -> null
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** Hex → bytes; null si no es hexadecimal par (una señal con basura no debe romper nada). */
    private fun fromHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0 || hex.isEmpty()) return null
        return runCatching {
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }

    /** Parsea el prefijo común `X\n<id>\n<body>` → (id, body). */
    private fun decodeIdAndBody(bytes: ByteArray): Pair<String, ByteArray>? {
        val idEnd = bytes.indexOf(NL, from = 2)
        if (idEnd < 0) return null
        val id = String(bytes, 2, idEnd - 2, Charsets.UTF_8)
        if (id.isBlank()) return null
        return id to bytes.copyOfRange(idEnd + 1, bytes.size)
    }

    private fun ByteArray.indexOf(b: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == b) return i
        return -1
    }
}

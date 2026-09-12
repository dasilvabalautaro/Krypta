package chat.neto.krypta.p2p

import java.io.ByteArrayOutputStream

/**
 * Estado del ratchet de una conversación (ver [docs/DISENO-ratchet.md] §1).
 *
 * Es **inmutable a propósito**: [Ratchet.encrypt] y [Ratchet.decrypt] son funciones puras
 * `estado → (estado', bytes)` y no tocan nada por su cuenta. De ahí salen dos propiedades que
 * el diseño necesita:
 *
 * - El avance del ratchet se puede **confirmar en la misma transacción** que la persistencia del
 *   mensaje (§4.2). Si el proceso muere entre medias no se guarda ninguno de los dos, y la
 *   reentrega del buzón sigue siendo legible. Con un estado mutable, descifrar y morir después
 *   perdería el mensaje para siempre.
 * - Una cabecera **forjada** no puede mover nada: el estado nuevo solo existe si el AEAD ha
 *   validado, porque quien lo recibe es quien decide guardarlo.
 *
 * Lo que **no** guarda es el secreto compartido: se deriva por ECDH cuando hace falta (misma
 * razón por la que se sacó de la base en la v6). Se pasa como parámetro a [Ratchet.decrypt].
 */
data class RatchetState(
    /** Linaje: unix millis de cuándo nació esta sesión. El mayor gana (§1.6). */
    val lineage: Long,
    /** Época actual: 0 = derivable del secreto compartido; ≥ 1 = con material efímero. */
    val epoch: Int,
    /** Sentido de **mi** cadena de envío (0/1), del orden canónico de los dos PeerID. */
    val sendDir: Int,
    /** `RK(epoch)`. */
    val rootKey: ByteArray,
    val sendChain: ByteArray,
    val sendN: Int,
    /** Mensajes que hubo en mi cadena de envío de la época anterior (el `PN` de la cabecera). */
    val sendPN: Int,
    val recvChain: ByteArray,
    val recvN: Int,
    /** Mi pública de esta época (32 ceros en la época 0, que no tiene material efímero). */
    val myCurPub: ByteArray,
    /** Mi propuesta para la época siguiente; la privada se borra al consumirla. */
    val nextPriv: ByteArray,
    val nextPub: ByteArray,
    /** Su propuesta para la época siguiente, si ya ha llegado. Con las dos, se avanza. */
    val peerNextPub: ByteArray?,
    /** Cadenas de recepción retiradas (épocas/linajes anteriores) para lo que llegue tarde. */
    val past: List<PastChain>,
    /** Claves de mensajes saltados, acotadas. */
    val skipped: List<SkippedKey>,
) {

    /** Cadena de recepción de una época ya abandonada, viva mientras quepa algo en vuelo. */
    data class PastChain(
        val lineage: Long,
        val epoch: Int,
        val chainKey: ByteArray,
        val n: Int,
    ) {
        override fun equals(other: Any?) = other is PastChain &&
            lineage == other.lineage && epoch == other.epoch && n == other.n &&
            chainKey.contentEquals(other.chainKey)

        override fun hashCode(): Int =
            (((lineage.hashCode() * 31) + epoch) * 31 + n) * 31 + chainKey.contentHashCode()
    }

    /** Clave de un mensaje que se saltó (llegó uno posterior antes). */
    data class SkippedKey(
        val lineage: Long,
        val epoch: Int,
        val n: Int,
        val messageKey: ByteArray,
    ) {
        override fun equals(other: Any?) = other is SkippedKey &&
            lineage == other.lineage && epoch == other.epoch && n == other.n &&
            messageKey.contentEquals(other.messageKey)

        override fun hashCode(): Int =
            (((lineage.hashCode() * 31) + epoch) * 31 + n) * 31 + messageKey.contentHashCode()
    }

    // Igualdad por contenido codificado: los ByteArray comparan por identidad, y este objeto
    // se compara en tests y se guarda en la base como un blob.
    override fun equals(other: Any?) = other is RatchetState && encode().contentEquals(other.encode())

    override fun hashCode(): Int = encode().contentHashCode()

    override fun toString(): String =
        "RatchetState(lineage=$lineage, epoch=$epoch, sendN=$sendN, recvN=$recvN, " +
            "past=${past.size}, skipped=${skipped.size})"

    /** Serializa el estado para guardarlo (va dentro de la base cifrada). */
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(FORMAT_VERSION)
        out.writeLong(lineage)
        out.writeInt(epoch)
        out.write(sendDir)
        out.writeBlob(rootKey)
        out.writeBlob(sendChain)
        out.writeInt(sendN)
        out.writeInt(sendPN)
        out.writeBlob(recvChain)
        out.writeInt(recvN)
        out.writeBlob(myCurPub)
        out.writeBlob(nextPriv)
        out.writeBlob(nextPub)
        out.writeBlob(peerNextPub ?: ByteArray(0))
        out.writeInt(past.size)
        for (p in past) {
            out.writeLong(p.lineage); out.writeInt(p.epoch); out.writeBlob(p.chainKey); out.writeInt(p.n)
        }
        out.writeInt(skipped.size)
        for (s in skipped) {
            out.writeLong(s.lineage); out.writeInt(s.epoch); out.writeInt(s.n); out.writeBlob(s.messageKey)
        }
        return out.toByteArray()
    }

    companion object {
        private const val FORMAT_VERSION = 1

        /** Lee un estado serializado por [encode]; lanza si el blob no es de esta versión. */
        fun decode(bytes: ByteArray): RatchetState {
            val r = Reader(bytes)
            val version = r.byte()
            require(version == FORMAT_VERSION) { "estado de ratchet versión $version desconocida" }
            val lineage = r.long()
            val epoch = r.int()
            val sendDir = r.byte()
            val rootKey = r.blob()
            val sendChain = r.blob()
            val sendN = r.int()
            val sendPN = r.int()
            val recvChain = r.blob()
            val recvN = r.int()
            val myCurPub = r.blob()
            val nextPriv = r.blob()
            val nextPub = r.blob()
            val peerNext = r.blob().takeIf { it.isNotEmpty() }
            // Los contadores van acotados por lo que queda del blob: `List(n)` reserva `n`
            // huecos ANTES de leer nada, y con un contador corrupto a 2³¹ eso sería un
            // OutOfMemoryError, no un "blob truncado" (cerrado de paso al arreglar `blob()`).
            val past = List(r.count(minBytesEach = PAST_CHAIN_MIN_BYTES)) {
                PastChain(r.long(), r.int(), r.blob(), r.int())
            }
            val skipped = List(r.count(minBytesEach = SKIPPED_KEY_MIN_BYTES)) {
                SkippedKey(r.long(), r.int(), r.int(), r.blob())
            }
            return RatchetState(
                lineage, epoch, sendDir, rootKey, sendChain, sendN, sendPN, recvChain, recvN,
                myCurPub, nextPriv, nextPub, peerNext, past, skipped,
            )
        }

        private fun ByteArrayOutputStream.writeInt(v: Int) {
            write(v ushr 24); write(v ushr 16 and 0xFF); write(v ushr 8 and 0xFF); write(v and 0xFF)
        }

        private fun ByteArrayOutputStream.writeLong(v: Long) {
            writeInt((v ushr 32).toInt()); writeInt(v.toInt())
        }

        private fun ByteArrayOutputStream.writeBlob(b: ByteArray) {
            writeInt(b.size); write(b)
        }

        /** Bytes mínimos de una entrada serializada: long + int + blob vacío (int) + int. */
        private const val PAST_CHAIN_MIN_BYTES = 8 + 4 + 4 + 4
        /** long + int + int + blob vacío (int). */
        private const val SKIPPED_KEY_MIN_BYTES = 8 + 4 + 4 + 4

        private class Reader(private val bytes: ByteArray) {
            private var pos = 0
            fun byte(): Int = bytes[pos++].toInt() and 0xFF
            fun int(): Int = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
            fun long(): Long = (int().toLong() and 0xFFFFFFFFL shl 32) or (int().toLong() and 0xFFFFFFFFL)
            fun blob(): ByteArray {
                val n = int()
                // Ojo a la forma: `pos + n <= bytes.size` desborda con n = 2³¹−1 (la suma
                // sale negativa y pasa), y `copyOfRange` calcula `to − from`, que vuelve a
                // dar n → `new byte[2³¹−1]` → OutOfMemoryError. Lo encontró `ParserFuzzTest`.
                require(n >= 0 && n <= bytes.size - pos) { "estado de ratchet truncado" }
                return bytes.copyOfRange(pos, pos + n).also { pos += n }
            }
            /** Un contador de entradas, que no puede superar las que caben en lo que queda. */
            fun count(minBytesEach: Int): Int {
                val n = int()
                require(n >= 0 && n <= (bytes.size - pos) / minBytesEach) { "estado de ratchet truncado" }
                return n
            }
        }
    }
}

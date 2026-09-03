package chat.neto.krypta.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageEnvelopeTest {

    @Test
    fun `text round-trips id and body (including newlines in body)`() {
        val body = "hola\ncon salto".toByteArray()
        val decoded = MessageEnvelope.decode(MessageEnvelope.encodeText("id-123", body))
        assertTrue(decoded is MessageEnvelope.Decoded.Text)
        decoded as MessageEnvelope.Decoded.Text
        assertEquals("id-123", decoded.id)
        assertArrayEquals(body, decoded.body)
    }

    @Test
    fun `text supports arbitrary bytes in body`() {
        val body = ByteArray(256) { it.toByte() }
        val decoded = MessageEnvelope.decode(MessageEnvelope.encodeText("x", body))
        assertArrayEquals(body, (decoded as MessageEnvelope.Decoded.Text).body)
    }

    @Test
    fun `image round-trips id and jpeg bytes`() {
        val jpeg = ByteArray(500) { (it % 256).toByte() }
        val decoded = MessageEnvelope.decode(MessageEnvelope.encodeImage("img-1", jpeg))
        assertTrue(decoded is MessageEnvelope.Decoded.Image)
        decoded as MessageEnvelope.Decoded.Image
        assertEquals("img-1", decoded.id)
        assertArrayEquals(jpeg, decoded.bytes)
    }

    @Test
    fun `file meta, chunk and descriptor round-trip`() {
        val meta = MessageEnvelope.decode(
            MessageEnvelope.encodeFileMeta("fid", "mi archivo.pdf", "application/pdf", 12345L, 3),
        )
        assertTrue(meta is MessageEnvelope.Decoded.FileMeta)
        meta as MessageEnvelope.Decoded.FileMeta
        assertEquals("fid", meta.fileId)
        assertEquals("mi archivo.pdf", meta.name)
        assertEquals("application/pdf", meta.mime)
        assertEquals(12345L, meta.size)
        assertEquals(3, meta.totalChunks)

        val chunkBytes = ByteArray(200) { (it % 256).toByte() }
        val chunk = MessageEnvelope.decode(MessageEnvelope.encodeFileChunk("fid", 2, chunkBytes))
        chunk as MessageEnvelope.Decoded.FileChunk
        assertEquals("fid", chunk.fileId)
        assertEquals(2, chunk.index)
        assertArrayEquals(chunkBytes, chunk.bytes)

        val desc = MessageEnvelope.decode(
            MessageEnvelope.encodeFileDescriptor("doc.txt", "text/plain", 99L, "/data/doc.txt"),
        )
        desc as MessageEnvelope.Decoded.FileDescriptor
        assertEquals("doc.txt", desc.name)
        assertEquals("/data/doc.txt", desc.path)
        assertEquals(99L, desc.size)
    }

    @Test
    fun `read round-trips the id list`() {
        val decoded = MessageEnvelope.decode(MessageEnvelope.encodeRead(listOf("a", "b", "c")))
        assertEquals(listOf("a", "b", "c"), (decoded as MessageEnvelope.Decoded.Read).ids)
    }

    @Test
    fun `call signal round-trips kind, callId and ts`() {
        val decoded = MessageEnvelope.decode(MessageEnvelope.encodeCall("invite", "call-42", 1_720_000_000_000))
        val call = decoded as MessageEnvelope.Decoded.Call
        assertEquals("invite", call.kind)
        assertEquals("call-42", call.callId)
        assertEquals(1_720_000_000_000, call.ts)
        // Malformados → null (no revientan el decode).
        assertNull(MessageEnvelope.decode("C\ninvite\nsolo-dos-campos".toByteArray()))
        assertNull(MessageEnvelope.decode("C\n\nid\n123".toByteArray()))
    }

    @Test
    fun `reply wraps any envelope and keeps the quoted id`() {
        // Texto: el cuerpo y el id del mensaje sobreviven al envoltorio de cita.
        val text = MessageEnvelope.decode(
            MessageEnvelope.encodeReply("citado-1", MessageEnvelope.encodeText("m1", "hola".toByteArray())),
        )
        val reply = text as MessageEnvelope.Decoded.Reply
        assertEquals("citado-1", reply.replyTo)
        val inner = reply.inner as MessageEnvelope.Decoded.Text
        assertEquals("m1", inner.id)
        assertEquals("hola", String(inner.body))

        // Imagen: bytes binarios intactos (el envoltorio no toca el cuerpo).
        val jpeg = byteArrayOf(1, 0, 10, -3, 7)
        val image = MessageEnvelope.decode(
            MessageEnvelope.encodeReply("citado-2", MessageEnvelope.encodeImage("m2", jpeg)),
        ) as MessageEnvelope.Decoded.Reply
        assertArrayEquals(jpeg, (image.inner as MessageEnvelope.Decoded.Image).bytes)

        // Meta de archivo: así viaja la cita de una foto/nota de voz/GIF troceados.
        val meta = MessageEnvelope.decode(
            MessageEnvelope.encodeReply(
                "citado-3",
                MessageEnvelope.encodeFileMeta("fid", "nota.m4a", "audio/mp4", 1234L, 3),
            ),
        ) as MessageEnvelope.Decoded.Reply
        assertEquals("citado-3", meta.replyTo)
        assertEquals("nota.m4a", (meta.inner as MessageEnvelope.Decoded.FileMeta).name)
    }

    @Test
    fun `wrapReply only wraps when there is a quoted id`() {
        val plain = MessageEnvelope.encodeText("m1", "hola".toByteArray())
        assertArrayEquals(plain, MessageEnvelope.wrapReply(null, plain))
        assertArrayEquals(plain, MessageEnvelope.wrapReply("", plain))
        val wrapped = MessageEnvelope.decode(MessageEnvelope.wrapReply("q", plain))
        assertEquals("q", (wrapped as MessageEnvelope.Decoded.Reply).replyTo)
    }

    @Test
    fun `malformed or nested replies decode to null`() {
        // Sin id citado, sin sobre interior, o con un interior ilegible.
        assertNull(MessageEnvelope.decode("Y\n\nT\nm1\nhola".toByteArray()))
        assertNull(MessageEnvelope.decode("Y\ncitado".toByteArray()))
        assertNull(MessageEnvelope.decode("Y\ncitado\nesto no es un sobre".toByteArray()))
        // Una respuesta no puede envolver a otra: la recursión no tendría fondo.
        assertNull(
            MessageEnvelope.decode(
                MessageEnvelope.encodeReply(
                    "a",
                    MessageEnvelope.encodeReply("b", MessageEnvelope.encodeText("m", "x".toByteArray())),
                ),
            ),
        )
    }

    @Test
    fun `unknown envelope type decodes as unsupported, not as legacy text`() {
        // Un tipo de una versión futura: se reconoce como sobre (no se pinta su cabecera
        // cruda como si fuera texto de un mensaje antiguo).
        assertEquals(
            MessageEnvelope.Decoded.Unsupported,
            MessageEnvelope.decode("Z\nalgo nuevo".toByteArray()),
        )
    }

    @Test
    fun `decode returns null for non-enveloped (legacy) bytes`() {
        assertNull(MessageEnvelope.decode("mensaje viejo sin sobre".toByteArray()))
        assertNull(MessageEnvelope.decode(ByteArray(0)))
        assertNull(MessageEnvelope.decode(byteArrayOf('T'.code.toByte()))) // sin salto
    }
}

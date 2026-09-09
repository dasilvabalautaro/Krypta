package chat.neto.krypta.p2p

import chat.neto.krypta.core.AudioEngine
import chat.neto.krypta.core.CallStream
import chat.neto.krypta.core.ISignalingService
import chat.neto.krypta.core.KeyExchange
import chat.neto.krypta.core.SignalingEvent
import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.core.model.Message
import chat.neto.krypta.core.model.MessageStatus
import chat.neto.krypta.core.repository.ContactRepository
import chat.neto.krypta.core.repository.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueba la orquestación de llamadas (Fase 7b) con dos extremos completos (ChatService +
 * CallService) cruzados en memoria: las señales `C` viajan como ciphertext real (E2EE) y el
 * stream de medios es un par de canales. El audio es un motor fake que registra los frames.
 */
class CallServiceTest {

    private val cipher = AesGcmMessageCipher()
    private val secret = ByteArray(32) { it.toByte() }

    // --- Fakes ---------------------------------------------------------------------------

    /** Extremo de stream en memoria: lo que A envía aparece en el receive de B y viceversa. */
    private class MemoryCallStream(
        private val inbox: Channel<ByteArray>,
        private val outbox: Channel<ByteArray>,
    ) : CallStream {
        override suspend fun sendFrame(frame: ByteArray) = outbox.send(frame)
        override suspend fun receiveFrame(): ByteArray? = runCatching { inbox.receive() }.getOrNull()
        override suspend fun close() {
            outbox.close()
            inbox.close()
        }

        companion object {
            fun pair(): Pair<MemoryCallStream, MemoryCallStream> {
                val ab = Channel<ByteArray>(Channel.UNLIMITED)
                val ba = Channel<ByteArray>(Channel.UNLIMITED)
                return MemoryCallStream(ba, ab) to MemoryCallStream(ab, ba)
            }
        }
    }

    /** Señalización fake de un extremo; se cruza con la del otro tras construir ambos. */
    private class FakeCallSignaling(val myPeerId: String) : ISignalingService {
        lateinit var other: FakeCallSignaling
        lateinit var deliver: suspend (fromPeerId: String, ciphertext: ByteArray) -> Unit

        override val events = MutableSharedFlow<SignalingEvent>()
        override val incomingCallStreams = MutableSharedFlow<Pair<String, CallStream>>(extraBufferCapacity = 4)

        var failSend = false
        val rawSent = mutableListOf<ByteArray>()

        override suspend fun send(contact: Contact, ciphertext: ByteArray) {
            if (failSend) error("failed to dial")
            rawSent.add(ciphertext)
            other.deliver(myPeerId, ciphertext)
        }

        override suspend fun sendOffline(contact: Contact, ciphertext: ByteArray, label: String) = send(contact, ciphertext)

        override suspend fun openCallStream(contact: Contact): CallStream {
            val (mine, theirs) = MemoryCallStream.pair()
            check(other.incomingCallStreams.tryEmit(myPeerId to theirs)) { "receptor sin colector" }
            return mine
        }

        override val incomingVideoStreams = MutableSharedFlow<Pair<String, CallStream>>(extraBufferCapacity = 4)

        override suspend fun openVideoStream(contact: Contact): CallStream {
            val (mine, theirs) = MemoryCallStream.pair()
            check(other.incomingVideoStreams.tryEmit(myPeerId to theirs)) { "receptor sin colector de vídeo" }
            return mine
        }

        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun announce(rendezvous: ByteArray) = Unit
        override suspend fun findPeers(rendezvous: ByteArray): List<String> = emptyList()
        override suspend fun fetchMailbox(labels: String): Int = 0
        override suspend fun startWake(labels: String) = Unit
        override suspend fun stopWake() = Unit
        override suspend fun wakeConnected(): Boolean = false
        override suspend fun bootstrap(): String? = null
        override suspend fun setBootstrap(addr: String) = Unit
        override suspend fun connectDht(bootstrap: String) = Unit
        override suspend fun selfAddrs(): List<String> = emptyList()
        override suspend fun reserveRelay(): String = ""
        override suspend fun pingProbe(count: Int, intervalMs: Int): String = "n=0/0"
        override fun setMailboxProcessor(
            processor: suspend (
                fromPeerId: String,
                ciphertext: ByteArray,
                envelopeId: String,
                timestamp: Long,
                label: String,
            ) -> Boolean,
        ) = Unit
    }

    private class FakeMessages : MessageRepository {
        val saved = mutableListOf<Message>()
        override fun observeConversation(conversationId: String): Flow<List<Message>> = flowOf(saved)
        override fun observeLastMessages(): Flow<List<Message>> = flowOf(emptyList())
        override fun observeUnreadCounts(): Flow<Map<String, Int>> = flowOf(emptyMap())
        override suspend fun save(message: Message) {
            val i = saved.indexOfFirst { it.id == message.id }
            if (i >= 0) saved[i] = message else saved.add(message)
        }
        override suspend fun findById(id: String): Message? = saved.find { it.id == id }
        override suspend fun findByStatus(status: MessageStatus, limit: Int): List<Message> =
            saved.filter { it.status == status }.take(limit)
        override suspend fun updateStatus(id: String, status: MessageStatus) = Unit
        override suspend fun markIncomingRead(conversationId: String) = Unit
        override suspend fun deleteConversation(conversationId: String) {
            saved.removeAll { it.conversationId == conversationId }
        }
    }

    private class FakeContacts(all: List<Contact>) : ContactRepository {
        val store = all.associateBy { it.id }.toMutableMap()
        override fun observeAll() = flowOf(store.values.toList())
        override suspend fun upsert(contact: Contact) { store[contact.id] = contact }
        override suspend fun findById(id: String) = store[id]
        override suspend fun findByPeerId(peerId: String) = store.values.find { it.peerId == peerId }
        override suspend fun delete(id: String) { store.remove(id) }
    }

    private class FakeKeyExchange(val peerId: String) : KeyExchange {
        override fun localPeerId() = peerId
        override fun sharedSecretWith(peerId: String) = ByteArray(32) { 7 }
    }

    private class FakeFileStore : chat.neto.krypta.core.FileStore {
        override suspend fun onMeta(fileId: String, m: chat.neto.krypta.core.IncomingFileMeta) = null
        override suspend fun onChunk(fileId: String, index: Int, bytes: ByteArray) = null
        override suspend fun deleteLocal(fileId: String, path: String?) = Unit
    }

    private class FakeAudioEngine : AudioEngine {
        var capture: ((ByteArray) -> Unit)? = null
        val played = mutableListOf<ByteArray>()
        var stopped = 0
        override fun start(onFrame: (ByteArray) -> Unit) { capture = onFrame }
        override fun onRemoteFrame(frame: ByteArray) { played.add(frame) }
        override fun stop() { stopped++; capture = null }
        override fun setMuted(muted: Boolean) = Unit
        override fun setSpeakerphone(on: Boolean) = Unit
    }

    /** Un extremo completo: señalización + chat + llamadas + audio fake. */
    private inner class Party(val peerId: String, otherContact: Contact, scope: CoroutineScope) {
        val signaling = FakeCallSignaling(peerId)
        val messages = FakeMessages()
        val contact = otherContact
        val chat = ChatService(
            signaling, cipher, messages, FakeContacts(listOf(otherContact)),
            FakeKeyExchange(peerId), RendezvousService(), FakeFileStore(), scope,
        )
        val audio = FakeAudioEngine()
        val calls = CallService(chat, signaling, cipher, audio, scope)
    }

    /** Construye A y B cruzados (cada uno tiene al otro como contacto). */
    private fun buildPair(scope: CoroutineScope): Pair<Party, Party> {
        val contactB = Contact("b", "Bea", "12D3KooWBBB", ByteArray(0), secret)
        val contactA = Contact("a", "Ana", "12D3KooWAAA", ByteArray(0), secret)
        val a = Party("12D3KooWAAA", contactB, scope)
        val b = Party("12D3KooWBBB", contactA, scope)
        a.signaling.other = b.signaling
        b.signaling.other = a.signaling
        a.signaling.deliver = { from, ct -> a.chat.onReceived(from, ct) }
        b.signaling.deliver = { from, ct -> b.chat.onReceived(from, ct) }
        return a to b
    }

    // --- Tests -----------------------------------------------------------------------------

    @Test
    fun `full call - invite rings, accept connects, audio flows E2EE, hangup ends both`() = runTest {
        val (a, b) = buildPair(backgroundScope)
        runCurrent() // los colectores de CallService deben estar suscritos antes de señalar

        a.calls.startCall(a.contact)
        runCurrent()
        assertEquals(CallPhase.CALLING, a.calls.state.value.phase)
        assertEquals(CallPhase.RINGING, b.calls.state.value.phase)
        assertEquals(a.calls.state.value.callId, b.calls.state.value.callId)

        b.calls.accept()
        runCurrent()
        assertEquals(CallPhase.ACTIVE, a.calls.state.value.phase)
        assertEquals(CallPhase.ACTIVE, b.calls.state.value.phase)

        // El micro de A produce un frame → llega descifrado al motor de B (y nunca en claro).
        val frame = "paquete-opus".toByteArray()
        a.audio.capture!!.invoke(frame)
        runCurrent()
        assertEquals(1, b.audio.played.size)
        assertArrayEquals(frame, b.audio.played[0])

        // Y al revés.
        val back = byteArrayOf(1, 2, 3)
        b.audio.capture!!.invoke(back)
        runCurrent()
        assertArrayEquals(back, a.audio.played.single())

        a.calls.hangup()
        runCurrent()
        assertEquals(CallPhase.ENDED, a.calls.state.value.phase)
        assertEquals(CallPhase.ENDED, b.calls.state.value.phase)
        assertEquals("finalizada", b.calls.state.value.endReason)
        assertTrue(a.audio.stopped >= 1)
        assertTrue(b.audio.stopped >= 1)
    }

    @Test
    fun `video toggles on during an active call, frames flow E2EE both ways, and off`() = runTest {
        val (a, b) = buildPair(backgroundScope)
        runCurrent()

        a.calls.startCall(a.contact)
        runCurrent()
        b.calls.accept()
        runCurrent()
        assertEquals(CallPhase.ACTIVE, a.calls.state.value.phase)

        // Antes de ACTIVE no hay vídeo posible; A lo enciende ya en llamada.
        val aFrames = mutableListOf<ByteArray>()
        val bFrames = mutableListOf<ByteArray>()
        backgroundScope.launch { a.calls.remoteVideoFrames.collect { aFrames.add(it) } }
        backgroundScope.launch { b.calls.remoteVideoFrames.collect { bFrames.add(it) } }
        runCurrent()

        assertTrue(a.calls.startVideo())
        runCurrent()
        assertTrue(a.calls.state.value.videoSending)
        assertTrue(b.calls.state.value.videoReceiving)

        // Frame H.264 de A → llega descifrado a B (viajó cifrado con la clave de la llamada).
        val keyframe = ByteArray(100_000) { (it % 253).toByte() } // >64 KiB: solo cabe en vídeo
        a.calls.sendVideoFrame(keyframe)
        runCurrent()
        assertEquals(1, bFrames.size)
        assertArrayEquals(keyframe, bFrames[0])

        // B enciende el suyo (canales independientes) y A lo recibe.
        assertTrue(b.calls.startVideo())
        runCurrent()
        assertTrue(a.calls.state.value.videoReceiving)
        b.calls.sendVideoFrame(byteArrayOf(9, 9, 9))
        runCurrent()
        assertArrayEquals(byteArrayOf(9, 9, 9), aFrames.single())

        // A apaga su cámara: B deja de recibir; el audio/llamada sigue ACTIVE.
        a.calls.stopVideo()
        runCurrent()
        assertEquals(false, a.calls.state.value.videoSending)
        assertEquals(false, b.calls.state.value.videoReceiving)
        assertEquals(CallPhase.ACTIVE, b.calls.state.value.phase)

        // Colgar limpia también el vídeo restante (B→A).
        a.calls.hangup()
        runCurrent()
        assertEquals(CallPhase.ENDED, b.calls.state.value.phase)
        assertEquals(false, a.calls.state.value.videoReceiving)
    }

    @Test
    fun `video congestion drops deltas until the next keyframe`() = runTest {
        val (a, b) = buildPair(backgroundScope)
        runCurrent()
        a.calls.startCall(a.contact)
        runCurrent()
        b.calls.accept()
        runCurrent()
        val bFrames = mutableListOf<ByteArray>()
        backgroundScope.launch { b.calls.remoteVideoFrames.collect { bFrames.add(it) } }
        runCurrent()
        assertTrue(a.calls.startVideo())
        runCurrent()

        // Sin drenar el TX (la red "no da abasto"): tras el umbral los delta se descartan…
        val delta = byteArrayOf(chat.neto.krypta.core.VideoFrame.DELTA, 1)
        repeat(20) { a.calls.sendVideoFrame(delta) }
        // …y el siguiente keyframe reabre el grifo (la imagen se recompone entera con él).
        val key = byteArrayOf(chat.neto.krypta.core.VideoFrame.KEY, 2)
        a.calls.sendVideoFrame(key)
        runCurrent()

        // Umbral (12) deltas encolados + el keyframe; los 8 delta sobrantes, descartados.
        assertEquals(13, bFrames.size)
        assertArrayEquals(key, bFrames.last())
    }

    @Test
    fun `startVideo is rejected outside an active call`() = runTest {
        val (a, _) = buildPair(backgroundScope)
        runCurrent()
        assertEquals(false, a.calls.startVideo()) // IDLE
        a.calls.startCall(a.contact)
        runCurrent()
        assertEquals(false, a.calls.startVideo()) // CALLING, aún sin ACTIVE
    }

    @Test
    fun `reject ends the caller with rechazada and callee returns to idle`() = runTest {
        val (a, b) = buildPair(backgroundScope)
        runCurrent()

        a.calls.startCall(a.contact)
        runCurrent()
        b.calls.reject()
        runCurrent()

        assertEquals(CallPhase.ENDED, a.calls.state.value.phase)
        assertEquals("rechazada", a.calls.state.value.endReason)
        assertEquals(CallPhase.IDLE, b.calls.state.value.phase)
    }

    @Test
    fun `stale invite does not ring and records a missed call`() = runTest {
        val (_, b) = buildPair(backgroundScope)
        runCurrent()

        val old = MessageEnvelope.encodeCall("invite", "call-vieja", System.currentTimeMillis() - 120_000)
        b.chat.onReceived("12D3KooWAAA", cipher.encrypt(secret, old))
        runCurrent()

        assertEquals(CallPhase.IDLE, b.calls.state.value.phase)
        assertEquals(1, b.messages.saved.size) // la fila local "llamada perdida"
    }

    @Test
    fun `caller hangup while ringing records a missed call on the callee`() = runTest {
        val (a, b) = buildPair(backgroundScope)
        runCurrent()

        a.calls.startCall(a.contact)
        runCurrent()
        assertEquals(CallPhase.RINGING, b.calls.state.value.phase)

        a.calls.hangup()
        runCurrent()

        assertEquals(CallPhase.IDLE, b.calls.state.value.phase)
        assertEquals(1, b.messages.saved.size)
        assertEquals(CallPhase.ENDED, a.calls.state.value.phase)
        assertEquals("cancelada", a.calls.state.value.endReason)
    }

    @Test
    fun `second concurrent invite gets busy`() = runTest {
        val (a, b) = buildPair(backgroundScope)
        runCurrent()

        a.calls.startCall(a.contact)
        runCurrent()
        assertEquals(CallPhase.RINGING, b.calls.state.value.phase)

        // Otro invite (otra llamada) mientras B ya timbra → B responde "busy" sin colgar la 1.ª.
        val sentBefore = b.signaling.rawSent.size
        val other = MessageEnvelope.encodeCall("invite", "otra-llamada", System.currentTimeMillis())
        b.chat.onReceived("12D3KooWAAA", cipher.encrypt(secret, other))
        runCurrent()

        assertEquals(CallPhase.RINGING, b.calls.state.value.phase)
        val busy = MessageEnvelope.decode(cipher.decrypt(secret, b.signaling.rawSent.last()))
        assertEquals(sentBefore + 1, b.signaling.rawSent.size)
        assertEquals("busy", (busy as MessageEnvelope.Decoded.Call).kind)
        assertEquals("otra-llamada", busy.callId)
    }
}

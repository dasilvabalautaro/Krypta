package chat.neto.krypta.p2p

import chat.neto.krypta.core.ISignalingService
import chat.neto.krypta.core.KeyExchange
import chat.neto.krypta.core.SignalingEvent
import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.core.model.Message
import chat.neto.krypta.core.model.MessageStatus
import chat.neto.krypta.core.repository.ContactRepository
import chat.neto.krypta.core.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatServiceTest {

    private val cipher = AesGcmMessageCipher()
    private val secret = ByteArray(32) { it.toByte() }
    private val contact = Contact(
        id = "c1",
        displayName = "Bob",
        peerId = "12D3KooWBob",
        publicKey = ByteArray(0),
        sharedSecret = secret,
    )

    private class FakeSignaling : ISignalingService {
        override val events = MutableSharedFlow<SignalingEvent>()
        var sentCiphertext: ByteArray? = null
        /** Simula que el arranque del host/mDNS revienta (p. ej. datos móviles sin multicast). */
        var failOnStart = false
        var bootstrapAddr: String? = null
        var connectedBootstrap: String? = null
        var lastSetBootstrap: String? = null
        override suspend fun start() {
            if (failOnStart) error("mDNS no disponible (sin interfaz multicast)")
        }
        override suspend fun stop() = Unit
        val announced = mutableListOf<ByteArray>()
        override suspend fun announce(rendezvous: ByteArray) { announced.add(rendezvous) }
        override suspend fun findPeers(rendezvous: ByteArray): List<String> = emptyList()
        override suspend fun bootstrap(): String? = bootstrapAddr
        override suspend fun setBootstrap(addr: String) { lastSetBootstrap = addr }
        var connectCalls = 0
        /** Simula un nodo que acepta el TCP y deja el handshake colgado (nunca vuelve). */
        var hangOnConnect = false
        /** Simula un dial que falla de inmediato. */
        var failOnConnect = false
        override suspend fun connectDht(bootstrap: String) {
            connectCalls++
            connectedBootstrap = bootstrap
            if (hangOnConnect) kotlinx.coroutines.awaitCancellation()
            if (failOnConnect) error("connect bootstrap: i/o timeout")
        }
        override suspend fun selfAddrs(): List<String> = emptyList()
        override suspend fun reserveRelay(): String = ""
        override suspend fun pingProbe(count: Int, intervalMs: Int): String =
            "n=$count/$count min=1ms p50=1ms p95=2ms max=3ms"
        override val incomingCallStreams =
            MutableSharedFlow<Pair<String, chat.neto.krypta.core.CallStream>>()
        override suspend fun openCallStream(contact: Contact): chat.neto.krypta.core.CallStream =
            error("sin streams de llamada en este fake")
        override val incomingVideoStreams =
            MutableSharedFlow<Pair<String, chat.neto.krypta.core.CallStream>>()
        override suspend fun openVideoStream(contact: Contact): chat.neto.krypta.core.CallStream =
            error("sin streams de vídeo en este fake")
        var failOnSend = false
        val sentAll = mutableListOf<ByteArray>()
        override suspend fun send(contact: Contact, ciphertext: ByteArray) {
            if (failOnSend) error("failed to dial ${contact.peerId}: no addresses")
            sentCiphertext = ciphertext
            sentAll.add(ciphertext)
        }
        var failOnMailbox = false
        var mailboxDeposits = mutableListOf<Pair<String, ByteArray>>()
        override suspend fun sendOffline(contact: Contact, ciphertext: ByteArray) {
            if (failOnMailbox) error("connect buzón: sin ruta al nodo")
            mailboxDeposits.add(contact.peerId to ciphertext)
        }
        var mailboxPending = 0
        var fetchCalls = 0
        override suspend fun fetchMailbox(): Int {
            fetchCalls++
            return mailboxPending.also { mailboxPending = 0 }
        }
        var wakeRunning = false
        override suspend fun startWake() { wakeRunning = true }
        override suspend fun stopWake() { wakeRunning = false }
        var wakeUp = false
        override suspend fun wakeConnected(): Boolean = wakeUp
        /** Procesador registrado por ChatService: los tests lo invocan como haría el fetch. */
        var registeredMailboxProcessor: (suspend (String, ByteArray, String, Long) -> Boolean)? = null
        override fun setMailboxProcessor(
            processor: suspend (fromPeerId: String, ciphertext: ByteArray, envelopeId: String, timestamp: Long) -> Boolean,
        ) { registeredMailboxProcessor = processor }
    }

    private class FakeMessages : MessageRepository {
        val saved = mutableListOf<Message>()
        /** Simula un fallo de Room al persistir (disco lleno, etc.). */
        var failOnSave = false
        override fun observeConversation(conversationId: String): Flow<List<Message>> =
            flowOf(saved.filter { it.conversationId == conversationId })
        override fun observeLastMessages(): Flow<List<Message>> =
            flowOf(
                saved.groupBy { it.conversationId }
                    .mapNotNull { (_, msgs) -> msgs.maxByOrNull { it.timestamp } },
            )
        override fun observeUnreadCounts(): Flow<Map<String, Int>> =
            flowOf(
                saved.filter { it.senderId == it.conversationId && it.status == MessageStatus.DELIVERED }
                    .groupingBy { it.conversationId }.eachCount(),
            )
        // Como Room (@Insert REPLACE): un save con el mismo id sustituye, no duplica.
        override suspend fun save(message: Message) {
            if (failOnSave) error("Room: disco lleno")
            val i = saved.indexOfFirst { it.id == message.id }
            if (i >= 0) saved[i] = message else saved.add(message)
        }
        override suspend fun findById(id: String): Message? = saved.find { it.id == id }
        override suspend fun updateStatus(id: String, status: MessageStatus) {
            val i = saved.indexOfFirst { it.id == id }
            if (i >= 0) saved[i] = saved[i].copy(status = status)
        }
        override suspend fun markIncomingRead(conversationId: String) {
            saved.replaceAll {
                if (it.conversationId == conversationId && it.senderId == conversationId) {
                    it.copy(status = MessageStatus.READ)
                } else it
            }
        }
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

    private class FakeKeyExchange : KeyExchange {
        override fun localPeerId() = "12D3KooWSelf"
        override fun sharedSecretWith(peerId: String) = ByteArray(32) { 7 }
    }

    /** Reensambla en memoria (sin disco), para probar el troceado de archivos. */
    private class FakeFileStore : chat.neto.krypta.core.FileStore {
        private var meta: chat.neto.krypta.core.IncomingFileMeta? = null
        private val chunks = mutableMapOf<Int, ByteArray>()
        /** Simula un fallo de staging (p. ej. escritura a disco) al guardar un trozo. */
        var failOnChunk = false
        override suspend fun onMeta(fileId: String, m: chat.neto.krypta.core.IncomingFileMeta) =
            run { meta = m; assemble() }
        override suspend fun onChunk(fileId: String, index: Int, bytes: ByteArray) =
            run {
                if (failOnChunk) error("staging: no se pudo escribir el trozo")
                chunks[index] = bytes
                assemble()
            }
        private fun assemble(): chat.neto.krypta.core.AssembledFile? {
            val m = meta ?: return null
            if (chunks.size < m.totalChunks) return null
            val all = (0 until m.totalChunks).fold(ByteArray(0)) { acc, i -> acc + chunks[i]!! }
            return chat.neto.krypta.core.AssembledFile(m.name, m.mime, m.size, "/tmp/${m.name}", m.replyTo).also {
                assembled = all
            }
        }
        var assembled: ByteArray? = null
        /** Borrados pedidos al vaciar un chat: (fileId, path de la copia local). */
        val deleted = mutableListOf<Pair<String, String?>>()
        override suspend fun deleteLocal(fileId: String, path: String?) {
            deleted.add(fileId to path)
        }
    }

    @Test
    fun `send encrypts, persists as SENT, and transmits ciphertext`() = runTest {
        val signaling = FakeSignaling()
        val messages = FakeMessages()
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        val plaintext = "hola camarada".toByteArray()
        val sent = chat.send(contact, plaintext)

        // lo transmitido es ciphertext de un sobre cuyo cuerpo descifra al plaintext
        val env = MessageEnvelope.decode(cipher.decrypt(secret, signaling.sentCiphertext!!))
        assertArrayEquals(plaintext, (env as MessageEnvelope.Decoded.Text).body)
        assertEquals(sent.id, env.id) // el id del sobre = el id del mensaje persistido
        // persistido y marcado SENT
        assertEquals(MessageStatus.SENT, sent.status)
        assertEquals(MessageStatus.SENT, messages.saved.single().status)
        // nunca se guarda el texto plano
        assertFalse(messages.saved.single().ciphertext.contentEquals(plaintext))
    }

    /** El fallo del envío directo cae al buzón (entrega offline) y el mensaje queda SENT. */
    @Test
    fun `send falls back to mailbox when dialing fails and marks SENT`() = runTest {
        val signaling = FakeSignaling().apply { failOnSend = true }
        val messages = FakeMessages()
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        val plaintext = "hola offline".toByteArray()
        val result = chat.send(contact, plaintext)

        assertEquals(MessageStatus.SENT, result.status)
        assertEquals(MessageStatus.SENT, messages.saved.single().status)
        // lo depositado es ciphertext para el peer correcto; el cuerpo del sobre descifra al plaintext
        val (to, blob) = signaling.mailboxDeposits.single()
        assertEquals(contact.peerId, to)
        val env = MessageEnvelope.decode(cipher.decrypt(secret, blob))
        assertArrayEquals(plaintext, (env as MessageEnvelope.Decoded.Text).body)
    }

    /** Regresión: si fallan directo Y buzón, marca FAILED y NO lanza (no crashea). */
    @Test
    fun `send marks FAILED and does not throw when direct and mailbox both fail`() = runTest {
        val signaling = FakeSignaling().apply { failOnSend = true; failOnMailbox = true }
        val messages = FakeMessages()
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        val result = chat.send(contact, "hola".toByteArray())

        assertEquals(MessageStatus.FAILED, result.status)
        assertEquals(MessageStatus.FAILED, messages.saved.single().status)
        // se guardó ciphertext (nunca texto plano), aunque el envío fallara
        assertFalse(messages.saved.single().ciphertext.contentEquals("hola".toByteArray()))
    }

    /** Una reentrega del buzón (ack perdido) con el mismo id de sobre no duplica el mensaje. */
    @Test
    fun `mailbox redelivery with same envelope id does not duplicate`() = runTest {
        val messages = FakeMessages()
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        // Sin sobre (legado): el id del Message cae al del buzón (mailboxId) → dedup por id.
        val ciphertext = cipher.encrypt(secret, "del buzón".toByteArray())
        val first = chat.onReceived(contact.peerId, ciphertext, mailboxId = "mbx-001", ts = 1234L)!!
        val again = chat.onReceived(contact.peerId, ciphertext, mailboxId = "mbx-001", ts = 1234L)!!

        assertEquals("mbx-001", first.id)
        assertEquals(1234L, first.timestamp) // conserva la hora del depósito
        assertEquals(first.id, again.id)
        assertEquals(1, messages.saved.size) // upsert idéntico, sin duplicados
        assertEquals(MessageStatus.DELIVERED, messages.saved.single().status)
    }

    /** Enviar una imagen la persiste (SENT), transmite el sobre imagen, y `content` la recupera. */
    @Test
    fun `sendImage persists and content returns the image`() = runTest {
        val signaling = FakeSignaling()
        val messages = FakeMessages()
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        val jpeg = ByteArray(300) { (it % 256).toByte() }
        val sent = chat.sendImage(contact, jpeg)

        assertEquals(MessageStatus.SENT, sent.status)
        // lo transmitido es un sobre imagen que descifra al JPEG original
        val env = MessageEnvelope.decode(cipher.decrypt(secret, signaling.sentCiphertext!!))
        assertArrayEquals(jpeg, (env as MessageEnvelope.Decoded.Image).bytes)
        // content() lo clasifica como imagen
        val c = chat.content(contact, messages.saved.single())
        assertArrayEquals(jpeg, (c as chat.neto.krypta.core.model.MessageContent.Image).jpeg)
        // la notificación de una imagen es "📷 Foto"
        assertEquals("📷 Foto", chat.notificationText(contact, messages.saved.single()))
    }

    /** Una imagen entrante se persiste (DELIVERED) con el id del sobre y se recupera. */
    @Test
    fun `received image is stored and decodable`() = runTest {
        val messages = FakeMessages()
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        val jpeg = ByteArray(128) { it.toByte() }
        val ciphertext = cipher.encrypt(secret, MessageEnvelope.encodeImage("img-9", jpeg))
        val received = chat.onReceived(contact.peerId, ciphertext)!!

        assertEquals("img-9", received.id)
        assertEquals(MessageStatus.DELIVERED, received.status)
        val c = chat.content(contact, received)
        assertArrayEquals(jpeg, (c as chat.neto.krypta.core.model.MessageContent.Image).jpeg)
    }

    /** Un archivo grande se trocea al enviar (emisor) y se reensambla idéntico al recibir (receptor). */
    @Test
    fun `sendFile chunks a file and it reassembles on receive`() = runTest {
        // Emisor y receptor son instancias distintas (como dos móviles).
        val senderSig = FakeSignaling()
        val sender = ChatService(senderSig, cipher, FakeMessages(), FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)
        val rxFileStore = FakeFileStore()
        val receiver = ChatService(FakeSignaling(), cipher, FakeMessages(), FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), rxFileStore, backgroundScope)

        // 120 KiB → meta + 3 trozos de 48 KiB = 4 envíos.
        val fileBytes = ByteArray(120 * 1024) { (it % 251).toByte() }
        val sent = sender.sendFile(contact, "doc.bin", "application/octet-stream", fileBytes)
        assertEquals(MessageStatus.SENT, sent.status)
        assertEquals(4, senderSig.sentAll.size)

        val received = senderSig.sentAll.map { receiver.onReceived(contact.peerId, it) }
        assertNull(received[0]); assertNull(received[1]); assertNull(received[2])
        assertEquals(MessageStatus.DELIVERED, received[3]!!.status)
        assertArrayEquals(fileBytes, rxFileStore.assembled) // reensamblado idéntico al original
    }

    /** Una nota de voz (audio con copia local) deja la burbuja del emisor reproducible y notifica 🎤. */
    @Test
    fun `sendFile with localPath keeps sender copy and audio notification label`() = runTest {
        val messages = FakeMessages()
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        val sent = chat.sendFile(
            contact, "nota-voz-1.m4a", "audio/mp4", ByteArray(1024) { 3 },
            localPath = "/data/notas/nota-voz-1.m4a",
        )

        assertEquals(MessageStatus.SENT, sent.status)
        val c = chat.content(contact, messages.saved.single()) as chat.neto.krypta.core.model.MessageContent.File
        assertEquals("audio/mp4", c.mime)
        assertEquals("/data/notas/nota-voz-1.m4a", c.localPath) // burbuja propia reproducible
        assertEquals("🎤 Nota de voz", chat.notificationText(contact, messages.saved.single()))
    }

    /**
     * Un GIF viaja **por el camino de archivos troceados y sin recodificar** (es la única forma
     * de conservar la animación: el sobre de imagen en línea no pasa de ~58 KiB y comprimirlo
     * lo dejaba en su primer fotograma). Debe llegar con sus bytes intactos, con copia local
     * para que la burbuja propia se anime, y rotularse "🎞 GIF" y no "📎 archivo.gif".
     */
    @Test
    fun `an animated GIF travels intact as a chunked file and is labelled as GIF`() = runTest {
        val senderSig = FakeSignaling()
        val messages = FakeMessages()
        val chat = ChatService(senderSig, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)
        // 100 KiB: por encima del límite del sobre en línea, así que obliga al troceado.
        val gif = ByteArray(100 * 1024) { (it % 251).toByte() }

        val sent = chat.sendFile(
            contact, "baile.gif", "image/gif", gif,
            localPath = "/data/krypta_files/sent/baile.gif",
        )

        assertEquals(MessageStatus.SENT, sent.status)
        assertEquals("🎞 GIF", chat.notificationText(contact, messages.saved.single()))
        val c = chat.content(contact, messages.saved.single()) as chat.neto.krypta.core.model.MessageContent.File
        assertEquals("image/gif", c.mime)
        assertEquals("/data/krypta_files/sent/baile.gif", c.localPath)

        // Y del otro lado los bytes se reensamblan **idénticos** (sin recodificar).
        val rxSig = FakeSignaling()
        val rxFileStore = FakeFileStore()
        ChatService(rxSig, cipher, FakeMessages(), FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), rxFileStore, backgroundScope)
        val processor = rxSig.registeredMailboxProcessor!!
        senderSig.sentAll.forEachIndexed { i, env ->
            assertTrue(processor(contact.peerId, env, "env-$i", i.toLong()))
        }
        assertArrayEquals(gif, rxFileStore.assembled)
    }

    /** Reintentar un mensaje FALLIDO lo reenvía (mismo id) y, si ahora va, queda SENT. */
    /**
     * Responder cita **solo el id** del mensaje citado: nunca viaja una copia de su texto.
     * Los dos extremos guardan cada mensaje con el mismo id (el del sobre), así que cada uno
     * resuelve la cita contra su propia base — y una cita no resucita lo que el otro borró.
     */
    @Test
    fun `a reply carries the quoted id end to end and never a copy of the quoted text`() = runTest {
        val senderSig = FakeSignaling()
        val senderMessages = FakeMessages()
        val sender = ChatService(senderSig, cipher, senderMessages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)
        val rxMessages = FakeMessages()
        val receiver = ChatService(FakeSignaling(), cipher, rxMessages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        // Un primer mensaje, que será el citado.
        val quoted = sender.send(contact, "el texto del mensaje citado".toByteArray())
        sender.send(contact, "respuesta".toByteArray(), replyTo = quoted.id)

        // Burbuja propia: contenido y cita.
        val own = sender.decodeMessage(contact, senderMessages.saved.last())
        assertEquals("respuesta", (own.content as chat.neto.krypta.core.model.MessageContent.Text).text)
        assertEquals(quoted.id, own.replyTo)

        // Dentro del cifrado solo va el **id** del citado, no una copia de su texto.
        val plain = String(cipher.decrypt(secret, senderSig.sentCiphertext!!))
        assertTrue(plain.contains(quoted.id))
        assertFalse(plain.contains("el texto del mensaje citado"))

        // Y llega igual al otro lado.
        val delivered = receiver.onReceived(contact.peerId, senderSig.sentCiphertext!!)!!
        val got = receiver.decodeMessage(contact, delivered)
        assertEquals("respuesta", (got.content as chat.neto.krypta.core.model.MessageContent.Text).text)
        assertEquals(quoted.id, got.replyTo)

        // Un mensaje normal sigue sin cita (el sobre no cambia si no se responde a nada).
        sender.send(contact, "suelto".toByteArray())
        assertNull(sender.decodeMessage(contact, senderMessages.saved.last()).replyTo)
    }

    /**
     * Responder **con** una foto/nota de voz/archivo: la cita viaja en la META, porque la
     * burbuja del receptor no nace hasta tener todos los trozos (y el proceso puede morir
     * entre medias). Debe seguir ahí tras el reensamblado.
     */
    @Test
    fun `a voice note sent as a reply keeps its quote after reassembly`() = runTest {
        val senderSig = FakeSignaling()
        val sender = ChatService(senderSig, cipher, FakeMessages(), FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)
        val rxMessages = FakeMessages()
        val receiver = ChatService(FakeSignaling(), cipher, rxMessages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        val sent = sender.sendFile(
            contact, "nota.m4a", "audio/mp4", ByteArray(1024) { 7 },
            localPath = "/data/notas/nota.m4a", replyTo = "id-citado",
        )
        assertEquals(MessageStatus.SENT, sent.status)
        // La burbuja propia (descriptor local) también lleva la cita.
        assertEquals("id-citado", sender.decodeMessage(contact, sent).replyTo)

        senderSig.sentAll.forEach { receiver.onReceived(contact.peerId, it) }
        val persisted = rxMessages.saved.single()
        val decoded = receiver.decodeMessage(contact, persisted)
        assertEquals("audio/mp4", (decoded.content as chat.neto.krypta.core.model.MessageContent.File).mime)
        assertEquals("id-citado", decoded.replyTo)
    }

    @Test
    fun `retry re-sends a FAILED message and marks SENT without duplicating`() = runTest {
        val signaling = FakeSignaling().apply { failOnSend = true; failOnMailbox = true }
        val messages = FakeMessages()
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        val failed = chat.send(contact, "hola".toByteArray())
        assertEquals(MessageStatus.FAILED, failed.status)

        // Ahora la red "vuelve": el reintento debe pasar a SENT, reusando el mismo id.
        signaling.failOnSend = false
        val retried = chat.retry(contact, failed.id)!!

        assertEquals(MessageStatus.SENT, retried.status)
        assertEquals(failed.id, retried.id)
        assertEquals(1, messages.saved.size) // sin duplicar
        assertEquals(MessageStatus.SENT, messages.saved.single().status)
    }

    @Test
    fun `received resolves contact by peerId, persists DELIVERED, and decrypts`() = runTest {
        val messages = FakeMessages()
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        val ciphertext = cipher.encrypt(secret, MessageEnvelope.encodeText("mid-1", "buenas".toByteArray()))
        val received = chat.onReceived(contact.peerId, ciphertext)!!

        assertEquals("mid-1", received.id) // usa el id del sobre del emisor (para acusar luego)
        assertEquals(MessageStatus.DELIVERED, received.status)
        assertEquals(contact.id, received.conversationId)
        assertArrayEquals("buenas".toByteArray(), chat.decrypt(contact, received))
        assertEquals(1, messages.saved.size)
    }

    /** Un acuse de lectura entrante marca como READ el mensaje saliente que cita. */
    @Test
    fun `read receipt marks the sent message READ`() = runTest {
        val messages = FakeMessages()
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        val sent = chat.send(contact, "hola".toByteArray())
        assertEquals(MessageStatus.SENT, sent.status)

        val receipt = cipher.encrypt(secret, MessageEnvelope.encodeRead(listOf(sent.id)))
        val res = chat.onReceived(contact.peerId, receipt)

        assertNull(res) // un acuse no crea mensaje visible
        assertEquals(MessageStatus.READ, messages.findById(sent.id)!!.status)
    }

    /** Al marcar leído, se envía un acuse citando los ids de los mensajes recibidos. */
    @Test
    fun `markConversationRead sends a read receipt citing received ids`() = runTest {
        val signaling = FakeSignaling()
        val messages = FakeMessages()
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)
        chat.onReceived(contact.peerId, cipher.encrypt(secret, MessageEnvelope.encodeText("rx-1", "hola".toByteArray())))

        chat.markConversationRead(contact)

        val decoded = MessageEnvelope.decode(cipher.decrypt(secret, signaling.sentCiphertext!!))
        assertEquals(listOf("rx-1"), (decoded as MessageEnvelope.Decoded.Read).ids)

        // No re-envía acuses ya emitidos (dedup interno).
        signaling.sentCiphertext = null
        chat.markConversationRead(contact)
        assertNull(signaling.sentCiphertext)
    }

    @Test
    fun `received from unknown peer is ignored`() = runTest {
        val messages = FakeMessages()
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        assertNull(chat.onReceived("12D3KooWStranger", ByteArray(28)))
        assertEquals(0, messages.saved.size)
    }

    /**
     * Regresión: el bug de los datos móviles. mDNS revienta sin interfaz multicast y eso NO
     * debe impedir el WAN (camino principal de Krypta). Aunque `signaling.start()` lance, el
     * bucle WAN debe arrancar y conectar a la DHT vía el bootstrap.
     */
    @Test
    fun `start launches WAN even when host-mDNS startup fails`() = runTest {
        val signaling = FakeSignaling().apply {
            failOnStart = true // mDNS revienta (datos móviles)
            bootstrapAddr = "/dns4/krypta.neto.chat/tcp/443/wss/p2p/12D3KooWNode"
        }
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        chat.start() // no debe propagar la excepción de start()
        testScheduler.runCurrent() // deja correr la 1ª iteración del wanLoop (hasta su delay)

        assertEquals(signaling.bootstrapAddr, signaling.connectedBootstrap)
        assertEquals(WanStatus.CONNECTED, chat.wanStatus.value)
    }

    /** El número de seguridad es simétrico: ambos contactos ven el mismo (anti-MITM). */
    @Test
    fun `safety number matches on both sides`() = runTest {
        val contacts = FakeContacts(listOf(contact))
        val chat = ChatService(FakeSignaling(), cipher, FakeMessages(), contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        // Mi PeerID = FakeKeyExchange.localPeerId(); el del contacto = contact.peerId.
        val mine = chat.safetyNumber(contact)
        val theirs = SafetyNumber.compute(contact.peerId, "12D3KooWSelf")
        assertEquals(theirs, mine)
    }

    /** Verificar persiste el flag; re-añadir el mismo PeerID conserva la verificación. */
    @Test
    fun `verifying persists and re-adding same peerId keeps it`() = runTest {
        val contacts = FakeContacts(listOf(contact))
        val chat = ChatService(FakeSignaling(), cipher, FakeMessages(), contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        chat.setVerified(contact, true)
        assertTrue(contacts.findById(contact.id)!!.verified)

        // Re-alta (p. ej. renombrar) del MISMO PeerID no borra la verificación.
        chat.addContact("Bob renombrado", contact.peerId)
        assertTrue(contacts.findById(contact.id)!!.verified)
    }

    /**
     * Añadirse a uno mismo se rechaza: pegar el PeerID propio en vez del del contacto es un
     * error real (23 jul 2026) y creaba un "contacto" con el que nunca llega nada.
     */
    @Test
    fun `addContact rejects your own peerId`() = runTest {
        val contacts = FakeContacts(emptyList())
        val chat = ChatService(FakeSignaling(), cipher, FakeMessages(), contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        val rejected = runCatching { chat.addContact("Yo mismo", chat.myPeerId()) }
        assertTrue(rejected.exceptionOrNull() is IllegalArgumentException)
        assertNull(contacts.findById(chat.myPeerId()))

        // Un PeerID ajeno sí se da de alta.
        chat.addContact("Jimena", "12D3KooWJimena")
        assertEquals("Jimena", contacts.findById("12D3KooWJimena")!!.displayName)
    }

    private val validAddr = "/dns4/krypta.neto.chat/tcp/443/wss/p2p/12D3KooWNode"

    @Test
    fun `bootstrap validation accepts real multiaddrs and rejects garbage`() {
        assertTrue(isValidBootstrapAddr(validAddr))
        assertTrue(isValidBootstrapAddr("/ip4/1.2.3.4/tcp/4001/p2p/12D3KooWNode"))
        assertFalse(isValidBootstrapAddr("krypta.neto.chat"))          // no empieza por /
        assertFalse(isValidBootstrapAddr("/dns4/x/tcp/443/wss"))       // sin /p2p/
        assertFalse(isValidBootstrapAddr("/dns4/x/tcp/443/wss/p2p/"))  // /p2p/ vacío
        assertFalse(isValidBootstrapAddr(""))
    }

    @Test
    fun `normalizeBootstrapList acepta varios nodos y rechaza la lista si alguno es basura`() {
        // Un solo nodo: idéntico al comportamiento previo.
        assertEquals(validAddr, normalizeBootstrapList(validAddr))
        // Varios nodos: recorta, descarta líneas vacías y conserva el orden (failover).
        assertEquals(
            "$validAddr\n/ip4/1.2.3.4/tcp/4001/p2p/12D3KooWDos",
            normalizeBootstrapList("  $validAddr  \n\n/ip4/1.2.3.4/tcp/4001/p2p/12D3KooWDos\n"),
        )
        // Una línea inválida invalida la lista entera (no se persiste a medias).
        assertNull(normalizeBootstrapList("$validAddr\nbasura"))
        assertNull(normalizeBootstrapList("   \n  "))
    }

    @Test
    fun `setBootstrap accepts a multi-node list and persists it normalized`() = runTest {
        val signaling = FakeSignaling()
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        val second = "/ip4/9.9.9.9/tcp/4001/p2p/12D3KooWSegundo"
        val result = chat.setBootstrap(" $validAddr \n$second\n")
        testScheduler.runCurrent()

        assertEquals(BootstrapResult.OK, result)
        assertEquals("$validAddr\n$second", signaling.lastSetBootstrap)
    }

    @Test
    fun `setBootstrap rejects invalid addr without persisting or starting WAN`() = runTest {
        val signaling = FakeSignaling()
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        val result = chat.setBootstrap("esto-no-es-un-multiaddr")
        testScheduler.runCurrent()

        assertEquals(BootstrapResult.INVALID, result)
        assertNull(signaling.lastSetBootstrap)        // no se persistió
        assertNull(signaling.connectedBootstrap)      // no arrancó el WAN
        assertEquals(WanStatus.DISABLED, chat.wanStatus.value)
    }

    /** kickWan() (p. ej. cambio de red) adelanta el ciclo WAN sin esperar los 30 s. */
    @Test
    fun `kickWan runs the next WAN cycle immediately`() = runTest {
        val signaling = FakeSignaling()
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        chat.setBootstrap(validAddr)
        testScheduler.runCurrent()
        assertEquals(1, signaling.connectCalls) // primer ciclo

        chat.kickWan()
        testScheduler.runCurrent() // sin avanzar el reloj virtual
        assertEquals(2, signaling.connectCalls) // el kick adelantó el ciclo

        testScheduler.advanceTimeBy(30_001)
        testScheduler.runCurrent()
        assertEquals(3, signaling.connectCalls) // y el ritmo normal sigue intacto
    }

    /** Con el wake conectado el bucle se relaja (3 min); sin él, sigue ágil (30 s). */
    @Test
    fun `wan loop relaxes its interval when wake is connected`() = runTest {
        val signaling = FakeSignaling().apply { wakeUp = true }
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        chat.setBootstrap(validAddr)
        testScheduler.runCurrent()
        assertEquals(1, signaling.connectCalls) // primer ciclo

        // A los 30 s NO hay nuevo ciclo (el intervalo relajado es 3 min).
        testScheduler.advanceTimeBy(31_000)
        testScheduler.runCurrent()
        assertEquals(1, signaling.connectCalls)

        // Pasados 3 min sí corre el siguiente ciclo.
        testScheduler.advanceTimeBy(150_000)
        testScheduler.runCurrent()
        assertEquals(2, signaling.connectCalls)
    }

    /** El aviso de wake del nodo dispara una retirada inmediata del buzón. */
    @Test
    fun `wake event triggers an immediate mailbox fetch`() = runTest {
        val signaling = FakeSignaling()
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)
        testScheduler.runCurrent() // el colector de eventos queda suscrito

        signaling.events.emit(SignalingEvent.WakeReceived)
        testScheduler.runCurrent()

        assertEquals(1, signaling.fetchCalls)
        assertTrue(chat.wanStatus.value == WanStatus.DISABLED) // el wake no toca el estado WAN
    }

    /** Configurar la WAN también suscribe el stream de wake; desactivarla lo corta. */
    @Test
    fun `wan start and stop toggle the wake subscription`() = runTest {
        val signaling = FakeSignaling()
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        chat.setBootstrap(validAddr)
        testScheduler.runCurrent()
        assertTrue(signaling.wakeRunning)

        chat.setBootstrap("")
        testScheduler.runCurrent()
        assertFalse(signaling.wakeRunning)
    }

    /** Regresión gap 1: vaciar el campo debe detener el bucle WAN en vivo, no solo al reiniciar. */
    @Test
    fun `clearing bootstrap stops the WAN loop live`() = runTest {
        val signaling = FakeSignaling()
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        assertEquals(BootstrapResult.OK, chat.setBootstrap(validAddr))
        testScheduler.runCurrent()
        assertEquals(WanStatus.CONNECTED, chat.wanStatus.value)

        assertEquals(BootstrapResult.CLEARED, chat.setBootstrap(""))
        testScheduler.runCurrent()
        assertEquals(WanStatus.DISABLED, chat.wanStatus.value)
        assertEquals("", signaling.lastSetBootstrap)  // persistió "solo LAN"
    }

    // --- Ack-tras-persistir del buzón (v2, 5 jul) -------------------------------------

    /** El procesador del buzón confirma (true) solo lo persistido: un fallo de Room → false. */
    @Test
    fun `mailbox processor acks after persist and rejects on storage failure`() = runTest {
        val signaling = FakeSignaling()
        val messages = FakeMessages()
        ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)
        val processor = signaling.registeredMailboxProcessor!! // registrado en el init

        val ciphertext = cipher.encrypt(secret, MessageEnvelope.encodeText("mid-7", "hola".toByteArray()))
        assertTrue(processor(contact.peerId, ciphertext, "env-1", 111L))
        assertEquals(1, messages.saved.size)

        // Room falla → false (sin ack): el nodo reentregará el sobre.
        messages.failOnSave = true
        val other = cipher.encrypt(secret, MessageEnvelope.encodeText("mid-8", "otro".toByteArray()))
        assertFalse(processor(contact.peerId, other, "env-2", 222L))
        assertEquals(1, messages.saved.size)

        // Reentrega tras recuperarse: persiste una sola vez.
        messages.failOnSave = false
        assertTrue(processor(contact.peerId, other, "env-2", 222L))
        assertEquals(2, messages.saved.size)
    }

    // --- Aviso garantizado del entrante (13 ago) --------------------------------------

    /**
     * Regresión del "llegó el mensaje pero no sonó nada": el aviso se dispara con el gancho
     * directo, **sin ningún suscriptor de `incoming`** — que es la situación real cuando el
     * OEM mata el proceso y lo revive solo la alarma del latido (el servicio en primer plano,
     * único coleccionador, no existe y el SharedFlow sin replay descartaba la emisión).
     */
    @Test
    fun `incoming notifier fires without any subscriber to the incoming flow`() = runTest {
        val signaling = FakeSignaling()
        val messages = FakeMessages()
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)
        val avisados = mutableListOf<Pair<String, String>>()
        chat.setIncomingNotifier { c, m -> avisados.add(c.id to m.id) }
        val processor = signaling.registeredMailboxProcessor!!

        // Texto por buzón, archivo troceado por buzón y llamada perdida: los tres avisan.
        val texto = cipher.encrypt(secret, MessageEnvelope.encodeText("mid-1", "hola".toByteArray()))
        assertTrue(processor(contact.peerId, texto, "env-1", 111L))
        assertTrue(processor(contact.peerId, cipher.encrypt(secret, MessageEnvelope.encodeFileMeta("f1", "n.txt", "text/plain", 4L, 1)), "env-2", 222L))
        assertTrue(processor(contact.peerId, cipher.encrypt(secret, MessageEnvelope.encodeFileChunk("f1", 0, "hola".toByteArray())), "env-3", 333L))
        chat.recordMissedCall(contact)

        assertEquals(listOf("mid-1", "f1"), avisados.take(2).map { it.second })
        assertEquals(3, avisados.size) // + la fila de llamada perdida
        assertTrue(avisados.all { it.first == contact.id })
    }

    /** Un aviso que revienta no debe impedir el ack: el mensaje ya está persistido. */
    @Test
    fun `a failing notifier does not block the mailbox ack`() = runTest {
        val signaling = FakeSignaling()
        val messages = FakeMessages()
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)
        chat.setIncomingNotifier { _, _ -> error("NotificationManager murió") }

        val texto = cipher.encrypt(secret, MessageEnvelope.encodeText("mid-9", "hola".toByteArray()))
        assertTrue(signaling.registeredMailboxProcessor!!(contact.peerId, texto, "env-9", 1L))
        assertEquals(1, messages.saved.size)
    }

    /**
     * Regresión del latido: en un proceso revivido solo por la alarma nadie llamó a `start()`,
     * así que `pollOnce` se iba de vacío (sin bootstrap en memoria, y sin host nativo) — el
     * salvavidas de entrega no hacía nada justo en su escenario. Ahora arranca y usa el
     * bootstrap **persistido**.
     */
    @Test
    fun `pollOnce starts the host and falls back to the saved bootstrap`() = runTest {
        val signaling = FakeSignaling()
        signaling.bootstrapAddr = validAddr // pref guardada, pero start() nunca se llamó
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        chat.pollOnce()

        assertEquals(validAddr, signaling.connectedBootstrap)
        assertTrue(signaling.fetchCalls > 0)
    }

    /**
     * Regresión del 2 sep 2026 (medida en vivo): `pollOnce` hacía `connectDht` y **luego**
     * `fetchMailbox`, ambos sin plazo. Un nodo que acepta el TCP y no completa el handshake
     * dejaba el latido colgado en el primer paso, así que **nunca** retiraba el buzón: la red
     * de seguridad fallaba justo en el escenario para el que existe. Ahora el buzón se retira
     * pase lo que pase con el DHT.
     */
    @Test
    fun `pollOnce still fetches the mailbox when the DHT dial hangs`() = runTest {
        val signaling = FakeSignaling().apply {
            bootstrapAddr = validAddr
            hangOnConnect = true
        }
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        chat.pollOnce()

        assertTrue("debería intentar el DHT", signaling.connectCalls > 0)
        assertTrue("el buzón debe retirarse aunque el DHT cuelgue", signaling.fetchCalls > 0)
    }

    /** Lo mismo cuando el dial falla rápido en vez de colgarse. */
    @Test
    fun `pollOnce still fetches the mailbox when the DHT dial fails`() = runTest {
        val signaling = FakeSignaling().apply {
            bootstrapAddr = validAddr
            failOnConnect = true
        }
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        chat.pollOnce()

        assertTrue("el buzón debe retirarse aunque falle el DHT", signaling.fetchCalls > 0)
    }

    /** Un trozo que no se pudo persistir no se confirma; su reentrega completa el archivo. */
    @Test
    fun `file chunk that fails to stage is redelivered and completes the file`() = runTest {
        val senderSig = FakeSignaling()
        val sender = ChatService(senderSig, cipher, FakeMessages(), FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)
        val rxSig = FakeSignaling()
        val rxMessages = FakeMessages()
        val rxFileStore = FakeFileStore()
        ChatService(rxSig, cipher, rxMessages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), rxFileStore, backgroundScope)
        val processor = rxSig.registeredMailboxProcessor!!

        // 60 KiB → meta + 2 trozos, todos por buzón (como con el receptor offline).
        val fileBytes = ByteArray(60 * 1024) { (it % 251).toByte() }
        sender.sendFile(contact, "doc.pdf", "application/pdf", fileBytes)
        val envs = senderSig.sentAll
        assertEquals(3, envs.size)

        assertTrue(processor(contact.peerId, envs[0], "env-meta", 1L)) // meta
        assertTrue(processor(contact.peerId, envs[1], "env-k0", 2L)) // trozo 0

        // El trozo 1 no se puede persistir (proceso moribundo / disco): NO se confirma…
        rxFileStore.failOnChunk = true
        assertFalse(processor(contact.peerId, envs[2], "env-k1", 3L))
        assertEquals(0, rxMessages.saved.size) // el archivo no se dio por recibido

        // …y como quedó en el buzón, la reentrega lo completa (antes: pérdida silenciosa).
        rxFileStore.failOnChunk = false
        assertTrue(processor(contact.peerId, envs[2], "env-k1", 3L))
        assertArrayEquals(fileBytes, rxFileStore.assembled)
        assertEquals(1, rxMessages.saved.size)
        assertEquals(MessageStatus.DELIVERED, rxMessages.saved.single().status)
    }

    /** Vaciar un chat borra sus mensajes y pide borrar los archivos locales; el contacto queda. */
    @Test
    fun `clearConversation deletes messages and local files but keeps the contact`() = runTest {
        val messages = FakeMessages()
        val contacts = FakeContacts(listOf(contact))
        val fileStore = FakeFileStore()
        val chat = ChatService(FakeSignaling(), cipher, messages, contacts, FakeKeyExchange(), RendezvousService(), fileStore, backgroundScope)

        chat.send(contact, "un texto".toByteArray())
        val voiceNote = chat.sendFile(
            contact, "nota.m4a", "audio/mp4",
            ByteArray(1024) { 5 }, localPath = "/data/krypta_files/sent/nota.m4a",
        )
        assertEquals(2, messages.saved.size)

        chat.clearConversation(contact)

        assertTrue(messages.saved.isEmpty())
        // El archivo pidió su borrado local (staging/ensamblado + copia propia).
        assertEquals(listOf(voiceNote.id to "/data/krypta_files/sent/nota.m4a"), fileStore.deleted)
        // El contacto sobrevive (solo se vació el chat).
        assertEquals(contact, contacts.store[contact.id])
    }

    /** Eliminar un contacto borra también su conversación; el bucle WAN deja de anunciarlo. */
    @Test
    fun `deleteContact removes the contact and its conversation`() = runTest {
        val messages = FakeMessages()
        val contacts = FakeContacts(listOf(contact))
        val chat = ChatService(FakeSignaling(), cipher, messages, contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        chat.send(contact, "adiós".toByteArray())
        chat.deleteContact(contact)

        assertTrue(messages.saved.isEmpty())
        assertTrue(contacts.store.isEmpty()) // announceAndFind ya no lo verá (relee de Room)
    }

    // --- Bloqueo de contacto ---------------------------------------------------------------

    private val blockedContact = contact.copy(blocked = true)

    /**
     * Lo que llega de un bloqueado se descarta: ni se persiste, ni avisa, ni sale por el flujo
     * de señales de llamada. Y `onReceived` devuelve null **sin lanzar**, que es lo que ack'ea
     * el sobre en el buzón: si lanzara, el nodo se lo reentregaría en cada ciclo para siempre.
     */
    @Test
    fun `incoming from a blocked contact is dropped, not persisted and not notified`() = runTest {
        val signaling = FakeSignaling()
        val messages = FakeMessages()
        val contacts = FakeContacts(listOf(blockedContact))
        val chat = ChatService(signaling, cipher, messages, contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)
        var notified = 0
        chat.setIncomingNotifier { _, _ -> notified++ }

        val text = cipher.encrypt(secret, MessageEnvelope.encodeText("m1", "hola".toByteArray()))
        assertNull(chat.onReceived(contact.peerId, text, mailboxId = "env-1", ts = 1L))
        val invite = cipher.encrypt(secret, MessageEnvelope.encodeCall("invite", "call-1", 1L))
        assertNull(chat.onReceived(contact.peerId, invite, mailboxId = "env-2", ts = 2L))

        assertTrue(messages.saved.isEmpty())
        assertEquals(0, notified)
        // El procesador del buzón lo da por procesado → el nodo borra el sobre en vez de
        // reentregarlo (si no, un bloqueado llenaría el cupo del destinatario indefinidamente).
        val processor = signaling.registeredMailboxProcessor!!
        assertTrue(processor(contact.peerId, text, "env-3", 3L))
    }

    /** Desbloquear devuelve el camino normal: lo siguiente que llega sí se guarda. */
    @Test
    fun `unblocking restores delivery`() = runTest {
        val messages = FakeMessages()
        val contacts = FakeContacts(listOf(blockedContact))
        val chat = ChatService(FakeSignaling(), cipher, messages, contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        chat.setBlocked(blockedContact, false)
        assertFalse(contacts.store.getValue(contact.id).blocked)

        val text = cipher.encrypt(secret, MessageEnvelope.encodeText("m1", "hola".toByteArray()))
        assertEquals(MessageStatus.DELIVERED, chat.onReceived(contact.peerId, text)!!.status)
    }

    /** A un bloqueado no se le envía nada por ningún camino (mensaje, archivo o llamada). */
    @Test
    fun `nothing is sent to a blocked contact`() = runTest {
        val signaling = FakeSignaling()
        val messages = FakeMessages()
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(blockedContact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)

        for (send in listOf<suspend () -> Unit>(
            { chat.send(blockedContact, "hola".toByteArray()) },
            { chat.sendImage(blockedContact, ByteArray(16)) },
            { chat.sendFile(blockedContact, "f.bin", "application/octet-stream", ByteArray(16)) },
            { chat.sendCallSignal(blockedContact, "invite", "call-1") },
            // Tocar una burbuja FALLIDA de antes del bloqueo tampoco puede reenviarla.
            { chat.retry(blockedContact, "cualquier-id") },
        )) {
            runCatching { send() }.let { assertTrue("debería rechazarse", it.isFailure) }
        }
        assertTrue(signaling.sentAll.isEmpty())
        assertTrue(signaling.mailboxDeposits.isEmpty())
        assertTrue(messages.saved.isEmpty()) // ni siquiera queda una burbuja propia FAILED
    }

    /**
     * Abrir el chat de un bloqueado limpia el badge local pero **no** manda acuse de lectura:
     * un ✓✓ le diría que sigues ahí leyéndole, que es justo la señal que no debe recibir.
     */
    @Test
    fun `reading a blocked conversation clears the badge without sending a receipt`() = runTest {
        val signaling = FakeSignaling()
        val messages = FakeMessages()
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope)
        val text = cipher.encrypt(secret, MessageEnvelope.encodeText("m1", "hola".toByteArray()))
        chat.onReceived(contact.peerId, text)

        chat.markConversationRead(blockedContact)

        assertEquals(MessageStatus.READ, messages.saved.single().status) // solo vista local
        assertTrue(signaling.sentAll.isEmpty())
        assertTrue(signaling.mailboxDeposits.isEmpty())
    }

    /** El bloqueado sale del rendezvous: se deja de anunciar el punto de cita compartido. */
    @Test
    fun `a blocked contact is not announced in the rendezvous`() = runTest {
        val other = Contact(
            id = "c2", displayName = "Ana", peerId = "12D3KooWAna",
            publicKey = ByteArray(0), sharedSecret = ByteArray(32) { 9 },
        )
        val signaling = FakeSignaling()
        val rendezvous = RendezvousService()
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(listOf(blockedContact, other)), FakeKeyExchange(), rendezvous, FakeFileStore(), backgroundScope)

        chat.announceAndFind()

        assertEquals(1, signaling.announced.size)
        assertArrayEquals(rendezvous.rendezvousFor(other.sharedSecret!!), signaling.announced.single())
    }
}

package chat.neto.krypta.p2p

import chat.neto.krypta.core.ISignalingService
import chat.neto.krypta.core.KeyExchange
import chat.neto.krypta.core.SignalingEvent
import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.core.model.Message
import chat.neto.krypta.core.model.MessageContent
import chat.neto.krypta.core.model.MessageStatus
import chat.neto.krypta.core.repository.ContactRepository
import chat.neto.krypta.core.repository.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
        val allowedPeers = mutableListOf<String>()
        override suspend fun setAllowedPeers(peers: String) { allowedPeers.add(peers) }
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
        /** Intentos de entrega que fallan por las dos vías (directo y buzón) antes de ir bien. */
        var flakyAttempts = 0
        val sentAll = mutableListOf<ByteArray>()
        override suspend fun send(contact: Contact, ciphertext: ByteArray) {
            if (failOnSend || flakyAttempts > 0) error("failed to dial ${contact.peerId}: no addresses")
            sentCiphertext = ciphertext
            sentAll.add(ciphertext)
        }
        var failOnMailbox = false
        var mailboxDeposits = mutableListOf<Pair<String, ByteArray>>()
        /** Etiquetas con las que se depositó (vacía = camino antiguo por PeerID). */
        val depositLabels = mutableListOf<String>()
        override suspend fun sendOffline(contact: Contact, ciphertext: ByteArray, label: String) {
            if (flakyAttempts > 0) { flakyAttempts--; error("buzón lleno") }
            if (failOnMailbox) error("connect buzón: sin ruta al nodo")
            mailboxDeposits.add(contact.peerId to ciphertext)
            depositLabels.add(label)
        }
        var mailboxPending = 0
        var fetchCalls = 0
        /** Etiquetas pedidas en la última retirada (una por línea). */
        var fetchedLabels: String = ""
        override suspend fun fetchMailbox(labels: String): Int {
            fetchCalls++
            fetchedLabels = labels
            return mailboxPending.also { mailboxPending = 0 }
        }
        var wakeRunning = false
        var wakeLabels: String = ""
        override suspend fun startWake(labels: String) { wakeRunning = true; wakeLabels = labels }
        override suspend fun stopWake() { wakeRunning = false }
        var wakeUp = false
        override suspend fun wakeConnected(): Boolean = wakeUp
        /** Procesador registrado por ChatService: los tests lo invocan como haría el fetch. */
        var registeredMailboxProcessor: (suspend (String, ByteArray, String, Long, String) -> Boolean)? = null
        override fun setMailboxProcessor(
            processor: suspend (
                fromPeerId: String,
                ciphertext: ByteArray,
                envelopeId: String,
                timestamp: Long,
                label: String,
            ) -> Boolean,
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
        override suspend fun saveAll(messages: List<Message>) { messages.forEach { save(it) } }
        override suspend fun findEncrypted(limit: Int, offset: Int): List<Message> =
            saved.filter { it.encrypted }.drop(offset).take(limit)
        override suspend fun findById(id: String): Message? = saved.find { it.id == id }
        override suspend fun findByStatus(status: MessageStatus, limit: Int): List<Message> =
            saved.filter { it.status == status }.sortedByDescending { it.timestamp }.take(limit)
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
        // El contrato de `ContactRepository.upsert` (y del SQL de Room): peerProtocol nunca baja.
        override suspend fun upsert(contact: Contact) {
            val previa = store[contact.id]?.peerProtocol ?: 0
            store[contact.id] = contact.copy(peerProtocol = maxOf(previa, contact.peerProtocol))
        }
        override suspend fun findById(id: String) = store[id]
        override suspend fun findByPeerId(peerId: String) = store.values.find { it.peerId == peerId }
        override suspend fun delete(id: String) { store.remove(id) }
    }

    private class FakeKeyExchange(private val me: String = "12D3KooWSelf") : KeyExchange {
        override fun localPeerId() = me
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
        /** Simula que no se pudo guardar la copia del emisor (o una fila de antes de guardarla). */
        var failSaveSent = false
        /** Borrados pedidos al vaciar un chat: (fileId, path de la copia local). */
        val deleted = mutableListOf<Pair<String, String?>>()
        /** Copias propias del emisor, por ruta. */
        val saved = mutableMapOf<String, ByteArray>()
        override suspend fun read(path: String): ByteArray? = saved[path]
        override suspend fun saveSent(name: String, bytes: ByteArray): String? =
            if (failSaveSent) null else "/fake/sent/$name".also { saved[it] = bytes }
        override suspend fun deleteLocal(fileId: String, path: String?) {
            deleted.add(fileId to path)
        }
    }

    @Test
    fun `send cifra para la red, guarda el sobre en claro y marca SENT`() = runTest {
        val signaling = FakeSignaling()
        val messages = FakeMessages()
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        val plaintext = "hola camarada".toByteArray()
        val sent = chat.send(contact, plaintext)

        // lo transmitido es ciphertext de un sobre cuyo cuerpo descifra al plaintext
        val env = MessageEnvelope.decode(cipher.decrypt(secret, signaling.sentCiphertext!!))
        assertArrayEquals(plaintext, (env as MessageEnvelope.Decoded.Text).body)
        assertEquals(sent.id, env.id) // el id del sobre = el id del mensaje persistido
        // persistido y marcado SENT
        assertEquals(MessageStatus.SENT, sent.status)
        assertEquals(MessageStatus.SENT, messages.saved.single().status)
        // Lo guardado es el SOBRE EN CLARO, no lo que viajó: desde la v8 el historial no se
        // puede guardar cifrado con la clave de transporte, porque el ratchet la borra al
        // usarla (ver docs/DISENO-ratchet.md §4). Lo que lo protege es el cifrado de la base.
        val guardado = messages.saved.single()
        assertFalse("no debe guardarse ya la clave de transporte", guardado.encrypted)
        assertFalse(
            "lo guardado no puede ser lo mismo que viajó por la red",
            guardado.payload.contentEquals(signaling.sentCiphertext!!),
        )
        val guardadoEnv = MessageEnvelope.decode(guardado.payload)
        assertArrayEquals(plaintext, (guardadoEnv as MessageEnvelope.Decoded.Text).body)
    }

    /** El fallo del envío directo cae al buzón (entrega offline) y el mensaje queda SENT. */
    @Test
    fun `send falls back to mailbox when dialing fails and marks SENT`() = runTest {
        val signaling = FakeSignaling().apply { failOnSend = true }
        val messages = FakeMessages()
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        val result = chat.send(contact, "hola".toByteArray())

        assertEquals(MessageStatus.FAILED, result.status)
        assertEquals(MessageStatus.FAILED, messages.saved.single().status)
        // Y el sobre queda guardado igual, para poder reintentarlo (aunque el envío fallara).
        val env = MessageEnvelope.decode(messages.saved.single().payload)
        assertArrayEquals("hola".toByteArray(), (env as MessageEnvelope.Decoded.Text).body)
    }

    /** Una reentrega del buzón (ack perdido) con el mismo id de sobre no duplica el mensaje. */
    @Test
    fun `mailbox redelivery with same envelope id does not duplicate`() = runTest {
        val messages = FakeMessages()
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        val sender = ChatService(senderSig, cipher, FakeMessages(), FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
        val rxFileStore = FakeFileStore()
        val receiver = ChatService(FakeSignaling(), cipher, FakeMessages(), FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), rxFileStore, backgroundScope, testSessions(FakeKeyExchange()))

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
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        val chat = ChatService(senderSig, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
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
        ChatService(rxSig, cipher, FakeMessages(), FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), rxFileStore, backgroundScope, testSessions(FakeKeyExchange()))
        val processor: suspend (String, ByteArray, String, Long) -> Boolean =
            { p, c, i, t -> rxSig.registeredMailboxProcessor!!(p, c, i, t, "") }
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
        val sender = ChatService(senderSig, cipher, senderMessages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
        val rxMessages = FakeMessages()
        val receiver = ChatService(FakeSignaling(), cipher, rxMessages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        val sender = ChatService(senderSig, cipher, FakeMessages(), FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
        val rxMessages = FakeMessages()
        val receiver = ChatService(FakeSignaling(), cipher, rxMessages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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

    /**
     * Regresión del 20 sep 2026 (dos móviles, PDF de 6,5 MB): reintentar un archivo FALLIDO
     * reenviaba **la fila**, que es el descriptor local — al otro lado salía una burbuja con
     * nombre y tamaño y sin archivo, y aquí quedaba como enviado. Tiene que reenviar la meta y
     * todos los trozos (mismo id) desde la copia local, y el receptor reensamblar el archivo.
     */
    @Test
    fun `reintentar un archivo fallido reenvia el archivo y no su descriptor`() = runTest {
        val senderSig = FakeSignaling().apply { failOnSend = true; failOnMailbox = true }
        val senderMessages = FakeMessages()
        val sender = ChatService(senderSig, cipher, senderMessages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
        val fileBytes = ByteArray(120 * 1024) { (it % 251).toByte() }

        val failed = sender.sendFile(contact, "libro.pdf", "application/pdf", fileBytes)
        assertEquals(MessageStatus.FAILED, failed.status)

        senderSig.failOnSend = false
        senderSig.sentAll.clear()
        val retried = sender.retry(contact, failed.id)!!

        assertEquals(MessageStatus.SENT, retried.status)
        assertEquals(failed.id, retried.id)
        assertEquals(4, senderSig.sentAll.size) // meta + 3 trozos, no un sobre suelto
        senderSig.sentAll.forEach {
            val env = MessageEnvelope.decode(cipher.decrypt(secret, it))
            assertFalse("viajó el descriptor local", env is MessageEnvelope.Decoded.FileDescriptor)
        }

        val rxFileStore = FakeFileStore()
        val rxMessages = FakeMessages()
        val receiver = ChatService(FakeSignaling(), cipher, rxMessages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), rxFileStore, backgroundScope, testSessions(FakeKeyExchange()))
        senderSig.sentAll.forEach { receiver.onReceived(contact.peerId, it) }
        assertArrayEquals(fileBytes, rxFileStore.assembled)
        assertEquals(failed.id, rxMessages.saved.single().id)
    }

    /** Un descriptor que llega por la red no pinta una burbuja de archivo inexistente. */
    @Test
    fun `un descriptor de archivo recibido por la red se descarta`() = runTest {
        val rxMessages = FakeMessages()
        val receiver = ChatService(FakeSignaling(), cipher, rxMessages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
        val descriptor = MessageEnvelope.encodeFileDescriptor(
            "libro.pdf", "application/pdf", 6_500_000, "/data/user/0/chat.neto.krypta/files/krypta_files/otro",
        )

        assertNull(receiver.onReceived(contact.peerId, cipher.encrypt(secret, descriptor)))
        assertNull(receiver.onReceived(contact.peerId, cipher.encrypt(secret, MessageEnvelope.encodeReply("x", descriptor))))
        assertTrue(rxMessages.saved.isEmpty())
    }

    /**
     * Una pieza que falla un momento (dial caído, buzón lleno a mitad de ráfaga) se reintenta
     * antes de dar el archivo por perdido: antes bastaba **un** fallo entre cientos de trozos.
     */
    @Test
    fun `una pieza que falla un momento se reintenta y el archivo sale entero`() = runTest {
        val senderSig = FakeSignaling().apply { flakyAttempts = 2 }
        val sender = ChatService(senderSig, cipher, FakeMessages(), FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
        val fileBytes = ByteArray(120 * 1024) { (it % 7).toByte() }

        val sent = sender.sendFile(contact, "doc.bin", "application/octet-stream", fileBytes)

        assertEquals(MessageStatus.SENT, sent.status)
        assertEquals(4, senderSig.sentAll.size)
        val rxFileStore = FakeFileStore()
        val receiver = ChatService(FakeSignaling(), cipher, FakeMessages(), FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), rxFileStore, backgroundScope, testSessions(FakeKeyExchange()))
        senderSig.sentAll.forEach { receiver.onReceived(contact.peerId, it) }
        assertArrayEquals(fileBytes, rxFileStore.assembled)
    }

    /** Sin copia local no hay nada que reenviar: se dice, no se manda nada ni se finge. */
    @Test
    fun `reintentar un archivo sin copia local falla con un aviso y no envia nada`() = runTest {
        val senderSig = FakeSignaling().apply { failOnSend = true; failOnMailbox = true }
        val messages = FakeMessages()
        val store = FakeFileStore().apply { failSaveSent = true }
        val sender = ChatService(senderSig, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), store, backgroundScope, testSessions(FakeKeyExchange()))
        val failed = sender.sendFile(contact, "viejo.pdf", "application/pdf", ByteArray(1024))

        senderSig.failOnSend = false
        senderSig.failOnMailbox = false
        val result = runCatching { sender.retry(contact, failed.id) }

        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("vuelve a adjuntarlo"))
        assertTrue(senderSig.sentAll.isEmpty())
        assertEquals(MessageStatus.FAILED, messages.saved.single().status)
    }

    /** La reconciliación de cada ciclo WAN también reenvía archivos, y los reenvía enteros. */
    @Test
    fun `retryFailed reenvia un archivo fallido desde su copia`() = runTest {
        val senderSig = FakeSignaling().apply { failOnSend = true; failOnMailbox = true }
        val messages = FakeMessages()
        val sender = ChatService(senderSig, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), scopeInmediato(), testSessions(FakeKeyExchange()))
        val failed = sender.sendFile(contact, "doc.bin", "application/octet-stream", ByteArray(100 * 1024) { 1 })
        assertEquals(MessageStatus.FAILED, failed.status)

        senderSig.failOnSend = false
        senderSig.sentAll.clear()
        sender.retryFailed() // lanza el reenvío en el scope, que aquí corre en el acto

        val diag = sender.log.value.joinToString("\n")
        assertEquals(diag, MessageStatus.SENT, messages.saved.single().status)
        assertEquals(diag, 4, senderSig.sentAll.size) // meta + 3 trozos
    }

    @Test
    fun `retry re-sends a FAILED message and marks SENT without duplicating`() = runTest {
        val signaling = FakeSignaling().apply { failOnSend = true; failOnMailbox = true }
        val messages = FakeMessages()
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
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
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        chat.start() // no debe propagar la excepción de start()
        testScheduler.runCurrent() // deja correr la 1ª iteración del wanLoop (hasta su delay)

        assertEquals(signaling.bootstrapAddr, signaling.connectedBootstrap)
        assertEquals(WanStatus.CONNECTED, chat.wanStatus.value)
    }

    /** El número de seguridad es simétrico: ambos contactos ven el mismo (anti-MITM). */
    @Test
    fun `safety number matches on both sides`() = runTest {
        val contacts = FakeContacts(listOf(contact))
        val chat = ChatService(FakeSignaling(), cipher, FakeMessages(), contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        // Mi PeerID = FakeKeyExchange.localPeerId(); el del contacto = contact.peerId.
        val mine = chat.safetyNumber(contact)
        val theirs = SafetyNumber.compute(contact.peerId, "12D3KooWSelf")
        assertEquals(theirs, mine)
    }

    /** Verificar persiste el flag; re-añadir el mismo PeerID conserva la verificación. */
    @Test
    fun `verifying persists and re-adding same peerId keeps it`() = runTest {
        val contacts = FakeContacts(listOf(contact))
        val chat = ChatService(FakeSignaling(), cipher, FakeMessages(), contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        val chat = ChatService(FakeSignaling(), cipher, FakeMessages(), contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        val second = "/ip4/9.9.9.9/tcp/4001/p2p/12D3KooWSegundo"
        val result = chat.setBootstrap(" $validAddr \n$second\n")
        testScheduler.runCurrent()

        assertEquals(BootstrapResult.OK, result)
        assertEquals("$validAddr\n$second", signaling.lastSetBootstrap)
    }

    @Test
    fun `setBootstrap rejects invalid addr without persisting or starting WAN`() = runTest {
        val signaling = FakeSignaling()
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
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
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
        val processor: suspend (String, ByteArray, String, Long) -> Boolean =
            { p, c, i, t -> signaling.registeredMailboxProcessor!!(p, c, i, t, "") } // registrado en el init

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
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
        val avisados = mutableListOf<Pair<String, String>>()
        chat.setIncomingNotifier { c, m -> avisados.add(c.id to m.id) }
        val processor: suspend (String, ByteArray, String, Long) -> Boolean =
            { p, c, i, t -> signaling.registeredMailboxProcessor!!(p, c, i, t, "") }

        // Texto por buzón, archivo troceado por buzón y llamada perdida: los tres avisan.
        val texto = cipher.encrypt(secret, MessageEnvelope.encodeText("mid-1", "hola".toByteArray()))
        assertTrue(processor(contact.peerId, texto, "env-1", 111L))
        assertTrue(processor(contact.peerId, cipher.encrypt(secret, MessageEnvelope.encodeFileMeta("f1", "n.txt", "text/plain", 4L, 1)), "env-2", 222L))
        assertTrue(processor(contact.peerId, cipher.encrypt(secret, MessageEnvelope.encodeFileChunk("f1", 0, "hola".toByteArray())), "env-3", 333L))
        chat.recordMissedCall(contact, "call-1")

        assertEquals(listOf("mid-1", "f1"), avisados.take(2).map { it.second })
        assertEquals(3, avisados.size) // + la fila de llamada perdida
        assertTrue(avisados.all { it.first == contact.id })
    }

    /**
     * H-7: una llamada deja **una** fila de perdida y **un** aviso, la registre quien la registre y
     * cuantas veces llegue. Otra llamada del mismo contacto sí deja la suya.
     */
    @Test
    fun `la fila de llamada perdida es una por llamada y no vuelve a avisar`() = runTest {
        val messages = FakeMessages()
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
        val avisados = mutableListOf<String>()
        chat.setIncomingNotifier { _, m -> avisados.add(m.id) }

        chat.recordMissedCall(contact, "call-1")
        val fila = messages.saved.single()
        chat.recordMissedCall(contact, "call-1")
        chat.recordMissedCall(contact, "call-1")

        assertEquals(1, messages.saved.size)
        assertEquals("la segunda vez no la reescribe (volvería a no leída)", fila, messages.saved.single())
        assertEquals(1, avisados.size)

        chat.recordMissedCall(contact, "call-2")
        assertEquals(2, messages.saved.size)
        assertEquals(2, avisados.size)
    }

    /** El id no es el `callId` que manda el otro, y depende del contacto. */
    @Test
    fun `el id de la fila de llamada perdida no lo elige el otro extremo`() {
        val id = ChatService.missedCallId("contacto-a", "call-1")
        assertEquals(id, ChatService.missedCallId("contacto-a", "call-1"))
        assertTrue(id != "call-1")
        assertTrue(id != ChatService.missedCallId("contacto-b", "call-1"))
        assertTrue(id != ChatService.missedCallId("contacto-a", "call-2"))
    }

    /**
     * El id de la fila de llamada perdida es el de la especificación §8: SHA-256 de la etiqueta, el
     * contacto y el `callId`, separados por **el byte 0**. El valor esperado se calcula aquí aparte,
     * byte a byte, para que un cambio en cómo se escribe el separador en el código fuente no pueda
     * alterar el id sin que se note (el 15 sep 2026 se coló un NUL literal en ese fuente; se cambió
     * por su escape sin tocar el valor).
     */
    @Test
    fun `el id de la fila de llamada perdida es el de la especificacion`() {
        val esperado = java.security.MessageDigest.getInstance("SHA-256").run {
            update("krypta-missed-call-v1".toByteArray(Charsets.UTF_8))
            update(0.toByte())
            update("contacto-a".toByteArray(Charsets.UTF_8))
            update(0.toByte())
            update("call-1".toByteArray(Charsets.UTF_8))
            digest().joinToString("") { "%02x".format(it) }
        }
        assertEquals(esperado, ChatService.missedCallId("contacto-a", "call-1"))
    }

    /** Un aviso que revienta no debe impedir el ack: el mensaje ya está persistido. */
    @Test
    fun `a failing notifier does not block the mailbox ack`() = runTest {
        val signaling = FakeSignaling()
        val messages = FakeMessages()
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
        chat.setIncomingNotifier { _, _ -> error("NotificationManager murió") }

        val texto = cipher.encrypt(secret, MessageEnvelope.encodeText("mid-9", "hola".toByteArray()))
        assertTrue(signaling.registeredMailboxProcessor!!(contact.peerId, texto, "env-9", 1L, ""))
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
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(emptyList()), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        chat.pollOnce()

        assertTrue("el buzón debe retirarse aunque falle el DHT", signaling.fetchCalls > 0)
    }

    /** Un trozo que no se pudo persistir no se confirma; su reentrega completa el archivo. */
    @Test
    fun `file chunk that fails to stage is redelivered and completes the file`() = runTest {
        val senderSig = FakeSignaling()
        val sender = ChatService(senderSig, cipher, FakeMessages(), FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
        val rxSig = FakeSignaling()
        val rxMessages = FakeMessages()
        val rxFileStore = FakeFileStore()
        ChatService(rxSig, cipher, rxMessages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), rxFileStore, backgroundScope, testSessions(FakeKeyExchange()))
        val processor: suspend (String, ByteArray, String, Long) -> Boolean =
            { p, c, i, t -> rxSig.registeredMailboxProcessor!!(p, c, i, t, "") }

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
        val chat = ChatService(FakeSignaling(), cipher, messages, contacts, FakeKeyExchange(), RendezvousService(), fileStore, backgroundScope, testSessions(FakeKeyExchange()))

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
        val chat = ChatService(FakeSignaling(), cipher, messages, contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        chat.send(contact, "adiós".toByteArray())
        chat.deleteContact(contact)

        assertTrue(messages.saved.isEmpty())
        assertTrue(contacts.store.isEmpty()) // announceAndFind ya no lo verá (relee de Room)
    }

    // --- Reengache de sesiones desincronizadas (DISENO-ratchet §1.9) ------------------------

    /** Un sobre con cabecera de ratchet que no se puede abrir por ninguna vía. */
    private fun sobreIlegible() = ByteArray(Ratchet.HEADER_BYTES + 24) { i ->
        if (i == 0) Ratchet.WIRE_VERSION else (i * 13 + 7).toByte()
    }

    /**
     * Scope para los tests del reengache, que se envía **lanzado** (el camino del buzón es
     * síncrono y no se puede bloquear con una llamada de red). `Unconfined` ejecuta el `launch`
     * en el acto y de forma determinista.
     *
     * Por qué no `backgroundScope` + `advanceUntilIdle()`, que sería lo natural: se midió y
     * **no ejecuta** el cuerpo del `launch` en este montaje. Costó tres hipótesis equivocadas
     * antes de comprobarlo en vez de razonarlo, así que queda escrito aquí.
     */
    private fun scopeInmediato() = CoroutineScope(Dispatchers.Unconfined)

    /**
     * La premisa de los tres tests de abajo: el sobre sintético tiene que **parecer** ratchet,
     * porque si no `onReceived` se va por la rama v1 y no hay reengache que probar. Se afirma
     * aparte para que un fallo diga *esto* en vez de "no salió el reengache".
     */
    @Test
    fun `el sobre sintetico parece un sobre de ratchet`() {
        val bytes = sobreIlegible()
        assertTrue(
            "size=${bytes.size} byte0=${bytes[0]} (esperado > ${Ratchet.HEADER_BYTES} y ${Ratchet.WIRE_VERSION})",
            Ratchet.looksLikeRatchet(bytes),
        )
    }

    /**
     * Cuando alguien reinstala, su linaje nuevo es mayor y **el nuestro se descarta**: sus
     * mensajes dejan de ser legibles para él hasta que escriba. Aquí somos el que lo sabe
     * —acabamos de fallar al abrir su sobre—, así que le mandamos algo para que adopte nuestro
     * linaje en vez de esperar a que el usuario escriba y perder mensajes por el camino.
     */
    @Test
    fun `un sobre de ratchet que no abre provoca un reengache`() = runTest {
        conRatchet {
            val signaling = FakeSignaling()
            val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(listOf(contactoV2)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), scopeInmediato(), testSessions(FakeKeyExchange()))

            assertNull(chat.onReceived(contactoV2.peerId, sobreIlegible()))

            // El log va en el mensaje a propósito: distingue "reengache enviado" de "no salió",
            // que es justo lo que no se puede adivinar desde un `expected 1 but was 0`.
            assertEquals("debería salir un reengache · log=${chat.log.value}", 1, signaling.sentAll.size)
        }
    }

    /**
     * Y **uno solo**: cualquiera de tus contactos podría mandar basura a propósito, y sin tope
     * eso nos haría emitir un mensaje por cada una.
     */
    @Test
    fun `el reengache no se repite con cada fallo`() = runTest {
        conRatchet {
            val signaling = FakeSignaling()
            val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(listOf(contactoV2)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), scopeInmediato(), testSessions(FakeKeyExchange()))

            repeat(5) { chat.onReceived(contactoV2.peerId, sobreIlegible()) }

            assertEquals(
                "cinco fallos seguidos, un solo reengache · log=${chat.log.value}",
                1,
                signaling.sentAll.size,
            )
        }
    }

    /**
     * Este test decía lo contrario hasta el 14 sep 2026 («sin ratchet no hay linajes que
     * desincronizar: no hay nada que reenganchar»), y ese era justo el punto ciego del hallazgo
     * H-1 de `docs/REVISION-protocolo-2026-09-14.md`: un contacto que aquí consta como v1 **y nos
     * escribe por ratchet** es uno cuya versión perdimos (lo borramos y lo volvimos a añadir,
     * importamos un `.krbk`). Callarse dejaba sus mensajes perdiéndose para siempre. Ahora se le
     * manda nuestro anuncio **por la clave estática** —sin sesión es lo único que abre— diciendo
     * qué tenemos apuntado de él, para que nos repita el suyo. Y sigue siendo uno solo por mucho
     * que insista, porque cualquiera de tus contactos podría mandar basura a propósito.
     */
    @Test
    fun `un contacto que consta como v1 y escribe por ratchet recibe un anuncio por la clave estatica`() = runTest {
        conRatchet {
            val signaling = FakeSignaling()
            val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), scopeInmediato(), testSessions(FakeKeyExchange()))

            repeat(3) { chat.onReceived(contact.peerId, sobreIlegible()) }

            assertEquals("uno solo, por mucho que insista · log=${chat.log.value}", 1, signaling.sentAll.size)
            // Se abre con la clave estática: si fuera por ratchet, esto lanzaría.
            val hello = MessageEnvelope.decode(cipher.decrypt(secret, signaling.sentAll.single()))
                as MessageEnvelope.Decoded.Hello
            assertEquals(ChatService.PROTOCOL_VERSION, hello.protocol)
            assertEquals("le decimos lo que tenemos apuntado de él", 0, hello.knows)
        }
    }

    // --- Filtro de quién puede abrirnos conexión (fuga de IP, security-model §5.1) ----------

    /**
     * La lista que se le pasa al transporte lleva los contactos **y los nodos**. Los nodos son
     * imprescindibles: el relay y AutoNAT necesitan poder hablarnos de vuelta, y si se
     * quedaran fuera la app se quedaría sin red.
     */
    @Test
    fun `the allowed-peer list carries contacts and infra nodes`() = runTest {
        val signaling = FakeSignaling()
        signaling.bootstrapAddr = "/ip4/1.2.3.4/tcp/4001/p2p/12D3KooWNodo"
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        chat.announceAndFind()

        val ultima = signaling.allowedPeers.last().lines()
        assertTrue("falta el contacto: $ultima", ultima.contains(contact.peerId))
        assertTrue("falta el nodo: $ultima", ultima.contains("12D3KooWNodo"))
    }

    /**
     * Un bloqueado no entra en la lista: además de no recibir nada, deja de poder sacarnos la
     * IP (que es lo que consigue quien logra abrirnos una conexión).
     */
    @Test
    fun `a blocked contact is left out of the allowed-peer list`() = runTest {
        val signaling = FakeSignaling()
        signaling.bootstrapAddr = "/ip4/1.2.3.4/tcp/4001/p2p/12D3KooWNodo"
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(listOf(contact.copy(blocked = true))), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        chat.refreshAllowedPeers()

        val ultima = signaling.allowedPeers.last().lines()
        assertFalse("el bloqueado no debería estar: $ultima", ultima.contains(contact.peerId))
        assertTrue("el nodo sí debería estar: $ultima", ultima.contains("12D3KooWNodo"))
    }

    /** El PeerID sale del multiaddr, también con `/p2p-circuit` detrás (direcciones de relay). */
    @Test
    fun `bootstrapPeerIds extracts one id per node, circuit form included`() = runTest {
        val chat = ChatService(FakeSignaling(), cipher, FakeMessages(), FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        val ids = chat.bootstrapPeerIds(
            "/ip4/1.2.3.4/tcp/4001/p2p/12D3KooWUno\n" +
                "  \n" +
                "/dns4/nodo.example/tcp/443/wss/p2p/12D3KooWDos\n" +
                "/ip4/1.2.3.4/tcp/4001/p2p/12D3KooWUno/p2p-circuit\n" +
                "esto-no-es-un-multiaddr",
        )

        assertEquals(listOf("12D3KooWUno", "12D3KooWDos"), ids)
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
        val chat = ChatService(signaling, cipher, messages, contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
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
        val processor: suspend (String, ByteArray, String, Long) -> Boolean =
            { p, c, i, t -> signaling.registeredMailboxProcessor!!(p, c, i, t, "") }
        assertTrue(processor(contact.peerId, text, "env-3", 3L))
    }

    /** Desbloquear devuelve el camino normal: lo siguiente que llega sí se guarda. */
    @Test
    fun `unblocking restores delivery`() = runTest {
        val messages = FakeMessages()
        val contacts = FakeContacts(listOf(blockedContact))
        val chat = ChatService(FakeSignaling(), cipher, messages, contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(blockedContact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

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
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
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
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(listOf(blockedContact, other)), FakeKeyExchange(), rendezvous, FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        chat.announceAndFind()

        assertEquals(1, signaling.announced.size)
        assertArrayEquals(rendezvous.rendezvousFor(other.sharedSecret!!), signaling.announced.single())
    }

    // --- Reconciliación de envíos fallidos (Fase 4 del plan; hallazgo A-7) ---

    @Test
    fun `retryFailed reenvia los FALLIDOS cuando vuelve la conexion`() = runTest {
        val signaling = FakeSignaling()
        val messages = FakeMessages()
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        // Un envío que falla por las dos vías queda FAILED (antes se quedaba así para siempre).
        signaling.failOnSend = true
        signaling.failOnMailbox = true
        val failed = chat.send(contact, "no salió".toByteArray())
        assertEquals(MessageStatus.FAILED, failed.status)

        // Vuelve la conexión: el ciclo WAN reintenta solo.
        signaling.failOnSend = false
        signaling.failOnMailbox = false
        chat.retryFailed()

        assertEquals(MessageStatus.SENT, messages.findById(failed.id)!!.status)
        // Desde la v8 el sobre se guarda en claro, así que reintentar **vuelve a cifrarlo**:
        // los bytes de la red no son los mismos, pero el sobre —y sobre todo su id, que es
        // por donde el receptor deduplica— sí. Eso es lo que impide que salga duplicado.
        val reenviado = MessageEnvelope.decode(cipher.decrypt(secret, signaling.sentCiphertext!!))
        assertEquals(failed.id, (reenviado as MessageEnvelope.Decoded.Text).id)
        assertArrayEquals("no salió".toByteArray(), reenviado.body)
    }

    @Test
    fun `retryFailed no resucita un fallo antiguo`() = runTest {
        val signaling = FakeSignaling()
        val messages = FakeMessages()
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        val viejo = Message(
            id = "hace-semanas",
            conversationId = contact.id,
            senderId = "self",
            payload = MessageEnvelope.encodeText("hace-semanas", "viejo".toByteArray()),
            timestamp = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000,
            status = MessageStatus.FAILED,
        )
        messages.save(viejo)

        chat.retryFailed()

        assertTrue("un mensaje de hace un mes no debe salir solo", signaling.sentAll.isEmpty())
        assertEquals(MessageStatus.FAILED, messages.findById(viejo.id)!!.status)
    }

    @Test
    fun `retryFailed no reenvia nada a un contacto bloqueado`() = runTest {
        val signaling = FakeSignaling()
        val messages = FakeMessages()
        val contacts = FakeContacts(listOf(contact))
        val chat = ChatService(signaling, cipher, messages, contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        signaling.failOnSend = true
        signaling.failOnMailbox = true
        val failed = chat.send(contact, "no salió".toByteArray())
        signaling.failOnSend = false
        signaling.failOnMailbox = false

        chat.setBlocked(contact, true)
        chat.retryFailed()

        assertEquals(MessageStatus.FAILED, messages.findById(failed.id)!!.status)
        assertTrue("a un bloqueado no se le manda nada", signaling.sentAll.isEmpty())
    }

    // --- fase 6: el envío con ratchet, encendido contacto a contacto ---

    /**
     * Ejecuta [body] con el envío por ratchet encendido y **deja el interruptor como estaba**.
     * Restaurarlo a `false` a pelo era un error latente: cuando el valor de producción pasó a
     * `true` (10 sep 2026), este helper lo habría apagado para el resto de la suite.
     */
    private inline fun conRatchet(body: () -> Unit) {
        val antes = ChatService.RATCHET_SEND
        ChatService.RATCHET_SEND = true
        try { body() } finally { ChatService.RATCHET_SEND = antes }
    }

    /** El contacto de enfrente, que ya anunció que sabe recibir v2. */
    private val contactoV2 get() = contact.copy(peerProtocol = ChatService.PROTOCOL_VERSION)

    private fun parte(
        me: String,
        peer: Contact,
        signaling: FakeSignaling = FakeSignaling(),
        messages: FakeMessages = FakeMessages(),
        fileStore: FakeFileStore = FakeFileStore(),
        contacts: FakeContacts = FakeContacts(listOf(peer)),
        scope: kotlinx.coroutines.CoroutineScope,
    ) = ChatService(
        signaling, cipher, messages, contacts, FakeKeyExchange(me), RendezvousService(),
        fileStore, scope, testSessions(FakeKeyExchange(me)),
    )

    @Test
    fun `a quien ha anunciado v2 se le escribe con ratchet, y al resto no`() = runTest {
        conRatchet {
            val conRatchetSignaling = FakeSignaling()
            val chatV2 = parte("12D3KooWSelf", contactoV2, conRatchetSignaling, scope = backgroundScope)
            kotlinx.coroutines.runBlocking { chatV2.send(contactoV2, "para el nuevo".toByteArray()) }
            assertTrue(
                "un contacto que anunció v2 debe recibir un sobre de ratchet",
                Ratchet.looksLikeRatchet(conRatchetSignaling.sentCiphertext!!),
            )

            // El mismo build, un contacto que no ha anunciado nada: sigue en v1.
            val v1Signaling = FakeSignaling()
            val chatV1 = parte("12D3KooWSelf", contact, v1Signaling, scope = backgroundScope)
            kotlinx.coroutines.runBlocking { chatV1.send(contact, "para el de siempre".toByteArray()) }
            val env = MessageEnvelope.decode(cipher.decrypt(secret, v1Signaling.sentCiphertext!!))
            assertEquals("para el de siempre", String((env as MessageEnvelope.Decoded.Text).body))
        }
    }

    /** Ida y vuelta completa entre dos clientes, cada uno con su propio estado de ratchet. */
    @Test
    fun `dos clientes conversan por ratchet de extremo a extremo`() = runTest {
        conRatchet {
            val contactoDeA = contact.copy(peerProtocol = 2) // para A, el otro es Bob
            // Ojo al id: "self" es el marcador de emisor propio en Room, así que un contacto
            // con ese id haría indistinguibles las dos mitades de la conversación.
            val contactoDeB = Contact(
                id = "contacto-a", displayName = "Yo", peerId = "12D3KooWSelf",
                publicKey = ByteArray(0), sharedSecret = secret, peerProtocol = 2,
            )
            val sigA = FakeSignaling()
            val sigB = FakeSignaling()
            val msgsB = FakeMessages()
            val a = parte("12D3KooWSelf", contactoDeA, sigA, scope = backgroundScope)
            val b = parte("12D3KooWBob", contactoDeB, sigB, messages = msgsB, scope = backgroundScope)

            kotlinx.coroutines.runBlocking {
                repeat(3) { i ->
                    val enviado = a.send(contactoDeA, "mensaje $i".toByteArray())
                    val recibido = b.onReceived("12D3KooWSelf", sigA.sentCiphertext!!)!!
                    assertEquals(enviado.id, recibido.id)
                    assertEquals(
                        "mensaje $i",
                        (b.content(contactoDeB, recibido) as MessageContent.Text).text,
                    )
                    // Y de vuelta, que es lo que hace girar la época.
                    val vuelta = b.send(contactoDeB, "respuesta $i".toByteArray())
                    val enA = a.onReceived("12D3KooWBob", sigB.sentCiphertext!!)!!
                    assertEquals(vuelta.id, enA.id)
                }
            }
            // B acaba con las dos mitades de la conversación: 3 recibidos y 3 enviados.
            assertEquals(3, msgsB.saved.count { it.senderId == contactoDeB.id })
            assertEquals(3, msgsB.saved.count { it.senderId == "self" })
            assertTrue("nada se guarda ya cifrado con la clave estática", msgsB.saved.none { it.encrypted })
        }
    }

    /** Un archivo troceado (ráfaga de sobres) entre dos extremos con ratchet. */
    @Test
    fun `un archivo troceado viaja completo por ratchet`() = runTest {
        conRatchet {
            val contactoDeA = contact.copy(peerProtocol = 2)
            // Ojo al id: "self" es el marcador de emisor propio en Room, así que un contacto
            // con ese id haría indistinguibles las dos mitades de la conversación.
            val contactoDeB = Contact(
                id = "contacto-a", displayName = "Yo", peerId = "12D3KooWSelf",
                publicKey = ByteArray(0), sharedSecret = secret, peerProtocol = 2,
            )
            val sigA = FakeSignaling()
            val fsB = FakeFileStore()
            val a = parte("12D3KooWSelf", contactoDeA, sigA, scope = backgroundScope)
            val b = parte("12D3KooWBob", contactoDeB, FakeSignaling(), fileStore = fsB, scope = backgroundScope)

            val bytes = ByteArray(120_000) { (it % 251).toByte() }
            kotlinx.coroutines.runBlocking {
                a.send(contactoDeA, "voy a mandarte algo".toByteArray())
                sigA.sentAll.clear()
                a.sendFile(contactoDeA, "cosa.bin", "application/octet-stream", bytes)
                // Todo lo que salió (meta + trozos) entra por el camino v2 del receptor.
                for (wire in sigA.sentAll.toList()) {
                    assertTrue("cada sobre debe ir con ratchet", Ratchet.looksLikeRatchet(wire))
                    b.onReceived("12D3KooWSelf", wire)
                }
            }
            assertArrayEquals("el archivo debe llegar idéntico", bytes, fsB.assembled)
        }
    }

    /**
     * El estado del ratchet se guarda **antes** de que los bytes salgan. Si se guardara después
     * y el envío fallara, el siguiente mensaje reutilizaría la misma clave —y el mismo nonce de
     * AES-GCM—, que es la forma clásica de romper del todo un cifrado autenticado.
     */
    @Test
    fun `un envio que falla deja el ratchet avanzado y no repite clave`() = runTest {
        conRatchet {
            val signaling = FakeSignaling().apply { failOnSend = true; failOnMailbox = true }
            val store = FakeRatchetStore()
            val sessions = testSessions(FakeKeyExchange("12D3KooWSelf"), store)
            val chat = ChatService(
                signaling, cipher, FakeMessages(), FakeContacts(listOf(contactoV2)),
                FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, sessions,
            )

            kotlinx.coroutines.runBlocking {
                val fallido = chat.send(contactoV2, "no sale".toByteArray())
                assertEquals(MessageStatus.FAILED, fallido.status)
                assertTrue("el estado debe estar guardado aunque el envío fallara", store.sessions.isNotEmpty())
                val primerEstado = store.sessions.getValue(contactoV2.id).toList()

                signaling.failOnSend = false
                signaling.failOnMailbox = false
                chat.send(contactoV2, "este sí".toByteArray())
                assertNotEquals(
                    "el segundo mensaje no puede salir del mismo estado que el primero",
                    primerEstado,
                    store.sessions.getValue(contactoV2.id).toList(),
                )
            }
        }
    }

    @Test
    fun `eliminar un contacto olvida su sesion de ratchet`() = runTest {
        conRatchet {
            val store = FakeRatchetStore()
            val sessions = testSessions(FakeKeyExchange("12D3KooWSelf"), store)
            val contacts = FakeContacts(listOf(contactoV2))
            val chat = ChatService(
                FakeSignaling(), cipher, FakeMessages(), contacts, FakeKeyExchange(),
                RendezvousService(), FakeFileStore(), backgroundScope, sessions,
            )

            kotlinx.coroutines.runBlocking {
                chat.send(contactoV2, "hola".toByteArray())
                assertTrue(store.sessions.isNotEmpty())
                chat.deleteContact(contactoV2)
                assertTrue("la sesión se va con el contacto", store.sessions.isEmpty())
            }
        }
    }

    // --- v2 del transporte: recepción por ratchet y anuncio de capacidad (fase 5) ---

    /** El ratchet del "otro extremo", para fabricar sobres v2 como los fabricaría su móvil. */
    private fun emisorRatchet(): Pair<Ratchet, RatchetState> {
        val ratchet = Ratchet(JdkCurve25519())
        // Los PeerID van cruzados respecto al receptor: el sentido de cada cadena sale de
        // ordenarlos, así que invertirlos sería exactamente el error que rompería la sesión.
        return ratchet to ratchet.initial(secret, contact.peerId, "12D3KooWSelf", lineage = 1_000L)
    }

    @Test
    fun `un sobre de ratchet entrante se abre y se persiste`() = runTest {
        val messages = FakeMessages()
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        val (ratchet, estado) = emisorRatchet()
        val sobre = MessageEnvelope.encodeText("rtc-1", "por ratchet".toByteArray())
        val wire = ratchet.encrypt(estado, sobre).ciphertext
        assertTrue("debe parecer un sobre v2", Ratchet.looksLikeRatchet(wire))

        val recibido = chat.onReceived(contact.peerId, wire)!!

        assertEquals("rtc-1", recibido.id)
        assertEquals("por ratchet", (chat.content(contact, recibido) as MessageContent.Text).text)
        assertFalse("se guarda el sobre en claro", messages.findById("rtc-1")!!.encrypted)
    }

    /**
     * La reentrega del buzón de un sobre v2 ya procesado **no puede parecer basura**: su clave
     * está gastada. Se reconoce por huella y se descarta, que es además lo que lo ack'ea en el
     * nodo en vez de dejarlo volviendo cada ciclo.
     */
    @Test
    fun `una reentrega de un sobre de ratchet se descarta sin duplicar`() = runTest {
        val messages = FakeMessages()
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        val (ratchet, estado) = emisorRatchet()
        val wire = ratchet.encrypt(estado, MessageEnvelope.encodeText("rtc-2", "una vez".toByteArray())).ciphertext

        assertNotNull(chat.onReceived(contact.peerId, wire, mailboxId = "env-1"))
        assertNull("la reentrega no debe crear otra burbuja", chat.onReceived(contact.peerId, wire, mailboxId = "env-1"))
        assertEquals(1, messages.saved.size)
    }

    @Test
    fun `un anuncio de capacidad apunta la version del contacto y no crea burbuja`() = runTest {
        val messages = FakeMessages()
        val contacts = FakeContacts(listOf(contact))
        val chat = ChatService(FakeSignaling(), cipher, messages, contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        val anuncio = cipher.encrypt(secret, MessageEnvelope.encodeHello(2))
        val resultado = chat.onReceived(contact.peerId, anuncio)

        assertNull("un anuncio no es un mensaje", resultado)
        assertTrue("no debe crear burbuja", messages.saved.isEmpty())
        assertEquals(2, contacts.findById(contact.id)!!.peerProtocol)
    }

    @Test
    fun `la capacidad se anuncia una sola vez por contacto`() = runTest {
        val signaling = FakeSignaling()
        val contacts = FakeContacts(listOf(contact))
        val chat = ChatService(signaling, cipher, FakeMessages(), contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        chat.announceCapabilities()
        chat.announceCapabilities()

        assertEquals("no debe repetirse en cada ciclo", 1, signaling.sentAll.size)
        val env = MessageEnvelope.decode(cipher.decrypt(secret, signaling.sentAll.single()))
        assertEquals(ChatService.PROTOCOL_VERSION, (env as MessageEnvelope.Decoded.Hello).protocol)
        assertEquals(ChatService.PROTOCOL_VERSION, contacts.findById(contact.id)!!.announcedProtocol)
    }

    /** Si no salió por ninguna vía, no se da por anunciado: se reintenta en el próximo ciclo. */
    @Test
    fun `un anuncio que no sale se reintenta`() = runTest {
        val signaling = FakeSignaling().apply { failOnSend = true; failOnMailbox = true }
        val contacts = FakeContacts(listOf(contact))
        val chat = ChatService(signaling, cipher, FakeMessages(), contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        chat.announceCapabilities()
        assertEquals(0, contacts.findById(contact.id)!!.announcedProtocol)

        signaling.failOnSend = false
        signaling.failOnMailbox = false
        chat.announceCapabilities()
        assertEquals(ChatService.PROTOCOL_VERSION, contacts.findById(contact.id)!!.announcedProtocol)
    }

    @Test
    fun `a un contacto bloqueado no se le anuncia nada`() = runTest {
        val signaling = FakeSignaling()
        val contacts = FakeContacts(listOf(contact.copy(blocked = true)))
        val chat = ChatService(signaling, cipher, FakeMessages(), contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        chat.announceCapabilities()

        assertTrue(signaling.sentAll.isEmpty())
        assertEquals(0, contacts.findById(contact.id)!!.announcedProtocol)
    }

    /**
     * **Uno de cada 256 mensajes v1 largos empieza por el byte de versión del ratchet**: el
     * sobre v1 es `nonce(12) ‖ ct+tag` y el nonce es aleatorio. No es un caso rebuscado, es el
     * ~0,4% de todo lo que pase de los 86 bytes — o sea, fotos, trozos de archivo y cualquier
     * texto de más de un par de líneas. (Lo más corto que eso nunca se confunde, porque
     * `looksLikeRatchet` exige además el tamaño mínimo de una cabecera; el test lo descubrió
     * solo, fallando con un mensaje corto que no había forma de disfrazar.)
     *
     * La cabecera es una pista para decidir en qué orden intentarlo; quien decide es el AEAD, y
     * si v2 no abre hay que caer a v1 en vez de perder el mensaje.
     */
    @Test
    fun `un mensaje v1 que parece de ratchet se entrega igual`() = runTest {
        val messages = FakeMessages()
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        // Se cifra hasta que el nonce empiece por 0x02, que es lo que pasa solo por azar.
        val texto = "hola de la vieja escuela, " + "y algo más de longitud para pasar de 86 bytes ".repeat(3)
        val sobre = MessageEnvelope.encodeText("v1-disfrazado", texto.toByteArray())
        var wire = cipher.encrypt(secret, sobre)
        var intentos = 0
        while (!Ratchet.looksLikeRatchet(wire) && intentos++ < 10_000) {
            wire = cipher.encrypt(secret, sobre)
        }
        assertTrue("no se pudo fabricar el caso en 10.000 intentos", Ratchet.looksLikeRatchet(wire))

        val recibido = chat.onReceived(contact.peerId, wire)!!

        assertEquals("v1-disfrazado", recibido.id)
        assertEquals(texto, (chat.content(contact, recibido) as MessageContent.Text).text)
    }

    /**
     * Lo que no se puede abrir por ninguna vía se descarta con una línea de diagnóstico. Antes
     * se persistía el ciphertext como "texto legado" y salía una burbuja de basura.
     */
    @Test
    fun `un mensaje ilegible no crea una burbuja de basura`() = runTest {
        val messages = FakeMessages()
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        val basura = ByteArray(64) { it.toByte() }
        assertNull(chat.onReceived(contact.peerId, basura, mailboxId = "env-basura"))
        assertTrue(messages.saved.isEmpty())
    }

    // --- v8: el historial deja de guardarse cifrado con la clave estática ---

    /**
     * Una fila anterior a la v8 guarda su ciphertext y **se sigue leyendo igual**. Es la
     * condición para que la conversión pueda ir en segundo plano sin que el usuario vea nada
     * raro mientras tanto (y para que una fila que no se pueda convertir nunca desaparezca).
     */
    @Test
    fun `un mensaje de antes de la v8 se lee sin convertirlo`() = runTest {
        val messages = FakeMessages()
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        val viejo = Message(
            id = "v7-1",
            conversationId = contact.id,
            senderId = contact.id,
            payload = cipher.encrypt(secret, MessageEnvelope.encodeText("v7-1", "del pasado".toByteArray())),
            timestamp = 1,
            status = MessageStatus.DELIVERED,
            encrypted = true,
        )
        messages.save(viejo)

        val contenido = chat.content(contact, viejo)
        assertEquals("del pasado", (contenido as MessageContent.Text).text)
    }

    @Test
    fun `unsealHistory convierte el historial y lo deja legible`() = runTest {
        val messages = FakeMessages()
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        repeat(3) { i ->
            messages.save(
                Message(
                    id = "v7-$i",
                    conversationId = contact.id,
                    senderId = contact.id,
                    payload = cipher.encrypt(secret, MessageEnvelope.encodeText("v7-$i", "hola $i".toByteArray())),
                    timestamp = i.toLong(),
                    status = MessageStatus.DELIVERED,
                    encrypted = true,
                ),
            )
        }

        chat.unsealHistory()

        assertTrue("no debe quedar nada cifrado con la clave estática", messages.saved.none { it.encrypted })
        repeat(3) { i ->
            val m = messages.findById("v7-$i")!!
            // Y el contenido es el mismo, ahora legible sin descifrar.
            assertEquals("hola $i", (chat.content(contact, m) as MessageContent.Text).text)
            val env = MessageEnvelope.decode(m.payload)
            assertEquals("v7-$i", (env as MessageEnvelope.Decoded.Text).id)
        }
    }

    /**
     * Regresión del bucle infinito: una fila que no se puede abrir (corrupta, o de un contacto
     * que ya no está) **no puede bloquear el resto del historial**. Sin el desplazamiento, la
     * consulta devolvería siempre la misma fila y el resto no se convertiría jamás.
     */
    @Test
    fun `unsealHistory salta lo que no puede abrir y sigue con el resto`() = runTest {
        val messages = FakeMessages()
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        fun legado(id: String, payload: ByteArray) = Message(
            id = id, conversationId = contact.id, senderId = contact.id,
            payload = payload, timestamp = 1, status = MessageStatus.DELIVERED, encrypted = true,
        )
        messages.save(legado("ok-1", cipher.encrypt(secret, MessageEnvelope.encodeText("ok-1", "uno".toByteArray()))))
        messages.save(legado("corrupto", "esto no descifra".toByteArray()))
        messages.save(legado("ok-2", cipher.encrypt(secret, MessageEnvelope.encodeText("ok-2", "dos".toByteArray()))))

        chat.unsealHistory(batch = 1)

        assertFalse(messages.findById("ok-1")!!.encrypted)
        assertFalse("el de después del corrupto también se convierte", messages.findById("ok-2")!!.encrypted)
        assertTrue("el ilegible se queda como estaba, no se destruye", messages.findById("corrupto")!!.encrypted)
    }

    /** Una fila anterior a la v8 se reenvía tal cual: sus bytes ya son los de la red. */
    @Test
    fun `reintentar un mensaje de antes de la v8 reenvia su ciphertext`() = runTest {
        val signaling = FakeSignaling()
        val messages = FakeMessages()
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        val original = cipher.encrypt(secret, MessageEnvelope.encodeText("v7-fallido", "reintento".toByteArray()))
        messages.save(
            Message(
                id = "v7-fallido", conversationId = contact.id, senderId = "self",
                payload = original, timestamp = System.currentTimeMillis(),
                status = MessageStatus.FAILED, encrypted = true,
            ),
        )

        chat.retry(contact, "v7-fallido")

        assertArrayEquals(original, signaling.sentCiphertext)
    }

    // --- El id lo elige el emisor: no debe poder pisar otra conversación (hallazgo A-9) ---

    @Test
    fun `un entrante no puede sobrescribir el mensaje de otra conversacion`() = runTest {
        val messages = FakeMessages()
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        val ajeno = Message(
            id = "id-en-disputa",
            conversationId = "otra-conversacion",
            senderId = "otra-conversacion",
            payload = "intacto".toByteArray(),
            timestamp = 1,
            status = MessageStatus.DELIVERED,
        )
        messages.save(ajeno)

        val forjado = cipher.encrypt(secret, MessageEnvelope.encodeText("id-en-disputa", "pisado".toByteArray()))
        val result = chat.onReceived(contact.peerId, forjado)

        assertNull("no debe persistirse encima de un mensaje ajeno", result)
        assertEquals(ajeno, messages.findById("id-en-disputa"))
    }

    @Test
    fun `un acuse de lectura solo marca mensajes de quien lo envia`() = runTest {
        val messages = FakeMessages()
        val chat = ChatService(FakeSignaling(), cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        val paraOtro = Message(
            id = "m-de-otro-chat",
            conversationId = "otra-conversacion",
            senderId = "self",
            payload = ByteArray(1),
            timestamp = 1,
            status = MessageStatus.SENT,
        )
        messages.save(paraOtro)

        val acuse = cipher.encrypt(secret, MessageEnvelope.encodeRead(listOf("m-de-otro-chat")))
        chat.onReceived(contact.peerId, acuse)

        assertEquals(
            "nadie puede marcar como leído lo que se envió a otro contacto",
            MessageStatus.SENT,
            messages.findById("m-de-otro-chat")!!.status,
        )
    }

    // --- Ventana de solape del rendezvous (hallazgo A-8) ---

    @Test
    fun `el rendezvous se anuncia con la ventana de solape del dia`() = runTest {
        val signaling = FakeSignaling()
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        chat.announceAndFind()

        // Sin fijar la hora: se comprueba la propiedad, no el instante — se anuncia la clave
        // de hoy y, como mucho, una de las contiguas (la ventana de solape).
        val rdv = RendezvousService()
        val hoy = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        val admisibles = listOf(hoy.minusDays(1), hoy, hoy.plusDays(1))
            .map { rdv.rendezvousFor(secret, it) }
        assertTrue("1 o 2 claves, nunca más", signaling.announced.size in 1..2)
        assertTrue(
            "la clave del día siempre se anuncia",
            signaling.announced.any { it.contentEquals(rdv.rendezvousFor(secret, hoy)) },
        )
        assertTrue(
            "no debe anunciarse ninguna clave fuera de la ventana",
            signaling.announced.all { a -> admisibles.any { it.contentEquals(a) } },
        )
    }

    // --- Buzón ciego: recepción por etiqueta (docs/DISENO-buzon-ciego.md) ---

    private fun etiquetaDe(c: Contact, chat: ChatService): String =
        MailboxLabel.toHex(
            MailboxLabel.outbox(c.sharedSecret!!, myPeerId = c.peerId, theirPeerId = chat.myPeerId()),
        )

    @Test
    fun `la retirada pide las etiquetas de recepcion de cada contacto`() = runTest {
        val signaling = FakeSignaling()
        signaling.bootstrapAddr = "/ip4/1.2.3.4/tcp/4001/p2p/12D3KooWNodo"
        val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        chat.pollOnce() // arranca y retira el buzón

        val pedidas = signaling.fetchedLabels.lines().filter { it.isNotBlank() }
        assertEquals("dos etiquetas por contacto: la semana en curso y la anterior", 2, pedidas.size)
        assertTrue("son las de recepción de ese contacto", pedidas.contains(etiquetaDe(contact, chat)))
    }

    @Test
    fun `un sobre ciego se atribuye al contacto por su etiqueta`() = runTest {
        val signaling = FakeSignaling()
        val messages = FakeMessages()
        signaling.bootstrapAddr = "/ip4/1.2.3.4/tcp/4001/p2p/12D3KooWNodo"
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
        chat.pollOnce() // construye el índice de etiquetas

        val ciphertext = cipher.encrypt(secret, MessageEnvelope.encodeText("mid-ciego", "hola a ciegas".toByteArray()))
        // Sin remitente: en v2 el nodo no lo manda. Quien identifica al contacto es la etiqueta.
        val ok = signaling.registeredMailboxProcessor!!("", ciphertext, "env-c", 1L, etiquetaDe(contact, chat))

        assertTrue("debía confirmarse tras persistir", ok)
        val guardado = messages.saved.single()
        assertEquals(contact.id, guardado.conversationId)
        assertEquals("el mensaje es del contacto, no propio", contact.id, guardado.senderId)
        assertEquals("hola a ciegas", String(chat.decrypt(contact, guardado)))
    }

    /**
     * **Limitación conocida, fijada a propósito** (H-5 de `docs/REVISION-protocolo-2026-09-14.md`,
     * W-3 de la especificación). La autenticación del remitente es la del secreto compartido `S`,
     * y `S` sale igual de la privada de cualquiera de los dos extremos. Quien robe **tu** identidad
     * —por ejemplo, tu `.krbk` con su frase— calcula el mismo `S` que cada uno de tus contactos
     * **sin tener la privada de ninguno**, y puede escribirte como cualquiera de ellos: suplantación
     * ante el compromiso de la propia clave (KCI). Krypta no promete resistirla (15 sep 2026).
     *
     * Lo que fija el test es **por qué vía entra y por cuál no**:
     *
     * - **buzón ciego**: el sobre no lleva remitente y lo atribuye la etiqueta, que también sale de
     *   `S`. Entra como del contacto;
     * - **buzón por PeerID, con un nodo honrado**: el nodo pone de remitente la identidad del stream,
     *   que es la robada, o sea tu propio PeerID. No es un contacto y no entra;
     * - **pero con un nodo que miente** sobre el remitente también entra: esa negativa depende de
     *   que el nodo sea honrado.
     *
     * Por stream directo no hace falta probarlo: el remitente lo autentica libp2p con la clave del
     * contacto, que el ladrón no tiene. Si algún cambio lo cierra (firmar los sobres con la
     * identidad), este test tiene que cambiar con él.
     */
    @Test
    fun `con tu identidad robada te pueden escribir como cualquier contacto por el buzon ciego`() = runTest {
        val curva = JdkCurve25519()
        val yo = curva.generateKeyPair() // este móvil, la víctima
        val bob = curva.generateKeyPair() // el contacto al que se suplanta
        val sDeMiMovil = curva.agree(yo.privateKey, bob.publicKey)
        // El ladrón tiene mi privada y la pública de Bob, que es su PeerID. La de Bob no le hace falta.
        val robada = yo.privateKey.copyOf()
        val sDelLadron = curva.agree(robada, bob.publicKey)
        assertArrayEquals(
            "sin la privada de Bob, el ladrón calcula el mismo S que Bob",
            curva.agree(bob.privateKey, yo.publicKey),
            sDelLadron,
        )

        val bobContacto = contact.copy(sharedSecret = sDeMiMovil)
        val signaling = FakeSignaling()
        val messages = FakeMessages()
        signaling.bootstrapAddr = "/ip4/1.2.3.4/tcp/4001/p2p/12D3KooWNodo"
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(bobContacto)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
        chat.pollOnce() // construye el índice de etiquetas
        val procesar = signaling.registeredMailboxProcessor!!

        // Todo lo que sigue lo fabrica el ladrón con S y los dos PeerID, nada más.
        fun comoBob(id: String, texto: String) =
            cipher.encrypt(sDelLadron, MessageEnvelope.encodeText(id, texto.toByteArray()))
        val etiqueta = MailboxLabel.toHex(
            MailboxLabel.outbox(sDelLadron, myPeerId = bobContacto.peerId, theirPeerId = chat.myPeerId()),
        )

        // 1. Buzón ciego: entra como de Bob.
        assertTrue(procesar("", comoBob("kci-ciego", "soy Bob"), "env-1", 1L, etiqueta))
        val suplantado = messages.saved.single()
        assertEquals("el buzón ciego lo atribuye a Bob", bobContacto.id, suplantado.senderId)
        assertEquals("soy Bob", String(chat.decrypt(bobContacto, suplantado)))

        // 2. Buzón por PeerID con un nodo honrado: el remitente es la identidad robada, la mía.
        procesar(chat.myPeerId(), comoBob("kci-peerid", "soy Bob otra vez"), "env-2", 2L, "")
        assertEquals("por PeerID y con un nodo honrado no entra", 1, messages.saved.size)

        // 3. Un nodo que miente sobre el remitente sí lo cuela.
        assertTrue(procesar(bobContacto.peerId, comoBob("kci-nodo", "soy Bob, dice el nodo"), "env-3", 3L, ""))
        assertEquals(2, messages.saved.size)
        assertEquals("con un nodo que miente, también entra", bobContacto.id, messages.saved.last().senderId)
    }

    @Test
    fun `una etiqueta desconocida no se confirma, para no destruir el sobre`() = runTest {
        val signaling = FakeSignaling()
        val messages = FakeMessages()
        signaling.bootstrapAddr = "/ip4/1.2.3.4/tcp/4001/p2p/12D3KooWNodo"
        val chat = ChatService(signaling, cipher, messages, FakeContacts(listOf(contact)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
        chat.pollOnce()

        val ajena = "f".repeat(64)
        val ok = signaling.registeredMailboxProcessor!!("", ByteArray(64), "env-x", 1L, ajena)

        // Confirmar lo borraría del nodo. Si la etiqueta no se resuelve —índice desfasado por
        // la rotación de semana, contacto recién añadido— más vale que vuelva en el próximo
        // ciclo que perderlo para siempre.
        assertFalse("no debe confirmarse lo que no se ha sabido atribuir", ok)
        assertTrue(messages.saved.isEmpty())
    }

    /**
     * A ciegas solo a quien ha anunciado que retira por etiquetas; al resto por PeerID.
     * Depositar a ciegas donde el otro aún no mira perdería el mensaje (caducaría en el nodo).
     */
    @Test
    fun `el deposito ciego se decide por contacto, segun lo que haya anunciado`() = runTest {
        val v1 = FakeSignaling().apply { failOnSend = true } // fuerza la caída al buzón
        parte("12D3KooWSelf", contact, v1, scope = backgroundScope).send(contact, "por buzón".toByteArray())
        assertEquals("sin anuncio, por PeerID", "", v1.depositLabels.single())

        val v2 = FakeSignaling().apply { failOnSend = true }
        parte("12D3KooWSelf", contactoV2, v2, scope = backgroundScope).send(contactoV2, "a ciegas".toByteArray())
        assertEquals(
            "quien anunció v2 ya retira por etiquetas: se le deposita bajo la suya",
            MailboxLabel.toHex(MailboxLabel.outbox(secret, "12D3KooWSelf", contact.peerId)),
            v2.depositLabels.single(),
        )

        // El interruptor global sigue mandando: apagarlo devuelve todos los depósitos a v1.
        val antes = ChatService.BLIND_DEPOSIT
        ChatService.BLIND_DEPOSIT = false
        try {
            val apagado = FakeSignaling().apply { failOnSend = true }
            parte("12D3KooWSelf", contactoV2, apagado, scope = backgroundScope).send(contactoV2, "v1".toByteArray())
            assertEquals("", apagado.depositLabels.single())
        } finally {
            ChatService.BLIND_DEPOSIT = antes
        }
    }

    /**
     * Dos extremos recién añadidos hacen el intercambio de anuncios de capacidad y después el
     * usuario de A escribe. Devuelve la cabecera de ese primer mensaje y los dos servicios.
     *
     * [relojDeA] adelanta o atrasa el reloj de A al crear su sesión: los linajes son la hora
     * local de cada móvil, y **de eso depende en qué época sale el primer mensaje** (ver los
     * dos tests de abajo). Se siembra el estado en el almacén de A en vez de tocar el reloj
     * del sistema, que es lo que `RatchetSessions.stateFor` haría con `now`.
     */
    private fun primerMensajeTrasAnadirse(
        scope: kotlinx.coroutines.CoroutineScope,
        relojDeA: Long,
    ): Triple<Ratchet.Header, Pair<ChatService, ChatService>, Pair<FakeSignaling, FakeSignaling>> {
        val contactoDeB = Contact(
            id = "contacto-a", displayName = "Yo", peerId = "12D3KooWSelf",
            publicKey = ByteArray(0), sharedSecret = secret,
        )
        val sigA = FakeSignaling()
        val sigB = FakeSignaling()
        val contactsA = FakeContacts(listOf(contact))
        val contactsB = FakeContacts(listOf(contactoDeB))
        val storeA = FakeRatchetStore().apply {
            sessions[contact.id] = Ratchet(JdkCurve25519())
                .initial(secret, "12D3KooWSelf", contact.peerId, lineage = System.currentTimeMillis() + relojDeA)
                .encode()
        }
        val a = ChatService(
            sigA, cipher, FakeMessages(), contactsA, FakeKeyExchange("12D3KooWSelf"), RendezvousService(),
            FakeFileStore(), scope, testSessions(FakeKeyExchange("12D3KooWSelf"), storeA),
        )
        val b = parte("12D3KooWBob", contactoDeB, sigB, contacts = contactsB, scope = scope)

        return kotlinx.coroutines.runBlocking {
            // 1. A anuncia; como no sabe qué habla B, va por el camino estático.
            a.announceCapabilities()
            assertFalse(Ratchet.looksLikeRatchet(sigA.sentCiphertext!!))
            assertNull(b.onReceived("12D3KooWSelf", sigA.sentCiphertext!!))
            // 2. B ya sabe que A habla v3: su anuncio va por el ratchet (época 0, con propuesta).
            b.announceCapabilities()
            assertEquals(0, Ratchet.Header.decode(sigB.sentCiphertext!!)!!.epoch)
            assertNull(a.onReceived("12D3KooWBob", sigB.sentCiphertext!!))
            // 3. El usuario de A escribe.
            val contactoActual = contactsA.findById(contact.id)!!
            assertEquals(ChatService.PROTOCOL_VERSION, contactoActual.peerProtocol)
            a.send(contactoActual, "hola".toByteArray())
            val recibido = b.onReceived("12D3KooWSelf", sigA.sentCiphertext!!)!!
            assertEquals("hola", (b.content(contactsB.findById(contactoDeB.id)!!, recibido) as MessageContent.Text).text)
            Triple(Ratchet.Header.decode(sigA.sentCiphertext!!)!!, a to b, sigA to sigB)
        }
    }

    /**
     * La época 0 se deriva del secreto compartido y no tiene secreto hacia adelante. Este test
     * fija **dónde acaba** esa exposición al añadir un contacto, que no es donde se pensaba:
     * el intercambio de anuncios de capacidad no basta. Cada lado crea su sesión por su cuenta
     * con su hora local como linaje; en el caso normal el receptor la crea **después** y su
     * linaje es mayor, así que abre el anuncio de B por `openOld` (la época 0 de cualquier
     * linaje es derivable) sin adoptar el linaje menor —y no puede, ver `RatchetTest`—, y **el
     * primer mensaje del usuario sale en la época 0**. Solo la primera respuesta de B saca a
     * los dos. Es el coste que documenta `DISENO-ratchet.md` §1.8.4; si algún cambio lo
     * alargara (que la respuesta de B tampoco avanzara), este test lo diría.
     */
    @Test
    fun `al anadirse, el primer mensaje del usuario va en epoca 0 y la primera respuesta saca a los dos`() = runTest {
        conRatchet {
            val (primero, servicios, señales) = primerMensajeTrasAnadirse(backgroundScope, relojDeA = +60_000L)
            val (a, b) = servicios
            val (sigA, sigB) = señales
            assertEquals("el primer mensaje del usuario no tiene secreto hacia adelante", 0, primero.epoch)

            kotlinx.coroutines.runBlocking {
                // B adopta el linaje de A (mayor) y su respuesta ya lleva material efímero;
                // con ella A también sale de la época 0. Desde aquí, todo tiene PFS.
                b.send(contact.copy(id = "contacto-a", peerId = "12D3KooWSelf", peerProtocol = 3), "qué tal".toByteArray())
                val respuesta = Ratchet.Header.decode(sigB.sentCiphertext!!)!!
                assertEquals(primero.lineage, respuesta.lineage)
                assertEquals("la primera respuesta ya va en época 1", 1, respuesta.epoch)
                assertNotNull(a.onReceived("12D3KooWBob", sigB.sentCiphertext!!))
                a.send(contactoV2, "bien".toByteArray())
                assertEquals(2, Ratchet.Header.decode(sigA.sentCiphertext!!)!!.epoch)
            }
        }
    }

    /**
     * El otro caso, que también ocurre: si el reloj de A va **por detrás** del de B, el anuncio
     * de B trae un linaje mayor, A lo adopta y consume la propuesta, y el primer mensaje del
     * usuario ya sale en la época 1. O sea que hoy el secreto hacia adelante del primer
     * mensaje **depende de los relojes**, no de nada que controle el protocolo. Documentado en
     * `DISENO-ratchet.md` §1.8.4 junto con lo que haría falta para que fuera siempre así.
     */
    @Test
    fun `si el reloj de A va por detras, el primer mensaje del usuario ya sale en epoca 1`() = runTest {
        conRatchet {
            val (primero, _, _) = primerMensajeTrasAnadirse(backgroundScope, relojDeA = -60_000L)
            assertEquals(1, primero.epoch)
        }
    }

    // --- Revisión del protocolo (14 sep 2026): lo que cada lado sabe de la versión del otro ---
    // Hallazgos H-1, H-2, H-3 y H-6 de docs/REVISION-protocolo-2026-09-14.md.

    /** Entrega a [to] lo que [from] haya enviado desde la última vez, como haría la red. */
    private class Cable(
        private val from: FakeSignaling,
        private val fromPeerId: String,
        private val to: ChatService,
    ) {
        private var entregados = 0

        /** Da por entregado lo que ya salió (se lo quedó otro destinatario, p. ej. el móvil viejo). */
        fun saltarLoEnviado() {
            entregados = from.sentAll.size
        }

        suspend fun entregar(): Int {
            val pendientes = from.sentAll.drop(entregados)
            entregados += pendientes.size
            for (wire in pendientes) to.onReceived(fromPeerId, wire)
            return pendientes.size
        }
    }

    /** Entrega en los dos sentidos hasta que ninguno tenga nada más que decir. */
    private suspend fun bombear(vararg cables: Cable) {
        repeat(10) {
            var movidos = 0
            for (c in cables) movidos += c.entregar()
            if (movidos == 0) return
        }
    }

    /**
     * Dos extremos con una conversación por ratchet ya establecida, fuera de la época 0. Los
     * linajes se siembran en el pasado para que el que nazca después (al borrar y volver a
     * añadir, al importar) sea estrictamente mayor, como pasa en la vida real.
     */
    private inner class Pareja {
        val secreto = ByteArray(32) { 7 } // el que deriva FakeKeyExchange: lo usa addContact
        private val ahora = System.currentTimeMillis()
        val bobDeAna = Contact(
            id = "12D3KooWBob", displayName = "Bob", peerId = "12D3KooWBob", publicKey = ByteArray(0),
            sharedSecret = secreto,
            peerProtocol = ChatService.PROTOCOL_VERSION, announcedProtocol = ChatService.PROTOCOL_VERSION,
        )
        val anaDeBob = Contact(
            id = "12D3KooWSelf", displayName = "Ana", peerId = "12D3KooWSelf", publicKey = ByteArray(0),
            sharedSecret = secreto,
            peerProtocol = ChatService.PROTOCOL_VERSION, announcedProtocol = ChatService.PROTOCOL_VERSION,
        )
        val sigAna = FakeSignaling()
        val sigBob = FakeSignaling()
        val contactosAna = FakeContacts(listOf(bobDeAna))
        val contactosBob = FakeContacts(listOf(anaDeBob))
        val mensajesBob = FakeMessages()
        private val storeAna = FakeRatchetStore().apply {
            sessions[bobDeAna.id] = Ratchet(JdkCurve25519())
                .initial(secreto, anaDeBob.peerId, bobDeAna.peerId, lineage = ahora - 7_200_000L).encode()
        }
        private val storeBob = FakeRatchetStore().apply {
            sessions[anaDeBob.id] = Ratchet(JdkCurve25519())
                .initial(secreto, bobDeAna.peerId, anaDeBob.peerId, lineage = ahora - 3_600_000L).encode()
        }
        val ana = ChatService(
            sigAna, cipher, FakeMessages(), contactosAna, FakeKeyExchange(anaDeBob.peerId), RendezvousService(),
            FakeFileStore(), scopeInmediato(), testSessions(FakeKeyExchange(anaDeBob.peerId), storeAna),
        )
        val bob = ChatService(
            sigBob, cipher, mensajesBob, contactosBob, FakeKeyExchange(bobDeAna.peerId), RendezvousService(),
            FakeFileStore(), scopeInmediato(), testSessions(FakeKeyExchange(bobDeAna.peerId), storeBob),
        )
        val anaHaciaBob = Cable(sigAna, anaDeBob.peerId, bob)
        val bobHaciaAna = Cable(sigBob, bobDeAna.peerId, ana)

        suspend fun bobVistoPorAna() = contactosAna.findById(bobDeAna.id)!!
        suspend fun anaVistaPorBob() = contactosBob.findById(anaDeBob.id)!!

        /** Tres mensajes de ida y vuelta: la sesión sale de la época 0 y los dos se reconocen. */
        suspend fun conversar() {
            ana.send(bobVistoPorAna(), "hola".toByteArray()); bombear(anaHaciaBob, bobHaciaAna)
            bob.send(anaVistaPorBob(), "qué tal".toByteArray()); bombear(anaHaciaBob, bobHaciaAna)
            ana.send(bobVistoPorAna(), "bien".toByteArray()); bombear(anaHaciaBob, bobHaciaAna)
            assertTrue(
                "la sesión debía haber salido de la época 0",
                Ratchet.Header.decode(sigAna.sentCiphertext!!)!!.epoch >= 1,
            )
        }

        fun textosDeBob(): List<String> = mensajesBob.saved
            .filter { it.senderId == anaDeBob.id }
            .mapNotNull { (MessageEnvelope.decode(it.payload) as? MessageEnvelope.Decoded.Text)?.let { t -> String(t.body) } }

        fun logs() = "log Bob=${bob.log.value} · log Ana=${ana.log.value}"
    }

    /**
     * **H-1.** Bob borra a Ana y la vuelve a añadir (o importa un `.krbk`, que desde aquí es lo
     * mismo: contacto sin sesión y sin saber qué versión habla Ana). Ana ya le anunció su versión
     * una vez y no lo repite, así que Bob se queda creyendo que Ana habla v1; Ana le sigue
     * escribiendo por ratchet en una sesión que Bob ya no tiene, y el reengache de Bob no sale
     * porque "no usa ratchet". Resultado antes del arreglo: **todo lo que Ana escribía se
     * descartaba (y se confirmaba en el buzón) para siempre**.
     */
    @Test
    fun `borrar y volver a anadir a un contacto no pierde para siempre lo que te escriba`() = runTest {
        conRatchet {
            val p = Pareja()
            kotlinx.coroutines.runBlocking {
                p.conversar()

                p.bob.deleteContact(p.anaVistaPorBob())
                p.bob.addContact("Ana", p.anaDeBob.peerId)
                p.bob.announceCapabilities()
                bombear(p.anaHaciaBob, p.bobHaciaAna)

                p.ana.send(p.bobVistoPorAna(), "¿me lees?".toByteArray())
                bombear(p.anaHaciaBob, p.bobHaciaAna)
            }
            assertTrue("lo que escribe Ana tiene que llegar · ${p.logs()}", "¿me lees?" in p.textosDeBob())

            // Y Bob vuelve al ratchet con ella, en vez de quedarse en la clave estática.
            kotlinx.coroutines.runBlocking { p.bob.send(p.anaVistaPorBob(), "sí".toByteArray()) }
            assertTrue(
                "Bob debe volver a escribirle por ratchet · ${p.logs()}",
                Ratchet.looksLikeRatchet(p.sigBob.sentCiphertext!!),
            )
        }
    }

    /**
     * **H-1, el otro camino**: Bob estrena móvil e importa su `.krbk`. La identidad es la misma,
     * pero el contacto llega sin la versión de Ana y sin sesión —el respaldo no lleva ninguna de
     * las dos—, que para el protocolo es exactamente borrar y volver a añadir. Es el punto 5 de
     * `PRUEBAS-PENDIENTES` §16, que con el código anterior habría fallado en el móvil.
     */
    @Test
    fun `tras importar un krbk en un movil nuevo lo que te escriben vuelve a llegar`() = runTest {
        conRatchet {
            val p = Pareja()
            val contactosNuevos = FakeContacts(listOf(p.anaDeBob.copy(peerProtocol = 0, announcedProtocol = 0)))
            val mensajesNuevos = FakeMessages()
            val sigNuevo = FakeSignaling()
            val bobNuevo = ChatService(
                sigNuevo, cipher, mensajesNuevos, contactosNuevos, FakeKeyExchange(p.bobDeAna.peerId),
                RendezvousService(), FakeFileStore(), scopeInmediato(),
                testSessions(FakeKeyExchange(p.bobDeAna.peerId)),
            )
            val anaHaciaNuevo = Cable(p.sigAna, p.anaDeBob.peerId, bobNuevo)
            val nuevoHaciaAna = Cable(sigNuevo, p.bobDeAna.peerId, p.ana)
            kotlinx.coroutines.runBlocking {
                p.conversar()
                anaHaciaNuevo.saltarLoEnviado() // eso se lo quedó el móvil viejo
                bobNuevo.announceCapabilities()
                bombear(anaHaciaNuevo, nuevoHaciaAna)
                p.ana.send(p.bobVistoPorAna(), "¿estrenas móvil?".toByteArray())
                bombear(anaHaciaNuevo, nuevoHaciaAna)
            }
            val textos = mensajesNuevos.saved.filter { it.senderId == p.anaDeBob.id }
                .mapNotNull { (MessageEnvelope.decode(it.payload) as? MessageEnvelope.Decoded.Text)?.let { t -> String(t.body) } }
            assertTrue(
                "lo que escribe Ana tiene que llegar al móvil nuevo · log nuevo=${bobNuevo.log.value} · log Ana=${p.ana.log.value}",
                "¿estrenas móvil?" in textos,
            )
        }
    }

    /**
     * Quien nos tiene apuntados por debajo de lo que ya le anunciamos lo ha perdido: se le repite
     * **por la clave estática** (sin sesión es lo único que abre), con lo que tenemos apuntado de
     * él, y **una sola vez** aunque insista — cualquiera de tus contactos podría provocarlo.
     */
    @Test
    fun `a quien nos tiene atrasados se le repite el anuncio por la clave estatica y una sola vez`() = runTest {
        conRatchet {
            val signaling = FakeSignaling()
            val yaAnunciado = contactoV2.copy(announcedProtocol = ChatService.PROTOCOL_VERSION)
            val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(listOf(yaAnunciado)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), scopeInmediato(), testSessions(FakeKeyExchange()))
            val nosTieneAtrasados = cipher.encrypt(secret, MessageEnvelope.encodeHello(ChatService.PROTOCOL_VERSION, knows = 0))

            repeat(3) { chat.onReceived(yaAnunciado.peerId, nosTieneAtrasados) }

            assertEquals("una sola respuesta · log=${chat.log.value}", 1, signaling.sentAll.size)
            val respuesta = MessageEnvelope.decode(cipher.decrypt(secret, signaling.sentAll.single()))
                as MessageEnvelope.Decoded.Hello
            assertEquals(ChatService.PROTOCOL_VERSION, respuesta.protocol)
            assertEquals("con lo que tenemos apuntado de él", ChatService.PROTOCOL_VERSION, respuesta.knows)
        }
    }

    /** Si aún no le hemos anunciado nada, no hay que repetir: el anuncio del ciclo WAN ya va a salir. */
    @Test
    fun `sin anuncio previo no se responde a quien nos tiene atrasados`() = runTest {
        conRatchet {
            val signaling = FakeSignaling()
            val chat = ChatService(signaling, cipher, FakeMessages(), FakeContacts(listOf(contactoV2)), FakeKeyExchange(), RendezvousService(), FakeFileStore(), scopeInmediato(), testSessions(FakeKeyExchange()))

            chat.onReceived(contactoV2.peerId, cipher.encrypt(secret, MessageEnvelope.encodeHello(ChatService.PROTOCOL_VERSION, knows = 0)))

            assertTrue("log=${chat.log.value}", signaling.sentAll.isEmpty())
        }
    }

    /**
     * Al enterarnos de que un contacto habla ratchet se le escribe algo **por ratchet**: si había
     * perdido la sesión, adopta nuestro linaje antes de escribirnos y no pierde lo primero que mande.
     */
    @Test
    fun `al saber que un contacto habla ratchet se le manda un primer sobre por ratchet`() = runTest {
        conRatchet {
            val signaling = FakeSignaling()
            val contacts = FakeContacts(listOf(contact))
            val chat = ChatService(signaling, cipher, FakeMessages(), contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), scopeInmediato(), testSessions(FakeKeyExchange()))

            chat.onReceived(contact.peerId, cipher.encrypt(secret, MessageEnvelope.encodeHello(ChatService.PROTOCOL_VERSION, knows = ChatService.PROTOCOL_VERSION)))

            assertEquals(ChatService.PROTOCOL_VERSION, contacts.findById(contact.id)!!.peerProtocol)
            assertEquals("log=${chat.log.value}", 1, signaling.sentAll.size)
            assertTrue("tiene que ir por ratchet", Ratchet.looksLikeRatchet(signaling.sentAll.single()))
        }
    }

    /**
     * **H-3.** Un anuncio con una versión menor que la apuntada no la baja. Si la bajara, quien
     * tenga el secreto compartido podría devolver la conversación a la clave estática con un solo
     * sobre y leer en pasivo todo lo que viniera después, sin romper nada que se notara.
     */
    @Test
    fun `un anuncio con una version menor no rebaja la del contacto`() = runTest {
        conRatchet {
            val signaling = FakeSignaling()
            val contacts = FakeContacts(listOf(contactoV2))
            val chat = ChatService(signaling, cipher, FakeMessages(), contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), scopeInmediato(), testSessions(FakeKeyExchange()))

            chat.onReceived(contactoV2.peerId, cipher.encrypt(secret, MessageEnvelope.encodeHello(1)))

            assertEquals(ChatService.PROTOCOL_VERSION, contacts.findById(contactoV2.id)!!.peerProtocol)
            chat.send(contacts.findById(contactoV2.id)!!, "sigo por ratchet".toByteArray())
            assertTrue(
                "debe seguir escribiéndole por ratchet · log=${chat.log.value}",
                Ratchet.looksLikeRatchet(signaling.sentCiphertext!!),
            )
        }
    }

    /**
     * **H-2.** Verificar o bloquear desde la copia que tenía la pantalla —leída antes de que llegara
     * el anuncio del contacto— lo devolvía a v1 para siempre. Lo impide el contrato del repositorio
     * (en Room, el SQL que prueba `ContactUpsertSqlTest`); esto fija el caso real en el dominio.
     */
    @Test
    fun `verificar desde una copia vieja del contacto no lo devuelve a la clave estatica`() = runTest {
        val contacts = FakeContacts(listOf(contact))
        val chat = ChatService(FakeSignaling(), cipher, FakeMessages(), contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
        val copiaDeLaPantalla = contacts.findById(contact.id)!!

        chat.onReceived(contact.peerId, cipher.encrypt(secret, MessageEnvelope.encodeHello(ChatService.PROTOCOL_VERSION)))
        chat.setVerified(copiaDeLaPantalla, true)
        chat.setBlocked(copiaDeLaPantalla.copy(verified = true), false)

        val guardado = contacts.findById(contact.id)!!
        assertTrue("lo que se quería guardar se guarda", guardado.verified)
        assertEquals("y lo que anunció no se pierde", ChatService.PROTOCOL_VERSION, guardado.peerProtocol)
    }

    /**
     * Al ratchet se le cree lo que demuestra: un sobre v2 que **abre** prueba que el contacto lo
     * habla, y uno relleno, que habla la v3. Cura a quien ya se quedó en v1 por H-2 antes del
     * arreglo: en cuanto escriba, vuelve a constar su versión.
     */
    @Test
    fun `un sobre de ratchet que abre sube la version apuntada del contacto`() = runTest {
        val contacts = FakeContacts(listOf(contact))
        val chat = ChatService(FakeSignaling(), cipher, FakeMessages(), contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))
        val (ratchet, estado) = emisorRatchet()

        val sinRelleno = ratchet.encrypt(estado, MessageEnvelope.encodeText("r-1", "hola".toByteArray()))
        assertNotNull(chat.onReceived(contact.peerId, sinRelleno.ciphertext))
        assertEquals(ChatService.RATCHET_MIN_PROTOCOL, contacts.findById(contact.id)!!.peerProtocol)

        val relleno = ratchet.encrypt(sinRelleno.state, MessageEnvelope.encodeText("r-2", "otra".toByteArray()), pad = true)
        assertNotNull(chat.onReceived(contact.peerId, relleno.ciphertext))
        assertEquals(ChatService.PADDING_MIN_PROTOCOL, contacts.findById(contact.id)!!.peerProtocol)
    }

    /** **H-6.** Volver a dar de alta (p. ej. renombrar) a un contacto bloqueado no lo desbloquea. */
    @Test
    fun `volver a anadir a un contacto bloqueado no lo desbloquea ni olvida su version`() = runTest {
        val bloqueado = contact.copy(
            id = contact.peerId, blocked = true,
            peerProtocol = ChatService.PROTOCOL_VERSION, announcedProtocol = ChatService.PROTOCOL_VERSION,
        )
        val contacts = FakeContacts(listOf(bloqueado))
        val chat = ChatService(FakeSignaling(), cipher, FakeMessages(), contacts, FakeKeyExchange(), RendezvousService(), FakeFileStore(), backgroundScope, testSessions(FakeKeyExchange()))

        chat.addContact("Bob renombrado", contact.peerId)

        val guardado = contacts.findById(contact.peerId)!!
        assertEquals("Bob renombrado", guardado.displayName)
        assertTrue("sigue bloqueado", guardado.blocked)
        assertEquals(ChatService.PROTOCOL_VERSION, guardado.peerProtocol)
        assertEquals("no hace falta volver a anunciarse", ChatService.PROTOCOL_VERSION, guardado.announcedProtocol)
    }
}

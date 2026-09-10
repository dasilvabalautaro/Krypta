package chat.neto.krypta.p2p

import chat.neto.krypta.core.ISignalingService
import chat.neto.krypta.core.SignalingEvent
import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.nativebridge.Libp2pNode
import chat.neto.krypta.nativebridge.NodeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orquesta el nodo nativo libp2p ([Libp2pNode]) y el descubrimiento por rendezvous
 * ([RendezvousService]). Implementa la abstracción [ISignalingService] del módulo :core
 * (reemplaza el DiscoveryService mDNS). Los payloads viajan ya cifrados (E2EE); el cifrado
 * lo aplica la capa de mensajería por encima usando [chat.neto.krypta.core.MessageCipher].
 */
@Singleton
class SignalingService @Inject constructor(
    private val node: Libp2pNode,
    private val rendezvous: RendezvousService,
) : ISignalingService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 64)
    override val events: Flow<SignalingEvent> = _events.asSharedFlow()

    private val _incomingCalls =
        MutableSharedFlow<Pair<String, chat.neto.krypta.core.CallStream>>(extraBufferCapacity = 8)
    override val incomingCallStreams: Flow<Pair<String, chat.neto.krypta.core.CallStream>> =
        _incomingCalls.asSharedFlow()

    private val _incomingVideo =
        MutableSharedFlow<Pair<String, chat.neto.krypta.core.CallStream>>(extraBufferCapacity = 8)
    override val incomingVideoStreams: Flow<Pair<String, chat.neto.krypta.core.CallStream>> =
        _incomingVideo.asSharedFlow()

    init {
        // Reenvía los mensajes entrantes del nodo (stream libp2p) como eventos de dominio.
        // Los del buzón NO pasan por aquí: van síncronos por el mailboxProcessor, para que
        // el ack al nodo espere a la persistencia (ack-tras-persistir).
        scope.launch {
            node.events.collect { event ->
                when (event) {
                    is NodeEvent.StreamData ->
                        _events.emit(SignalingEvent.MessageReceived(event.peerId, event.bytes))
                    is NodeEvent.Connected ->
                        _events.emit(SignalingEvent.PeerFound(event.peerId))
                    is NodeEvent.WakePing ->
                        _events.emit(SignalingEvent.WakeReceived)
                    is NodeEvent.IncomingCall ->
                        _incomingCalls.emit(event.peerId to event.stream)
                    is NodeEvent.IncomingVideo ->
                        _incomingVideo.emit(event.peerId to event.stream)
                }
            }
        }
    }

    override fun setMailboxProcessor(
        processor: suspend (
            fromPeerId: String,
            ciphertext: ByteArray,
            envelopeId: String,
            timestamp: Long,
            label: String,
        ) -> Boolean,
    ) {
        // El callback del bridge llega en un hilo de Go dentro de MailboxFetch; bloquearlo
        // hasta persistir es justo lo que retrasa el ack (runBlocking es correcto aquí).
        node.mailboxProcessor = { id, from, label, ts, data ->
            kotlinx.coroutines.runBlocking { processor(from, data, id, ts, label) }
        }
    }

    override suspend fun openCallStream(contact: Contact): chat.neto.krypta.core.CallStream =
        node.openCallStream(contact.peerId)

    override suspend fun openVideoStream(contact: Contact): chat.neto.krypta.core.CallStream =
        node.openVideoStream(contact.peerId)

    override suspend fun start() {
        node.start()
        // mDNS es solo un atajo de pruebas en LAN; el descubrimiento WAN real es por DHT +
        // rendezvous. Va **apagado de serie** desde el 10 sep 2026: anunciarse en la WiFi
        // delata el PeerID a cualquiera que comparta la red. Si falla (p. ej. en datos
        // móviles, sin interfaz multicast) NO debe impedir el host ni el arranque del bucle
        // WAN, que es el camino principal de Krypta.
        if (node.lanDiscoveryEnabled()) runCatching { node.startMdns() }
    }

    override suspend fun lanDiscovery(): Boolean = node.lanDiscoveryEnabled()

    override suspend fun setLanDiscovery(enabled: Boolean) {
        node.setLanDiscovery(enabled)
        // Surte efecto en el momento, en los dos sentidos: el puente guarda el servicio mDNS
        // para poder cerrarlo (y soltar el MulticastLock).
        if (enabled) runCatching { node.startMdns() } else runCatching { node.stopMdns() }
    }

    override suspend fun stop() = node.stop()

    override suspend fun announce(rendezvous: ByteArray) {
        // Publica el rendezvous diario en la DHT (provide). Requiere host + DHT arrancados.
        node.advertise(rendezvous)
    }

    override suspend fun findPeers(rendezvous: ByteArray): List<String> =
        node.findPeers(rendezvous)

    override suspend fun bootstrap(): String? = node.savedBootstrap()

    override suspend fun setBootstrap(addr: String) = node.setBootstrap(addr)

    override suspend fun connectDht(bootstrap: String) = node.startDht(bootstrap, server = false)

    override suspend fun setAllowedPeers(peers: String) = node.setAllowedPeers(peers)

    override suspend fun allowedPeersStatus(): String = node.gaterStats()

    override suspend fun selfAddrs(): List<String> =
        node.listenAddrs().split("\n").filter { it.isNotBlank() }

    override suspend fun reserveRelay(): String =
        node.reserveRelay(node.savedBootstrap().orEmpty())

    override suspend fun pingProbe(count: Int, intervalMs: Int): String =
        node.pingProbe(count, intervalMs)

    override suspend fun send(contact: Contact, ciphertext: ByteArray) {
        // El payload ya llega cifrado (E2EE). Lo entrega por un stream libp2p al PeerID
        // del contacto (el descubrimiento/conexión por rendezvous debe haberse hecho antes).
        node.sendMessage(contact.peerId, ciphertext)
    }

    override suspend fun sendOffline(contact: Contact, ciphertext: ByteArray, label: String) =
        node.mailboxPut(contact.peerId, ciphertext, label)

    override suspend fun fetchMailbox(labels: String): Int = node.mailboxFetch(labels).toInt()

    override suspend fun startWake(labels: String) = node.startWake(labels)

    override suspend fun stopWake() = node.stopWake()

    override suspend fun wakeConnected(): Boolean = node.wakeOnline()
}

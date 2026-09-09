package chat.neto.krypta.nativebridge

import android.content.Context
import android.net.wifi.WifiManager
import chat.neto.krypta.bridge.Bridge
import chat.neto.krypta.bridge.MailboxHandler
import chat.neto.krypta.bridge.MessageHandler
import chat.neto.krypta.bridge.Node
import chat.neto.krypta.bridge.PeerHandler
import chat.neto.krypta.bridge.WakeHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper Kotlin sobre el nodo go-libp2p empaquetado con gomobile (krypta-p2p.aar): arranque
 * y parada del host, identidad persistente, DHT + rendezvous, relay v2, buzón, wake y streams
 * de llamada/vídeo. Todo lo que cruza a Go pasa por aquí, y los eventos vuelven como [events].
 */
@Singleton
class Libp2pNode @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    // Canal SIN límite: una ráfaga de trozos de archivo (un fetch de buzón entrega decenas
    // de sobres seguidos) desbordaba el SharedFlow(64) y tryEmit DESCARTABA eventos en
    // silencio — trozos perdidos e irrecuperables. Con Channel.UNLIMITED nada se descarta.
    private val _events = Channel<NodeEvent>(Channel.UNLIMITED)
    val events: Flow<NodeEvent> = _events.receiveAsFlow()

    /**
     * Almacén de la identidad: la envuelve con una clave del Android Keystore (no exportable)
     * y migra sola la copia en claro que dejaron versiones anteriores. Ver [IdentityStore],
     * que es donde vive la lógica —y sus tests— sin depender de Android.
     */
    private val identityStore: IdentityStore by lazy {
        IdentityStore(
            prefs = SharedIdentityPrefs(
                context.getSharedPreferences("krypta_identity", Context.MODE_PRIVATE),
            ),
            // Si el Keystore no está disponible en este móvil, IdentityStore sigue con el
            // almacenamiento privado: quedarse sin identidad sería mucho peor.
            wrapper = runCatching { KeystoreKeyWrapper() }.getOrNull(),
            generate = { Bridge.generateIdentity() },
            log = { android.util.Log.i("KryptaIdentity", it) },
        )
    }

    // Identidad libp2p persistente: estable entre arranques para que el PeerID y los
    // secretos compartidos derivados de él no cambien.
    private val identity: ByteArray by lazy { identityStore.load() }

    private val settings get() =
        context.getSharedPreferences("krypta_settings", Context.MODE_PRIVATE)

    /**
     * Multiaddr del nodo bootstrap WAN. Por defecto el nodo de infraestructura de Krypta
     * ([DEFAULT_BOOTSTRAP]), de modo que la app se une a la WAN sola en el primer arranque
     * sin que el usuario pegue nada. Un valor vacío guardado (`""`) significa "solo LAN/mDNS"
     * y se respeta — solo la *ausencia* de la pref cae al default.
     */
    fun savedBootstrap(): String? = settings.getString("bootstrap", DEFAULT_BOOTSTRAP)

    fun setBootstrap(addr: String) {
        settings.edit().putString("bootstrap", addr).apply()
    }

    /** PeerID de este dispositivo (derivado de la identidad persistente). */
    fun localPeerId(): String = Bridge.peerIDForIdentity(identity)

    /** Secreto compartido (ECDH X25519) con [peerId], a partir de nuestra identidad. */
    fun sharedSecretWith(peerId: String): ByteArray = Bridge.sharedSecretFor(identity, peerId)

    // --- Respaldo de identidad ---------------------------------------------------

    /** Bytes de la identidad Ed25519 (¡material sensible!) para el respaldo cifrado. */
    fun exportIdentityBytes(): ByteArray = identity.copyOf()

    /** Secreto ECDH con [peerId] usando una identidad EXPLÍCITA (p. ej. recién importada). */
    fun sharedSecretFor(identityBytes: ByteArray, peerId: String): ByteArray =
        Bridge.sharedSecretFor(identityBytes, peerId)

    /**
     * Valida y persiste una identidad importada; devuelve su PeerID. La identidad en uso
     * es un `lazy` y el host ya puede estar corriendo con la anterior, así que el cambio
     * es efectivo **al reiniciar el proceso** — el llamador debe forzar/indicar el reinicio.
     */
    fun importIdentityBytes(bytes: ByteArray): String {
        val peerId = Bridge.peerIDForIdentity(bytes) // lanza si no es una identidad válida
        identityStore.save(bytes)
        return peerId
    }

    // --- Fase 0: pipeline gomobile -> AAR -> JNI ---------------------------------
    // Llamadas reales al AAR de Go (krypta-p2p.aar). Cargan libgojni.so y prueban que
    // el bridge nativo funciona en el dispositivo antes de meter go-libp2p.

    /** Saludo desde el lado Go. Prueba la llamada JNI. */
    fun nativePing(): String = Bridge.ping()

    /** Versión de la API del bridge nativo. */
    fun nativeVersion(): String = Bridge.version()

    /** Suma cruzando la frontera JNI (Go int -> Java long). */
    fun nativeSum(a: Long, b: Long): Long = Bridge.sum(a, b)

    // --- Host go-libp2p real -----------------------------------------------------

    @Volatile
    private var node: Node? = null

    // Reenvía los mensajes entrantes (stream libp2p) al Flow de eventos del nodo.
    private val messageHandler = MessageHandler { from, data ->
        _events.trySend(NodeEvent.StreamData(from, data))
    }

    // Notifica conexiones de peers (p. ej. descubiertos por mDNS en LAN).
    private val peerHandler = PeerHandler { peerId ->
        _events.trySend(NodeEvent.Connected(peerId))
    }

    /**
     * Procesador SÍNCRONO de los sobres retirados del buzón (lo registra la capa de
     * señalización). Debe persistir el mensaje y devolver `true`; solo entonces el bridge
     * Go ack'ea el sobre y el nodo lo borra — si devuelve `false` (o lanza), el sobre se
     * reentrega en el próximo fetch y el cliente deduplica por `id`. Así un trozo de
     * archivo nunca se pierde entre el fetch y su persistencia.
     */
    @Volatile
    var mailboxProcessor: ((id: String, from: String, label: String, ts: Long, data: ByteArray) -> Boolean)? = null

    // El callback llega en un hilo de Go durante MailboxFetch; procesar aquí (bloqueando)
    // es lo que retrasa el ack hasta que la capa de dominio persistió. Sin procesador
    // registrado no se confirma nada (el buzón lo reentrega cuando ya haya quien persista).
    private val mailboxHandler = MailboxHandler { id, from, label, ts, data ->
        mailboxProcessor?.let { runCatching { it(id, from, label, ts, data) }.getOrDefault(false) } ?: false
    }

    // Aviso de buzón: el nodo señala que hay correo (o el stream de wake se reconectó).
    private val wakeHandler = WakeHandler {
        _events.trySend(NodeEvent.WakePing)
    }

    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * Crea y arranca el host libp2p (identidad persistente, transportes TCP + QUIC + relay).
     * El nodo bootstrap guardado se pasa como **relay estático**: el host activa AutoRelay
     * contra él (Circuit Relay v2), de modo que obtiene una reserva y una dirección
     * `/p2p-circuit` para ser alcanzable tras NAT/CGNAT (+ DCUtR para intentar conexión directa).
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (node == null) {
            node = Bridge.newNodeWithIdentity(identity, savedBootstrap().orEmpty()).also {
                it.setMessageHandler(messageHandler)
                it.setPeerHandler(peerHandler)
                it.setMailboxHandler(mailboxHandler)
                // Streams de llamada entrantes → NodeEvent (los valida CallService con el hello).
                it.setCallHandler { s ->
                    _events.trySend(NodeEvent.IncomingCall(s.remotePeer(), GoCallStream(s)))
                }
                // Streams de vídeo entrantes (Fase 7c): canal aparte del audio.
                it.setVideoHandler { s ->
                    _events.trySend(NodeEvent.IncomingVideo(s.remotePeer(), GoVideoStream(s)))
                }
            }
        }
        Unit
    }

    /**
     * Activa el descubrimiento en LAN por mDNS (SOLO para pruebas en la misma WiFi). El
     * descubrimiento real de Krypta es WAN por DHT + rendezvous; esto es un atajo de test.
     * Requiere un MulticastLock o Android ignora el multicast de mDNS.
     */
    suspend fun startMdns(serviceTag: String = "krypta-lan") = withContext(Dispatchers.IO) {
        if (multicastLock == null) {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("krypta-mdns").apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
        }
        node?.startMdns(serviceTag)
        Unit
    }

    /** Envía un blob a [peerId] abriendo un stream libp2p (lo descubre/conecta antes). */
    suspend fun sendMessage(peerId: String, data: ByteArray) = withContext(Dispatchers.IO) {
        node?.sendMessage(peerId, data)
        Unit
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        node?.close()
        node = null
        multicastLock?.let { runCatching { it.release() } }
        multicastLock = null
    }

    /** PeerID del host activo, o cadena vacía si no se ha arrancado. */
    fun peerId(): String = node?.peerID().orEmpty()

    /** Multiaddrs de escucha del host activo (diagnóstico). */
    fun listenAddrs(): String = node?.listenAddrs().orEmpty()

    /**
     * Inicializa la DHT Kademlia y conecta a los bootstrap dados (multiaddrs separados por
     * salto de línea). En el móvil [server] = false (modo client).
     */
    suspend fun startDht(bootstrap: String, server: Boolean = false) = withContext(Dispatchers.IO) {
        node?.startDHT(bootstrap, server)
        Unit
    }

    /** Reserva explícita de Circuit Relay v2 en [addrs]; "" (OK) o texto de error. */
    suspend fun reserveRelay(addrs: String): String = withContext(Dispatchers.IO) {
        node?.reserveRelay(addrs) ?: "nodo no iniciado"
    }

    /**
     * Sonda de latencia (Fase 7a, llamadas): mide el RTT contra el nodo bootstrap con el
     * ping estándar de libp2p ([count] pings cada [intervalMs] ms) y devuelve
     * "n=… min=… p50=… p95=… max=…". Lanza si el nodo no es alcanzable.
     */
    suspend fun pingProbe(count: Int = 50, intervalMs: Int = 20): String = withContext(Dispatchers.IO) {
        checkNotNull(node) { "nodo no iniciado" }
            .pingProbe(savedBootstrap().orEmpty(), count.toLong(), intervalMs.toLong())
    }

    /**
     * Mantiene el stream ligero de wake al nodo bootstrap (con reconexión automática en
     * Go). Cada aviso —o reconexión— emite [NodeEvent.WakePing]. Idempotente.
     */
    /**
     * Mantiene el stream de aviso. Con [labels] se suscribe **por etiquetas** (v2), que es lo
     * único que despierta ante un depósito ciego: el nodo ya no sabe a qué PeerID avisar. Si el
     * conjunto de etiquetas cambia (rotan cada semana, o se añade un contacto), el puente
     * rehace la suscripción.
     */
    suspend fun startWake(labels: String = "") = withContext(Dispatchers.IO) {
        node?.startWake(savedBootstrap().orEmpty(), labels, wakeHandler)
        Unit
    }

    /** Corta el stream de wake (p. ej. al desactivar la WAN). */
    suspend fun stopWake() = withContext(Dispatchers.IO) {
        node?.stopWake()
        Unit
    }

    /**
     * ¿Está el stream de wake abierto ahora mismo? Si lo está, los depósitos llegan al
     * instante y el bucle WAN puede espaciarse (ahorro de batería). False durante el backoff
     * de reconexión o si el wake no corre.
     */
    suspend fun wakeOnline(): Boolean = withContext(Dispatchers.IO) {
        node?.wakeOnline() ?: false
    }

    /**
     * Deposita [data] (ciphertext) en el buzón del nodo bootstrap para entrega offline
     * a [to]. Lanza si el nodo no es alcanzable o rechaza el depósito (cuota/tamaño).
     */
    /**
     * Deposita [data] en el buzón para [to]. Con [label] no vacía se usa el **depósito ciego**
     * (el nodo guarda bajo esa etiqueta y no llega a saber para quién es); el puente cae al
     * camino de siempre solo contra los nodos que aún no entiendan v2.
     */
    suspend fun mailboxPut(to: String, data: ByteArray, label: String = "") = withContext(Dispatchers.IO) {
        checkNotNull(node) { "nodo no iniciado" }.mailboxPut(savedBootstrap().orEmpty(), to, label, data)
    }

    /**
     * Retira los mensajes pendientes del buzón propio; cada uno se entrega en el sitio al
     * [mailboxProcessor] y solo los que este confirma se ack'ean (borran) en el nodo.
     * Devuelve cuántos se confirmaron.
     */
    /**
     * Retira el buzón. [labels] (una etiqueta por línea) añade la retirada **a ciegas**; el
     * puente consulta además el buzón v1 por el PeerID propio, porque durante la transición un
     * contacto sin actualizar sigue depositando por ahí.
     */
    suspend fun mailboxFetch(labels: String = ""): Long = withContext(Dispatchers.IO) {
        node?.mailboxFetch(savedBootstrap().orEmpty(), labels) ?: 0L
    }

    /** Anuncia este nodo bajo el rendezvous diario (bytes HKDF) en la DHT. */
    suspend fun advertise(rendezvous: ByteArray) = withContext(Dispatchers.IO) {
        node?.advertise(rendezvous.toHex())
        Unit
    }

    /** Busca peers anunciados bajo el rendezvous; devuelve sus PeerID. */
    suspend fun findPeers(rendezvous: ByteArray, timeoutSec: Long = 20): List<String> =
        withContext(Dispatchers.IO) {
            val res = node?.findPeers(rendezvous.toHex(), timeoutSec).orEmpty()
            if (res.isEmpty()) emptyList() else res.split("\n").distinct()
        }

    private fun ByteArray.toHex(): String =
        joinToString("") { b -> "%02x".format(b) }

    // --- Llamadas (Fase 7b): streams full-duplex /krypta/call/1.0.0 -------------------

    /**
     * Abre un stream de llamada hacia [peerId] (directo por DCUtR o relayed). Los frames
     * van con framing uint16 en Go; aquí solo se envuelve como [chat.neto.krypta.core.CallStream].
     */
    suspend fun openCallStream(peerId: String): chat.neto.krypta.core.CallStream =
        withContext(Dispatchers.IO) {
            GoCallStream(checkNotNull(node) { "nodo no iniciado" }.openCallStream(peerId))
        }

    /** Abre un stream de vídeo (Fase 7c) hacia [peerId]; framing uint32 (frames ≤ 1 MiB). */
    suspend fun openVideoStream(peerId: String): chat.neto.krypta.core.CallStream =
        withContext(Dispatchers.IO) {
            GoVideoStream(checkNotNull(node) { "nodo no iniciado" }.openVideoStream(peerId))
        }

    /** Adapta el CallStream del AAR (lecturas bloqueantes JNI) a la interfaz de dominio. */
    private class GoCallStream(
        private val s: chat.neto.krypta.bridge.CallStream,
    ) : chat.neto.krypta.core.CallStream {
        override suspend fun sendFrame(frame: ByteArray) = withContext(Dispatchers.IO) {
            s.writeFrame(frame)
        }

        override suspend fun receiveFrame(): ByteArray? = withContext(Dispatchers.IO) {
            // El error (EOF/reset) señala el fin del stream → null; el que llama cuelga.
            runCatching { s.readFrame() }.getOrNull()
        }

        override suspend fun close() {
            withContext(Dispatchers.IO) { runCatching { s.close() } }
            Unit
        }
    }

    /** Ídem para el stream de vídeo del AAR (misma interfaz de dominio, framing uint32). */
    private class GoVideoStream(
        private val s: chat.neto.krypta.bridge.VideoStream,
    ) : chat.neto.krypta.core.CallStream {
        override suspend fun sendFrame(frame: ByteArray) = withContext(Dispatchers.IO) {
            s.writeFrame(frame)
        }

        override suspend fun receiveFrame(): ByteArray? = withContext(Dispatchers.IO) {
            runCatching { s.readFrame() }.getOrNull()
        }

        override suspend fun close() {
            withContext(Dispatchers.IO) { runCatching { s.close() } }
            Unit
        }
    }

    companion object {
        /**
         * Nodos bootstrap WAN por defecto (uno por línea), en orden de preferencia —
         * `MailboxPut` deposita en el primero vivo, así que quien esté primero aquí es el
         * primario. Primero el **VPS de São Paulo** (31 jul 2026): IP pública dedicada, sin
         * Cloudflare Tunnel de por medio (TCP directo, no `wss`), región cercana a
         * Latinoamérica para el relay de voz/vídeo — validado con
         * `TestMailboxFetchAgainstLiveNode`/`TestWakeAgainstLiveNode` antes de entrar aquí.
         * Los otros dos quedan como **respaldo doméstico**, expuestos vía Cloudflare Tunnel
         * como `wss` sobre el 443: el Mac (`krypta`) y el PC Windows (`krypta2`). Es
         * infraestructura compartida (igual para todos los usuarios) y pública, no identidad
         * de nadie. El bridge retira/escucha de TODOS los nodos (`MailboxFetch`,
         * `StartWake`), así que la caída de cualquiera —incluido el VPS— no corta la
         * entrega. Si cambia el PeerID de un nodo (p. ej. se pierde su `node.key`) o el
         * dominio/IP, actualiza esta constante.
         */
        const val DEFAULT_BOOTSTRAP =
            "/ip4/216.128.169.83/tcp/4001/p2p/12D3KooWBwcbXveKDSf4LrH9DYnwMDAyagkzh2uPYZyWkeoVMuk5\n" +
                "/dns4/krypta.neto.chat/tcp/443/wss/p2p/12D3KooWPTUUREfK1dqiEmppLy3ycyFxCvuCbK6s1TPqaQm2UBog\n" +
                "/dns4/krypta2.neto.chat/tcp/443/wss/p2p/12D3KooWNGNzFsntPcabJ3DxmYKuXzSD6skeTaeepsnbntc6JTEm"
    }
}

/** Adaptador de `SharedPreferences` al puerto que usa [IdentityStore]. */
private class SharedIdentityPrefs(
    private val prefs: android.content.SharedPreferences,
) : IdentityPrefs {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
}

sealed interface NodeEvent {
    data class Connected(val peerId: String) : NodeEvent
    data class StreamData(val peerId: String, val bytes: ByteArray) : NodeEvent

    /** Aviso de buzón del nodo (o reconexión del stream de wake): retirar ahora. */
    data object WakePing : NodeEvent

    /** Stream de llamada entrante: [peerId] autenticado + el stream ya envuelto. */
    data class IncomingCall(val peerId: String, val stream: chat.neto.krypta.core.CallStream) : NodeEvent

    /** Stream de vídeo entrante (Fase 7c): [peerId] autenticado + el stream ya envuelto. */
    data class IncomingVideo(val peerId: String, val stream: chat.neto.krypta.core.CallStream) : NodeEvent
}

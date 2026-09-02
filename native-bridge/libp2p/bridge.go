// Package bridge is the gomobile entry point for Krypta's native P2P layer.
//
// Phase 0/3 (spike): proves the gomobile -> AAR -> Kotlin/JNI pipeline, that a real
// go-libp2p host runs on Android, that rendezvous discovery over a Kademlia DHT works,
// and that two peers can exchange a message over a libp2p stream after discovering each
// other. Circuit Relay v2 and DCUtR hole-punching are layered on later. The exported API
// stays gomobile-friendly (string/int/[]byte, structs with methods, callback interfaces,
// error returns).
package bridge

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/sha512"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"
	"sync"
	"time"

	"filippo.io/edwards25519"
	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/protocol"
	"github.com/libp2p/go-libp2p/p2p/discovery/mdns"
	drouting "github.com/libp2p/go-libp2p/p2p/discovery/routing"
	dutil "github.com/libp2p/go-libp2p/p2p/discovery/util"
	rcclient "github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/client"
	"github.com/libp2p/go-libp2p/p2p/protocol/ping"
	multiaddr "github.com/multiformats/go-multiaddr"
	"golang.org/x/crypto/curve25519"
)

// BootstrapDialTimeout acota cada dial a un nodo bootstrap. Sin plazo propio, un nodo que
// acepta el TCP pero no completa el handshake (frecuente en móvil tras un cambio de red, o
// atravesando Cloudflare) se lleva por delante el ciclo entero de entrega.
const BootstrapDialTimeout = 20 * time.Second

// ProtocolID is the libp2p protocol for Krypta one-shot E2EE message streams.
const ProtocolID = protocol.ID("/krypta/msg/1.0.0")

// --- Spike sanity checks (JNI marshalling) -----------------------------------

func Ping() string     { return "pong from krypta go-libp2p bridge" }
func Sum(a, b int) int { return a + b }
func Version() string  { return "0.0.17-multinode" }

// --- Identidad persistente + intercambio de claves (X25519 desde la identidad libp2p) ---

// GenerateIdentity crea una identidad Ed25519 nueva y la devuelve marshalada (para
// persistirla en el dispositivo). El PeerID se deriva de ella.
func GenerateIdentity() ([]byte, error) {
	priv, _, err := crypto.GenerateEd25519Key(rand.Reader)
	if err != nil {
		return nil, err
	}
	return crypto.MarshalPrivateKey(priv)
}

// PeerIDForIdentity devuelve el PeerID (base58) correspondiente a una identidad.
func PeerIDForIdentity(identity []byte) (string, error) {
	priv, err := crypto.UnmarshalPrivateKey(identity)
	if err != nil {
		return "", err
	}
	id, err := peer.IDFromPrivateKey(priv)
	if err != nil {
		return "", err
	}
	return id.String(), nil
}

// SharedSecretFor calcula el secreto compartido (32 B) con un contacto por ECDH X25519,
// usando NUESTRA identidad y la clave pública del contacto, que va embebida en su PeerID
// (Ed25519). Es simétrico: ambos lados obtienen el mismo valor. La clave AES se deriva
// luego con HKDF (lado Kotlin), igual que con el PSK.
func SharedSecretFor(identity []byte, peerID string) ([]byte, error) {
	priv, err := crypto.UnmarshalPrivateKey(identity)
	if err != nil {
		return nil, err
	}
	raw, err := priv.Raw() // ed25519: 64 bytes (seed||pub)
	if err != nil {
		return nil, err
	}
	// Escalar X25519 a partir de la semilla Ed25519 (clamp de SHA-512(seed)[:32]).
	digest := sha512.Sum512(raw[:32])
	var scalar [32]byte
	copy(scalar[:], digest[:32])
	scalar[0] &= 248
	scalar[31] &= 127
	scalar[31] |= 64

	// Clave pública Ed25519 del contacto, extraída de su PeerID.
	pid, err := peer.Decode(peerID)
	if err != nil {
		return nil, err
	}
	pubk, err := pid.ExtractPublicKey()
	if err != nil {
		return nil, fmt.Errorf("peerID sin clave pública embebida: %w", err)
	}
	edPub, err := pubk.Raw() // 32 bytes
	if err != nil {
		return nil, err
	}
	point, err := new(edwards25519.Point).SetBytes(edPub)
	if err != nil {
		return nil, err
	}
	montPub := point.BytesMontgomery() // u-coordinate Montgomery (X25519)

	return curve25519.X25519(scalar[:], montPub)
}

// MessageHandler is implemented on the Kotlin side to receive inbound messages.
// gomobile binds this Go interface as a Java/Kotlin interface.
type MessageHandler interface {
	OnMessage(from string, data []byte)
}

// PeerHandler is implemented on the Kotlin side to learn when a LAN/peer connection opens.
type PeerHandler interface {
	OnPeerConnected(peerID string)
}

// Node wraps a go-libp2p host plus a Kademlia DHT and a routing-discovery layer.
type Node struct {
	h           host.Host
	dht         *dht.IpfsDHT
	disc        *drouting.RoutingDiscovery
	ctx         context.Context
	cancel      context.CancelFunc
	peerHandler PeerHandler

	mailboxHandler MailboxHandler

	wakeMu      sync.Mutex
	wakeCancel  context.CancelFunc
	wakeStreams int // nº de streams de wake abiertos (multi-nodo: uno por nodo alcanzable)
}

// shortID abrevia un PeerID para los mensajes de error/diagnóstico.
func shortID(id peer.ID) string {
	s := id.String()
	if len(s) > 8 {
		return s[len(s)-8:]
	}
	return s
}

// NewNode creates a libp2p host with a fresh random Ed25519 identity (for tests/anonymous).
func NewNode() (*Node, error) {
	return newNode(nil, "")
}

// NewNodeWithIdentity creates a host using the persisted identity (so the PeerID — and thus
// the shared secrets derived from it — stay stable across runs). relayAddrs is a newline-
// separated list of relay multiaddrs (typically Krypta's infra node, which runs Circuit
// Relay v2): when set, the host enables AutoRelay against them so it can be reached behind
// NAT/CGNAT via a /p2p-circuit address. Pass "" to disable (LAN-only).
func NewNodeWithIdentity(identity []byte, relayAddrs string) (*Node, error) {
	priv, err := crypto.UnmarshalPrivateKey(identity)
	if err != nil {
		return nil, err
	}
	return newNode(priv, relayAddrs)
}

func newNode(priv crypto.PrivKey, relayAddrs string) (*Node, error) {
	opts := []libp2p.Option{
		libp2p.ListenAddrStrings(
			"/ip4/0.0.0.0/tcp/0",
			"/ip4/0.0.0.0/udp/0/quic-v1",
		),
		libp2p.EnableRelay(),        // usa relays para dialar/ser dialado (cliente Relay v2)
		libp2p.EnableHolePunching(), // DCUtR: tras conectar por relay, intenta upgrade a directo
		libp2p.NATPortMap(),         // mapea puerto vía UPnP/NAT-PMP si el router lo permite
	}
	if priv != nil {
		opts = append(opts, libp2p.Identity(priv))
	}
	// Relay determinista (en vez de AutoRelay, que tras Cloudflare no publica la dirección):
	// anunciamos NOSOTROS mismos una dirección /p2p-circuit construida con la addr conocida del
	// relay (el nodo de infra). Combinado con la reserva explícita que renueva la app
	// (ReserveRelay), el contacto nos descubre por rendezvous con esa addr y dial a través del
	// relay; luego DCUtR intenta pasar a conexión directa.
	if relays := parseAddrInfos(relayAddrs); len(relays) > 0 {
		opts = append(opts, libp2p.AddrsFactory(circuitAddrsFactory(relays)))
	}
	h, err := libp2p.New(opts...)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &Node{h: h, ctx: ctx, cancel: cancel}, nil
}

// circuitAddrsFactory devuelve un AddrsFactory que AÑADE, a las direcciones anunciadas del
// host, una dirección `/p2p-circuit` por cada relay conocido (p. ej.
// `/dns4/krypta.neto.chat/tcp/443/wss/p2p/<relayID>/p2p-circuit`). Así el contacto que nos
// descubre por rendezvous obtiene una addr alcanzable tras NAT y dial a través del relay
// (que debe tener una reserva activa nuestra, renovada por ReserveRelay).
func circuitAddrsFactory(relays []peer.AddrInfo) func([]multiaddr.Multiaddr) []multiaddr.Multiaddr {
	var circuits []multiaddr.Multiaddr
	for _, r := range relays {
		for _, ra := range r.Addrs {
			c, err := multiaddr.NewMultiaddr(ra.String() + "/p2p/" + r.ID.String() + "/p2p-circuit")
			if err == nil {
				circuits = append(circuits, c)
			}
		}
	}
	return func(addrs []multiaddr.Multiaddr) []multiaddr.Multiaddr {
		return append(addrs, circuits...)
	}
}

// parseAddrInfos convierte multiaddrs (separadas por salto de línea, con /p2p/<id>) en
// AddrInfo, fusionando las direcciones del mismo peer. Ignora las líneas inválidas.
func parseAddrInfos(s string) []peer.AddrInfo {
	byID := map[peer.ID]*peer.AddrInfo{}
	var order []peer.ID
	for _, line := range strings.Split(s, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		ai, err := peerInfo(line)
		if err != nil {
			continue
		}
		if existing, ok := byID[ai.ID]; ok {
			existing.Addrs = append(existing.Addrs, ai.Addrs...)
			continue
		}
		cp := *ai
		byID[ai.ID] = &cp
		order = append(order, ai.ID)
	}
	out := make([]peer.AddrInfo, 0, len(order))
	for _, id := range order {
		out = append(out, *byID[id])
	}
	return out
}

// SetPeerHandler registers a handler invoked when a peer connection is established.
func (n *Node) SetPeerHandler(h PeerHandler) { n.peerHandler = h }

// StartMdns enables LAN peer discovery over mDNS: peers on the same Wi-Fi advertising the
// same serviceTag are found and auto-connected (no bootstrap/DHT needed). This is Krypta's
// optional LAN path; WAN discovery still goes through the DHT + rendezvous.
func (n *Node) StartMdns(serviceTag string) error {
	svc := mdns.NewMdnsService(n.h, serviceTag, &mdnsNotifee{n: n})
	return svc.Start()
}

type mdnsNotifee struct{ n *Node }

func (m *mdnsNotifee) HandlePeerFound(pi peer.AddrInfo) {
	if pi.ID == m.n.h.ID() {
		return
	}
	ctx, cancel := context.WithTimeout(m.n.ctx, 10*time.Second)
	defer cancel()
	if err := m.n.h.Connect(ctx, pi); err != nil {
		return
	}
	if m.n.peerHandler != nil {
		m.n.peerHandler.OnPeerConnected(pi.ID.String())
	}
}

// SetMessageHandler registers a handler invoked for every inbound message stream.
func (n *Node) SetMessageHandler(h MessageHandler) {
	n.h.SetStreamHandler(ProtocolID, func(s network.Stream) {
		defer s.Close()
		data, err := io.ReadAll(s)
		if err != nil && err != io.EOF {
			_ = s.Reset()
			return
		}
		if h != nil {
			h.OnMessage(s.Conn().RemotePeer().String(), data)
		}
	})
}

// SendMessage dials peerID (reusing existing connections / peerstore addrs) and writes
// data on a fresh Krypta stream, half-closing so the receiver gets a clean EOF.
func (n *Node) SendMessage(peerID string, data []byte) error {
	pid, err := peer.Decode(peerID)
	if err != nil {
		return err
	}
	// Las conexiones por Circuit Relay v2 son "limited" (transient). go-libp2p se niega a abrir
	// un stream sobre ellas salvo que el contexto lo permita explícitamente; sin esto espera una
	// conexión directa que (tras NAT) no llega → "context deadline exceeded".
	ctx := network.WithAllowLimitedConn(n.ctx, "krypta-msg")
	s, err := n.h.NewStream(ctx, pid, ProtocolID)
	if err != nil {
		return err
	}
	defer s.Close()
	if _, err := s.Write(data); err != nil {
		_ = s.Reset()
		return err
	}
	return s.CloseWrite()
}

// StartDHT initializes the Kademlia DHT and connects to the given bootstrap peers
// (newline-separated multiaddrs ending in /p2p/<id>). server=false => client mode.
// StartDHT is idempotent: it creates the DHT once and, on every call, (re)connects to the
// bootstrap peers. Call it periodically to self-heal the WAN link (e.g., Cloudflare recycles
// WebSocket connections ~every 10 min on the Free plan).
func (n *Node) StartDHT(bootstrap string, server bool) error {
	if n.dht == nil {
		mode := dht.ModeClient
		if server {
			mode = dht.ModeServer
		}
		kad, err := dht.New(n.ctx, n.h, dht.Mode(mode))
		if err != nil {
			return err
		}
		n.dht = kad
		n.disc = drouting.NewRoutingDiscovery(kad)
		if err := kad.Bootstrap(n.ctx); err != nil {
			return err
		}
	}

	// Multi-nodo: basta con que UN bootstrap conecte para estar en la WAN (el resto de la
	// capa ya tolera nodos caídos: el buzón deposita en el primero vivo y retira de todos).
	// Devolver el error de un solo nodo caído hacía que la app marcara "sin conexión" con
	// la mensajería funcionando por el otro nodo (visto en vivo el 17 jul 2026).
	//
	// Los dials van **en paralelo y con plazo propio** (BootstrapDialTimeout). Antes iban en
	// serie sobre n.ctx —el contexto de vida del nodo, sin plazo—, así que un nodo lento
	// retrasaba a los siguientes y el ciclo entero del `wanLoop` (reconectar → relay → buzón
	// → rendezvous) se alargaba hasta varios minutos; medido el 2 sep 2026: 37 min sin un
	// solo ciclo, con el buzón lleno y el móvil sin recoger nada. En paralelo el coste del
	// paso es el del nodo más lento, no la suma.
	var ais []*peer.AddrInfo
	for _, line := range strings.Split(bootstrap, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		ai, err := peerInfo(line)
		if err != nil {
			return fmt.Errorf("bootstrap %q: %w", line, err)
		}
		ais = append(ais, ai)
	}
	// Sin bootstrap no hay nada que conectar y NO es un error: es el caso del propio nodo
	// de infra (arranca la DHT en modo servidor sin nadie a quien llamar).
	if len(ais) == 0 {
		return nil
	}

	// Cada dial en su goroutine, con su plazo. El resultado se espera hasta que UNO conecte
	// (basta para estar en la WAN) o hasta que fallen todos: así el paso cuesta lo que el
	// nodo **más rápido**, no lo que el más lento. Los dials que queden en marcha NO se
	// cancelan —se mueren solos al vencer su plazo— para que un nodo algo lento acabe
	// conectando igual y sirva en el siguiente ciclo.
	results := make(chan error, len(ais))
	for _, ai := range ais {
		go func(ai *peer.AddrInfo) {
			ctx, cancel := context.WithTimeout(n.ctx, BootstrapDialTimeout)
			defer cancel()
			if err := n.h.Connect(ctx, *ai); err != nil {
				results <- fmt.Errorf("connect bootstrap %s: %w", ai.ID, err)
				return
			}
			results <- nil
		}(ai)
	}

	var lastErr error
	for range ais {
		if err := <-results; err == nil {
			return nil // un bootstrap vivo = estamos en la WAN
		} else {
			lastErr = err
		}
	}
	return lastErr
}

// ReserveRelay reserves a Circuit Relay v2 slot on EVERY relay in relayAddrs (multi-node:
// the AddrsFactory advertises one /p2p-circuit address per relay, so each reachable relay
// needs a live reservation for its address to be dialable). Returns a per-relay summary.
// This complements AutoRelay: it forces/diagnoses the reservation deterministically, so
// behind NAT the node has dialable addresses. Safe to call repeatedly (renews the slots).
func (n *Node) ReserveRelay(relayAddrs string) string {
	relays := parseAddrInfos(relayAddrs)
	if len(relays) == 0 {
		return "sin relay configurado"
	}
	// En paralelo: cada reserva ya tenía su plazo de 30 s, pero iban en serie, así que con
	// tres nodos el paso costaba hasta 90 s dentro del ciclo del wanLoop.
	parts := make([]string, len(relays))
	var wg sync.WaitGroup
	for i, ai := range relays {
		wg.Add(1)
		go func(i int, ai peer.AddrInfo) {
			defer wg.Done()
			parts[i] = n.reserveOne(ai)
		}(i, ai)
	}
	wg.Wait()
	return strings.Join(parts, " | ")
}

func (n *Node) reserveOne(ai peer.AddrInfo) string {
	ctx, cancel := context.WithTimeout(n.ctx, 30*time.Second)
	defer cancel()
	if n.h.Network().Connectedness(ai.ID) != network.Connected {
		if err := n.h.Connect(ctx, ai); err != nil {
			return fmt.Sprintf("connect relay: %v", err)
		}
	}
	res, err := rcclient.Reserve(ctx, n.h, ai)
	if err != nil {
		return fmt.Sprintf("reserve: %v", err)
	}
	return fmt.Sprintf("OK (%d addrs, exp %s)", len(res.Addrs), res.Expiration.Format("15:04:05"))
}

// PingProbe mide la latencia RTT (ms) hasta el primer peer de addrs (típicamente el nodo
// infra, atravesando Cloudflare por wss) con el servicio ping estándar de libp2p
// (/ipfs/ping/1.0.0, activo por defecto en el nodo). Hace count pings espaciados intervalMs
// y devuelve "n=<ok>/<count> min=… p50=… p95=… max=…". Es la sonda del gate de llamadas
// (Fase 7a): un RTT móvil→CF→nodo→CF→móvil ≈ la latencia one-way de un frame de audio
// relayed A→CF→nodo→CF→B (dos travesías de Cloudflare en ambos casos).
func (n *Node) PingProbe(addrs string, count int, intervalMs int) (string, error) {
	nodes := parseAddrInfos(addrs)
	if len(nodes) == 0 {
		return "", errors.New("sin nodo configurado")
	}
	ai := nodes[0]
	budget := time.Duration(count*intervalMs)*time.Millisecond + 60*time.Second
	ctx, cancel := context.WithTimeout(n.ctx, budget)
	defer cancel()
	if n.h.Network().Connectedness(ai.ID) != network.Connected {
		if err := n.h.Connect(ctx, ai); err != nil {
			return "", fmt.Errorf("connect: %w", err)
		}
	}
	// El canal es sin buffer: el productor espera a cada lectura, así que el sleep del
	// bucle marca el ritmo (≈ un ping cada intervalMs, como frames de audio).
	ch := ping.Ping(ctx, n.h, ai.ID)
	var rtts []time.Duration
	fails := 0
	for i := 0; i < count; i++ {
		res, ok := <-ch
		if !ok {
			break
		}
		if res.Error != nil {
			fails++
		} else {
			rtts = append(rtts, res.RTT)
		}
		time.Sleep(time.Duration(intervalMs) * time.Millisecond)
	}
	if len(rtts) == 0 {
		return "", fmt.Errorf("sin respuestas (%d fallos)", fails)
	}
	sort.Slice(rtts, func(a, b int) bool { return rtts[a] < rtts[b] })
	ms := func(d time.Duration) int64 { return d.Milliseconds() }
	pct := func(p float64) time.Duration {
		i := int(p * float64(len(rtts)-1))
		return rtts[i]
	}
	return fmt.Sprintf(
		"n=%d/%d min=%dms p50=%dms p95=%dms max=%dms",
		len(rtts), count, ms(rtts[0]), ms(pct(0.5)), ms(pct(0.95)), ms(rtts[len(rtts)-1]),
	), nil
}

// --- Llamadas (Fase 7b): stream full-duplex de frames de audio E2EE ------------------

// CallProtocolID es el protocolo libp2p de los streams de llamada de Krypta.
const CallProtocolID = protocol.ID("/krypta/call/1.0.0")

// CallStream envuelve un stream libp2p con framing binario: cada frame va precedido de su
// longitud (uint16 big-endian). El payload es opaco para esta capa (viene cifrado E2EE de
// Kotlin). Es duplex: ambos lados escriben y leen frames a la vez (el transporte libp2p —
// yamux/QUIC — es fiable y ordenado, así que no hay pérdida ni reordenación de frames).
type CallStream struct {
	s   network.Stream
	r   *bufio.Reader
	wmu sync.Mutex
}

func newCallStream(s network.Stream) *CallStream {
	return &CallStream{s: s, r: bufio.NewReaderSize(s, 4096)}
}

// RemotePeer devuelve el PeerID del otro extremo (autenticado por libp2p/Noise).
func (c *CallStream) RemotePeer() string { return c.s.Conn().RemotePeer().String() }

// WriteFrame envía un frame (≤ 65535 bytes) como una sola escritura (long. + payload).
func (c *CallStream) WriteFrame(data []byte) error {
	if len(data) > 0xFFFF {
		return fmt.Errorf("frame de %d bytes excede 64 KiB", len(data))
	}
	buf := make([]byte, 2+len(data))
	buf[0] = byte(len(data) >> 8)
	buf[1] = byte(len(data))
	copy(buf[2:], data)
	c.wmu.Lock()
	defer c.wmu.Unlock()
	_, err := c.s.Write(buf)
	return err
}

// ReadFrame bloquea hasta el siguiente frame; devuelve error al cerrarse el stream (EOF).
func (c *CallStream) ReadFrame() ([]byte, error) {
	var hdr [2]byte
	if _, err := io.ReadFull(c.r, hdr[:]); err != nil {
		return nil, err
	}
	buf := make([]byte, int(hdr[0])<<8|int(hdr[1]))
	if _, err := io.ReadFull(c.r, buf); err != nil {
		return nil, err
	}
	return buf, nil
}

// Close cierra el stream; el otro extremo ve EOF en su ReadFrame.
func (c *CallStream) Close() error { return c.s.Close() }

// CallHandler lo implementa el lado Kotlin para recibir streams de llamada entrantes.
type CallHandler interface {
	OnCallStream(s *CallStream)
}

// SetCallHandler registra el handler de streams de llamada entrantes.
func (n *Node) SetCallHandler(h CallHandler) {
	n.h.SetStreamHandler(CallProtocolID, func(s network.Stream) {
		if h == nil {
			_ = s.Reset()
			return
		}
		h.OnCallStream(newCallStream(s))
	})
}

// OpenCallStream abre un stream de llamada hacia peerID. Igual que SendMessage, permite
// conexiones relayed ("limited"); DCUtR intentará subir a directa en paralelo.
func (n *Node) OpenCallStream(peerID string) (*CallStream, error) {
	pid, err := peer.Decode(peerID)
	if err != nil {
		return nil, err
	}
	ctx := network.WithAllowLimitedConn(n.ctx, "krypta-call")
	s, err := n.h.NewStream(ctx, pid, CallProtocolID)
	if err != nil {
		return nil, err
	}
	return newCallStream(s), nil
}

// --- Vídeo (Fase 7c): stream full-duplex de frames de vídeo E2EE ---------------------

// VideoProtocolID es el protocolo libp2p de los streams de vídeo (canal aparte del audio:
// si el vídeo se cae o se cierra, la voz no se ve afectada).
const VideoProtocolID = protocol.ID("/krypta/video/1.0.0")

// videoMaxFrame acota un frame de vídeo cifrado. Un keyframe H.264 a ~400 kbps ronda las
// decenas de KiB; 1 MiB da margen de sobra y corta payloads absurdos de un peer hostil.
const videoMaxFrame = 1 << 20

// VideoStream es como CallStream pero con framing uint32 (un NAL/keyframe H.264 no cabe
// en los 64 KiB del framing de audio). El payload es opaco (cifrado E2EE en Kotlin).
type VideoStream struct {
	s   network.Stream
	r   *bufio.Reader
	wmu sync.Mutex
}

func newVideoStream(s network.Stream) *VideoStream {
	return &VideoStream{s: s, r: bufio.NewReaderSize(s, 32*1024)}
}

// RemotePeer devuelve el PeerID del otro extremo (autenticado por libp2p/Noise).
func (v *VideoStream) RemotePeer() string { return v.s.Conn().RemotePeer().String() }

// WriteFrame envía un frame (≤ 1 MiB) como una sola escritura (long. uint32 + payload).
func (v *VideoStream) WriteFrame(data []byte) error {
	if len(data) > videoMaxFrame {
		return fmt.Errorf("frame de vídeo de %d bytes excede 1 MiB", len(data))
	}
	buf := make([]byte, 4+len(data))
	buf[0] = byte(len(data) >> 24)
	buf[1] = byte(len(data) >> 16)
	buf[2] = byte(len(data) >> 8)
	buf[3] = byte(len(data))
	copy(buf[4:], data)
	v.wmu.Lock()
	defer v.wmu.Unlock()
	_, err := v.s.Write(buf)
	return err
}

// ReadFrame bloquea hasta el siguiente frame; devuelve error al cerrarse el stream (EOF)
// o si el otro extremo anuncia un frame por encima del tope.
func (v *VideoStream) ReadFrame() ([]byte, error) {
	var hdr [4]byte
	if _, err := io.ReadFull(v.r, hdr[:]); err != nil {
		return nil, err
	}
	n := int(hdr[0])<<24 | int(hdr[1])<<16 | int(hdr[2])<<8 | int(hdr[3])
	if n > videoMaxFrame {
		return nil, fmt.Errorf("frame de vídeo anunciado de %d bytes excede 1 MiB", n)
	}
	buf := make([]byte, n)
	if _, err := io.ReadFull(v.r, buf); err != nil {
		return nil, err
	}
	return buf, nil
}

// Close cierra el stream; el otro extremo ve EOF en su ReadFrame.
func (v *VideoStream) Close() error { return v.s.Close() }

// VideoHandler lo implementa el lado Kotlin para recibir streams de vídeo entrantes.
type VideoHandler interface {
	OnVideoStream(s *VideoStream)
}

// SetVideoHandler registra el handler de streams de vídeo entrantes.
func (n *Node) SetVideoHandler(h VideoHandler) {
	n.h.SetStreamHandler(VideoProtocolID, func(s network.Stream) {
		if h == nil {
			_ = s.Reset()
			return
		}
		h.OnVideoStream(newVideoStream(s))
	})
}

// OpenVideoStream abre un stream de vídeo hacia peerID (permite conexiones relayed,
// igual que las llamadas; DCUtR intentará subir a directa en paralelo).
func (n *Node) OpenVideoStream(peerID string) (*VideoStream, error) {
	pid, err := peer.Decode(peerID)
	if err != nil {
		return nil, err
	}
	ctx := network.WithAllowLimitedConn(n.ctx, "krypta-video")
	s, err := n.h.NewStream(ctx, pid, VideoProtocolID)
	if err != nil {
		return nil, err
	}
	return newVideoStream(s), nil
}

// --- Buzón E2EE store-and-forward (cliente; el servidor vive en infra/node) ----------
//
// Protocolo JSON por líneas sobre streams libp2p (interop v0.48 móvil ↔ v0.38 nodo):
// put = {"v":1,"to","blob"} → {"ok"}|{"err"}; get = sobres {"id","from","ts","blob"} +
// {"done":true}, y el cliente responde {"ack":[ids]} para que el nodo borre. El nodo fija
// el `from` desde la identidad del stream y solo entrega los sobres dirigidos a ella.

const (
	mbxPutProtocol = protocol.ID("/krypta/mbx/put/1.0.0")
	mbxGetProtocol = protocol.ID("/krypta/mbx/get/1.0.0")
)

type mbxEnvelope struct {
	ID   string `json:"id"`
	From string `json:"from"`
	Ts   int64  `json:"ts"`
	Blob string `json:"blob"`
	Done bool   `json:"done,omitempty"`
	Err  string `json:"err,omitempty"`
	OK   bool   `json:"ok,omitempty"`
}

// MailboxHandler is implemented on the Kotlin side to receive messages fetched from the
// mailbox. `id` is the envelope id (used for client-side dedup on redelivery) and `ts`
// the deposit time (unix millis). It must process the message SYNCHRONOUSLY and return
// true only once it is safely persisted: only handled envelopes get ack'd (deleted) at
// the node — the rest are redelivered on the next fetch, so a crash or a failed write
// mid-batch no longer loses messages/chunks.
type MailboxHandler interface {
	OnMailboxMessage(id string, from string, ts int64, data []byte) bool
}

// SetMailboxHandler registers the handler that MailboxFetch delivers messages to.
func (n *Node) SetMailboxHandler(h MailboxHandler) { n.mailboxHandler = h }

// connectNode conecta (si hace falta) con un nodo concreto.
func (n *Node) connectNode(ctx context.Context, ai peer.AddrInfo) error {
	if n.h.Network().Connectedness(ai.ID) != network.Connected {
		if err := n.h.Connect(ctx, ai); err != nil {
			return fmt.Errorf("connect buzón: %w", err)
		}
	}
	return nil
}

// MailboxPut deposita un blob (ya cifrado E2EE) para `to` en el buzón, para entrega
// offline. Multi-nodo: prueba los nodos EN ORDEN y deposita en el primero que acepte
// (failover); como todo cliente retira de TODOS los nodos (MailboxFetch), da igual en
// cuál aterrice. Devuelve error solo si ninguno lo aceptó.
func (n *Node) MailboxPut(mailboxAddrs string, to string, data []byte) error {
	nodes := parseAddrInfos(mailboxAddrs)
	if len(nodes) == 0 {
		return errors.New("sin nodo de buzón configurado")
	}
	var errs []string
	for _, ai := range nodes {
		if err := n.mailboxPutTo(ai, to, data); err != nil {
			errs = append(errs, fmt.Sprintf("%s: %v", shortID(ai.ID), err))
			continue
		}
		return nil
	}
	return errors.New("buzón: " + strings.Join(errs, "; "))
}

func (n *Node) mailboxPutTo(ai peer.AddrInfo, to string, data []byte) error {
	ctx, cancel := context.WithTimeout(n.ctx, 30*time.Second)
	defer cancel()
	if err := n.connectNode(ctx, ai); err != nil {
		return err
	}
	s, err := n.h.NewStream(ctx, ai.ID, mbxPutProtocol)
	if err != nil {
		return err
	}
	defer s.Close()
	req, err := json.Marshal(map[string]any{
		"v":    1,
		"to":   to,
		"blob": base64.StdEncoding.EncodeToString(data),
	})
	if err != nil {
		return err
	}
	if _, err := fmt.Fprintf(s, "%s\n", req); err != nil {
		return err
	}
	_ = s.CloseWrite()
	line, err := bufio.NewReader(io.LimitReader(s, 8<<10)).ReadBytes('\n')
	if err != nil && len(line) == 0 {
		return fmt.Errorf("buzón sin respuesta: %w", err)
	}
	var resp mbxEnvelope
	if err := json.Unmarshal(line, &resp); err != nil {
		return fmt.Errorf("respuesta del buzón ilegible: %w", err)
	}
	if resp.Err != "" {
		return errors.New("buzón: " + resp.Err)
	}
	return nil
}

// MailboxFetch retira los mensajes pendientes del buzón propio (autenticado por la
// identidad libp2p del stream), los entrega uno a uno al MailboxHandler registrado y
// ack'ea al nodo para que los borre. Multi-nodo: retira de TODOS los nodos alcanzables
// (un depósito puede haber aterrizado en cualquiera, según qué nodo viera el emisor);
// devuelve el total y error solo si TODOS fallaron.
func (n *Node) MailboxFetch(mailboxAddrs string) (int, error) {
	nodes := parseAddrInfos(mailboxAddrs)
	if len(nodes) == 0 {
		return 0, errors.New("sin nodo de buzón configurado")
	}
	// En paralelo: cada nodo ya tenía su plazo de 60 s, pero en serie el paso podía costar
	// hasta 180 s con tres nodos — y este es justo el paso que entrega los mensajes.
	total := 0
	var errs []string
	var (
		mu sync.Mutex
		wg sync.WaitGroup
	)
	for _, ai := range nodes {
		wg.Add(1)
		go func(ai peer.AddrInfo) {
			defer wg.Done()
			got, err := n.mailboxFetchFrom(ai)
			mu.Lock()
			defer mu.Unlock()
			total += got
			if err != nil {
				errs = append(errs, fmt.Sprintf("%s: %v", shortID(ai.ID), err))
			}
		}(ai)
	}
	wg.Wait()
	if len(errs) == len(nodes) {
		return total, errors.New("buzón: " + strings.Join(errs, "; "))
	}
	return total, nil
}

func (n *Node) mailboxFetchFrom(ai peer.AddrInfo) (int, error) {
	ctx, cancel := context.WithTimeout(n.ctx, 60*time.Second)
	defer cancel()
	if err := n.connectNode(ctx, ai); err != nil {
		return 0, err
	}
	s, err := n.h.NewStream(ctx, ai.ID, mbxGetProtocol)
	if err != nil {
		return 0, err
	}
	defer s.Close()
	r := bufio.NewReader(s)
	var envs []mbxEnvelope
	for {
		line, err := r.ReadBytes('\n')
		if err != nil {
			return 0, fmt.Errorf("buzón interrumpido: %w", err)
		}
		var env mbxEnvelope
		if err := json.Unmarshal(line, &env); err != nil {
			return 0, fmt.Errorf("sobre ilegible: %w", err)
		}
		if env.Done {
			break
		}
		envs = append(envs, env)
	}
	// Entregar al handler antes del ack, y ack'ear SOLO lo que el handler confirme
	// persistido: lo no confirmado queda en el nodo y se reentrega en el próximo fetch
	// (el cliente deduplica por id). Un blob con base64 corrupto sí se ack'ea: no hay
	// nada que persistir y reentregarlo sería un bucle envenenado.
	ids := make([]string, 0, len(envs))
	for _, env := range envs {
		data, err := base64.StdEncoding.DecodeString(env.Blob)
		if err != nil {
			ids = append(ids, env.ID)
			continue
		}
		if handleMailboxEnvelope(n.mailboxHandler, env.ID, env.From, env.Ts, data) {
			ids = append(ids, env.ID)
		}
	}
	ack, err := json.Marshal(map[string][]string{"ack": ids})
	if err != nil {
		return len(ids), err
	}
	if _, err := fmt.Fprintf(s, "%s\n", ack); err != nil {
		return len(ids), err
	}
	_ = s.CloseWrite()
	return len(ids), nil
}

// handleMailboxEnvelope llama al handler protegiéndose de panics: una excepción del lado
// Kotlin dentro del callback gomobile aflora aquí como panic y debe contar como "no
// persistido" (→ sin ack, el nodo reentrega), no tumbar el fetch entero.
func handleMailboxEnvelope(h MailboxHandler, id, from string, ts int64, data []byte) (ok bool) {
	if h == nil {
		return false
	}
	defer func() {
		if r := recover(); r != nil {
			ok = false
		}
	}()
	return h.OnMailboxMessage(id, from, ts, data)
}

// --- Wake (aviso de buzón; el servidor vive integrado en infra/node) -----------------

const wakeProtocol = protocol.ID("/krypta/wake/1.0.0")

// WakeHandler is implemented on the Kotlin side. OnWake fires when the node signals
// there is mail in our mailbox — and also right after each (re)connection of the wake
// stream, so any notice lost while disconnected is covered by an immediate fetch.
type WakeHandler interface {
	OnWake()
}

// StartWake keeps a lightweight wake stream to EVERY node in wakeAddrs (multi-node: a
// deposit can land on any of them, and each node only notifies its own mailbox), auto-
// reconnecting (Cloudflare Free recycles WebSockets ~every 10 min; the node sends
// keepalives every ~50 s against the ~100 s idle cut). Idempotent: a second call while
// running is a no-op.
func (n *Node) StartWake(wakeAddrs string, h WakeHandler) {
	n.wakeMu.Lock()
	defer n.wakeMu.Unlock()
	if n.wakeCancel != nil {
		return
	}
	// Parsear ANTES de marcar el wake como arrancado. Antes se guardaba wakeCancel y solo
	// después se miraba la lista: si StartWake llegaba sin nodos (host aún sin arrancar, o
	// pref de bootstrap todavía vacía), quedaba "arrancado" con **cero** streams y el guard
	// de arriba impedía reintentarlo para siempre — sin push, solo sondeo.
	nodes := parseAddrInfos(wakeAddrs)
	if len(nodes) == 0 {
		return
	}
	ctx, cancel := context.WithCancel(n.ctx)
	n.wakeCancel = cancel
	for _, ai := range nodes {
		go n.wakeLoop(ctx, ai, h)
	}
}

// StopWake tears the wake stream down (e.g., WAN disabled).
func (n *Node) StopWake() {
	n.wakeMu.Lock()
	defer n.wakeMu.Unlock()
	if n.wakeCancel != nil {
		n.wakeCancel()
		n.wakeCancel = nil
	}
}

// WakeOnline reports whether at least one wake stream is currently open. When true,
// deposits (to that node) are pushed instantly, so the client can relax its WAN polling
// loop (battery). False during the reconnect backoff or when wake isn't running.
func (n *Node) WakeOnline() bool {
	n.wakeMu.Lock()
	defer n.wakeMu.Unlock()
	return n.wakeStreams > 0
}

// addWakeOnline suma/resta streams de wake vivos (multi-nodo: uno por nodo).
func (n *Node) addWakeOnline(delta int) {
	n.wakeMu.Lock()
	n.wakeStreams += delta
	n.wakeMu.Unlock()
}

func (n *Node) wakeLoop(ctx context.Context, ai peer.AddrInfo, h WakeHandler) {
	for ctx.Err() == nil {
		_ = n.wakeSession(ctx, ai, h)
		select {
		case <-ctx.Done():
			return
		case <-time.After(5 * time.Second): // backoff antes de reintentar
		}
	}
}

// wakeSession abre el stream de wake hacia un nodo y lo lee hasta que muera.
func (n *Node) wakeSession(ctx context.Context, ai peer.AddrInfo, h WakeHandler) error {
	dialCtx, cancelDial := context.WithTimeout(ctx, 30*time.Second)
	if err := n.connectNode(dialCtx, ai); err != nil {
		cancelDial()
		return err
	}
	s, err := n.h.NewStream(dialCtx, ai.ID, wakeProtocol)
	cancelDial()
	if err != nil {
		return err
	}
	// Cuenta este stream como online mientras viva (el bucle WAN del cliente lo consulta).
	n.addWakeOnline(1)
	defer n.addWakeOnline(-1)
	// Al cancelar el contexto, resetea el stream para desbloquear el Read.
	sctx, cancelSession := context.WithCancel(ctx)
	defer cancelSession()
	go func() {
		<-sctx.Done()
		_ = s.Reset()
	}()

	// (Re)conectados: retirar una vez, por si hubo avisos durante la desconexión.
	h.OnWake()

	r := bufio.NewReader(s)
	for {
		line, err := r.ReadBytes('\n')
		if err != nil {
			return err
		}
		var msg struct {
			Wake bool `json:"wake"`
		}
		if json.Unmarshal(line, &msg) == nil && msg.Wake {
			h.OnWake()
		}
	}
}

// Advertise announces this node under the given rendezvous key (the daily HKDF value,
// hex-encoded by the caller).
func (n *Node) Advertise(rendezvous string) {
	dutil.Advertise(n.ctx, n.disc, rendezvous)
}

// FindPeers looks up peers advertising under rendezvous (waiting up to timeoutSec),
// connects to each (best effort, so SendMessage can reuse the connection), and returns
// their peer IDs (one per line), excluding self.
func (n *Node) FindPeers(rendezvous string, timeoutSec int) (string, error) {
	ctx, cancel := context.WithTimeout(n.ctx, time.Duration(timeoutSec)*time.Second)
	defer cancel()
	ch, err := n.disc.FindPeers(ctx, rendezvous)
	if err != nil {
		return "", err
	}
	var found []string
	for ai := range ch {
		if ai.ID == n.h.ID() || ai.ID == "" {
			continue
		}
		if len(ai.Addrs) > 0 {
			_ = n.h.Connect(ctx, ai)
		}
		found = append(found, ai.ID.String())
	}
	return strings.Join(found, "\n"), nil
}

// PeerID returns the node's libp2p peer identity (derived from its public key).
func (n *Node) PeerID() string { return n.h.ID().String() }

// ListenAddrs returns the node's full multiaddrs (with /p2p/<id>), newline-separated.
func (n *Node) ListenAddrs() string {
	var out []string
	for _, a := range n.h.Addrs() {
		out = append(out, a.String()+"/p2p/"+n.h.ID().String())
	}
	return strings.Join(out, "\n")
}

// Close shuts the DHT and host down and releases sockets.
func (n *Node) Close() error {
	if n.cancel != nil {
		n.cancel()
	}
	if n.dht != nil {
		_ = n.dht.Close()
	}
	return n.h.Close()
}

func peerInfo(maddr string) (*peer.AddrInfo, error) {
	ma, err := multiaddr.NewMultiaddr(maddr)
	if err != nil {
		return nil, err
	}
	return peer.AddrInfoFromP2pAddr(ma)
}

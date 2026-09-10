package bridge

import (
	"context"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	multiaddr "github.com/multiformats/go-multiaddr"
)

func plainHost(t *testing.T, opts ...libp2p.Option) host.Host {
	t.Helper()
	h, err := libp2p.New(append([]libp2p.Option{libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0")}, opts...)...)
	if err != nil {
		t.Fatalf("host: %v", err)
	}
	t.Cleanup(func() { _ = h.Close() })
	return h
}

// TestGaterAbiertoSinLista: mientras la app no fije lista, todo entra (comportamiento anterior).
func TestGaterAbiertoSinLista(t *testing.T) {
	g := newPeerGater()
	if !g.allows("12D3KooWCualquiera") {
		t.Fatal("con lista vacía el filtro debe estar abierto")
	}
	if !g.InterceptSecured(network.DirInbound, "12D3KooWCualquiera", nil) {
		t.Fatal("entrante debería pasar con lista vacía")
	}
}

// TestGaterSoloDejaEntrarALaLista es la propiedad que cierra la fuga de IP: un desconocido no
// llega ni a establecer conexión, así que no hay identify ni hole punching que le dé la IP.
func TestGaterSoloDejaEntrarALaLista(t *testing.T) {
	amigo, extraño := plainHost(t), plainHost(t)
	g := newPeerGater()
	g.set([]peer.ID{amigo.ID()})
	yo := plainHost(t, libp2p.ConnectionGater(g))

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	if err := amigo.Connect(ctx, peer.AddrInfo{ID: yo.ID(), Addrs: yo.Addrs()}); err != nil {
		t.Fatalf("el contacto de la lista debería entrar: %v", err)
	}
	if yo.Network().Connectedness(amigo.ID()) != network.Connected {
		t.Fatal("el contacto de la lista debería quedar conectado")
	}

	// El desconocido intenta entrar. Lo que importa NO es el error que ve él —el que marca
	// puede dar por completado el handshake un instante antes de que el otro lado cierre—,
	// sino que NOSOTROS nunca demos la conexión por establecida: es la notificación de
	// "conectado" la que dispara identify y el hole punching, o sea la fuga.
	_ = extraño.Connect(ctx, peer.AddrInfo{ID: yo.ID(), Addrs: yo.Addrs()})
	conectado := true
	for i := 0; i < 40 && conectado; i++ {
		if yo.Network().Connectedness(extraño.ID()) != network.Connected {
			conectado = false
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if conectado {
		t.Fatal("un desconocido quedó conectado: el filtro no cortó la entrada")
	}

	// Y no puede usarla para nada: sin conexión aceptada no hay stream.
	sctx, scancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer scancel()
	if _, err := extraño.NewStream(sctx, yo.ID(), "/krypta/msg/1.0.0"); err == nil {
		t.Fatal("un desconocido pudo abrir un stream")
	}
}

// TestGaterNoEstorbaLasSalidas: hay que poder marcar a los nodos y a los contactos aunque no
// estén en la lista (la lista es de ENTRADAS). Si esto fallara, la app se quedaría sin red.
func TestGaterNoEstorbaLasSalidas(t *testing.T) {
	nodo := plainHost(t)
	g := newPeerGater()
	g.set([]peer.ID{"12D3KooWOtroCualquiera"}) // el nodo NO está en la lista
	yo := plainHost(t, libp2p.ConnectionGater(g))

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := yo.Connect(ctx, peer.AddrInfo{ID: nodo.ID(), Addrs: nodo.Addrs()}); err != nil {
		t.Fatalf("una salida no debe filtrarse: %v", err)
	}
}

// TestGaterCortaLaEntradaPorRelay reproduce el caso REAL (verificado contra el móvil el 10 sep
// 2026): el extraño no marca directo —no puede, la víctima está tras NAT— sino por la
// **dirección de relay** que el propio nodo le entrega. Ese era el camino por el que se
// escapaba la IP: la conexión se aceptaba y libp2p iniciaba el hole punching por su cuenta,
// mandándole las direcciones públicas.
//
// El test está escrito para distinguir las tres causas posibles de un fallo, que a ojo se
// confunden: que la conexión entrante por relay no pase por el filtro, que pase pero no se
// rechace, o que la lista estuviera vacía (modo abierto).
func TestGaterCortaLaEntradaPorRelay(t *testing.T) {
	relay, err := libp2p.New(
		libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"),
		libp2p.EnableRelayService(),
		libp2p.ForceReachabilityPublic(),
	)
	if err != nil {
		t.Fatalf("relay: %v", err)
	}
	defer relay.Close()
	relayAddr := relay.Addrs()[0].String() + "/p2p/" + relay.ID().String()

	id, err := GenerateIdentity()
	if err != nil {
		t.Fatalf("identidad: %v", err)
	}
	victima, err := NewNodeWithIdentity(id, relayAddr)
	if err != nil {
		t.Fatalf("víctima: %v", err)
	}
	defer victima.Close()
	if r := victima.ReserveRelay(relayAddr); len(r) < 2 || r[:2] != "OK" {
		t.Fatalf("reserva de relay: %s", r)
	}

	// La lista tal como la fija la app: el relay sí (tiene que poder hablarnos de vuelta para
	// entregarnos las conexiones), el extraño no.
	victima.SetAllowedPeers(relay.ID().String())
	if got := victima.AllowedPeerCount(); got != 1 {
		t.Fatalf("la lista debería tener 1 PeerID, tiene %d (con 0 el filtro está abierto)", got)
	}

	extraño := plainHost(t, libp2p.EnableRelay())
	circuit, err := multiaddr.NewMultiaddr(relayAddr + "/p2p-circuit")
	if err != nil {
		t.Fatalf("circuit: %v", err)
	}
	victimaID, err := peer.Decode(victima.PeerID())
	if err != nil {
		t.Fatalf("PeerID de la víctima: %v", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	// Las conexiones relayadas son "limited": sin esto libp2p no las intenta.
	if err := extraño.Connect(network.WithAllowLimitedConn(ctx, "test"), peer.AddrInfo{ID: victimaID, Addrs: []multiaddr.Multiaddr{circuit}}); err == nil {
		// El que marca puede dar el handshake por completado antes de que el otro cierre; lo
		// que decide es si puede USAR la conexión.
		sctx, scancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer scancel()
		if _, serr := extraño.NewStream(network.WithAllowLimitedConn(sctx, "test"), victimaID, "/krypta/msg/1.0.0"); serr == nil {
			t.Errorf("el extraño abrió un stream por el relay: el filtro no cortó")
		}
	}

	vistas, rechazadas := victima.gater.inboundChecked.Load(), victima.gater.inboundDenied.Load()
	t.Logf("filtro: vistas=%d rechazadas=%d", vistas, rechazadas)
	if vistas == 0 {
		t.Fatal("la conexión entrante por relay NO pasa por InterceptSecured: hay que filtrar en otro punto")
	}
	if rechazadas == 0 {
		t.Fatal("el filtro se consultó pero dejó entrar al extraño (¿dirección mal interpretada?)")
	}
}

// TestSetAllowedPeersParsea: la app manda una lista separada por saltos de línea; las entradas
// inválidas se ignoran en vez de dejar el filtro a medias.
func TestSetAllowedPeersParsea(t *testing.T) {
	n, err := NewNode()
	if err != nil {
		t.Fatalf("node: %v", err)
	}
	defer n.Close()

	a, b := plainHost(t), plainHost(t)
	n.SetAllowedPeers(a.ID().String() + "\n" + b.ID().String() + "\nesto-no-es-un-peerid\n")
	if got := n.AllowedPeerCount(); got != 2 {
		t.Fatalf("esperaba 2 PeerID válidos, hubo %d", got)
	}
	n.SetAllowedPeers("")
	if got := n.AllowedPeerCount(); got != 0 {
		t.Fatalf("la cadena vacía debe volver al modo abierto, hubo %d", got)
	}
}

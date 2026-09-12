package bridge

import (
	"context"
	"io"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	multiaddr "github.com/multiformats/go-multiaddr"
)

// hostTCPyWS levanta un nodo destino que escucha por tcp y por ws en loopback, y devuelve sus
// dos líneas de bootstrap (con /p2p/<id>), la directa y la WebSocket.
func hostTCPyWS(t *testing.T) (host.Host, string, string) {
	t.Helper()
	target, err := libp2p.New(libp2p.ListenAddrStrings(
		"/ip4/127.0.0.1/tcp/0",
		"/ip4/127.0.0.1/tcp/0/ws",
	))
	if err != nil {
		t.Fatalf("nodo destino: %v", err)
	}
	t.Cleanup(func() { target.Close() })
	var tcpLine, wsLine string
	for _, a := range target.Addrs() {
		line := a.String() + "/p2p/" + target.ID().String()
		switch {
		case strings.HasSuffix(a.String(), "/ws"):
			wsLine = line
		case strings.Contains(a.String(), "/tcp/"):
			tcpLine = line
		}
	}
	if tcpLine == "" || wsLine == "" {
		t.Fatalf("el destino debería escuchar por tcp y por ws: %v", target.Addrs())
	}
	return target, tcpLine, wsLine
}

func viasDe(h host.Host, p peer.ID) []string {
	var vias []string
	for _, c := range h.Network().ConnsToPeer(p) {
		vias = append(vias, c.RemoteMultiaddr().String())
	}
	return vias
}

// TestStartDHTUnaConexionPorNodoConDosVias: con las dos vías en la lista, como en
// DEFAULT_BOOTSTRAP, queda UNA conexión y es la directa. Se prueba en los dos órdenes. Es el
// estado final que se exige; StartDHT agrupa las líneas por PeerID para que el ranker vea las
// dos vías en un solo dial.
func TestStartDHTUnaConexionPorNodoConDosVias(t *testing.T) {
	for _, tc := range []struct {
		name    string
		wsFirst bool
	}{
		{"directa primero (como DEFAULT_BOOTSTRAP)", false},
		{"websocket primero", true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			target, tcpLine, wsLine := hostTCPyWS(t)
			list := tcpLine + "\n" + wsLine
			if tc.wsFirst {
				list = wsLine + "\n" + tcpLine
			}
			client, err := NewNode()
			if err != nil {
				t.Fatalf("NewNode: %v", err)
			}
			defer client.Close()
			if err := client.StartDHT(list, false); err != nil {
				t.Fatalf("StartDHT: %v", err)
			}
			// Más que wsDialDelay: si la vía WebSocket se marcara por su cuenta, ya estaría.
			time.Sleep(wsDialDelay + 500*time.Millisecond)
			conns := client.h.Network().ConnsToPeer(target.ID())
			if len(conns) != 1 || isWebsocketAddr(conns[0].RemoteMultiaddr()) {
				t.Fatalf("esperaba una sola conexión y directa, hay: %v", viasDe(client.h, target.ID()))
			}
		})
	}
}

// TestWebsocketRedundanteSeCierra: lo visto en el TECNO el 12 sep 2026 era un VPS con una
// conexión por tcp/4001 **y otra por wss/443** a la vez. Un solo dial de libp2p no deja dos
// (el worker cancela los que quedan en vuelo al completar uno), así que vienen de episodios
// distintos; sea cual sea el camino, la regla de conn_prune.go es la misma: con una directa
// abierta, la WebSocket sobra.
//
// Para fabricar la situación de forma determinista se usan **dos hosts con la misma
// identidad** (dos swarms, un solo PeerID) que entran al nodo podado uno por ws y otro por
// tcp: para el nodo son dos conexiones del mismo peer, exactamente lo que ve el podador.
func TestWebsocketRedundanteSeCierra(t *testing.T) {
	target, tcpLine, wsLine := hostTCPyWS(t)
	var pruned atomic.Int64
	installWebsocketPruner(target, &pruned)

	priv, _, err := crypto.GenerateEd25519Key(nil)
	if err != nil {
		t.Fatal(err)
	}
	dialer := func(line string) host.Host {
		h, err := libp2p.New(libp2p.Identity(priv), libp2p.NoListenAddrs)
		if err != nil {
			t.Fatalf("host: %v", err)
		}
		t.Cleanup(func() { h.Close() })
		ma, _ := multiaddr.NewMultiaddr(line)
		ai, _ := peer.AddrInfoFromP2pAddr(ma)
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := h.Connect(ctx, *ai); err != nil {
			t.Fatalf("connect %s: %v", line, err)
		}
		return h
	}
	viaWS := dialer(wsLine)
	peerID := viaWS.ID()
	time.Sleep(200 * time.Millisecond)
	if got := viasDe(target, peerID); len(got) != 1 || !isWebsocketAddr(target.Network().ConnsToPeer(peerID)[0].RemoteMultiaddr()) {
		t.Fatalf("antes de la directa debería haber solo la ws: %v", got)
	}

	// Llega la directa del mismo peer: la ws tiene que cerrarse sola.
	viaTCP := dialer(tcpLine)
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		conns := target.Network().ConnsToPeer(peerID)
		if len(conns) == 1 && !isWebsocketAddr(conns[0].RemoteMultiaddr()) && pruned.Load() == 1 {
			if viaWS.Network().Connectedness(target.ID()) == 1 /* Connected */ {
				time.Sleep(100 * time.Millisecond)
				continue // el cierre aún no ha llegado al otro extremo
			}
			if viaTCP.Network().Connectedness(target.ID()) != 1 {
				t.Fatalf("la directa no debería cerrarse")
			}
			t.Logf("OK: ws cerrada (%d), queda %v", pruned.Load(), viasDe(target, peerID))
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatalf("pasados 5 s el nodo sigue con %v (podadas=%d); se esperaba solo la directa", viasDe(target, peerID), pruned.Load())
}

// TestPodaEsperaAQueLaWebsocketQuedeLibre: una WebSocket que lleva una llamada (o un circuito
// de relay) no se corta aunque sobre; se cierra en cuanto ese stream termina.
func TestPodaEsperaAQueLaWebsocketQuedeLibre(t *testing.T) {
	antes := pruneRecheck
	pruneRecheck = 150 * time.Millisecond
	defer func() { pruneRecheck = antes }()

	target, tcpLine, wsLine := hostTCPyWS(t)
	var pruned atomic.Int64
	installWebsocketPruner(target, &pruned)
	target.SetStreamHandler("/krypta/call/1.0.0", func(s network.Stream) {
		io.Copy(io.Discard, s) // la "llamada" dura lo que el otro lado la mantenga abierta
		s.Close()
	})

	priv, _, err := crypto.GenerateEd25519Key(nil)
	if err != nil {
		t.Fatal(err)
	}
	connect := func(line string) host.Host {
		h, err := libp2p.New(libp2p.Identity(priv), libp2p.NoListenAddrs)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { h.Close() })
		ma, _ := multiaddr.NewMultiaddr(line)
		ai, _ := peer.AddrInfoFromP2pAddr(ma)
		if err := h.Connect(context.Background(), *ai); err != nil {
			t.Fatalf("connect %s: %v", line, err)
		}
		return h
	}
	viaWS := connect(wsLine)
	call, err := viaWS.NewStream(context.Background(), target.ID(), "/krypta/call/1.0.0")
	if err != nil {
		t.Fatalf("stream de llamada: %v", err)
	}
	if _, err := call.Write([]byte("audio")); err != nil {
		t.Fatal(err)
	}
	time.Sleep(200 * time.Millisecond)

	connect(tcpLine) // llega la directa: la ws sobra, pero lleva una llamada
	time.Sleep(3 * pruneRecheck)
	if n := len(target.Network().ConnsToPeer(viaWS.ID())); n != 2 || pruned.Load() != 0 {
		t.Fatalf("con la llamada en curso la ws no debía cerrarse: conns=%d podadas=%d", n, pruned.Load())
	}

	call.Close() // cuelga
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if pruned.Load() == 1 && len(target.Network().ConnsToPeer(viaWS.ID())) == 1 {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("tras colgar, la ws sobrante debía cerrarse: %v (podadas=%d)", viasDe(target, viaWS.ID()), pruned.Load())
}

// TestPodaNoTocaLoQueNoSobra: sin conexión directa no se cierra nada (un móvil en una red que
// solo deja el 443 vive por la WebSocket), y una conexión por relay ni cuenta como directa ni
// se poda.
func TestPodaNoTocaLoQueNoSobra(t *testing.T) {
	target, _, wsLine := hostTCPyWS(t)
	var pruned atomic.Int64
	installWebsocketPruner(target, &pruned)
	h, err := libp2p.New(libp2p.NoListenAddrs)
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	ma, _ := multiaddr.NewMultiaddr(wsLine)
	ai, _ := peer.AddrInfoFromP2pAddr(ma)
	if err := h.Connect(context.Background(), *ai); err != nil {
		t.Fatal(err)
	}
	time.Sleep(300 * time.Millisecond)
	if n, _ := closeRedundantWebsocketConns(target.Network(), h.ID()); n != 0 || pruned.Load() != 0 {
		t.Fatalf("sin directa no debía cerrarse nada: %d/%d", n, pruned.Load())
	}
	if got := viasDe(target, h.ID()); len(got) != 1 {
		t.Fatalf("la ws debía seguir abierta: %v", got)
	}
	circuit, _ := multiaddr.NewMultiaddr("/ip4/1.2.3.4/tcp/443/wss/p2p/" + target.ID().String() + "/p2p-circuit")
	if !isCircuitAddr(circuit) {
		t.Fatalf("una dirección por relay debe reconocerse como tal")
	}
}

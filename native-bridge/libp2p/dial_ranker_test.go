package bridge

import (
	"testing"
	"time"

	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/p2p/net/swarm"
	multiaddr "github.com/multiformats/go-multiaddr"
)

func delayOf(t *testing.T, ranked []network.AddrDelay, addr string) time.Duration {
	t.Helper()
	for _, r := range ranked {
		if r.Addr.String() == addr {
			return r.Delay
		}
	}
	t.Fatalf("%s no está en el resultado del ranker: %v", addr, ranked)
	return 0
}

func addrs(ss ...string) []multiaddr.Multiaddr {
	out := make([]multiaddr.Multiaddr, 0, len(ss))
	for _, s := range ss {
		out = append(out, multiaddr.StringCast(s))
	}
	return out
}

const (
	rankTCP   = "/ip4/203.0.113.7/tcp/4001"
	rankWSS   = "/ip4/203.0.113.7/tcp/443/wss"
	rankTLSWS = "/ip4/203.0.113.7/tcp/443/tls/sni/krypta-sp.neto.chat/ws"
	rankQUIC  = "/ip4/203.0.113.7/udp/4001/quic-v1"
)

// TestDialRankerEstandarPrefiereWSS documenta el motivo del ranker propio: el de libp2p marca
// `wss/443` ANTES que `tcp/4001` (puerto más bajo primero). Si un día deja de hacerlo, este
// test avisa de que el ranker propio sobra.
func TestDialRankerEstandarPrefiereWSS(t *testing.T) {
	ranked := swarm.DefaultDialRanker(addrs(rankTCP, rankWSS))
	if !(delayOf(t, ranked, rankWSS) < delayOf(t, ranked, rankTCP)) {
		t.Skipf("el ranker estándar ya no adelanta wss/443 a tcp/4001: %v", ranked)
	}
}

// TestDialRankerDirectoPrimero: con una directa disponible, la vía WebSocket va al menos
// wsDialDelay por detrás de ella, en sus dos formas.
func TestDialRankerDirectoPrimero(t *testing.T) {
	for _, ws := range []string{rankWSS, rankTLSWS} {
		ranked := directFirstDialRanker(addrs(rankTCP, ws))
		if len(ranked) != 2 {
			t.Fatalf("se perdieron direcciones: %v", ranked)
		}
		tcp, w := delayOf(t, ranked, rankTCP), delayOf(t, ranked, ws)
		if tcp != 0 {
			t.Errorf("la TCP directa debe marcarse al instante, retraso %v", tcp)
		}
		if w < tcp+wsDialDelay {
			t.Errorf("%s debe ir ≥ %v por detrás de la TCP: tcp=%v ws=%v", ws, wsDialDelay, tcp, w)
		}
	}
}

// TestDialRankerConQUIC: con QUIC delante, la vía WebSocket sigue siendo la última.
func TestDialRankerConQUIC(t *testing.T) {
	ranked := directFirstDialRanker(addrs(rankWSS, rankTCP, rankQUIC))
	w := delayOf(t, ranked, rankWSS)
	for _, d := range []string{rankTCP, rankQUIC} {
		if w < delayOf(t, ranked, d)+wsDialDelay {
			t.Errorf("wss (%v) no va por detrás de %s (%v)", w, d, delayOf(t, ranked, d))
		}
	}
}

// TestDialRankerSoloWebsocket: si no hay otra vía (un nodo solo por wss), no se retrasa nada.
func TestDialRankerSoloWebsocket(t *testing.T) {
	ranked := directFirstDialRanker(addrs(rankWSS))
	if got := delayOf(t, ranked, rankWSS); got != 0 {
		t.Fatalf("un peer solo con wss debe marcarse al instante, retraso %v", got)
	}
}

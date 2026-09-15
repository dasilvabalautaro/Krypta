package bridge

import (
	"context"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/peer"
	multiaddr "github.com/multiformats/go-multiaddr"
)

// recvHandler capta el último mensaje recibido para aserciones.
type recvHandler struct{ got chan []byte }

func (r *recvHandler) OnMessage(from string, data []byte) { r.got <- data }

// TestRelayMessagingLocal valida el camino COMPLETO de relay sin Cloudflare: un host relay
// (como el nodo de infra) + dos nodos cliente que reservan slot; A envía a B a través del relay
// y B lo recibe. Si esto pasa, el cableado de relay es correcto y un fallo en producción es de
// la ruta (Cloudflare/latencia), no del código.
func TestRelayMessagingLocal(t *testing.T) {
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

	idA, _ := GenerateIdentity()
	idB, _ := GenerateIdentity()
	a, err := NewNodeWithIdentity(idA, relayAddr)
	if err != nil {
		t.Fatalf("node A: %v", err)
	}
	defer a.Close()
	b, err := NewNodeWithIdentity(idB, relayAddr)
	if err != nil {
		t.Fatalf("node B: %v", err)
	}
	defer b.Close()

	rh := &recvHandler{got: make(chan []byte, 1)}
	b.SetMessageHandler(rh)

	if r := a.ReserveRelay(relayAddr); r[:2] != "OK" {
		t.Fatalf("A reserve: %s", r)
	}
	if r := b.ReserveRelay(relayAddr); r[:2] != "OK" {
		t.Fatalf("B reserve: %s", r)
	}

	// A dial B por su dirección de circuit (lo que anunciaría B por rendezvous).
	circuit, _ := multiaddr.NewMultiaddr(relayAddr + "/p2p-circuit")
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := a.h.Connect(ctx, peer.AddrInfo{ID: b.h.ID(), Addrs: []multiaddr.Multiaddr{circuit}}); err != nil {
		t.Fatalf("A connect B via relay: %v", err)
	}
	// W-8: la sesión que va por el circuito autentica a B ante A y a A ante B, no al relay. Que
	// además va cifrada lo comprueba TestRelayNoVeLoQueViajaPorElCircuito.
	exigirCifradoDeExtremoAExtremo(t, a, b, true)
	exigirCifradoDeExtremoAExtremo(t, b, a, true)

	if err := a.SendMessage(b.PeerID(), []byte("hola por relay")); err != nil {
		t.Fatalf("SendMessage: %v", err)
	}
	select {
	case data := <-rh.got:
		if string(data) != "hola por relay" {
			t.Fatalf("mensaje distinto: %q", data)
		}
		t.Log("OK: B recibió el mensaje a través del relay")
	case <-time.After(10 * time.Second):
		t.Fatal("timeout: B no recibió el mensaje")
	}
}

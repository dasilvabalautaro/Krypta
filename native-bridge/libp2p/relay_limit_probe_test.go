package bridge

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p"
	rcclient "github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/client"
)

// TestRelayLimitsAgainstLiveNode comprueba que un nodo REAL ofrece relay **con límites
// finitos**, y de paso sirve para saber, sin entrar en la máquina, si ya corre el binario con
// la capa anti-abuso.
//
// El truco: en Circuit Relay v2 la respuesta de reserva incluye el límite solo cuando lo hay.
// Un nodo con `WithInfiniteLimits()` (el binario anterior al 8 sep 2026) responde 0/0; uno con
// los límites nuevos responde 6 h / 8 GiB. Es la única diferencia del despliegue observable
// desde fuera — así se detectó que el VPS se había quedado atrás dos días.
//
// Se auto-omite sin RELAY_ADDR:
//
//	RELAY_ADDR="/ip4/1.2.3.4/tcp/4001/p2p/<PeerID>" go test -run TestRelayLimitsAgainstLiveNode -v .
func TestRelayLimitsAgainstLiveNode(t *testing.T) {
	addr := os.Getenv("RELAY_ADDR")
	if addr == "" {
		t.Skip("RELAY_ADDR no definido; sonda solo bajo demanda")
	}
	ai, err := peerInfo(addr)
	if err != nil {
		t.Fatalf("multiaddr: %v", err)
	}
	// Sin escucha propia: solo hace falta poder dialar y reservar.
	h, err := libp2p.New(libp2p.NoListenAddrs, libp2p.EnableRelay())
	if err != nil {
		t.Fatalf("host: %v", err)
	}
	defer h.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	if err := h.Connect(ctx, *ai); err != nil {
		t.Fatalf("no se pudo conectar al nodo: %v", err)
	}
	res, err := rcclient.Reserve(ctx, h, *ai)
	if err != nil {
		t.Fatalf("el nodo no ofrece reserva de relay: %v", err)
	}

	if res.LimitDuration == 0 || res.LimitData == 0 {
		t.Fatalf("el nodo anuncia relay SIN límites (duracion=%v datos=%d): corre el binario "+
			"anterior al anti-abuso, hay que redesplegarlo", res.LimitDuration, res.LimitData)
	}
	t.Logf("OK: relay con límites finitos — %v y %.1f GiB por conexión",
		res.LimitDuration, float64(res.LimitData)/(1<<30))
}

package bridge

import (
	"os"
	"testing"
	"time"
)

// TestWakeAgainstLiveNode sondea el wake de un nodo REAL (producción, vía wss/Cloudflare):
// se suscribe con una identidad efímera y espera el OnWake de conexión. Si el nodo no
// expone /krypta/wake/1.0.0 (binario viejo), no llega y falla. Se auto-omite sin WAKE_ADDR:
//
//	WAKE_ADDR="/dns4/krypta.neto.chat/tcp/443/wss/p2p/<PeerID>" go test -run TestWakeAgainstLiveNode -v .
func TestWakeAgainstLiveNode(t *testing.T) {
	addr := os.Getenv("WAKE_ADDR")
	if addr == "" {
		t.Skip("WAKE_ADDR no definido; sonda solo bajo demanda")
	}
	id, err := GenerateIdentity()
	if err != nil {
		t.Fatalf("identity: %v", err)
	}
	n, err := NewNodeWithIdentity(id, "")
	if err != nil {
		t.Fatalf("node: %v", err)
	}
	defer n.Close()

	recv := newWakeRecv()
	n.StartWake(addr, "", recv)
	defer n.StopWake()
	select {
	case <-recv.fired:
		t.Log("OK: el nodo habla /krypta/wake/1.0.0 (suscripción de identidad efímera activa)")
	case <-time.After(20 * time.Second):
		t.Fatal("timeout: el nodo no aceptó la suscripción de wake (¿binario sin redeploy?)")
	}
}

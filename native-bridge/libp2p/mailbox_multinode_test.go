package bridge

import (
	"sort"
	"strings"
	"testing"
)

// deadNodeAddr fabrica una multiaddr válida pero inalcanzable (puerto 1 en loopback →
// connection refused inmediato) con un PeerID real, para simular un nodo de infra caído.
func deadNodeAddr(t *testing.T) string {
	t.Helper()
	id, err := GenerateIdentity()
	if err != nil {
		t.Fatalf("identity: %v", err)
	}
	pid, err := PeerIDForIdentity(id)
	if err != nil {
		t.Fatalf("peerID: %v", err)
	}
	return "/ip4/127.0.0.1/tcp/1/p2p/" + pid
}

// TestMailboxMultiNode valida la semántica multi-nodo del buzón que quita el punto único
// de fallo: el put hace **failover** al primer nodo vivo de la lista (aunque el primero
// esté caído) y el fetch retira de **todos** los nodos alcanzables, de modo que da igual
// dónde aterrizara cada depósito.
func TestMailboxMultiNode(t *testing.T) {
	dead := deadNodeAddr(t)
	_, addrA := startTestMailbox(t)
	_, addrB := startTestMailbox(t)

	idA, _ := GenerateIdentity()
	idB, _ := GenerateIdentity()
	sender, err := NewNodeWithIdentity(idA, "")
	if err != nil {
		t.Fatalf("sender: %v", err)
	}
	defer sender.Close()
	recipient, err := NewNodeWithIdentity(idB, "")
	if err != nil {
		t.Fatalf("recipient: %v", err)
	}
	defer recipient.Close()

	// Failover: con [caído, A] el depósito debe aterrizar en A sin error.
	if err := sender.MailboxPut(dead+"\n"+addrA, recipient.PeerID(), "", []byte("via-A")); err != nil {
		t.Fatalf("put con failover: %v", err)
	}
	// Split-brain: otro emisor (o el mismo en otro momento) solo alcanza B.
	if err := sender.MailboxPut(addrB, recipient.PeerID(), "", []byte("via-B")); err != nil {
		t.Fatalf("put en B: %v", err)
	}

	// El receptor retira con la lista completa (incluido el caído): deben llegar AMBOS
	// sobres y no debe haber error (solo fallaron algunos nodos, no todos).
	recv := &mbxRecv{}
	recipient.SetMailboxHandler(recv)
	n, err := recipient.MailboxFetch(dead+"\n"+addrA+"\n"+addrB, "")
	if err != nil {
		t.Fatalf("fetch multi-nodo: %v", err)
	}
	if n != 2 {
		t.Fatalf("fetch entregó %d (esperado 2): %v", n, recv.got)
	}
	got := append([]string(nil), recv.got...)
	sort.Strings(got)
	if got[0] != "via-A" || got[1] != "via-B" {
		t.Fatalf("payloads %v", recv.got)
	}

	// Con TODOS los nodos caídos sí es error (y n=0).
	if n, err := recipient.MailboxFetch(dead, ""); err == nil || n != 0 {
		t.Fatalf("fetch con todo caído: n=%d err=%v (esperado error)", n, err)
	}
	if err := sender.MailboxPut(dead, recipient.PeerID(), "", []byte("x")); err == nil ||
		!strings.Contains(err.Error(), "buzón") {
		t.Fatalf("put con todo caído: err=%v (esperado error de buzón)", err)
	}
}

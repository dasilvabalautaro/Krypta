package bridge

import (
	"strings"
	"testing"
	"time"
)

// loopbackBootstrap extracts node n's loopback TCP multiaddr(s) so a second in-process
// node can dial it without depending on the machine's external interfaces.
func loopbackBootstrap(t *testing.T, n *Node) string {
	t.Helper()
	var lines []string
	for _, l := range strings.Split(n.ListenAddrs(), "\n") {
		if strings.Contains(l, "127.0.0.1") && strings.Contains(l, "/tcp/") {
			lines = append(lines, l)
		}
	}
	if len(lines) == 0 {
		t.Fatalf("no loopback tcp addr found in:\n%s", n.ListenAddrs())
	}
	return strings.Join(lines, "\n")
}

// TestRendezvousDiscovery proves the core Phase 3 mechanism deterministically and without
// any device: node A advertises under a rendezvous key on a Kademlia DHT, and node B
// (bootstrapped to A) discovers A under the same key.
func TestRendezvousDiscovery(t *testing.T) {
	a, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode A: %v", err)
	}
	defer a.Close()
	if err := a.StartDHT("", true); err != nil {
		t.Fatalf("A StartDHT: %v", err)
	}

	boot := loopbackBootstrap(t, a)

	b, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode B: %v", err)
	}
	defer b.Close()
	if err := b.StartDHT(boot, true); err != nil {
		t.Fatalf("B StartDHT: %v", err)
	}

	const rendezvous = "krypta-test-rendezvous-deadbeefcafe"

	// Advertise es de una sola pasada (ver advertise_test.go): quien reanuncia es el bucle
	// WAN de la app, así que aquí el test lo imita reanunciando en cada vuelta.
	deadline := time.Now().Add(40 * time.Second)
	for time.Now().Before(deadline) {
		a.Advertise(rendezvous)
		b.Advertise(rendezvous)
		res, err := b.FindPeers(rendezvous, 5)
		if err != nil {
			t.Fatalf("B FindPeers: %v", err)
		}
		if strings.Contains(res, a.PeerID()) {
			t.Logf("OK: B discovered A (%s) under rendezvous", a.PeerID())
			return
		}
		time.Sleep(1 * time.Second)
	}
	t.Fatalf("node B never discovered node A (%s) under the rendezvous key", a.PeerID())
}

// TestSharedSecretSymmetry proves the X25519-from-PeerID key agreement: A's secret with B
// equals B's secret with A, and a third party gets a different value.
func TestSharedSecretSymmetry(t *testing.T) {
	idA, _ := GenerateIdentity()
	idB, _ := GenerateIdentity()
	idC, _ := GenerateIdentity()
	peerA, _ := PeerIDForIdentity(idA)
	peerB, _ := PeerIDForIdentity(idB)

	secretAB, err := SharedSecretFor(idA, peerB)
	if err != nil {
		t.Fatalf("A->B: %v", err)
	}
	secretBA, err := SharedSecretFor(idB, peerA)
	if err != nil {
		t.Fatalf("B->A: %v", err)
	}
	if string(secretAB) != string(secretBA) {
		t.Fatal("shared secret A↔B is not symmetric")
	}
	secretCB, _ := SharedSecretFor(idC, peerB)
	if string(secretCB) == string(secretAB) {
		t.Fatal("a third party derived the same secret")
	}
	if len(secretAB) != 32 {
		t.Fatalf("expected 32-byte secret, got %d", len(secretAB))
	}
}

type capture struct{ ch chan string }

func (c *capture) OnMessage(from string, data []byte) { c.ch <- string(data) }

// TestMessageExchange proves the full client path: B discovers A under a rendezvous and
// then sends A a message over a libp2p stream, which A's handler receives.
func TestMessageExchange(t *testing.T) {
	a, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode A: %v", err)
	}
	defer a.Close()
	if err := a.StartDHT("", true); err != nil {
		t.Fatalf("A StartDHT: %v", err)
	}
	cap := &capture{ch: make(chan string, 1)}
	a.SetMessageHandler(cap)
	a.Advertise("krypta-msg-test")

	b, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode B: %v", err)
	}
	defer b.Close()
	if err := b.StartDHT(loopbackBootstrap(t, a), true); err != nil {
		t.Fatalf("B StartDHT: %v", err)
	}

	// discover A (also connects to it) before sending
	deadline := time.Now().Add(40 * time.Second)
	for time.Now().Before(deadline) {
		ids, err := b.FindPeers("krypta-msg-test", 5)
		if err != nil {
			t.Fatalf("FindPeers: %v", err)
		}
		if strings.Contains(ids, a.PeerID()) {
			break
		}
		time.Sleep(time.Second)
	}

	if err := b.SendMessage(a.PeerID(), []byte("hola krypta")); err != nil {
		t.Fatalf("SendMessage: %v", err)
	}

	select {
	case msg := <-cap.ch:
		if msg != "hola krypta" {
			t.Fatalf("A received %q, want %q", msg, "hola krypta")
		}
		t.Logf("OK: A received message from B over libp2p stream")
	case <-time.After(15 * time.Second):
		t.Fatal("A never received the message")
	}
}

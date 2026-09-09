package bridge

import (
	"crypto/rand"
	"encoding/hex"
	"os"
	"testing"
)

// TestBlindMailboxAgainstLiveNode comprueba que un nodo REAL sirve el **depósito ciego** (v2):
// dos identidades efímeras se pasan un blob por una etiqueta al azar y se verifica que vuelve
// intacto y **sin remitente**.
//
// Es la sonda que distingue un nodo con v2 de uno que solo habla v1, cosa que la del relay no
// puede: aquella solo separa "con anti-abuso" de "sin él". Se limpia sola —la retirada
// confirma, así que el nodo borra— y la etiqueta es aleatoria para que dos ejecuciones no se
// pisen. Se auto-omite sin MBX_ADDR:
//
//	MBX_ADDR="/ip4/1.2.3.4/tcp/4001/p2p/<PeerID>" go test -run TestBlindMailboxAgainstLiveNode -v .
func TestBlindMailboxAgainstLiveNode(t *testing.T) {
	addr := os.Getenv("MBX_ADDR")
	if addr == "" {
		t.Skip("MBX_ADDR no definido; sonda solo bajo demanda")
	}
	var raw [32]byte
	if _, err := rand.Read(raw[:]); err != nil {
		t.Fatalf("aleatorio: %v", err)
	}
	label := hex.EncodeToString(raw[:])

	idA, err := GenerateIdentity()
	if err != nil {
		t.Fatalf("identidad A: %v", err)
	}
	a, err := NewNodeWithIdentity(idA, "")
	if err != nil {
		t.Fatalf("nodo A: %v", err)
	}
	defer a.Close()
	idB, err := GenerateIdentity()
	if err != nil {
		t.Fatalf("identidad B: %v", err)
	}
	b, err := NewNodeWithIdentity(idB, "")
	if err != nil {
		t.Fatalf("nodo B: %v", err)
	}
	defer b.Close()

	const payload = "sonda de deposito ciego"
	if err := a.MailboxPut(addr, b.PeerID(), label, []byte(payload)); err != nil {
		t.Fatalf("el nodo no acepta el depósito ciego (¿binario sin v2?): %v", err)
	}

	recv := &blindRecv{}
	b.SetMailboxHandler(recv)
	n, err := b.MailboxFetch(addr, label)
	if err != nil {
		t.Fatalf("retirada por etiqueta: %v", err)
	}
	if n != 1 || len(recv.got) != 1 || recv.got[0] != payload {
		// Ojo al caso más probable: contra un nodo que solo habla v1, el depósito **cae a v1**
		// sin ruido (es lo que debe hacer) y entonces no hay nada bajo la etiqueta. O sea que
		// un cero aquí significa "este nodo todavía no sirve el depósito ciego".
		t.Fatalf("nada bajo la etiqueta (n=%d): el nodo no sirve el depósito ciego, "+
			"probablemente corre un binario anterior a v2", n)
	}
	if recv.froms[0] != "" {
		t.Fatalf("el nodo devolvió remitente en un depósito ciego: %q", recv.froms[0])
	}
	if recv.labels[0] != label {
		t.Fatalf("etiqueta distinta: %q", recv.labels[0])
	}
	t.Logf("OK: depósito ciego servido — ida y vuelta por etiqueta, sin remitente")
}

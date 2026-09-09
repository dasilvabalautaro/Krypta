package bridge

import (
	"fmt"
	"os"
	"testing"
	"time"
)

// TestMailboxPutAgainstLiveNode deposita un blob de prueba en el buzón de MBX_TO en un
// nodo REAL — sirve para disparar el wake del destinatario y medir la latencia de punta a
// punta (el receptor lo ignorará por remitente desconocido, pero su diagnóstico registra
// la retirada). Se auto-omite sin MBX_ADDR/MBX_TO.
func TestMailboxPutAgainstLiveNode(t *testing.T) {
	addr, to := os.Getenv("MBX_ADDR"), os.Getenv("MBX_TO")
	if addr == "" || to == "" {
		t.Skip("MBX_ADDR/MBX_TO no definidos; sonda solo bajo demanda")
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

	if err := n.MailboxPut(addr, to, "", []byte("sonda wake")); err != nil {
		t.Fatalf("put: %v", err)
	}
	t.Logf("OK: blob depositado para %s — el wake debería dispararlo en segundos", to)
}

// TestMailboxFetchAgainstLiveNode sondea el buzón de un nodo REAL (p. ej. el de infra en
// producción, vía wss/Cloudflare): crea una identidad efímera y retira su buzón (vacío).
// Si el nodo no expone /krypta/mbx/get/1.0.0 (binario viejo), falla con "protocols not
// supported". Se auto-omite sin MBX_ADDR:
//
//	MBX_ADDR="/dns4/krypta.neto.chat/tcp/443/wss/p2p/<PeerID>" go test -run TestMailboxFetchAgainstLiveNode -v .
func TestMailboxFetchAgainstLiveNode(t *testing.T) {
	addr := os.Getenv("MBX_ADDR")
	if addr == "" {
		t.Skip("MBX_ADDR no definido; sonda solo bajo demanda")
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

	got, err := n.MailboxFetch(addr, "")
	if err != nil {
		t.Fatalf("el nodo no atiende el buzón: %v", err)
	}
	t.Logf("OK: el nodo habla /krypta/mbx/get/1.0.0 (buzón de identidad efímera: %d sobres)", got)
}

// TestMailboxRoundTripAgainstLiveNode cierra el ciclo completo contra un nodo REAL, que es
// lo que las dos sondas de arriba NO comprueban: ellas solo verifican que el protocolo
// responde. Aquí dos identidades efímeras A y B hacen el viaje entero — A deposita, el nodo
// persiste, B retira — y se valida (a) que llega exactamente 1 sobre, (b) que el payload
// vuelve byte a byte, y (c) que el `from` del sobre es el PeerID real de A: ese campo lo
// fija el nodo desde la identidad del stream, no el emisor, así que comprobarlo verifica en
// vivo la propiedad anti-suplantación del buzón.
//
// El destinatario es efímero y su buzón se crea vacío en cada ejecución, así que la sonda no
// puede dar un falso positivo con restos de una ejecución anterior (el payload lleva marca
// de tiempo por si acaso). Al ack'ear, el nodo borra el sobre; queda el directorio vacío del
// destinatario, que el `sweep()` del nodo barre en su pasada horaria — ver directorios
// sueltos en el buzón tras correr la sonda es normal, no una fuga.
//
// Ojo si falla a mitad: un sobre depositado pero no ack'eado se queda en el nodo hasta el
// TTL de 7 días (es el diseño ack-after-persist, no un fallo). Como el buzón es de una
// identidad de usar y tirar, no molesta a nadie, pero explica que quede basura tras un fallo.
// Se auto-omite sin MBX_ADDR:
//
//	MBX_ADDR="/ip4/<IP>/tcp/4001/p2p/<PeerID>" go test -run TestMailboxRoundTripAgainstLiveNode -v .
func TestMailboxRoundTripAgainstLiveNode(t *testing.T) {
	addr := os.Getenv("MBX_ADDR")
	if addr == "" {
		t.Skip("MBX_ADDR no definido; sonda solo bajo demanda")
	}

	idA, err := GenerateIdentity()
	if err != nil {
		t.Fatalf("identidad A: %v", err)
	}
	nA, err := NewNodeWithIdentity(idA, "")
	if err != nil {
		t.Fatalf("nodo A: %v", err)
	}
	defer nA.Close()
	peerA, err := PeerIDForIdentity(idA)
	if err != nil {
		t.Fatalf("PeerID A: %v", err)
	}

	idB, err := GenerateIdentity()
	if err != nil {
		t.Fatalf("identidad B: %v", err)
	}
	nB, err := NewNodeWithIdentity(idB, "")
	if err != nil {
		t.Fatalf("nodo B: %v", err)
	}
	defer nB.Close()
	peerB, err := PeerIDForIdentity(idB)
	if err != nil {
		t.Fatalf("PeerID B: %v", err)
	}

	recv := &mbxRecv{}
	nB.SetMailboxHandler(recv)

	payload := fmt.Sprintf("sonda round-trip %d", time.Now().UnixNano())
	if err := nA.MailboxPut(addr, peerB, "", []byte(payload)); err != nil {
		t.Fatalf("A no pudo depositar para B: %v", err)
	}

	got, err := nB.MailboxFetch(addr, "")
	if err != nil {
		t.Fatalf("B no pudo retirar: %v", err)
	}
	if got != 1 {
		t.Fatalf("esperaba 1 sobre, llegaron %d", got)
	}
	if len(recv.got) != 1 || recv.got[0] != payload {
		t.Fatalf("payload alterado: %q, esperado %q", recv.got, payload)
	}
	if len(recv.from) != 1 || recv.from[0] != peerA {
		t.Fatalf("remitente incorrecto: %q, esperado %s", recv.from, peerA)
	}
	t.Logf("OK ciclo completo: A(%s) → nodo → B(%s), payload y remitente verificados",
		shortProbeID(peerA), shortProbeID(peerB))
}

// shortProbeID acorta un PeerID para que el log de la sonda sea legible.
func shortProbeID(id string) string {
	if len(id) <= 12 {
		return id
	}
	return id[:6] + "…" + id[len(id)-4:]
}

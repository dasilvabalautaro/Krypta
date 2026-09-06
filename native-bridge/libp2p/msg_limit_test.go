package bridge

import (
	"testing"
	"time"
)

// sizeHandler anota el tamaño de cada mensaje que el handler llega a entregar (no el
// contenido: aquí los mensajes son de 1 MiB).
type sizeHandler struct{ got chan int }

func (h *sizeHandler) OnMessage(from string, data []byte) { h.got <- len(data) }

// TestIncomingMessageIsBounded fija el tope de lectura del stream de mensajes entrante.
// Importa porque ese stream lo puede abrir **cualquier** peer que sepa marcarnos: quién
// envía no se comprueba en Go, sino después en Kotlin, así que antes de esto un extraño
// podía escribir sin fin y hacer que la app reservara esa memoria entera.
//
// Se comprueban las dos orillas del límite, que es lo único que distingue un tope correcto
// de uno que corta mensajes legítimos: justo en el tope se entrega **entero**, y un solo
// byte por encima no se entrega **nada** (el stream se corta; no llega troceado, que sería
// peor: pasaría por un mensaje ilegible en vez de por lo que es).
func TestIncomingMessageIsBounded(t *testing.T) {
	a, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode A: %v", err)
	}
	defer a.Close()
	h := &sizeHandler{got: make(chan int, 1)}
	a.SetMessageHandler(h)

	b, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode B: %v", err)
	}
	defer b.Close()
	// Conectar al bootstrap deja las direcciones de A en el peerstore de B, que es lo que
	// necesita SendMessage para marcar (sin pasar por el descubrimiento del DHT).
	if err := b.StartDHT(loopbackBootstrap(t, a), true); err != nil {
		t.Fatalf("B StartDHT: %v", err)
	}

	if err := b.SendMessage(a.PeerID(), make([]byte, maxIncomingMessage)); err != nil {
		t.Fatalf("SendMessage en el tope: %v", err)
	}
	select {
	case n := <-h.got:
		if n != maxIncomingMessage {
			t.Fatalf("entregados %d bytes, se esperaban %d", n, maxIncomingMessage)
		}
	case <-time.After(30 * time.Second):
		t.Fatal("un mensaje justo en el tope no se entregó")
	}

	// Un byte por encima: el emisor puede incluso no ver error (escribe en un buffer que ya
	// nadie lee), pero el receptor no debe entregar nada.
	_ = b.SendMessage(a.PeerID(), make([]byte, maxIncomingMessage+1))
	select {
	case n := <-h.got:
		t.Fatalf("se entregó un mensaje de %d bytes, por encima del tope de %d", n, maxIncomingMessage)
	case <-time.After(5 * time.Second):
	}
}

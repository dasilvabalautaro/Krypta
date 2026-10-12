package bridge

import (
	"io"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p/core/network"
)

// verdictHandler entrega lo recibido y contesta lo que diga store (O-7).
type verdictHandler struct {
	got   chan []byte
	store bool
}

func (h *verdictHandler) OnMessage(from string, data []byte) bool {
	h.got <- data
	return h.store
}

// pair crea A (receptor) y B (emisor), con B conociendo las direcciones de A.
func pair(t *testing.T) (a, b *Node) {
	t.Helper()
	a, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode A: %v", err)
	}
	t.Cleanup(func() { a.Close() })
	b, err = NewNode()
	if err != nil {
		t.Fatalf("NewNode B: %v", err)
	}
	t.Cleanup(func() { b.Close() })
	if err := b.StartDHT(loopbackBootstrap(t, a), true); err != nil {
		t.Fatalf("B StartDHT: %v", err)
	}
	return a, b
}

// TestEntregaDirectaConAcuse: con un receptor actual, SendMessage solo vuelve bien cuando el
// receptor dice que lo guardó (O-7 de docs/AUDITORIA-2026-10-12.md).
func TestEntregaDirectaConAcuse(t *testing.T) {
	a, b := pair(t)
	h := &verdictHandler{got: make(chan []byte, 1), store: true}
	a.SetMessageHandler(h)

	if err := b.SendMessage(a.PeerID(), []byte("hola")); err != nil {
		t.Fatalf("con acuse positivo SendMessage no debe fallar: %v", err)
	}
	select {
	case d := <-h.got:
		if string(d) != "hola" {
			t.Fatalf("entregado %q", d)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("no se entregó")
	}
}

// TestEntregaDirectaSinGuardarEsError: si el receptor no pudo guardarlo, el emisor lo sabe y
// puede caer al buzón. Antes de 1.1.0 esto se daba por enviado y se perdía.
func TestEntregaDirectaSinGuardarEsError(t *testing.T) {
	a, b := pair(t)
	h := &verdictHandler{got: make(chan []byte, 1), store: false}
	a.SetMessageHandler(h)

	if err := b.SendMessage(a.PeerID(), []byte("hola")); err == nil {
		t.Fatal("un receptor que no guardó no puede dar el mensaje por entregado")
	}
	select {
	case <-h.got:
	case <-time.After(10 * time.Second):
		t.Fatal("el receptor ni siquiera lo recibió: el error no prueba nada")
	}
}

// TestEntregaDirectaSinAcuseEsError: un receptor que lee y no contesta (proceso congelado,
// conexión que se cae) cuenta como no entregado al vencer el plazo.
func TestEntregaDirectaSinAcuseEsError(t *testing.T) {
	antes := msgAckTimeout
	msgAckTimeout = 2 * time.Second
	defer func() { msgAckTimeout = antes }()

	a, b := pair(t)
	leido := make(chan struct{}, 1)
	a.h.SetStreamHandler(ProtocolIDAck, func(s network.Stream) {
		_, _ = io.ReadAll(s)
		leido <- struct{}{}
		time.Sleep(5 * time.Second) // nunca contesta a tiempo
		_ = s.Reset()
	})

	inicio := time.Now()
	if err := b.SendMessage(a.PeerID(), []byte("hola")); err == nil {
		t.Fatal("sin acuse no puede darse por entregado")
	}
	if d := time.Since(inicio); d > 4*time.Second {
		t.Fatalf("la espera del acuse no respeta su plazo: %v", d)
	}
	<-leido
}

// TestEntregaDirectaAUnReceptorAnterior: con un receptor que solo habla 1.0.0, se negocia
// 1.0.0 y se entrega como siempre, sin esperar un acuse que nunca llegaría.
func TestEntregaDirectaAUnReceptorAnterior(t *testing.T) {
	a, b := pair(t)
	got := make(chan []byte, 1)
	a.h.SetStreamHandler(ProtocolID, func(s network.Stream) {
		defer s.Close()
		d, _ := io.ReadAll(s)
		got <- d
	})

	if err := b.SendMessage(a.PeerID(), []byte("hola")); err != nil {
		t.Fatalf("con un receptor 1.0.0 debe entregarse como antes: %v", err)
	}
	select {
	case d := <-got:
		if string(d) != "hola" {
			t.Fatalf("entregado %q", d)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("no se entregó por 1.0.0")
	}
}

// TestUnEmisorAnteriorSigueEntregando: un emisor que solo ofrece 1.0.0 (un cliente sin
// actualizar) llega al mismo manejador, que entrega sin contestar nada.
func TestUnEmisorAnteriorSigueEntregando(t *testing.T) {
	a, b := pair(t)
	h := &verdictHandler{got: make(chan []byte, 1), store: true}
	a.SetMessageHandler(h)

	ctx := network.WithAllowLimitedConn(b.ctx, "test")
	s, err := b.h.NewStream(ctx, a.h.ID(), ProtocolID)
	if err != nil {
		t.Fatalf("NewStream 1.0.0: %v", err)
	}
	_, _ = s.Write([]byte("viejo"))
	_ = s.CloseWrite()
	select {
	case d := <-h.got:
		if string(d) != "viejo" {
			t.Fatalf("entregado %q", d)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("un emisor 1.0.0 ya no entrega")
	}
	_ = s.Close()
}

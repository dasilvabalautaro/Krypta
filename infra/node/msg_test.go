package main

import (
	"bytes"
	"context"
	"io"
	"strings"
	"sync"
	"testing"
)

// lockedBuffer: el handler escribe desde la goroutine del stream y el test lee desde la suya.
type lockedBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *lockedBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *lockedBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

// sendMsg entrega un mensaje por /krypta/msg y espera a que el nodo cierre el stream, que es
// cuando el handler ya ha terminado (el Close va diferido tras la escritura al log).
func sendMsg(t *testing.T, debug bool) (logged string, sender string) {
	t.Helper()
	node, a := newTestHost(t), newTestHost(t)
	out := &lockedBuffer{}
	node.SetStreamHandler(kryptaProtocol, msgHandler(out, debug))
	connect(t, a, node)

	s, err := a.NewStream(context.Background(), node.ID(), kryptaProtocol)
	if err != nil {
		t.Fatalf("stream: %v", err)
	}
	if _, err := s.Write([]byte("ciphertext opaco")); err != nil {
		t.Fatalf("write: %v", err)
	}
	if err := s.CloseWrite(); err != nil {
		t.Fatalf("close write: %v", err)
	}
	_, _ = io.ReadAll(s)
	_ = s.Close()
	return out.String(), a.ID().String()
}

// TestMsgHandlerNoRegistraIdentificadores: un nodo público no escribe a su log quién le
// habla. Es lo que promete la política de privacidad, y lo que el nodo de São Paulo incumplía
// hasta el 10 sep 2026 (el log iba al journal persistente y a /var/log/syslog).
func TestMsgHandlerNoRegistraIdentificadores(t *testing.T) {
	logged, sender := sendMsg(t, false)
	if strings.Contains(logged, sender) {
		t.Fatalf("el log contiene el PeerID del remitente: %q", logged)
	}
	if logged != "" {
		t.Fatalf("sin -debugmsg el handler no debe escribir nada, escribió: %q", logged)
	}
}

// TestMsgHandlerDebugSiRegistra existe para que el test anterior no pase por accidente: si la
// captura del log no funcionara, ambos verían una cadena vacía y solo este fallaría.
func TestMsgHandlerDebugSiRegistra(t *testing.T) {
	logged, sender := sendMsg(t, true)
	if !strings.Contains(logged, sender) {
		t.Fatalf("con -debugmsg el log debería llevar el PeerID del remitente, fue: %q", logged)
	}
}

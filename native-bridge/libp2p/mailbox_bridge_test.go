package bridge

import (
	"bufio"
	"encoding/json"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
)

// testMailboxServer es un buzón mínimo EN MEMORIA que habla el mismo protocolo JSON que
// infra/node (mailbox.go). Vive en el test porque los módulos usan go-libp2p distintos
// (v0.48 aquí, v0.38 el nodo) y no pueden compartir código; el contrato es el protocolo.
type testMailboxServer struct {
	mu    sync.Mutex
	boxes map[string][]mbxEnvelope // key = PeerID destinatario
	seq   int
}

func startTestMailbox(t *testing.T) (peer.ID, string) {
	t.Helper()
	h, err := libp2p.New(libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"))
	if err != nil {
		t.Fatalf("mailbox host: %v", err)
	}
	t.Cleanup(func() { _ = h.Close() })
	srv := &testMailboxServer{boxes: map[string][]mbxEnvelope{}}

	h.SetStreamHandler(mbxPutProtocol, func(s network.Stream) {
		defer s.Close()
		line, _ := bufio.NewReader(s).ReadBytes('\n')
		var req struct {
			To   string `json:"to"`
			Blob string `json:"blob"`
		}
		if err := json.Unmarshal(line, &req); err != nil {
			fmt.Fprintln(s, `{"err":"json"}`)
			return
		}
		srv.mu.Lock()
		srv.seq++
		srv.boxes[req.To] = append(srv.boxes[req.To], mbxEnvelope{
			ID:   fmt.Sprintf("id-%03d", srv.seq),
			From: s.Conn().RemotePeer().String(),
			Ts:   time.Now().UnixMilli(),
			Blob: req.Blob,
		})
		srv.mu.Unlock()
		fmt.Fprintln(s, `{"ok":true}`)
	})

	h.SetStreamHandler(mbxGetProtocol, func(s network.Stream) {
		defer s.Close()
		to := s.Conn().RemotePeer().String()
		srv.mu.Lock()
		envs := append([]mbxEnvelope(nil), srv.boxes[to]...)
		srv.mu.Unlock()
		w := bufio.NewWriter(s)
		for _, env := range envs {
			out, _ := json.Marshal(env)
			fmt.Fprintf(w, "%s\n", out)
		}
		fmt.Fprintln(w, `{"done":true}`)
		_ = w.Flush()
		line, _ := bufio.NewReader(s).ReadBytes('\n')
		var ack struct {
			Ack []string `json:"ack"`
		}
		if json.Unmarshal(line, &ack) != nil {
			return
		}
		acked := map[string]bool{}
		for _, id := range ack.Ack {
			acked[id] = true
		}
		srv.mu.Lock()
		var rest []mbxEnvelope
		for _, env := range srv.boxes[to] {
			if !acked[env.ID] {
				rest = append(rest, env)
			}
		}
		srv.boxes[to] = rest
		srv.mu.Unlock()
	})

	return h.ID(), h.Addrs()[0].String() + "/p2p/" + h.ID().String()
}

// mbxRecv acumula lo que entrega MailboxFetch vía el handler. `reject` simula un fallo de
// persistencia en el lado Kotlin (→ false, sin ack); `panicOn` simula una excepción.
type mbxRecv struct {
	mu      sync.Mutex
	got     []string
	from    []string
	ids     []string
	reject  map[string]bool // por payload
	panicOn map[string]bool // por payload
}

func (r *mbxRecv) OnMailboxMessage(id, from, label string, ts int64, data []byte) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.panicOn[string(data)] {
		panic("kotlin exception simulada")
	}
	r.ids = append(r.ids, id)
	r.from = append(r.from, from)
	r.got = append(r.got, string(data))
	return !r.reject[string(data)]
}

// TestMailboxPutFetch valida el cliente del bridge de punta a punta contra un servidor
// que habla el protocolo del nodo: A deposita para B ("offline"), B hace fetch (recibe
// por el handler, con el from fijado por el servidor) y, tras el ack, un segundo fetch
// llega vacío.
func TestMailboxPutFetch(t *testing.T) {
	_, mbxAddr := startTestMailbox(t)

	idA, _ := GenerateIdentity()
	idB, _ := GenerateIdentity()
	a, err := NewNodeWithIdentity(idA, "")
	if err != nil {
		t.Fatalf("node A: %v", err)
	}
	defer a.Close()
	b, err := NewNodeWithIdentity(idB, "")
	if err != nil {
		t.Fatalf("node B: %v", err)
	}
	defer b.Close()

	// A deposita dos mensajes para B, que no está conectado a nada.
	if err := a.MailboxPut(mbxAddr, b.PeerID(), "", []byte("uno")); err != nil {
		t.Fatalf("put 1: %v", err)
	}
	if err := a.MailboxPut(mbxAddr, b.PeerID(), "", []byte("dos")); err != nil {
		t.Fatalf("put 2: %v", err)
	}

	recv := &mbxRecv{}
	b.SetMailboxHandler(recv)
	n, err := b.MailboxFetch(mbxAddr, "")
	if err != nil {
		t.Fatalf("fetch: %v", err)
	}
	if n != 2 || len(recv.got) != 2 || recv.got[0] != "uno" || recv.got[1] != "dos" {
		t.Fatalf("fetch entregó %d: %v", n, recv.got)
	}
	if recv.from[0] != a.PeerID() {
		t.Fatalf("from esperado %s, fue %s", a.PeerID(), recv.from[0])
	}
	if recv.ids[0] == "" || recv.ids[0] == recv.ids[1] {
		t.Fatalf("ids de sobre no únicos: %v", recv.ids)
	}

	// Tras el ack no debe reentregarse nada.
	if n, err := b.MailboxFetch(mbxAddr, ""); err != nil || n != 0 {
		t.Fatalf("segundo fetch: n=%d err=%v", n, err)
	}
}

// TestMailboxRedeliverUnacked: un sobre que el handler NO confirma (falló la persistencia
// en Kotlin, o lanzó) no se ack'ea, así que el nodo lo reentrega en el siguiente fetch;
// cuando el handler por fin lo confirma, deja de reentregarse. Es la garantía
// "ack-tras-persistir" que evita perder trozos de archivo si el proceso muere a mitad.
func TestMailboxRedeliverUnacked(t *testing.T) {
	_, mbxAddr := startTestMailbox(t)

	idA, _ := GenerateIdentity()
	idB, _ := GenerateIdentity()
	a, err := NewNodeWithIdentity(idA, "")
	if err != nil {
		t.Fatalf("node A: %v", err)
	}
	defer a.Close()
	b, err := NewNodeWithIdentity(idB, "")
	if err != nil {
		t.Fatalf("node B: %v", err)
	}
	defer b.Close()

	for _, payload := range []string{"ok", "falla-persistencia", "lanza"} {
		if err := a.MailboxPut(mbxAddr, b.PeerID(), "", []byte(payload)); err != nil {
			t.Fatalf("put %q: %v", payload, err)
		}
	}

	recv := &mbxRecv{
		reject:  map[string]bool{"falla-persistencia": true},
		panicOn: map[string]bool{"lanza": true},
	}
	b.SetMailboxHandler(recv)
	if n, err := b.MailboxFetch(mbxAddr, ""); err != nil || n != 1 {
		t.Fatalf("primer fetch: ack'd n=%d err=%v (esperado 1)", n, err)
	}

	// El handler "se recupera": ahora todo persiste → los dos pendientes se reentregan.
	recv.mu.Lock()
	recv.reject = nil
	recv.panicOn = nil
	recv.got = nil
	recv.mu.Unlock()
	if n, err := b.MailboxFetch(mbxAddr, ""); err != nil || n != 2 {
		t.Fatalf("reentrega: n=%d err=%v got=%v (esperado 2)", n, err, recv.got)
	}
	if len(recv.got) != 2 {
		t.Fatalf("reentregados %v", recv.got)
	}

	// Y tras confirmarse, el buzón queda vacío.
	if n, err := b.MailboxFetch(mbxAddr, ""); err != nil || n != 0 {
		t.Fatalf("fetch final: n=%d err=%v", n, err)
	}
}

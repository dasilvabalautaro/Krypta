package bridge

import (
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/network"
)

// wakeRecv cuenta los OnWake y expone una señal por cada uno.
type wakeRecv struct {
	mu    sync.Mutex
	count int
	fired chan struct{}
}

func newWakeRecv() *wakeRecv { return &wakeRecv{fired: make(chan struct{}, 8)} }

func (r *wakeRecv) OnWake() {
	r.mu.Lock()
	r.count++
	r.mu.Unlock()
	r.fired <- struct{}{}
}

func (r *wakeRecv) total() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.count
}

// TestWakeSubscribe valida el cliente de wake del bridge contra un servidor que habla el
// protocolo del nodo: al conectar dispara un OnWake (retirada de cobertura) y cada línea
// {"wake":true} del servidor dispara otro. StopWake corta la sesión.
func TestWakeSubscribe(t *testing.T) {
	srv, err := libp2p.New(libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"))
	if err != nil {
		t.Fatalf("wake server host: %v", err)
	}
	defer srv.Close()

	wakeNow := make(chan struct{}, 4)
	srv.SetStreamHandler(wakeProtocol, func(s network.Stream) {
		defer s.Close()
		for range wakeNow {
			if _, err := fmt.Fprintln(s, `{"wake":true}`); err != nil {
				return
			}
		}
	})
	addr := srv.Addrs()[0].String() + "/p2p/" + srv.ID().String()

	id, _ := GenerateIdentity()
	n, err := NewNodeWithIdentity(id, "")
	if err != nil {
		t.Fatalf("node: %v", err)
	}
	defer n.Close()

	recv := newWakeRecv()
	n.StartWake(addr, "", recv)
	n.StartWake(addr, "", recv) // idempotente: no abre una segunda sesión

	// 1. Al (re)conectar llega el OnWake de cobertura.
	select {
	case <-recv.fired:
	case <-time.After(10 * time.Second):
		t.Fatal("timeout esperando el OnWake de conexión")
	}

	// 2. Un aviso del servidor dispara otro OnWake.
	wakeNow <- struct{}{}
	select {
	case <-recv.fired:
	case <-time.After(5 * time.Second):
		t.Fatal("timeout esperando el OnWake del aviso")
	}
	if got := recv.total(); got != 2 {
		t.Fatalf("esperaba 2 OnWake, hubo %d", got)
	}

	// 3. StopWake corta: un aviso posterior ya no dispara nada.
	n.StopWake()
	time.Sleep(200 * time.Millisecond)
	wakeNow <- struct{}{}
	select {
	case <-recv.fired:
		t.Fatal("OnWake tras StopWake")
	case <-time.After(500 * time.Millisecond):
	}
	close(wakeNow)
}

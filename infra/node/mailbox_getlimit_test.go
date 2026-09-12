package main

import (
	"fmt"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p/core/host"
)

// --- Límite de ritmo de la retirada ----------------------------------------------------------
//
// Lo que se exige no es solo que corte: es que **cortar no pierda correo**. Una retirada
// limitada contesta vacía siguiendo el protocolo (fin de lista + ack), el correo se queda en el
// nodo y sale en cuanto vuelve a haber ficha. Los tests usan streams reales para que cualquier
// desvío del protocolo (cerrar sin leer el ack, contestar otra cosa) haga fallar a los helpers
// `mbxGet`/`mbxGetV2`, que abortan ante un error de lectura o un JSON inválido.

func nodoConRetiradaLimitada(t *testing.T) (*mailbox, *fakeClock, host.Host) {
	t.Helper()
	node := newTestHost(t)
	m := newMailbox(t.TempDir())
	clock := &fakeClock{t: time.Unix(1_700_000_000, 0)}
	m.now = clock.now
	m.attach(node)
	return m, clock, node
}

// TestRetiradaLimitadaNoPierdeCorreo: agotadas las fichas, la retirada llega vacía y limpia; el
// sobre sigue en el nodo y se entrega en cuanto se repone una ficha.
func TestRetiradaLimitadaNoPierdeCorreo(t *testing.T) {
	m, clock, node := nodoConRetiradaLimitada(t)
	m.getBurst = 2
	a, b := newTestHost(t), newTestHost(t)
	connect(t, a, node)
	connect(t, b, node)
	if err := mbxPut(t, a, node.ID(), b.ID().String(), []byte("para B")); err != nil {
		t.Fatalf("put: %v", err)
	}

	for i := 0; i < 2; i++ { // dos retiradas sin ack: gastan las dos fichas
		if got := mbxGet(t, b, node.ID(), []string{}); len(got) != 1 {
			t.Fatalf("retirada %d dentro del cupo: esperaba 1 sobre, hay %d", i, len(got))
		}
	}
	if got := mbxGet(t, b, node.ID(), nil); len(got) != 0 {
		t.Fatalf("sin fichas la retirada debía llegar vacía, trajo %d", len(got))
	}
	if n := countFor(t, m, b.ID().String()); n != 1 {
		t.Fatalf("la retirada limitada no puede tocar el buzón: quedan %d sobres", n)
	}

	clock.add(m.getRefill)
	if got := mbxGet(t, b, node.ID(), nil); len(got) != 1 {
		t.Fatalf("repuesta una ficha, el sobre debía entregarse: %d", len(got))
	}
	// El helper manda el ack y vuelve sin esperar a que el nodo lo procese: se da un momento.
	deadline := time.Now().Add(2 * time.Second)
	for countFor(t, m, b.ID().String()) != 0 {
		if time.Now().After(deadline) {
			t.Fatalf("tras el ack el buzón debía vaciarse: quedan %d", countFor(t, m, b.ID().String()))
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// TestRetiradaLimitadaEsPorPeer: que un peer agote su cupo no afecta ni a otro peer ni a sus
// propios depósitos (cubos distintos).
func TestRetiradaLimitadaEsPorPeer(t *testing.T) {
	m, _, node := nodoConRetiradaLimitada(t)
	m.getBurst = 1
	a, b, c := newTestHost(t), newTestHost(t), newTestHost(t)
	for _, h := range []host.Host{a, b, c} {
		connect(t, h, node)
	}
	if err := mbxPut(t, a, node.ID(), c.ID().String(), []byte("para C")); err != nil {
		t.Fatalf("put: %v", err)
	}

	mbxGet(t, b, node.ID(), nil)
	mbxGet(t, b, node.ID(), nil) // b ya está limitado
	if got := mbxGet(t, c, node.ID(), nil); len(got) != 1 {
		t.Fatalf("el límite de b no puede afectar a c: c recibió %d", len(got))
	}
	if err := mbxPut(t, b, node.ID(), a.ID().String(), []byte("de B")); err != nil {
		t.Fatalf("retirar sin fichas no puede impedir depositar: %v", err)
	}
}

// TestRetiradaTopeGlobal: las identidades libp2p son gratis, así que un cubo por peer no basta
// contra quien rote PeerIDs. Agotado el global, nadie retira hasta que se repone — y el correo
// de quien se quedó fuera sigue ahí.
func TestRetiradaTopeGlobal(t *testing.T) {
	m, clock, node := nodoConRetiradaLimitada(t)
	m.getGlobalBurst = 2
	m.getGlobalRefill = time.Hour
	a := newTestHost(t)
	connect(t, a, node)
	var peers []host.Host
	for i := 0; i < 3; i++ {
		h := newTestHost(t)
		connect(t, h, node)
		if err := mbxPut(t, a, node.ID(), h.ID().String(), []byte(fmt.Sprintf("para %d", i))); err != nil {
			t.Fatalf("put %d: %v", i, err)
		}
		peers = append(peers, h)
	}

	for i := 0; i < 2; i++ {
		if got := mbxGet(t, peers[i], node.ID(), nil); len(got) != 1 {
			t.Fatalf("peer %d dentro del tope global: %d sobres", i, len(got))
		}
	}
	if got := mbxGet(t, peers[2], node.ID(), nil); len(got) != 0 {
		t.Fatalf("agotado el tope global la retirada debía llegar vacía: %d", len(got))
	}
	if n := countFor(t, m, peers[2].ID().String()); n != 1 {
		t.Fatalf("el correo del peer que se quedó fuera debe seguir en el nodo: %d", n)
	}

	clock.add(time.Hour)
	if got := mbxGet(t, peers[2], node.ID(), nil); len(got) != 1 {
		t.Fatalf("repuesto el global, el sobre debía salir: %d", len(got))
	}
}

// TestRetiradaV2LimitadaContestaLimpio: la vía ciega comparte el cubo del peer (el cliente abre
// v1 y v2 en cada retirada) y, limitada, también contesta dentro del protocolo.
func TestRetiradaV2LimitadaContestaLimpio(t *testing.T) {
	m, _, node := nodoConRetiradaLimitada(t)
	m.getBurst = 1
	a, b := newTestHost(t), newTestHost(t)
	connect(t, a, node)
	connect(t, b, node)
	if err := mbxPutV2(t, a, node.ID(), etiqueta, []byte("a ciegas")); err != nil {
		t.Fatalf("put v2: %v", err)
	}

	if got := mbxGetV2(t, b, node.ID(), []string{etiqueta}, false); len(got) != 1 {
		t.Fatalf("primera retirada v2: %d", len(got))
	}
	if got := mbxGetV2(t, b, node.ID(), []string{etiqueta}, true); len(got) != 0 {
		t.Fatalf("sin fichas la retirada v2 debía llegar vacía: %d", len(got))
	}
	if n := countFor(t, m, etiqueta); n != 1 {
		t.Fatalf("la retirada v2 limitada no puede borrar nada: quedan %d", n)
	}
}

// TestRetiradaOlvidaPeersInactivos: el mapa de cubos de retirada tampoco puede crecer sin fin.
func TestRetiradaOlvidaPeersInactivos(t *testing.T) {
	m := newMailbox(t.TempDir())
	clock := &fakeClock{t: time.Unix(1_700_000_000, 0)}
	m.now = clock.now
	for i := 0; i < 50; i++ {
		m.allowGet(fmt.Sprintf("peer-%d", i))
	}
	if len(m.getBuckets) != 50 {
		t.Fatalf("esperaba 50 cubos, hay %d", len(m.getBuckets))
	}
	clock.add(time.Duration(m.getBurst)*m.getRefill + time.Second)
	pruneMap(m.getBuckets, m.getBurst, m.getRefill, clock.now())
	if len(m.getBuckets) != 0 {
		t.Fatalf("los cubos llenos debían olvidarse, quedan %d", len(m.getBuckets))
	}
}

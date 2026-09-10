package bridge

import (
	"fmt"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/libp2p/go-libp2p/core/control"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	multiaddr "github.com/multiformats/go-multiaddr"
)

// peerGater decide QUIÉN puede abrirnos una conexión. Las salidas nunca se filtran (hay que
// poder marcar a los nodos y a los contactos); las **entradas** solo pasan si el PeerID está en
// la lista, que la app rellena con sus contactos y los nodos de infraestructura.
//
// Por qué existe (10 sep 2026, verificado con `ip_leak_probe_test.go`): sin esto, cualquiera que
// conociera tu PeerID te marcaba por tu dirección de relay —que el propio nodo le entrega— y
// libp2p, ante una conexión ENTRANTE por relay, iniciaba el hole punching por su cuenta y le
// mandaba tus direcciones públicas. Tu IP, en 1,7 s, a un desconocido. El descarte de PeerIDs
// desconocidos de `ChatService.onReceived` no lo impedía porque está una capa por encima: cuando
// llega, identify y DCUtR ya han hablado. `InterceptSecured` corta antes de eso: en cuanto el
// handshake Noise revela el PeerID y antes de que exista conexión, así que no hay identify, no
// hay hole punching y no hay fuga.
//
// **Con la lista vacía se deja pasar todo.** Es deliberado: entre que arranca el host y que la
// app fija la lista hay una ventana, y es mejor una ventana corta que perder entregas si algo
// falla al construir la lista. La app la fija en cuanto arranca y en cada ciclo WAN.
type peerGater struct {
	mu      sync.RWMutex
	allowed map[peer.ID]struct{}

	// Contadores de entrantes vistas y rechazadas. Existen porque "el filtro no cortó" no
	// distingue entre tres cosas muy distintas: que no se consultara en ese camino, que se
	// consultara con la dirección equivocada, o que la lista estuviera vacía (modo abierto).
	// Con esto, un test o el diagnóstico lo dicen sin adivinar.
	inboundChecked atomic.Int64
	inboundDenied  atomic.Int64
}

func newPeerGater() *peerGater { return &peerGater{} }

// set reemplaza la lista entera. Una lista vacía vuelve al modo abierto.
func (g *peerGater) set(ids []peer.ID) {
	m := make(map[peer.ID]struct{}, len(ids))
	for _, id := range ids {
		if id != "" {
			m[id] = struct{}{}
		}
	}
	g.mu.Lock()
	g.allowed = m
	g.mu.Unlock()
}

// allows dice si una conexión entrante de p sería aceptada (y si el filtro está activo).
func (g *peerGater) allows(p peer.ID) bool {
	g.mu.RLock()
	defer g.mu.RUnlock()
	if len(g.allowed) == 0 {
		return true // modo abierto: la app aún no ha fijado lista
	}
	_, ok := g.allowed[p]
	return ok
}

func (g *peerGater) InterceptPeerDial(peer.ID) bool { return true }

func (g *peerGater) InterceptAddrDial(peer.ID, multiaddr.Multiaddr) bool { return true }

// InterceptAccept no puede filtrar: en ese momento solo se conoce la dirección, no el PeerID.
func (g *peerGater) InterceptAccept(network.ConnMultiaddrs) bool { return true }

// InterceptSecured es el punto útil: el handshake ya reveló el PeerID y aún no hay conexión.
func (g *peerGater) InterceptSecured(dir network.Direction, p peer.ID, _ network.ConnMultiaddrs) bool {
	if dir == network.DirOutbound {
		return true
	}
	g.inboundChecked.Add(1)
	if g.allows(p) {
		return true
	}
	g.inboundDenied.Add(1)
	return false
}

func (g *peerGater) InterceptUpgraded(network.Conn) (bool, control.DisconnectReason) {
	return true, 0
}

// SetAllowedPeers fija quién puede abrirnos conexión: los PeerID de los contactos y de los
// nodos, separados por saltos de línea o comas. Una cadena vacía deja el filtro **abierto**
// (comportamiento anterior). La app la llama al arrancar y en cada ciclo WAN, así que también
// recoge los contactos nuevos, los borrados y los **bloqueados** (que se quedan fuera y así
// tampoco pueden sacar la IP).
func (n *Node) SetAllowedPeers(peers string) {
	if n.gater == nil {
		return
	}
	var ids []peer.ID
	for _, f := range strings.FieldsFunc(peers, func(r rune) bool { return r == '\n' || r == ',' || r == ' ' }) {
		if id, err := peer.Decode(strings.TrimSpace(f)); err == nil {
			ids = append(ids, id)
		}
	}
	n.gater.set(ids)
}

// GaterStats resume el estado del filtro para el diagnóstico y los tests:
// cuántos PeerID tiene la lista, cuántas conexiones entrantes ha mirado y cuántas ha
// rechazado. `permitidos=0` significa **filtro abierto**, que es lo que hay que poder ver.
func (n *Node) GaterStats() string {
	if n.gater == nil {
		return "sin filtro"
	}
	return fmt.Sprintf("permitidos=%d entrantes=%d rechazadas=%d",
		n.AllowedPeerCount(), n.gater.inboundChecked.Load(), n.gater.inboundDenied.Load())
}

// AllowedPeerCount expone cuántos PeerID tiene la lista (0 = filtro abierto). Para diagnóstico.
func (n *Node) AllowedPeerCount() int {
	if n.gater == nil {
		return 0
	}
	n.gater.mu.RLock()
	defer n.gater.mu.RUnlock()
	return len(n.gater.allowed)
}

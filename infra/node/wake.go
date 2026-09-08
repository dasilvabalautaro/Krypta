// Wake integrado (Fase 5, decisión simplificada respecto al plan: sin servidor
// UnifiedPush separado): como el buzón vive en este mismo nodo, el nodo ya sabe el
// instante exacto en que llega un depósito para un PeerID. El móvil mantiene un stream
// ligero `/krypta/wake/1.0.0` (lo sostiene su Foreground Service) y el nodo le escribe
// un aviso cuando hay correo — el móvil retira el buzón al segundo, sin polling.
//
// Protocolo (JSON por líneas, solo nodo→móvil):
//
//	{"ping":true}  keepalive cada ~50 s (Cloudflare Free corta la wss a ~100 s de idle)
//	{"wake":true}  hay un depósito nuevo para ti → retira el buzón
//
// Autenticación gratis por libp2p: el registro es el PeerID remoto del stream; nadie
// puede suscribirse al wake de otro. El aviso no lleva payload (ni remitente): el
// contenido viaja solo por el buzón E2EE.
package main

import (
	"fmt"
	"sync"
	"time"

	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/protocol"
)

const wakeProtocol = protocol.ID("/krypta/wake/1.0.0")

// wakeKeepalive debe quedar por debajo del corte por inactividad de Cloudflare (~100 s).
const wakeKeepalive = 50 * time.Second

// maxWakeSubs acota cuántas suscripciones de aviso mantiene el nodo a la vez. Cualquier peer
// de internet puede abrir este stream y cada uno cuesta un stream abierto más dos goroutines
// (el temporizador de keepalive y el lector que detecta el cierre); sin tope, agotarlo era
// gratis. El número va muy por encima de la base de usuarios prevista, así que un usuario
// legítimo no lo verá nunca; si alguna vez se acercara, es señal de que toca repartir la
// carga en más nodos.
const maxWakeSubs = 2000

type wakeRegistry struct {
	mu   sync.Mutex
	subs map[string]*wakeConn // PeerID → conexión de aviso activa
}

type wakeConn struct {
	s  network.Stream
	mu sync.Mutex // serializa escrituras (keepalive y avisos concurrentes)
}

func (c *wakeConn) writeLine(line string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	_, err := fmt.Fprintln(c.s, line)
	return err
}

func newWakeRegistry() *wakeRegistry {
	return &wakeRegistry{subs: map[string]*wakeConn{}}
}

// attach registra el handler del protocolo wake en el host.
func (w *wakeRegistry) attach(h host.Host) {
	h.SetStreamHandler(wakeProtocol, w.handle)
}

func (w *wakeRegistry) handle(s network.Stream) {
	peerID := s.Conn().RemotePeer().String()
	conn := &wakeConn{s: s}

	w.mu.Lock()
	old, replacing := w.subs[peerID]
	// Tope de suscripciones: se aplica solo a peers NUEVOS, para que quien ya estaba suscrito
	// pueda reconectar aunque el nodo esté al límite.
	if !replacing && len(w.subs) >= maxWakeSubs {
		w.mu.Unlock()
		_ = s.Reset()
		return
	}
	if replacing {
		_ = old.s.Reset() // una suscripción por peer: la nueva sustituye a la vieja
	}
	w.subs[peerID] = conn
	w.mu.Unlock()

	defer func() {
		w.mu.Lock()
		if w.subs[peerID] == conn {
			delete(w.subs, peerID)
		}
		w.mu.Unlock()
		_ = s.Close()
	}()

	// Detecta el cierre del cliente: no envía nada, así que el Read desbloquea al morir
	// el stream (o al ser sustituido por una suscripción nueva).
	done := make(chan struct{})
	go func() {
		buf := make([]byte, 16)
		for {
			if _, err := s.Read(buf); err != nil {
				close(done)
				return
			}
		}
	}()

	ticker := time.NewTicker(wakeKeepalive)
	defer ticker.Stop()
	for {
		select {
		case <-done:
			return
		case <-ticker.C:
			if err := conn.writeLine(`{"ping":true}`); err != nil {
				return
			}
		}
	}
}

// wake avisa al peer (si está suscrito) de que tiene correo en el buzón. Best-effort:
// si no hay suscripción o la escritura falla, el mensaje espera en el buzón igualmente
// (el móvil también retira al reconectar y en su bucle WAN).
func (w *wakeRegistry) wake(peerID string) {
	w.mu.Lock()
	conn := w.subs[peerID]
	w.mu.Unlock()
	if conn != nil {
		_ = conn.writeLine(`{"wake":true}`)
	}
}

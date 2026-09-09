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
// v1: el registro es el PeerID remoto del stream; nadie puede suscribirse al wake de otro,
// porque libp2p ya autentica quién abre la conexión.
//
// v2 (`/krypta/wake/2.0.0`, depósito ciego): el nodo ya no sabe de quién es cada buzón, así
// que el cliente empieza mandando **sus etiquetas** —`{"v":2,"labels":[…]}`— y se le avisa
// cuando cae algo en cualquiera de ellas. Se apoya en lo mismo que el GET v2: la etiqueta es
// una credencial al portador que solo la pareja puede derivar.
//
// En los dos casos el aviso no lleva payload ni remitente: el contenido viaja solo por el
// buzón E2EE.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"sync"
	"time"

	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/protocol"
)

const (
	wakeProtocol   = protocol.ID("/krypta/wake/1.0.0")
	wakeProtocolV2 = protocol.ID("/krypta/wake/2.0.0")
)

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
	mu    sync.Mutex
	subs  map[string]*wakeConn // clave (PeerID en v1, etiqueta en v2) → conexión activa
	conns int                  // conexiones vivas; el tope se aplica aquí, no por clave
}

type wakeConn struct {
	s    network.Stream
	mu   sync.Mutex // serializa escrituras (keepalive y avisos concurrentes)
	keys []string   // por qué claves está suscrita, para poder soltarlas todas al cerrar
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

// attach registra los handlers del protocolo wake en el host (v1 y v2).
func (w *wakeRegistry) attach(h host.Host) {
	h.SetStreamHandler(wakeProtocol, w.handle)
	h.SetStreamHandler(wakeProtocolV2, w.handleV2)
}

// handle (v1): la clave de suscripción es el PeerID del propio stream.
func (w *wakeRegistry) handle(s network.Stream) {
	w.serve(s, []string{s.Conn().RemotePeer().String()})
}

// handleV2: el cliente manda primero sus etiquetas y el nodo le avisa de cualquiera de ellas.
func (w *wakeRegistry) handleV2(s network.Stream) {
	line, err := readLine(io.LimitReader(s, 128<<10))
	if err != nil {
		_ = s.Reset()
		return
	}
	var req struct {
		V      int      `json:"v"`
		Labels []string `json:"labels"`
	}
	if err := json.Unmarshal(line, &req); err != nil {
		_ = s.Reset()
		return
	}
	keys := make([]string, 0, len(req.Labels))
	for _, l := range req.Labels {
		if labelPattern.MatchString(l) {
			keys = append(keys, l)
		}
		if len(keys) >= maxLabelsPerRequest {
			break
		}
	}
	// Además de las etiquetas, se suscribe SIEMPRE al PeerID del propio stream. Sin esto la
	// transición rompía el aviso: mientras el depósito ciego no esté encendido, todo el correo
	// entra por v1 —o sea, dirigido al PeerID— y un cliente suscrito solo a etiquetas no se
	// enteraría de nada, cayendo del push instantáneo al sondeo de minutos. El nodo conoce ese
	// PeerID de todas formas: es quien abre la conexión.
	keys = append(keys, s.Conn().RemotePeer().String())
	w.serve(s, keys)
}

// serve mantiene la suscripción: registra las claves, manda keepalives y las suelta al cerrar.
func (w *wakeRegistry) serve(s network.Stream, keys []string) {
	conn := &wakeConn{s: s, keys: keys}

	w.mu.Lock()
	// El tope cuenta CONEXIONES, no claves: en v2 una sola conexión trae muchas etiquetas y
	// contar claves castigaría a quien tiene muchos contactos.
	if w.conns >= maxWakeSubs {
		w.mu.Unlock()
		_ = s.Reset()
		return
	}
	w.conns++
	for _, k := range keys {
		if old, ok := w.subs[k]; ok && old != conn {
			_ = old.s.Reset() // una suscripción por clave: la nueva sustituye a la vieja
		}
		w.subs[k] = conn
	}
	w.mu.Unlock()

	defer func() {
		w.mu.Lock()
		for _, k := range conn.keys {
			if w.subs[k] == conn {
				delete(w.subs, k)
			}
		}
		w.conns--
		w.mu.Unlock()
		_ = s.Close()
	}()

	// Detecta el cierre del cliente: no envía nada más, así que el Read desbloquea al morir
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

// wake avisa de que hay correo bajo una clave —el PeerID del destinatario en v1, la etiqueta
// en v2— si alguien está suscrito a ella. Best-effort:
// si no hay suscripción o la escritura falla, el mensaje espera en el buzón igualmente
// (el móvil también retira al reconectar y en su bucle WAN).
func (w *wakeRegistry) wake(key string) {
	w.mu.Lock()
	conn := w.subs[key]
	w.mu.Unlock()
	if conn != nil {
		_ = conn.writeLine(`{"wake":true}`)
	}
}

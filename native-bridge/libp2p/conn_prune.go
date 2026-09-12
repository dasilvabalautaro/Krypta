package bridge

import (
	"strings"
	"sync/atomic"
	"time"

	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	multiaddr "github.com/multiformats/go-multiaddr"
)

// Poda de conexiones WebSocket redundantes.
//
// El ranker (dial_ranker.go) marca la vía wss/443 un segundo por detrás de la directa, pero
// eso solo decide el **orden**. En el TECNO, el 12 sep 2026, se vio dos veces (con la app en
// marcha y en un arranque en frío; en otro arranque, no) una conexión por tcp/4001 **y otra
// por wss/443** con el mismo VPS a la vez. Un solo dial de
// libp2p no deja dos (el worker cancela los que quedan en vuelo al completar uno), así que
// vienen de episodios solapados: `StartDHT` vuelve con el primer nodo y el ciclo de la app ya
// hace `Connect` al otro para relay, buzón y wake. Por la conexión de Caddy el nodo nos ve
// llegar desde 127.0.0.1 y sus límites por IP dejan de contar.
//
// La regla no depende de cuál fue la carrera: **si con un peer hay una conexión directa, las
// WebSocket sobran y se cierran**. Se aplica en cada conexión nueva, en una goroutine (cerrar
// desde el Notifiee bloquearía al swarm). Las conexiones por relay (`/p2p-circuit`) quedan
// fuera en los dos lados de la regla.
//
// Excepción: una WebSocket que lleva **tráfico que no se puede cortar** no se cierra todavía.
// Por la conexión con un nodo viajan los circuitos de relay hacia los contactos (streams
// hop/stop) y, dentro de ellos, llamadas, vídeo y mensajes: cerrarla mataría una llamada en
// curso. Se vuelve a mirar cada `pruneRecheck` hasta que quede libre (o deje de sobrar).

// pruneRecheck es cada cuánto se reintenta cerrar una WebSocket que estaba ocupada. Variable
// para que los tests no esperen medio minuto.
var pruneRecheck = 30 * time.Second

// pruneMaxRechecks acota los reintentos: una conexión con un circuito vivo durante horas no
// merece una goroutine despierta para siempre; la siguiente conexión nueva vuelve a mirarla.
const pruneMaxRechecks = 20

// busyProtocols son los prefijos de protocolo que hacen que una conexión no se pueda cortar.
var busyProtocols = []string{
	"/libp2p/circuit/relay/", // hop y stop: circuitos de relay hacia/desde contactos
	"/krypta/call/",
	"/krypta/video/",
}

func connBusy(c network.Conn) bool {
	for _, s := range c.GetStreams() {
		p := string(s.Protocol())
		for _, prefix := range busyProtocols {
			if strings.HasPrefix(p, prefix) {
				return true
			}
		}
	}
	return false
}

// closeRedundantWebsocketConns cierra las conexiones WebSocket con `p` si hay al menos una
// directa abierta. Devuelve cuántas cerró y si quedó alguna redundante sin cerrar por ocupada.
func closeRedundantWebsocketConns(nw network.Network, p peer.ID) (closed int, busyLeft bool) {
	conns := nw.ConnsToPeer(p)
	hasDirect := false
	for _, c := range conns {
		a := c.RemoteMultiaddr()
		if !isCircuitAddr(a) && !isWebsocketAddr(a) && !c.IsClosed() {
			hasDirect = true
			break
		}
	}
	if !hasDirect {
		return 0, false
	}
	for _, c := range conns {
		a := c.RemoteMultiaddr()
		if isCircuitAddr(a) || !isWebsocketAddr(a) || c.IsClosed() {
			continue
		}
		if connBusy(c) {
			busyLeft = true
			continue
		}
		if err := c.Close(); err == nil {
			closed++
		}
	}
	return closed, busyLeft
}

// installWebsocketPruner aplica la poda en cada conexión nueva; `pruned` acumula cuántas se
// han cerrado, para el diagnóstico de la app.
func installWebsocketPruner(h host.Host, pruned *atomic.Int64) {
	h.Network().Notify(&network.NotifyBundle{
		ConnectedF: func(nw network.Network, c network.Conn) {
			p := c.RemotePeer()
			go func() {
				for i := 0; ; i++ {
					n, busy := closeRedundantWebsocketConns(nw, p)
					pruned.Add(int64(n))
					if !busy || i >= pruneMaxRechecks {
						return
					}
					time.Sleep(pruneRecheck)
				}
			}()
		},
	})
}

func isCircuitAddr(a multiaddr.Multiaddr) bool {
	_, err := a.ValueForProtocol(multiaddr.P_CIRCUIT)
	return err == nil
}

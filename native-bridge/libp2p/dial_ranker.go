package bridge

import (
	"time"

	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/p2p/net/swarm"
	multiaddr "github.com/multiformats/go-multiaddr"
)

// wsDialDelay es cuánto va por detrás la vía WebSocket de la última dirección directa
// (TCP/QUIC) al marcar un mismo peer. El mismo valor que libp2p usa para "otros transportes".
const wsDialDelay = 1 * time.Second

// directFirstDialRanker es el orden de marcado estándar de libp2p con una corrección: las
// direcciones WebSocket (`ws`/`wss`) se marcan **después** de las directas, no a la vez.
//
// Por qué hace falta (10 sep 2026): cada nodo de DEFAULT_BOOTSTRAP va dos veces, por
// `tcp/4001` directo y por `wss/443` a través de Caddy — el 443 es la salida para redes que
// solo dejan ese puerto. El ranker estándar trata una dirección `wss` como TCP (lleva /tcp/)
// y, dentro de TCP, **marca primero el puerto más bajo**: 443 antes que 4001, con la directa
// 250 ms por detrás. Resultado: los móviles habrían preferido el camino por Caddy, y el nodo
// vería a todos esos usuarios llegar desde 127.0.0.1 — compartiendo un único cupo de
// reservas de relay por IP y sin que los límites por IP protegieran nada.
//
// Con esto la vía `wss` solo entra si la directa no ha conectado en ~1 s, que es justo el caso
// de una red que filtra el 4001. Si un peer solo tiene direcciones WebSocket, se marcan al
// instante como antes.
//
// Esto decide el **orden**, no garantiza que quede una sola conexión: si la directa tarda más
// de ese segundo, la wss también se marca, y con dials solapados de varios llamadores pueden
// quedar las dos abiertas (visto en el TECNO el 12 sep 2026). De eso se ocupa conn_prune.go:
// con una directa abierta, la WebSocket se cierra.
//
// Los dos grupos se ordenan **por separado**. Retrasar solo las WebSocket sobre el resultado
// del ranker estándar no basta: ahí la TCP ya viene 250 ms por detrás del `wss` (mismo grupo,
// puerto más alto), y seguiría esperando sin motivo — el test lo cazó en la primera versión.
func directFirstDialRanker(addrs []multiaddr.Multiaddr) []network.AddrDelay {
	var direct, ws []multiaddr.Multiaddr
	for _, a := range addrs {
		if isWebsocketAddr(a) {
			ws = append(ws, a)
		} else {
			direct = append(direct, a)
		}
	}
	if len(direct) == 0 || len(ws) == 0 {
		return swarm.DefaultDialRanker(addrs)
	}
	ranked := swarm.DefaultDialRanker(direct)
	var lastDirect time.Duration
	for _, r := range ranked {
		if r.Delay > lastDirect {
			lastDirect = r.Delay
		}
	}
	for _, r := range swarm.DefaultDialRanker(ws) {
		r.Delay += lastDirect + wsDialDelay
		ranked = append(ranked, r)
	}
	return ranked
}

// isWebsocketAddr reconoce las dos formas: `/tcp/443/wss` y la resuelta `/tcp/443/tls/…/ws`.
func isWebsocketAddr(a multiaddr.Multiaddr) bool {
	if _, err := a.ValueForProtocol(multiaddr.P_WSS); err == nil {
		return true
	}
	_, err := a.ValueForProtocol(multiaddr.P_WS)
	return err == nil
}

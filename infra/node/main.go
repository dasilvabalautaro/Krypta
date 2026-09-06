// Command node is Krypta's minimal self-hosted infrastructure node (Phase 1 seed):
// a libp2p bootstrap + Kademlia DHT *server*. Later phases add Circuit Relay v2, the
// E2EE store-and-forward mailbox and the wake server to this same binary/fleet.
//
// It also doubles as the discovery counterpart for on-device tests: pass -rendezvous to
// make it advertise + look up a rendezvous key so a phone can find it (and vice versa).
//
// Run:
//   cd infra/node && go mod tidy
//   go run . -listen /ip4/0.0.0.0/tcp/4001 -rendezvous <hex>
package main

import (
	"context"
	"crypto/rand"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	dht "github.com/libp2p/go-libp2p-kad-dht"
	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/protocol"
	drouting "github.com/libp2p/go-libp2p/p2p/discovery/routing"
	dutil "github.com/libp2p/go-libp2p/p2p/discovery/util"
	relayv2 "github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/relay"
)

const kryptaProtocol = protocol.ID("/krypta/msg/1.0.0")

// maxInboundMessage acota lo que este nodo lee de un stream de mensajes entrante. El nodo es
// público: cualquier peer de internet puede abrirlo. 1 MiB va muy por encima de lo legítimo
// (el propio buzón rechaza blobs de más de 64 KiB).
const maxInboundMessage = 1 << 20

func main() {
	listen := flag.String("listen", "/ip4/0.0.0.0/tcp/4001", "listen multiaddr")
	keyPath := flag.String("key", "node.key", "path to persist the Ed25519 identity (stable PeerID)")
	rendezvous := flag.String("rendezvous", "", "if set, advertise + find this rendezvous key")
	wsPortFlag := flag.String("wsport", "8081", "local WebSocket port (Cloudflare Tunnel exposes it as wss/443)")
	// Puerto QUIC. 0 = efímero, que es lo correcto detrás de Cloudflare Tunnel (el túnel no
	// lleva UDP, así que QUIC no se usa y no merece la pena ocupar un puerto fijo). En un VPS
	// con IP pública SÍ hay que fijarlo (p. ej. 4001): el multiaddr QUIC del bootstrap tiene
	// que ser estable entre reinicios para poder publicarlo en DEFAULT_BOOTSTRAP.
	quicPortFlag := flag.String("quicport", "0", "UDP port for QUIC (0 = ephemeral; set it on a public-IP node)")
	mailboxDir := flag.String("mailboxdir", "", "E2EE store-and-forward mailbox dir (default: <key dir>/mailbox)")
	flag.Parse()
	wsPort := *wsPortFlag
	quicPort := *quicPortFlag

	ctx := context.Background()

	priv, err := loadOrCreateKey(*keyPath)
	if err != nil {
		log.Fatalf("identity: %v", err)
	}

	h, err := libp2p.New(
		libp2p.Identity(priv),
		libp2p.ListenAddrStrings(
			*listen, // TCP crudo (LAN / IP pública directa)
			"/ip4/0.0.0.0/udp/"+quicPort+"/quic-v1", // QUIC (no cruza Cloudflare; fíjalo en VPS)
			"/ip4/0.0.0.0/tcp/"+wsPort+"/ws", // WebSocket: lo expone Cloudflare Tunnel como wss/443
		),
		// Circuit Relay v2: este nodo reenvía tráfico (E2EE) cuando DCUtR no perfora el NAT.
		// Sin WithInfiniteLimits, go-libp2p corta cada conexión relayada a los 128 KiB o
		// 2 min (por defecto) — una llamada de voz (~5-6 KB/s) moría a los ~20 s. Este nodo
		// ES el relay de Krypta y el tráfico va E2EE, así que sin límites.
		libp2p.EnableRelayService(relayv2.WithInfiniteLimits()),
		// El servicio de relay v2 solo ofrece el protocolo `hop` cuando el nodo se cree
		// PÚBLICAMENTE alcanzable. Tras Cloudflare Tunnel (sin IP pública directa) AutoNAT no
		// lo confirma y desactivaría el relay → los móviles no podrían reservar slot. Como este
		// nodo ES el relay público de Krypta, forzamos reachability=public para que el hop
		// quede siempre activo.
		libp2p.ForceReachabilityPublic(),
	)
	if err != nil {
		log.Fatalf("libp2p.New: %v", err)
	}
	defer h.Close()

	// Print any inbound Krypta message (lets on-device tests verify the phone can dial
	// this node and deliver a message over a libp2p stream). Bounded read: see below.
	h.SetStreamHandler(kryptaProtocol, func(s network.Stream) {
		defer s.Close()
		// Acotado por lo mismo que en el puente del móvil: este nodo es público y cualquiera
		// puede abrirle este stream, así que sin tope un extraño le hace reservar memoria sin
		// fin. Aquí el mensaje solo se imprime, pero el nodo es infraestructura compartida.
		data, _ := io.ReadAll(io.LimitReader(s, maxInboundMessage))
		// Print as hex: payloads are E2EE, so this node sees only opaque ciphertext.
		fmt.Printf("message from %s: %d bytes (ciphertext, hex): %x\n",
			s.Conn().RemotePeer(), len(data), data)
	})

	// Buzón E2EE store-and-forward (ver mailbox.go). Default junto al node.key para que
	// funcione igual bajo launchd (que no fija WorkingDirectory) que a mano.
	mbxDir := *mailboxDir
	if mbxDir == "" {
		mbxDir = filepath.Join(filepath.Dir(*keyPath), "mailbox")
	}
	if err := os.MkdirAll(mbxDir, 0o700); err != nil {
		log.Fatalf("mailbox dir: %v", err)
	}
	mbx := newMailbox(mbxDir)
	mbx.attach(h)
	go func() {
		for {
			mbx.sweep()
			time.Sleep(time.Hour)
		}
	}()

	// Wake integrado: un depósito en el buzón avisa al instante al destinatario suscrito.
	wake := newWakeRegistry()
	wake.attach(h)
	mbx.notify = wake.wake

	kad, err := dht.New(ctx, h, dht.Mode(dht.ModeServer))
	if err != nil {
		log.Fatalf("dht.New: %v", err)
	}
	if err := kad.Bootstrap(ctx); err != nil {
		log.Fatalf("dht.Bootstrap: %v", err)
	}

	fmt.Println("Krypta infra node up (bootstrap + DHT server + relay v2). PeerID:", h.ID().String())
	fmt.Println("Bootstrap addrs (use one of these from the phone):")
	for _, a := range h.Addrs() {
		fmt.Printf("  %s/p2p/%s\n", a, h.ID().String())
	}

	if *rendezvous != "" {
		disc := drouting.NewRoutingDiscovery(kad)
		dutil.Advertise(ctx, disc, *rendezvous)
		go func() {
			for {
				time.Sleep(5 * time.Second)
				c, cancel := context.WithTimeout(ctx, 5*time.Second)
				peers, err := disc.FindPeers(c, *rendezvous)
				if err != nil {
					cancel()
					continue
				}
				for p := range peers {
					if p.ID != h.ID() && p.ID != "" {
						fmt.Println("discovered under rendezvous:", p.ID.String())
					}
				}
				cancel()
			}
		}()
	}

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig
	fmt.Println("\nshutting down")
}

func loadOrCreateKey(path string) (crypto.PrivKey, error) {
	if data, err := os.ReadFile(path); err == nil {
		return crypto.UnmarshalPrivateKey(data)
	}
	priv, _, err := crypto.GenerateEd25519Key(rand.Reader)
	if err != nil {
		return nil, err
	}
	data, err := crypto.MarshalPrivateKey(priv)
	if err != nil {
		return nil, err
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		return nil, err
	}
	return priv, nil
}

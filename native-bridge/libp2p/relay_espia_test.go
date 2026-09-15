package bridge

import (
	"bytes"
	"context"
	cryptorand "crypto/rand"
	"encoding/hex"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/crypto"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/protocol"
	"github.com/libp2p/go-libp2p/core/sec"
	tptu "github.com/libp2p/go-libp2p/p2p/net/upgrader"
	circuitproto "github.com/libp2p/go-libp2p/p2p/protocol/circuitv2/proto"
	libp2ptls "github.com/libp2p/go-libp2p/p2p/security/tls"
	multiaddr "github.com/multiformats/go-multiaddr"
)

// registroEspia acumula lo que el relay ha descifrado de sus propias conexiones.
type registroEspia struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (r *registroEspia) anotar(b []byte) {
	r.mu.Lock()
	r.buf.Write(b)
	r.mu.Unlock()
}

func (r *registroEspia) contiene(b []byte) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return bytes.Contains(r.buf.Bytes(), b)
}

// transporteEspia es el TLS del relay con una copia de todo lo que lee ya descifrado: exactamente
// lo que ve el operador de un nodo, que termina el TLS de cada teléfono con él pero no el de
// teléfono a teléfono.
type transporteEspia struct {
	sec.SecureTransport
	visto *registroEspia
}

func (t *transporteEspia) SecureInbound(ctx context.Context, c net.Conn, p peer.ID) (sec.SecureConn, error) {
	sc, err := t.SecureTransport.SecureInbound(ctx, c, p)
	if err != nil {
		return nil, err
	}
	return connEspia{sc, t.visto}, nil
}

func (t *transporteEspia) SecureOutbound(ctx context.Context, c net.Conn, p peer.ID) (sec.SecureConn, error) {
	sc, err := t.SecureTransport.SecureOutbound(ctx, c, p)
	if err != nil {
		return nil, err
	}
	return connEspia{sc, t.visto}, nil
}

type connEspia struct {
	sec.SecureConn
	visto *registroEspia
}

func (c connEspia) Read(b []byte) (int, error) {
	n, err := c.SecureConn.Read(b)
	c.visto.anotar(b[:n])
	return n, err
}

// TestRelayNoVeLoQueViajaPorElCircuito responde a la parte de W-8 que quedaba por verificar: que en
// una conexión relayed la seguridad de transporte va de teléfono a teléfono y no se termina en el
// relay. Es lo que impide que el relay repita, reordene o refleje frames de una llamada, porque
// solo ve registros TLS o Noise que no puede abrir ni fabricar.
//
// No basta con mirar ConnState(): en una conexión relayed go-libp2p deja vacío el campo de
// seguridad (ver exigirCifradoDeExtremoAExtremo). Así que se mira desde el otro lado, desde el
// relay, con un TLS espía que guarda todo lo que el relay descifra. Los controles son que el espía
// ve el protocolo del propio relay y un marcador que solo va bajo el TLS del relay; lo que se prueba
// es que no ve ni el frame de la llamada ni una negociación de protocolo hecha dentro del circuito.
//
// Lo que sí ve, y el primer intento de este test lo confundió con una fuga del circuito: el
// **nombre** `/krypta/call/1.0.0`. No viaja por el circuito sino por identify, con el que cada
// teléfono le dice al relay qué protocolos admite. Por eso la sonda del circuito es un protocolo con
// un nonce que no figura en esa lista, y el test fija además que el nombre de la llamada ya lo
// conoce el relay **antes** de abrir ningún stream.
func TestRelayNoVeLoQueViajaPorElCircuito(t *testing.T) {
	visto := &registroEspia{}
	relay, err := libp2p.New(
		libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"),
		libp2p.Security(libp2ptls.ID, func(id protocol.ID, key crypto.PrivKey, muxers []tptu.StreamMuxer) (*transporteEspia, error) {
			inner, err := libp2ptls.New(id, key, muxers)
			if err != nil {
				return nil, err
			}
			return &transporteEspia{SecureTransport: inner, visto: visto}, nil
		}),
		libp2p.EnableRelayService(),
		libp2p.ForceReachabilityPublic(),
	)
	if err != nil {
		t.Fatalf("relay: %v", err)
	}
	defer relay.Close()
	relayAddr := relay.Addrs()[0].String() + "/p2p/" + relay.ID().String()

	idA, _ := GenerateIdentity()
	idB, _ := GenerateIdentity()
	a, err := NewNodeWithIdentity(idA, relayAddr)
	if err != nil {
		t.Fatalf("node A: %v", err)
	}
	defer a.Close()
	b, err := NewNodeWithIdentity(idB, relayAddr)
	if err != nil {
		t.Fatalf("node B: %v", err)
	}
	defer b.Close()

	g := &grabadorLlamada{frames: make(chan []byte, 4), fin: make(chan error, 4)}
	b.SetCallHandler(g)

	if r := a.ReserveRelay(relayAddr); !strings.HasPrefix(r, "OK") {
		t.Fatalf("A reserve: %s", r)
	}
	if r := b.ReserveRelay(relayAddr); !strings.HasPrefix(r, "OK") {
		t.Fatalf("B reserve: %s", r)
	}

	circuit, _ := multiaddr.NewMultiaddr(relayAddr + "/p2p-circuit")
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := a.h.Connect(ctx, peer.AddrInfo{ID: b.h.ID(), Addrs: []multiaddr.Multiaddr{circuit}}); err != nil {
		t.Fatalf("A connect B via relay: %v", err)
	}

	// Metadato conocido: los nombres de protocolo se los cuenta identify al relay, sin circuito.
	limite := time.Now().Add(5 * time.Second)
	for !visto.contiene([]byte(CallProtocolID)) {
		if time.Now().After(limite) {
			t.Fatal("el relay no conoce el nombre del protocolo de llamada por identify")
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Logf("antes de abrir ningún stream, el relay ya conoce %q: se lo dice identify", CallProtocolID)

	s, err := a.OpenCallStream(b.PeerID())
	if err != nil {
		t.Fatalf("OpenCallStream: %v", err)
	}
	defer s.Close()
	// Si hubiera subido a directa, el frame no pasaría por el relay y el test no probaría nada.
	if !strings.Contains(s.s.Conn().RemoteMultiaddr().String(), "/p2p-circuit") {
		t.Fatalf("el stream de llamada no va por el relay: %s", s.s.Conn().RemoteMultiaddr())
	}

	marcador := []byte("MARCADOR-DE-UN-FRAME-DE-LLAMADA-W8")
	if err := s.WriteFrame(marcador); err != nil {
		t.Fatalf("WriteFrame: %v", err)
	}
	select {
	case f := <-g.frames:
		if !bytes.Equal(f, marcador) {
			t.Fatalf("B recibió otra cosa: %q", f)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("B no recibió el frame por el relay")
	}
	exigirCifradoDeExtremoAExtremo(t, a, b, true)

	// Control de sensibilidad: lo que viaja solo bajo el TLS de A con el relay, y no por el
	// circuito, el espía tiene que verlo byte a byte. Si no lo viera, que no vea el frame de la
	// llamada no probaría nada.
	const protoControl = protocol.ID("/krypta/test-espia/1.0.0")
	recibidoEnRelay := make(chan struct{}, 1)
	relay.SetStreamHandler(protoControl, func(st network.Stream) {
		_, _ = io.ReadAll(st)
		recibidoEnRelay <- struct{}{}
	})
	control := []byte("CONTROL-QUE-SOLO-VA-BAJO-EL-TLS-DEL-RELAY")
	cs, err := a.h.NewStream(ctx, relay.ID(), protoControl)
	if err != nil {
		t.Fatalf("stream de control al relay: %v", err)
	}
	_, _ = cs.Write(control)
	_ = cs.CloseWrite()
	select {
	case <-recibidoEnRelay:
	case <-time.After(10 * time.Second):
		t.Fatal("el relay no recibió el control")
	}
	if !visto.contiene(control) {
		t.Fatal("el espía no ve ni lo que va solo bajo el TLS del relay: el test no prueba nada")
	}

	if !visto.contiene([]byte(circuitproto.ProtoIDv2Hop)) {
		t.Fatal("el espía no ve ni el protocolo del relay: no está leyendo lo que descifra el relay")
	}

	// Sonda del circuito: B acepta cualquier protocolo con este prefijo, así que identify solo
	// anuncia el prefijo. El nombre completo, con el nonce, solo existe en la negociación que A
	// hace con B dentro del circuito: si el relay lo ve, el circuito va en claro.
	const prefijoSonda = "/krypta/sonda-circuito"
	nonce := make([]byte, 16)
	if _, err := cryptorand.Read(nonce); err != nil {
		t.Fatalf("nonce: %v", err)
	}
	sonda := protocol.ID(prefijoSonda + "/" + hex.EncodeToString(nonce))
	recibidoEnB := make(chan struct{}, 1)
	b.h.SetStreamHandlerMatch(prefijoSonda, func(p protocol.ID) bool {
		return strings.HasPrefix(string(p), prefijoSonda+"/")
	}, func(st network.Stream) {
		_, _ = io.ReadAll(st)
		recibidoEnB <- struct{}{}
	})
	ss, err := a.h.NewStream(network.WithAllowLimitedConn(ctx, "sonda"), b.h.ID(), sonda)
	if err != nil {
		t.Fatalf("stream de sonda: %v", err)
	}
	if !strings.Contains(ss.Conn().RemoteMultiaddr().String(), "/p2p-circuit") {
		t.Fatalf("la sonda no va por el relay: %s", ss.Conn().RemoteMultiaddr())
	}
	_, _ = ss.Write([]byte("sonda"))
	_ = ss.CloseWrite()
	select {
	case <-recibidoEnB:
	case <-time.After(10 * time.Second):
		t.Fatal("B no recibió la sonda por el relay")
	}

	for _, enClaro := range [][]byte{marcador, []byte(sonda)} {
		if visto.contiene(enClaro) {
			t.Fatalf("el relay ve %q en claro: el circuito no va cifrado de extremo a extremo", enClaro)
		}
	}
	t.Log("el relay ve su propio protocolo y el control, pero ni el frame ni la negociación del circuito")
}

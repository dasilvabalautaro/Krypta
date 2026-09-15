package bridge

import (
	"bytes"
	"context"
	"fmt"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p/core/peer"
	libp2pnoise "github.com/libp2p/go-libp2p/p2p/security/noise"
	libp2ptls "github.com/libp2p/go-libp2p/p2p/security/tls"
	multiaddr "github.com/multiformats/go-multiaddr"
)

// Estos tests responden a W-8 de docs/ESPECIFICACION-protocolo.md. Los frames de una llamada van
// cifrados con AES-GCM **sin contador**, así que la capa de aplicación no distingue un frame
// repetido, reordenado o devuelto al emisor. Lo que impide que la red lo haga es la seguridad de
// transporte de libp2p (TLS 1.3 o Noise), que numera sus registros y usa una clave distinta en cada
// sentido. Aquí se comprueba esa afirmación en vez de darla por hecha:
//
//   - TestTransporteRechazaBytesManipulados: un intermediario que duplica, reordena o refleja bytes
//     ya cifrados no consigue que se entregue un frame dos veces, fuera de orden o de vuelta a quien
//     lo mandó. La conexión se cae.
//   - TestRelayMessagingLocal (relay_msg_test.go) comprueba que la conexión a través de un relay va
//     cifrada y autenticada entre los dos extremos, y no termina en el relay.
//
// Montado el circuito, un relay es un tubo de bytes entre los dos extremos: go-libp2p cifra la
// conexión relayed con el mismo upgrader que una directa (p2p/protocol/circuitv2/client/transport.go,
// al marcar y al aceptar). Por eso un proxy TCP que manipula ese tubo es el mismo ataque, sin tener
// que reescribir un relay.

type modoManipulacion int

const (
	sinTocar modoManipulacion = iota
	duplicar
	reordenar
	reflejar
)

// proxyManipulador copia bytes entre A (que marca) y B. Mientras no esté armado no toca nada; una
// vez armado aplica su modo a la primera lectura de A hacia B (reordenar: a las dos primeras) y
// vuelve a copiar tal cual, para que un fallo solo pueda deberse a esa manipulación.
type proxyManipulador struct {
	ln      net.Listener
	destino string
	modo    modoManipulacion
	armado  atomic.Bool
	hechas  atomic.Int32
}

func nuevoProxyManipulador(t *testing.T, destino string, modo modoManipulacion) *proxyManipulador {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("proxy: %v", err)
	}
	p := &proxyManipulador{ln: ln, destino: destino, modo: modo}
	t.Cleanup(func() { _ = ln.Close() })
	go p.aceptar()
	return p
}

func (p *proxyManipulador) multiaddr(t *testing.T) multiaddr.Multiaddr {
	t.Helper()
	m, err := multiaddr.NewMultiaddr(fmt.Sprintf("/ip4/127.0.0.1/tcp/%d", p.ln.Addr().(*net.TCPAddr).Port))
	if err != nil {
		t.Fatalf("multiaddr del proxy: %v", err)
	}
	return m
}

func (p *proxyManipulador) aceptar() {
	for {
		a, err := p.ln.Accept()
		if err != nil {
			return
		}
		b, err := net.Dial("tcp", p.destino)
		if err != nil {
			_ = a.Close()
			continue
		}
		var haciaA sync.Mutex // B→A y el reflejo escriben los dos en A
		cerrar := func() { _ = a.Close(); _ = b.Close() }

		go func() { // B → A, siempre tal cual
			defer cerrar()
			buf := make([]byte, 64*1024)
			for {
				n, err := b.Read(buf)
				if n > 0 {
					haciaA.Lock()
					_, werr := a.Write(buf[:n])
					haciaA.Unlock()
					if werr != nil {
						return
					}
				}
				if err != nil {
					return
				}
			}
		}()

		go func() { // A → B, donde se manipula
			defer cerrar()
			buf := make([]byte, 64*1024)
			var retenido []byte
			tocadas := 0
			for {
				n, err := a.Read(buf)
				if n > 0 {
					trozo := append([]byte(nil), buf[:n]...)
					var werr error
					switch {
					case !p.armado.Load() || p.modo == sinTocar:
						_, werr = b.Write(trozo)
					case p.modo == duplicar && tocadas == 0:
						if _, werr = b.Write(trozo); werr == nil {
							_, werr = b.Write(trozo)
						}
						tocadas++
						p.hechas.Add(1)
					case p.modo == reordenar && tocadas == 0:
						retenido = trozo // se entrega detrás del siguiente
						tocadas++
					case p.modo == reordenar && tocadas == 1:
						if _, werr = b.Write(trozo); werr == nil {
							_, werr = b.Write(retenido)
						}
						tocadas++
						p.hechas.Add(1)
					case p.modo == reflejar && tocadas == 0:
						haciaA.Lock()
						_, werr = a.Write(trozo) // A recibe lo que él mismo cifró
						haciaA.Unlock()
						if werr == nil {
							_, werr = b.Write(trozo)
						}
						tocadas++
						p.hechas.Add(1)
					default:
						_, werr = b.Write(trozo)
					}
					if werr != nil {
						return
					}
				}
				if err != nil {
					return
				}
			}
		}()
	}
}

// grabadorLlamada guarda lo que llega por los streams de llamada entrantes, sin eco.
type grabadorLlamada struct {
	frames chan []byte
	fin    chan error
}

func (g *grabadorLlamada) OnCallStream(s *CallStream) {
	go func() {
		for {
			f, err := s.ReadFrame()
			if err != nil {
				g.fin <- err
				return
			}
			g.frames <- f
		}
	}()
}

// destinoTCPLoopback devuelve "127.0.0.1:<puerto>" de la escucha TCP de n.
func destinoTCPLoopback(t *testing.T, n *Node) string {
	t.Helper()
	linea := strings.Split(loopbackBootstrap(t, n), "\n")[0]
	m, err := multiaddr.NewMultiaddr(linea)
	if err != nil {
		t.Fatalf("addr de %s: %v", linea, err)
	}
	puerto, err := m.ValueForProtocol(multiaddr.P_TCP)
	if err != nil {
		t.Fatalf("puerto tcp de %s: %v", linea, err)
	}
	return "127.0.0.1:" + puerto
}

// exigirCifradoDeExtremoAExtremo falla si alguna conexión de desde→hacia del tipo pedido (relayed
// o directa) autentica a otro que no sea hacia, o si una directa no va con TLS o Noise. Lo primero
// es lo que dice que un relay no termina la sesión: si lo hiciera, la clave remota sería la suya.
// Espera hasta 5 s a que aparezca la conexión (el lado que acepta la registra un poco después).
//
// En una conexión relayed no se puede mirar el protocolo de seguridad: go-libp2p la cifra (el
// cliente del circuito pasa por upgrader.Upgrade) pero luego la envuelve en un capableConn cuyo
// ConnState() solo devuelve el transporte y deja Security vacío (circuitv2/client/conn.go). Que va
// cifrada de verdad lo demuestra TestRelayNoVeLoQueViajaPorElCircuito, mirando lo que ve el relay.
func exigirCifradoDeExtremoAExtremo(t *testing.T, desde, hacia *Node, relayed bool) {
	t.Helper()
	limite := time.Now().Add(5 * time.Second)
	for {
		vistas := 0
		for _, c := range desde.h.Network().ConnsToPeer(hacia.h.ID()) {
			if strings.Contains(c.RemoteMultiaddr().String(), "/p2p-circuit") != relayed {
				continue
			}
			vistas++
			seguridad := "relayed: go-libp2p no la informa"
			if !relayed {
				sec := c.ConnState().Security
				if sec != libp2ptls.ID && sec != libp2pnoise.ID {
					t.Fatalf("conexión %s sin TLS ni Noise: seguridad = %q", c.RemoteMultiaddr(), sec)
				}
				seguridad = string(sec)
			}
			remoto, err := peer.IDFromPublicKey(c.RemotePublicKey())
			if err != nil || remoto != hacia.h.ID() {
				t.Fatalf("la sesión de %s autentica a %s, no al extremo %s", c.RemoteMultiaddr(), remoto, hacia.h.ID())
			}
			t.Logf("conexión %s (%s), autenticada con la clave del extremo", c.RemoteMultiaddr(), seguridad)
		}
		if vistas > 0 {
			return
		}
		if time.Now().After(limite) {
			t.Fatalf("sin conexión (relayed=%v) de %s hacia %s", relayed, desde.h.ID(), hacia.h.ID())
		}
		time.Sleep(50 * time.Millisecond)
	}
}

func TestTransporteRechazaBytesManipulados(t *testing.T) {
	casos := []struct {
		nombre string
		modo   modoManipulacion
	}{
		// El control demuestra que el proxy entrega bien sin tocar nada: si fallara, los otros
		// casos no probarían nada.
		{"control sin tocar", sinTocar},
		{"duplicar", duplicar},
		{"reordenar", reordenar},
		{"reflejar al emisor", reflejar},
	}
	for _, c := range casos {
		t.Run(c.nombre, func(t *testing.T) { probarManipulacion(t, c.modo) })
	}
}

func probarManipulacion(t *testing.T, modo modoManipulacion) {
	a, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode A: %v", err)
	}
	defer a.Close()
	b, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode B: %v", err)
	}
	defer b.Close()

	g := &grabadorLlamada{frames: make(chan []byte, 16), fin: make(chan error, 4)}
	b.SetCallHandler(g)

	proxy := nuevoProxyManipulador(t, destinoTCPLoopback(t, b), modo)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if err := a.h.Connect(ctx, peer.AddrInfo{ID: b.h.ID(), Addrs: []multiaddr.Multiaddr{proxy.multiaddr(t)}}); err != nil {
		t.Fatalf("A no conecta con B a través del proxy: %v", err)
	}
	exigirCifradoDeExtremoAExtremo(t, a, b, false)

	s, err := a.OpenCallStream(b.PeerID())
	if err != nil {
		t.Fatalf("OpenCallStream: %v", err)
	}
	defer s.Close()

	// Un primer frame sin tocar nada: deja hechos el handshake, el muxer y la negociación del
	// protocolo, para que lo que se manipule después sean frames de la llamada.
	hello := []byte("HELLO: antes de tocar nada")
	if err := s.WriteFrame(hello); err != nil {
		t.Fatalf("WriteFrame hello: %v", err)
	}
	select {
	case f := <-g.frames:
		if !bytes.Equal(f, hello) {
			t.Fatalf("hello distinto: %q", f)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("B no recibió el hello")
	}

	// A lee en segundo plano: al reflejar, es aquí donde aparecería su propio frame.
	leidoEnA := make(chan []byte, 8)
	finA := make(chan error, 1)
	go func() {
		for {
			f, err := s.ReadFrame()
			if err != nil {
				finA <- err
				return
			}
			leidoEnA <- f
		}
	}()

	proxy.armado.Store(true)
	f1 := bytes.Repeat([]byte{0xA1}, 300)
	f2 := bytes.Repeat([]byte{0xB2}, 300)
	_ = s.WriteFrame(f1)
	time.Sleep(200 * time.Millisecond) // dos escrituras separadas, para poder reordenarlas
	_ = s.WriteFrame(f2)

	if modo == sinTocar {
		for i, quiero := range [][]byte{f1, f2} {
			select {
			case f := <-g.frames:
				if !bytes.Equal(f, quiero) {
					t.Fatalf("control: frame %d distinto", i+1)
				}
			case <-time.After(10 * time.Second):
				t.Fatalf("control: B no recibió el frame %d; el proxy no sirve de referencia", i+1)
			}
		}
		return
	}

	// Con manipulación, la conexión tiene que caerse en los dos extremos: quien detecta el
	// registro inválido la cierra y el otro ve el cierre.
	for _, lado := range []struct {
		nombre string
		fin    chan error
	}{{"B", g.fin}, {"A", finA}} {
		select {
		case err := <-lado.fin:
			t.Logf("%s: el stream terminó con %v", lado.nombre, err)
		case <-time.After(15 * time.Second):
			t.Fatalf("la conexión sigue viva en %s tras manipular los bytes", lado.nombre)
		}
	}
	if proxy.hechas.Load() != 1 {
		t.Fatalf("el proxy manipuló %d veces, esperaba 1: el test no ha probado nada", proxy.hechas.Load())
	}

	// B solo puede haber recibido un prefijo, en orden y sin repetir, de lo que A envió.
	var recibidos [][]byte
	for len(g.frames) > 0 {
		recibidos = append(recibidos, <-g.frames)
	}
	enviados := [][]byte{f1, f2}
	if len(recibidos) > len(enviados) {
		t.Fatalf("B recibió %d frames y A solo envió %d: hubo repetición", len(recibidos), len(enviados))
	}
	for i, f := range recibidos {
		if !bytes.Equal(f, enviados[i]) {
			t.Fatalf("B recibió como frame %d otra cosa (fuera de orden o repetido)", i+1)
		}
	}
	for len(leidoEnA) > 0 {
		if f := <-leidoEnA; bytes.Equal(f, f1) || bytes.Equal(f, f2) {
			t.Fatal("A recibió su propio frame de vuelta")
		}
	}
	t.Logf("B recibió %d de 2 frames, en orden y sin repetir", len(recibidos))
}

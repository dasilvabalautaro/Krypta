package bridge

import (
	"bufio"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p"
	"github.com/libp2p/go-libp2p/core/network"
)

const etiquetaA = "aa11bb22cc33dd44ee55ff6600778899aabbccddeeff00112233445566778899"

// blindMailboxServer habla el protocolo v2 (depósito ciego) como lo hará infra/node: guarda
// por etiqueta y NO registra quién deposita. `putErr`, si está puesto, hace que el nodo
// rechace los depósitos — sirve para comprobar que un error de verdad no degrada a v1.
type blindMailboxServer struct {
	mu      sync.Mutex
	boxes   map[string][]mbxEnvelope // clave = etiqueta
	senders map[string]bool          // quién ha depositado, para comprobar que no se guarda
	seq     int
	putErr  string
}

func startBlindMailbox(t *testing.T, putErr string) (*blindMailboxServer, string) {
	t.Helper()
	h, err := libp2p.New(libp2p.ListenAddrStrings("/ip4/127.0.0.1/tcp/0"))
	if err != nil {
		t.Fatalf("mailbox host: %v", err)
	}
	t.Cleanup(func() { _ = h.Close() })
	srv := &blindMailboxServer{boxes: map[string][]mbxEnvelope{}, senders: map[string]bool{}, putErr: putErr}

	h.SetStreamHandler(mbxPutProtocolV2, func(s network.Stream) {
		defer s.Close()
		line, _ := bufio.NewReader(s).ReadBytes('\n')
		var req struct {
			V     int    `json:"v"`
			Label string `json:"label"`
			Blob  string `json:"blob"`
		}
		if err := json.Unmarshal(line, &req); err != nil {
			fmt.Fprintln(s, `{"err":"json"}`)
			return
		}
		srv.mu.Lock()
		srv.senders[s.Conn().RemotePeer().String()] = true
		if srv.putErr != "" {
			srv.mu.Unlock()
			out, _ := json.Marshal(map[string]string{"err": srv.putErr})
			fmt.Fprintf(s, "%s\n", out)
			return
		}
		srv.seq++
		// Lo que se guarda: ni `to` ni `from`. Solo etiqueta, id, hora y blob.
		srv.boxes[req.Label] = append(srv.boxes[req.Label], mbxEnvelope{
			ID:   fmt.Sprintf("id-%03d", srv.seq),
			Ts:   time.Now().UnixMilli(),
			Blob: req.Blob,
		})
		srv.mu.Unlock()
		fmt.Fprintln(s, `{"ok":true}`)
	})

	h.SetStreamHandler(mbxGetProtocolV2, func(s network.Stream) {
		defer s.Close()
		r := bufio.NewReader(s)
		line, _ := r.ReadBytes('\n')
		var req struct {
			Labels []string `json:"labels"`
		}
		if json.Unmarshal(line, &req) != nil {
			return
		}
		w := bufio.NewWriter(s)
		srv.mu.Lock()
		for _, label := range req.Labels {
			for _, env := range srv.boxes[label] {
				env.Label = label
				out, _ := json.Marshal(env)
				fmt.Fprintf(w, "%s\n", out)
			}
		}
		srv.mu.Unlock()
		fmt.Fprintln(w, `{"done":true}`)
		_ = w.Flush()

		line, _ = r.ReadBytes('\n')
		var ack struct {
			Ack map[string][]string `json:"ack"`
		}
		if json.Unmarshal(line, &ack) != nil {
			return
		}
		srv.mu.Lock()
		for label, ids := range ack.Ack {
			acked := map[string]bool{}
			for _, id := range ids {
				acked[id] = true
			}
			var rest []mbxEnvelope
			for _, env := range srv.boxes[label] {
				if !acked[env.ID] {
					rest = append(rest, env)
				}
			}
			srv.boxes[label] = rest
		}
		srv.mu.Unlock()
	})

	return srv, h.Addrs()[0].String() + "/p2p/" + h.ID().String()
}

type blindRecv struct {
	mu     sync.Mutex
	got    []string
	labels []string
	froms  []string
}

func (r *blindRecv) OnMailboxMessage(id, from, label string, ts int64, data []byte) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.got = append(r.got, string(data))
	r.labels = append(r.labels, label)
	r.froms = append(r.froms, from)
	return true
}

func newBridgeNode(t *testing.T) *Node {
	t.Helper()
	id, err := GenerateIdentity()
	if err != nil {
		t.Fatalf("identity: %v", err)
	}
	n, err := NewNodeWithIdentity(id, "")
	if err != nil {
		t.Fatalf("node: %v", err)
	}
	t.Cleanup(func() { _ = n.Close() })
	return n
}

// TestBuzonCiegoIdaYVuelta: A deposita bajo una etiqueta y B lo retira pidiendo esa etiqueta.
// El nodo no ve ni de quién es ni para quién va.
func TestBuzonCiegoIdaYVuelta(t *testing.T) {
	srv, addr := startBlindMailbox(t, "")
	a, b := newBridgeNode(t), newBridgeNode(t)

	if err := a.MailboxPut(addr, b.PeerID(), etiquetaA, []byte("hola a ciegas")); err != nil {
		t.Fatalf("put: %v", err)
	}

	// Lo guardado no menciona a nadie.
	srv.mu.Lock()
	env := srv.boxes[etiquetaA][0]
	srv.mu.Unlock()
	if env.From != "" {
		t.Fatalf("el nodo no debe guardar remitente, guardó %q", env.From)
	}

	recv := &blindRecv{}
	b.SetMailboxHandler(recv)
	n, err := b.MailboxFetch(addr, etiquetaA)
	if err != nil {
		t.Fatalf("fetch: %v", err)
	}
	if n != 1 || len(recv.got) != 1 || recv.got[0] != "hola a ciegas" {
		t.Fatalf("retirada inesperada: n=%d got=%v", n, recv.got)
	}
	// El cliente resuelve el contacto por la ETIQUETA, no por el remitente (que ya no viene).
	if recv.labels[0] != etiquetaA || recv.froms[0] != "" {
		t.Fatalf("esperaba etiqueta y sin remitente: label=%q from=%q", recv.labels[0], recv.froms[0])
	}

	// Tras el ack, el buzón queda vacío.
	if n, err := b.MailboxFetch(addr, etiquetaA); err != nil || n != 0 {
		t.Fatalf("tras el ack debía quedar vacío: n=%d err=%v", n, err)
	}
}

// TestBuzonCiegoCaeAV1SiElNodoNoLoEntiende: mientras no estén todos los nodos desplegados, un
// nodo antiguo tiene que seguir recibiendo el mensaje por el camino de siempre.
func TestBuzonCiegoCaeAV1SiElNodoNoLoEntiende(t *testing.T) {
	_, addr := startTestMailbox(t) // solo habla v1
	a, b := newBridgeNode(t), newBridgeNode(t)

	if err := a.MailboxPut(addr, b.PeerID(), etiquetaA, []byte("por el camino viejo")); err != nil {
		t.Fatalf("debía caer a v1: %v", err)
	}
	recv := &blindRecv{}
	b.SetMailboxHandler(recv)
	n, err := b.MailboxFetch(addr, etiquetaA)
	if err != nil || n != 1 {
		t.Fatalf("fetch v1: n=%d err=%v", n, err)
	}
	if recv.froms[0] != a.PeerID() || recv.labels[0] != "" {
		t.Fatalf("en v1 debe venir el remitente y no etiqueta: from=%q label=%q", recv.froms[0], recv.labels[0])
	}
}

// TestBuzonCiegoNoDegradaAnteUnErrorDeVerdad es la propiedad que protege la privacidad: si el
// nodo SÍ habla v2 y rechaza el depósito (cuota, ritmo…), no se puede reintentar por v1, porque
// eso publicaría el destinatario y el remitente justo cuando el nodo sabía hacerlo bien.
func TestBuzonCiegoNoDegradaAnteUnErrorDeVerdad(t *testing.T) {
	srv, addr := startBlindMailbox(t, "buzón lleno para esa etiqueta")
	a, b := newBridgeNode(t), newBridgeNode(t)

	err := a.MailboxPut(addr, b.PeerID(), etiquetaA, []byte("no debería colarse"))
	if err == nil {
		t.Fatal("el depósito debía fallar, no degradar a v1")
	}
	if !strings.Contains(err.Error(), "lleno") {
		t.Fatalf("debía propagarse el error del nodo, no otro: %v", err)
	}
	srv.mu.Lock()
	defer srv.mu.Unlock()
	if len(srv.boxes) != 0 {
		t.Fatalf("no debía guardarse nada: %+v", srv.boxes)
	}
}

// TestBuzonCiegoRetiraPorLasDosViasEnLaTransicion: durante el cambio, un contacto ya
// actualizado deposita a ciegas y otro sin actualizar deposita por v1. Hay que recoger ambos.
func TestBuzonCiegoRetiraPorLasDosViasEnLaTransicion(t *testing.T) {
	// Dos nodos: uno ya desplegado (habla v2) y otro sin actualizar (solo v1). Es exactamente
	// la foto de la transición, con la lista multi-nodo mezclando ambos.
	_, viejoAddr := startTestMailbox(t)
	_, ciegoAddr := startBlindMailbox(t, "")

	nuevo, antiguo, b := newBridgeNode(t), newBridgeNode(t), newBridgeNode(t)

	if err := nuevo.MailboxPut(ciegoAddr, b.PeerID(), etiquetaA, []byte("del actualizado")); err != nil {
		t.Fatalf("put ciego: %v", err)
	}
	if err := antiguo.MailboxPut(viejoAddr, b.PeerID(), "", []byte("del que no actualizó")); err != nil {
		t.Fatalf("put v1: %v", err)
	}

	recv := &blindRecv{}
	b.SetMailboxHandler(recv)
	n, err := b.MailboxFetch(ciegoAddr+"\n"+viejoAddr, etiquetaA)
	if err != nil {
		t.Fatalf("fetch: %v", err)
	}
	if n != 2 {
		t.Fatalf("debía recoger los dos mensajes, recogió %d (%v)", n, recv.got)
	}
}

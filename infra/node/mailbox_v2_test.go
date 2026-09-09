package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/peer"
)

const etiqueta = "aa11bb22cc33dd44ee55ff6600778899aabbccddeeff00112233445566778899"

// mbxPutV2 deposita bajo una etiqueta, como hará el móvil con el depósito ciego.
func mbxPutV2(t *testing.T, from host.Host, node peer.ID, label string, blob []byte) error {
	t.Helper()
	s, err := from.NewStream(context.Background(), node, mbxPutProtocolV2)
	if err != nil {
		t.Fatalf("put v2 stream: %v", err)
	}
	defer s.Close()
	req, _ := json.Marshal(mbxPutReqV2{V: 2, Label: label, Blob: base64.StdEncoding.EncodeToString(blob)})
	fmt.Fprintf(s, "%s\n", req)
	_ = s.CloseWrite()
	line, err := bufio.NewReader(s).ReadString('\n')
	if err != nil {
		t.Fatalf("put v2 respuesta: %v", err)
	}
	var resp map[string]any
	_ = json.Unmarshal([]byte(line), &resp)
	if e, ok := resp["err"].(string); ok {
		return fmt.Errorf("%s", e)
	}
	return nil
}

// mbxGetV2 retira por etiquetas; ack=true confirma todo lo recibido.
func mbxGetV2(t *testing.T, from host.Host, node peer.ID, labels []string, ack bool) []mbxEnvelopeV2 {
	t.Helper()
	s, err := from.NewStream(context.Background(), node, mbxGetProtocolV2)
	if err != nil {
		t.Fatalf("get v2 stream: %v", err)
	}
	defer s.Close()
	req, _ := json.Marshal(mbxGetReqV2{V: 2, Labels: labels})
	fmt.Fprintf(s, "%s\n", req)

	r := bufio.NewReader(s)
	var envs []mbxEnvelopeV2
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			t.Fatalf("get v2 lectura: %v", err)
		}
		var env mbxEnvelopeV2
		if err := json.Unmarshal([]byte(line), &env); err != nil {
			t.Fatalf("get v2 json: %v", err)
		}
		if env.Done {
			break
		}
		envs = append(envs, env)
	}
	acks := map[string][]string{}
	if ack {
		for _, e := range envs {
			acks[e.Label] = append(acks[e.Label], e.ID)
		}
	}
	out, _ := json.Marshal(map[string]any{"ack": acks})
	fmt.Fprintf(s, "%s\n", out)
	_ = s.CloseWrite()
	return envs
}

// TestMailboxV2NoGuardaNiRemitenteNiDestinatario es la razón de ser del depósito ciego: lo que
// queda escrito en el disco del nodo no debe permitir reconstruir quién habla con quién.
func TestMailboxV2NoGuardaNiRemitenteNiDestinatario(t *testing.T) {
	node, a, b := newTestHost(t), newTestHost(t), newTestHost(t)
	dir := t.TempDir()
	mbx := newMailbox(dir)
	mbx.attach(node)
	connect(t, a, node)
	connect(t, b, node)

	if err := mbxPutV2(t, a, node.ID(), etiqueta, []byte("ciphertext opaco")); err != nil {
		t.Fatalf("put: %v", err)
	}

	// El directorio se llama como la etiqueta, no como nadie.
	if _, err := os.Stat(filepath.Join(dir, etiqueta)); err != nil {
		t.Fatalf("debía guardarse bajo la etiqueta: %v", err)
	}
	files, _ := os.ReadDir(filepath.Join(dir, etiqueta))
	if len(files) != 1 {
		t.Fatalf("esperaba un sobre, hay %d", len(files))
	}
	raw, _ := os.ReadFile(filepath.Join(dir, etiqueta, files[0].Name()))
	for _, quien := range []string{a.ID().String(), b.ID().String()} {
		if strings.Contains(string(raw), quien) {
			t.Fatalf("el sobre en disco delata a %s: %s", quien, raw)
		}
	}
	if strings.Contains(string(raw), `"from"`) {
		t.Fatalf("v2 no debe escribir el remitente: %s", raw)
	}
}

// TestMailboxV2LaEtiquetaEsLaLlave: quien presenta la etiqueta retira, y el nodo no necesita
// saber de quién es el buzón. Lo retira un peer DISTINTO del que depositó.
func TestMailboxV2LaEtiquetaEsLaLlave(t *testing.T) {
	node, a, b := newTestHost(t), newTestHost(t), newTestHost(t)
	mbx := newMailbox(t.TempDir())
	mbx.attach(node)
	connect(t, a, node)
	connect(t, b, node)

	msg := []byte("para quien sepa la etiqueta")
	if err := mbxPutV2(t, a, node.ID(), etiqueta, msg); err != nil {
		t.Fatalf("put: %v", err)
	}

	// Sin ack: sigue disponible (misma garantía de reentrega que en v1).
	got := mbxGetV2(t, b, node.ID(), []string{etiqueta}, false)
	if len(got) != 1 || string(mustB64(t, got[0].Blob)) != string(msg) {
		t.Fatalf("primera retirada: %+v", got)
	}
	if got[0].Label != etiqueta {
		t.Fatalf("el sobre debe decir de qué etiqueta viene: %q", got[0].Label)
	}

	// Con ack: se borra.
	if got = mbxGetV2(t, b, node.ID(), []string{etiqueta}, true); len(got) != 1 {
		t.Fatalf("se esperaba reentrega, hubo %d", len(got))
	}
	if got = mbxGetV2(t, b, node.ID(), []string{etiqueta}, true); len(got) != 0 {
		t.Fatalf("tras el ack debía quedar vacío, hay %d", len(got))
	}

	// Otra etiqueta cualquiera no devuelve nada: no hay forma de "listar el buzón de alguien".
	otra := strings.Repeat("0", 64)
	if got = mbxGetV2(t, b, node.ID(), []string{otra}, false); len(got) != 0 {
		t.Fatalf("una etiqueta ajena no debe devolver nada, devolvió %d", len(got))
	}
}

// TestMailboxV2RechazaEtiquetasInvalidas: la etiqueta acaba siendo un nombre de directorio, así
// que cualquier cosa que no sean 64 hex es un intento de salirse del almacén.
func TestMailboxV2RechazaEtiquetasInvalidas(t *testing.T) {
	node, a := newTestHost(t), newTestHost(t)
	dir := t.TempDir()
	mbx := newMailbox(dir)
	mbx.attach(node)
	connect(t, a, node)

	for _, mala := range []string{
		"../../../etc/passwd",
		strings.Repeat("a", 63),
		strings.Repeat("A", 64), // mayúsculas fuera
		"",
		"aa/bb",
	} {
		if err := mbxPutV2(t, a, node.ID(), mala, []byte("x")); err == nil {
			t.Fatalf("debía rechazarse la etiqueta %q", mala)
		}
	}
	entradas, _ := os.ReadDir(dir)
	if len(entradas) != 0 {
		t.Fatalf("no debía crearse nada en disco, hay %d entradas", len(entradas))
	}
}

// TestMailboxV2AvisaPorEtiqueta: el wake v2 despierta a quien está suscrito a esa etiqueta.
func TestMailboxV2AvisaPorEtiqueta(t *testing.T) {
	node, a, b := newTestHost(t), newTestHost(t), newTestHost(t)
	mbx := newMailbox(t.TempDir())
	mbx.attach(node)
	wake := newWakeRegistry()
	wake.attach(node)
	mbx.notify = wake.wake
	connect(t, a, node)
	connect(t, b, node)

	s, err := b.NewStream(context.Background(), node.ID(), wakeProtocolV2)
	if err != nil {
		t.Fatalf("wake v2 stream: %v", err)
	}
	defer s.Close()
	req, _ := json.Marshal(map[string]any{"v": 2, "labels": []string{etiqueta}})
	fmt.Fprintf(s, "%s\n", req)

	avisos := make(chan string, 1)
	go func() {
		line, err := bufio.NewReader(s).ReadString('\n')
		if err == nil {
			avisos <- line
		}
	}()
	time.Sleep(300 * time.Millisecond) // que la suscripción quede registrada

	if err := mbxPutV2(t, a, node.ID(), etiqueta, []byte("hola")); err != nil {
		t.Fatalf("put: %v", err)
	}
	select {
	case linea := <-avisos:
		if !strings.Contains(linea, `"wake"`) {
			t.Fatalf("aviso inesperado: %s", linea)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("el depósito bajo la etiqueta no despertó al suscriptor")
	}
}

// TestSweepRespetaElTopeGlobal: sin cuota por destinatario (v2 no sabe quién es), el techo
// agregado es lo único que impide que inventando etiquetas se llene el disco.
func TestSweepRespetaElTopeGlobal(t *testing.T) {
	dir := t.TempDir()
	m := newMailbox(dir)
	m.maxTotal = 4096 // pequeño para la prueba

	// Diez etiquetas distintas con un sobre cada una, envejecidas de más a menos.
	for i := 0; i < 10; i++ {
		label := fmt.Sprintf("%064x", i)
		if err := m.storeBlind(label, mbxEnvelope{ID: newMbxID(), Ts: 1, Blob: strings.Repeat("A", 1000)}, "remitente"); err != nil {
			t.Fatalf("sobre %d: %v", i, err)
		}
		envejecer(t, filepath.Join(dir, label), time.Now().Add(-time.Duration(10-i)*time.Hour))
	}

	m.sweep()

	total := int64(0)
	boxes, _ := os.ReadDir(dir)
	for _, b := range boxes {
		files, _ := os.ReadDir(filepath.Join(dir, b.Name()))
		for _, f := range files {
			if info, err := f.Info(); err == nil {
				total += info.Size()
			}
		}
	}
	if total > m.maxTotal {
		t.Fatalf("el barrido debía dejar el almacén bajo el tope: %d > %d", total, m.maxTotal)
	}
	if total == 0 {
		t.Fatal("no debía vaciarlo entero, solo desalojar lo más antiguo")
	}
}

func envejecer(t *testing.T, dir string, cuando time.Time) {
	t.Helper()
	files, _ := os.ReadDir(dir)
	for _, f := range files {
		p := filepath.Join(dir, f.Name())
		if err := os.Chtimes(p, cuando, cuando); err != nil {
			t.Fatalf("chtimes: %v", err)
		}
	}
}

// TestWakeV2SigueAvisandoDeLosDepositosV1 fija la propiedad que salva la transición: un cliente
// que se suscribe por etiquetas tiene que seguir enterándose del correo que le llega por el
// camino antiguo, porque mientras el depósito ciego no se encienda ese es TODO el correo.
func TestWakeV2SigueAvisandoDeLosDepositosV1(t *testing.T) {
	node, a, b := newTestHost(t), newTestHost(t), newTestHost(t)
	mbx := newMailbox(t.TempDir())
	mbx.attach(node)
	wake := newWakeRegistry()
	wake.attach(node)
	mbx.notify = wake.wake
	connect(t, a, node)
	connect(t, b, node)

	// B se suscribe con el wake NUEVO, solo con etiquetas.
	s, err := b.NewStream(context.Background(), node.ID(), wakeProtocolV2)
	if err != nil {
		t.Fatalf("wake v2: %v", err)
	}
	defer s.Close()
	req, _ := json.Marshal(map[string]any{"v": 2, "labels": []string{etiqueta}})
	fmt.Fprintf(s, "%s\n", req)

	avisos := make(chan string, 1)
	go func() {
		if line, err := bufio.NewReader(s).ReadString('\n'); err == nil {
			avisos <- line
		}
	}()
	time.Sleep(300 * time.Millisecond)

	// …y A deposita por el camino VIEJO, dirigido a su PeerID.
	if err := mbxPut(t, a, node.ID(), b.ID().String(), []byte("por v1")); err != nil {
		t.Fatalf("put v1: %v", err)
	}
	select {
	case linea := <-avisos:
		if !strings.Contains(linea, `"wake"`) {
			t.Fatalf("aviso inesperado: %s", linea)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("un depósito v1 debe seguir despertando a quien se suscribió con v2")
	}
}

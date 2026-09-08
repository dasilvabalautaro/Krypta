// Buzón E2EE store-and-forward (Fase 1/4): el emisor deposita blobs cifrados para un
// destinatario offline y este los retira al conectarse. El nodo NUNCA puede descifrar:
// solo guarda ciphertext opaco, con TTL y cuotas anti-abuso.
//
// Protocolo (JSON por líneas sobre streams libp2p, interop v0.38 nodo ↔ v0.48 móviles):
//
//	/krypta/mbx/put/1.0.0  cliente → {"v":1,"to":"<peerid>","blob":"<b64>"}\n
//	                       nodo    → {"ok":true} | {"err":"…"}
//	/krypta/mbx/get/1.0.0  nodo    → {"id","from","ts","blob"}\n … {"done":true}\n
//	                       cliente → {"ack":["id",…]}\n   (el nodo borra solo lo ack'eado)
//
// Autenticación gratis por libp2p: en GET solo se entregan los blobs cuyo `to` es el
// PeerID remoto del stream, y en PUT el `from` del sobre lo fija el nodo desde el stream
// (no se puede suplantar al remitente). Si el ack se pierde, el mensaje se reentrega y el
// cliente deduplica por `id`.
//
// Anti-abuso (auditoría A-11): cualquiera que conozca un PeerID —y el PeerID se comparte
// abiertamente para darse de alta— puede depositar en el buzón de otro. Con una cuota solo
// global, un desconocido podía llenarla y **dejar sin entrega a los contactos de verdad**
// (denegación de entrega, no solo spam). Por eso hay además un **reparto justo por
// remitente**, en dos reglas que no rompen el caso legítimo:
//
//  1. Un remitente puede ocupar el buzón ENTERO mientras sea el único que ha depositado
//     (es el caso del archivo troceado grande a un contacto desconectado). En cuanto hay
//     correo de otro remitente, ninguno puede pasar de la mitad de la cuota.
//  2. Si el buzón está lleno y algún remitente se pasa de su mitad, se desaloja su correo
//     más antiguo para hacer sitio al que llega. Así el que se pasó de la raya pierde su
//     exceso, en vez de bloquear a los demás.
//
// El nodo no aprende nada nuevo con esto: el remitente ya lo fijaba él mismo desde la
// identidad del stream. En disco solo queda un hash corto del PeerID del remitente (en el
// nombre del fichero), lo justo para contar cuota sin listar quién escribe a quién.
package main

import (
	"bufio"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/libp2p/go-libp2p/core/host"
	"github.com/libp2p/go-libp2p/core/network"
	"github.com/libp2p/go-libp2p/core/peer"
	"github.com/libp2p/go-libp2p/core/protocol"
)

const (
	mbxPutProtocol = protocol.ID("/krypta/mbx/put/1.0.0")
	mbxGetProtocol = protocol.ID("/krypta/mbx/get/1.0.0")
)

type mailbox struct {
	dir      string
	ttl      time.Duration
	maxBlob  int   // bytes de ciphertext por mensaje
	maxMsgs  int   // mensajes pendientes por destinatario
	maxBytes int64 // bytes pendientes por destinatario
	// Reparto justo: fracción máxima de la cuota que puede ocupar UN remitente cuando hay
	// correo de otro (ver la cabecera del fichero). 2 = la mitad.
	shareDiv int
	mu       sync.Mutex

	// notify (opcional) se invoca tras cada depósito con el PeerID del destinatario —
	// el wake integrado (wake.go) le avisa al instante si está suscrito.
	notify func(to string)
}

type mbxPutReq struct {
	V    int    `json:"v"`
	To   string `json:"to"`
	Blob string `json:"blob"`
}

type mbxEnvelope struct {
	ID   string `json:"id"`
	From string `json:"from"`
	Ts   int64  `json:"ts"` // unix millis del depósito
	Blob string `json:"blob"`
	Done bool   `json:"done,omitempty"`
}

type mbxAck struct {
	Ack []string `json:"ack"`
}

func newMailbox(dir string) *mailbox {
	return &mailbox{
		dir:      dir,
		ttl:      7 * 24 * time.Hour,
		maxBlob:  64 << 10,
		maxMsgs:  200,
		maxBytes: 5 << 20,
		shareDiv: 2,
	}
}

// attach registra los handlers del buzón en el host.
func (m *mailbox) attach(h host.Host) {
	h.SetStreamHandler(mbxPutProtocol, m.handlePut)
	h.SetStreamHandler(mbxGetProtocol, m.handleGet)
}

func (m *mailbox) handlePut(s network.Stream) {
	defer s.Close()
	reply := func(errMsg string) {
		if errMsg == "" {
			fmt.Fprintln(s, `{"ok":true}`)
		} else {
			out, _ := json.Marshal(map[string]string{"err": errMsg})
			fmt.Fprintf(s, "%s\n", out)
		}
	}

	// Límite de lectura: blob de 64 KiB → ~87 KiB en base64 + el sobre JSON.
	line, err := readLine(io.LimitReader(s, 128<<10))
	if err != nil {
		reply("petición ilegible: " + err.Error())
		return
	}
	var req mbxPutReq
	if err := json.Unmarshal(line, &req); err != nil {
		reply("JSON inválido: " + err.Error())
		return
	}
	to, err := peer.Decode(req.To)
	if err != nil {
		reply("destinatario inválido: " + err.Error())
		return
	}
	blob, err := base64.StdEncoding.DecodeString(req.Blob)
	if err != nil {
		reply("blob no es base64: " + err.Error())
		return
	}
	if len(blob) == 0 || len(blob) > m.maxBlob {
		reply(fmt.Sprintf("blob fuera de límite (1..%d bytes)", m.maxBlob))
		return
	}

	env := mbxEnvelope{
		ID:   newMbxID(),
		From: s.Conn().RemotePeer().String(), // identidad verificada del stream, no suplantable
		Ts:   time.Now().UnixMilli(),
		Blob: req.Blob,
	}
	if err := m.store(to.String(), env); err != nil {
		reply(err.Error())
		return
	}
	reply("")
	if m.notify != nil {
		m.notify(to.String())
	}
}

func (m *mailbox) handleGet(s network.Stream) {
	defer s.Close()
	// Solo se entrega el buzón del peer autenticado en el stream.
	to := s.Conn().RemotePeer().String()

	envs, err := m.list(to)
	if err != nil {
		return
	}
	w := bufio.NewWriter(s)
	for _, env := range envs {
		out, err := json.Marshal(env)
		if err != nil {
			continue
		}
		fmt.Fprintf(w, "%s\n", out)
	}
	fmt.Fprintln(w, `{"done":true}`)
	if err := w.Flush(); err != nil {
		return
	}

	// El cliente confirma lo procesado; solo eso se borra (si el ack se pierde, reentrega).
	line, err := readLine(io.LimitReader(s, 64<<10))
	if err != nil {
		return
	}
	var ack mbxAck
	if err := json.Unmarshal(line, &ack); err != nil {
		return
	}
	m.delete(to, ack.Ack)
}

// boxFile es un sobre ya en disco, visto solo por su entrada de directorio (sin leerlo).
type boxFile struct {
	name string // <id>.<tag>.json  (o el antiguo <id>.json, sin remitente conocido)
	tag  string // hash corto del PeerID del remitente, "" si es un fichero antiguo
	size int64
}

// senderTag es el hash corto del PeerID del remitente, que va en el nombre del fichero para
// poder contar la cuota por remitente sin abrir ni un sobre. Se usa un hash y no el PeerID
// para que el listado del directorio no sea, de un vistazo, la lista de quién le escribe a
// quién.
func senderTag(from string) string {
	sum := sha256.Sum256([]byte(from))
	return hex.EncodeToString(sum[:6])
}

// scanBox lee las entradas del buzón de un destinatario (sin abrir los ficheros).
// Devuelve la lista en orden cronológico (el id empieza por unix-nano con ceros).
func scanBox(dir string) []boxFile {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	out := make([]boxFile, 0, len(entries))
	for _, e := range entries {
		name := e.Name()
		if e.IsDir() || !strings.HasSuffix(name, ".json") {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		// <id>.<tag>.json → tag; <id>.json (formato antiguo) → "" (remitente desconocido).
		tag := ""
		if parts := strings.Split(strings.TrimSuffix(name, ".json"), "."); len(parts) == 2 {
			tag = parts[1]
		}
		out = append(out, boxFile{name: name, tag: tag, size: info.Size()})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].name < out[j].name })
	return out
}

// usage suma cuántos sobres y cuántos bytes ocupa cada remitente.
func usage(box []boxFile) (perTag map[string]struct {
	count int
	bytes int64
}, count int, bytes int64) {
	perTag = map[string]struct {
		count int
		bytes int64
	}{}
	for _, f := range box {
		u := perTag[f.tag]
		u.count++
		u.bytes += f.size
		perTag[f.tag] = u
		count++
		bytes += f.size
	}
	return perTag, count, bytes
}

// store guarda el sobre en mailboxdir/<to>/<id>.<tag>.json aplicando la cuota global del
// destinatario y el reparto justo entre remitentes (ver la cabecera del fichero).
func (m *mailbox) store(to string, env mbxEnvelope) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	data, err := json.Marshal(env)
	if err != nil {
		return err
	}
	incoming := int64(len(data))
	dir := filepath.Join(m.dir, to)
	tag := senderTag(env.From)
	box := scanBox(dir)
	perTag, count, size := usage(box)

	shareMsgs, shareBytes := m.maxMsgs, m.maxBytes
	if m.shareDiv > 1 {
		shareMsgs, shareBytes = m.maxMsgs/m.shareDiv, m.maxBytes/int64(m.shareDiv)
	}
	mine := perTag[tag]

	// Regla 1 — reparto justo, solo si hay correo de OTRO remitente. Mientras sea el único
	// que ha depositado puede ocupar el buzón entero (archivo troceado grande a un contacto
	// desconectado); en cuanto hay más de uno, nadie pasa de su mitad.
	if count > mine.count {
		if mine.count+1 > shareMsgs || mine.bytes+incoming > shareBytes {
			return fmt.Errorf("cuota de este remitente agotada en el buzón del destinatario (reparto justo: %d msgs / %d bytes)", shareMsgs, shareBytes)
		}
	}

	// Regla 2 — cuota global. Si está llena, se desaloja al remitente que se haya pasado de
	// su mitad (el más antiguo primero) para hacer sitio: el que abusó pierde su exceso en
	// vez de bloquear la entrega de los demás.
	for count+1 > m.maxMsgs || size+incoming > m.maxBytes {
		victim := evictionVictim(box, perTag, tag, shareMsgs, shareBytes)
		if victim < 0 {
			return fmt.Errorf("buzón del destinatario lleno (%d msgs, %d bytes)", count, size)
		}
		f := box[victim]
		if err := os.Remove(filepath.Join(dir, f.name)); err != nil {
			return fmt.Errorf("buzón del destinatario lleno (%d msgs, %d bytes)", count, size)
		}
		box = append(box[:victim], box[victim+1:]...)
		perTag, count, size = usage(box)
	}

	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(dir, env.ID+"."+tag+".json"), data, 0o600)
}

// evictionVictim elige qué sobre desalojar: el MÁS ANTIGUO del remitente que más se pasa de
// su reparto, sin contar al que está depositando ahora (nadie hace sitio a costa de sí
// mismo). Devuelve -1 si nadie se está pasando — entonces el buzón está legítimamente lleno
// y el depósito se rechaza.
func evictionVictim(box []boxFile, perTag map[string]struct {
	count int
	bytes int64
}, depositor string, shareMsgs int, shareBytes int64) int {
	worst, worstBytes := "", int64(-1)
	for tag, u := range perTag {
		if tag == depositor {
			continue
		}
		if u.count <= shareMsgs && u.bytes <= shareBytes {
			continue // este remitente está dentro de su reparto: no se le toca
		}
		if u.bytes > worstBytes {
			worst, worstBytes = tag, u.bytes
		}
	}
	if worst == "" {
		return -1
	}
	for i, f := range box { // box viene en orden cronológico: el primero es el más antiguo
		if f.tag == worst {
			return i
		}
	}
	return -1
}

// list devuelve los sobres pendientes de un destinatario en orden de depósito.
func (m *mailbox) list(to string) ([]mbxEnvelope, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	dir := filepath.Join(m.dir, to)
	files, err := os.ReadDir(dir)
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	names := make([]string, 0, len(files))
	for _, f := range files {
		if strings.HasSuffix(f.Name(), ".json") {
			names = append(names, f.Name())
		}
	}
	sort.Strings(names) // el id empieza por unix-nano con ceros → orden cronológico
	var envs []mbxEnvelope
	for _, name := range names {
		data, err := os.ReadFile(filepath.Join(dir, name))
		if err != nil {
			continue
		}
		var env mbxEnvelope
		if err := json.Unmarshal(data, &env); err == nil {
			envs = append(envs, env)
		}
	}
	return envs, nil
}

// delete borra los sobres ack'eados de un destinatario (nunca fuera de su directorio).
// Acepta los dos nombres posibles: `<id>.<tag>.json` (con remitente) y `<id>.json`, el
// formato anterior al reparto justo — un nodo que se actualiza sigue pudiendo borrar lo que
// ya tenía guardado.
func (m *mailbox) delete(to string, ids []string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	dir := filepath.Join(m.dir, to)
	box := scanBox(dir)
	for _, id := range ids {
		if id == "" || id != filepath.Base(id) || strings.Contains(id, "..") {
			continue
		}
		if err := os.Remove(filepath.Join(dir, id+".json")); err == nil {
			continue
		}
		for _, f := range box {
			if strings.HasPrefix(f.name, id+".") {
				_ = os.Remove(filepath.Join(dir, f.name))
				break
			}
		}
	}
}

// sweep borra los sobres más viejos que el TTL (por mtime) y los buzones vacíos.
func (m *mailbox) sweep() {
	m.mu.Lock()
	defer m.mu.Unlock()
	cutoff := time.Now().Add(-m.ttl)
	boxes, err := os.ReadDir(m.dir)
	if err != nil {
		return
	}
	for _, box := range boxes {
		if !box.IsDir() {
			continue
		}
		dir := filepath.Join(m.dir, box.Name())
		files, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		remaining := 0
		for _, f := range files {
			info, err := f.Info()
			if err == nil && info.ModTime().Before(cutoff) {
				_ = os.Remove(filepath.Join(dir, f.Name()))
			} else {
				remaining++
			}
		}
		if remaining == 0 {
			_ = os.Remove(dir)
		}
	}
}

// newMbxID genera un id único ordenable cronológicamente: unix-nano con ceros + azar.
func newMbxID() string {
	var r [4]byte
	_, _ = rand.Read(r[:])
	return fmt.Sprintf("%020d-%x", time.Now().UnixNano(), r)
}

// readLine lee hasta '\n' o EOF (los clientes pueden cerrar la escritura sin salto final).
func readLine(r io.Reader) ([]byte, error) {
	line, err := bufio.NewReader(r).ReadBytes('\n')
	if err != nil && len(line) == 0 {
		return nil, err
	}
	return line, nil
}

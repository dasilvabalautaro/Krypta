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
//
// A la cuota (cuánto ocupas) se le suma un **límite de ritmo** (cuán rápido depositas), que es
// lo que faltaba: la cuota sola no impide machacar el nodo a escrituras, porque los rechazos
// salen gratis. Es un cubo de fichas por remitente, deliberadamente generoso en ráfaga —un
// archivo troceado son ~110 depósitos seguidos y tiene que pasar sin despeinarse— y estrecho
// en régimen sostenido.
package main

import (
	"bufio"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
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

	// v2 — depósito ciego: el buzón se direcciona por una etiqueta derivada del secreto de la
	// pareja, no por el PeerID del destinatario, y el sobre no guarda el remitente. Ver
	// docs/DISENO-buzon-ciego.md. Conviven con v1 mientras queden clientes sin actualizar.
	mbxPutProtocolV2 = protocol.ID("/krypta/mbx/put/2.0.0")
	mbxGetProtocolV2 = protocol.ID("/krypta/mbx/get/2.0.0")
)

// labelPattern acota lo que se acepta como etiqueta. Es importante que sea estricto: la
// etiqueta acaba siendo el **nombre de un directorio**, así que cualquier cosa que no sean
// exactamente 64 caracteres hexadecimales es un intento de salirse del almacén.
var labelPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

// maxLabelsPerRequest acota cuántas etiquetas puede consultar un cliente de una vez. Con
// rotación semanal son 2 por contacto, así que 1024 da para 500 contactos y de paso evita que
// una petición gigante haga al nodo recorrer el disco entero.
const maxLabelsPerRequest = 1024

type mailbox struct {
	dir      string
	ttl      time.Duration
	maxBlob  int   // bytes de ciphertext por mensaje
	maxMsgs  int   // mensajes pendientes por destinatario
	maxBytes int64 // bytes pendientes por destinatario (o por etiqueta, en v2)
	maxTotal int64 // tope de disco del buzón entero, para todos los destinatarios juntos
	// Reparto justo: fracción máxima de la cuota que puede ocupar UN remitente cuando hay
	// correo de otro (ver la cabecera del fichero). 2 = la mitad.
	shareDiv int

	// Límite de ritmo por remitente (cubo de fichas).
	burst   int           // fichas acumulables: la ráfaga máxima admitida de golpe
	refill  time.Duration // cada cuánto se repone una ficha
	buckets map[string]*bucket
	now     func() time.Time // inyectable en los tests

	// Límite de ritmo de la **retirada** (12 sep 2026). Retirar no escribe, pero lista y lee
	// ficheros: sin límite, cualquiera podía hacer trabajar al disco del nodo a voluntad (un
	// GET v2 pide hasta 1024 etiquetas). Un cubo por PeerID del stream, aparte del de
	// depósitos, y uno **global**, porque las identidades libp2p no cuestan nada y un cubo por
	// peer solo no frena a quien rote PeerIDs. Pasado el límite se contesta una retirada vacía:
	// el correo se queda en el nodo y sale en la siguiente, así que no se pierde nada.
	getBurst        int
	getRefill       time.Duration
	getBuckets      map[string]*bucket
	getGlobalBurst  int
	getGlobalRefill time.Duration
	getGlobal       *bucket

	mu sync.Mutex

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
	ID string `json:"id"`
	// `omitempty` a propósito: en v1 el nodo siempre lo rellena desde la identidad del stream,
	// así que el formato no cambia; en v2 **no existe remitente que guardar** y sin esto se
	// escribía un `"from":""` en disco. Un campo vacío no filtra nada hoy, pero deja la puerta
	// abierta a que un cambio futuro lo rellene sin que nadie se dé cuenta, que es justo lo que
	// el depósito ciego viene a impedir.
	From string `json:"from,omitempty"`
	Ts   int64  `json:"ts"` // unix millis del depósito
	Blob string `json:"blob"`
	Done bool   `json:"done,omitempty"`
}

type mbxAck struct {
	Ack []string `json:"ack"`
}

// --- v2 (depósito ciego) ---------------------------------------------------------------

type mbxPutReqV2 struct {
	V     int    `json:"v"`
	Label string `json:"label"`
	Blob  string `json:"blob"`
}

type mbxGetReqV2 struct {
	V      int      `json:"v"`
	Labels []string `json:"labels"`
}

// mbxEnvelopeV2 es lo que viaja en la retirada v2. No lleva `from`: en v1 lo ponía el nodo
// desde la identidad del stream (y por eso no era suplantable), pero en v2 esa garantía vive
// dentro del cifrado — solo la pareja de esa etiqueta tiene la clave con la que el blob
// descifra, así que ya no hace falta que el nodo lo sepa ni lo escriba.
type mbxEnvelopeV2 struct {
	Label string `json:"label,omitempty"`
	ID    string `json:"id,omitempty"`
	Ts    int64  `json:"ts,omitempty"`
	Blob  string `json:"blob,omitempty"`
	Done  bool   `json:"done,omitempty"`
	Err   string `json:"err,omitempty"`
}

func newMailbox(dir string) *mailbox {
	return &mailbox{
		dir:      dir,
		ttl:      7 * 24 * time.Hour,
		maxBlob:  64 << 10,
		maxMsgs:  200,
		maxBytes: 5 << 20,
		// Tope global del almacén. En v2 desaparece la cuota "por destinatario" —el nodo ya no
		// sabe quién es— así que hace falta un techo agregado o un extraño podría inventar
		// etiquetas al azar hasta llenar el disco. 2 GiB va muy holgado sobre el uso real
		// (5 MiB el 9 sep 2026) y deja libre el resto del volumen.
		maxTotal: 2 << 30,
		shareDiv: 2,
		// 256 de ráfaga cubre de sobra el peor caso legítimo (un archivo que llene el buzón son
		// ~110 trozos de 48 KiB) y una ficha por segundo deja 3.600 depósitos/hora sostenidos,
		// que ninguna persona alcanza escribiendo.
		burst:   256,
		refill:  time.Second,
		buckets: map[string]*bucket{},
		now:     time.Now,
		// Retirada: un cliente legítimo abre dos streams por nodo en cada retirada (v1 y v2) y
		// retira cada 30–180 s, más una por aviso de wake. 60 de ráfaga absorbe una tanda de
		// avisos (un archivo troceado), y una ficha cada 5 s son 12 retiradas por minuto
		// sostenidas, varias veces lo que hace una app normal.
		getBurst:   60,
		getRefill:  5 * time.Second,
		getBuckets: map[string]*bucket{},
		// Global: 2000 suscriptores de wake retirando cada 30 s por las dos vías son ~133 por
		// segundo en el peor caso; 200/s con ráfaga de 2000 lo cubre con margen.
		getGlobalBurst:  2000,
		getGlobalRefill: 5 * time.Millisecond,
		getGlobal:       &bucket{},
	}
}

// attach registra los handlers del buzón en el host.
func (m *mailbox) attach(h host.Host) {
	h.SetStreamHandler(mbxPutProtocol, m.handlePut)
	h.SetStreamHandler(mbxGetProtocol, m.handleGet)
	h.SetStreamHandler(mbxPutProtocolV2, m.handlePutV2)
	h.SetStreamHandler(mbxGetProtocolV2, m.handleGetV2)
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
	if !m.allowGet(to) {
		replyEmptyFetch(s)
		return
	}

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

// bucket es el cubo de fichas de un remitente: cuántos depósitos le quedan y desde cuándo.
type bucket struct {
	tokens float64
	last   time.Time
}

// allow descuenta una ficha al remitente y dice si el depósito puede seguir. Reponer se hace
// por tiempo transcurrido, sin temporizadores: el cubo se pone al día cuando se le consulta.
func (m *mailbox) allow(from string) bool {
	now := m.now()
	b := bucketFor(m.buckets, from, m.burst, m.refill, now)
	topUp(b, m.burst, m.refill, now)
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

// allowGet es lo mismo para la retirada: cubo del peer y cubo global. Solo se descuenta si
// hay ficha en los dos, para que el global agotado no le coma fichas a un peer que no ha
// retirado nada.
func (m *mailbox) allowGet(peerID string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.now()
	b := bucketFor(m.getBuckets, peerID, m.getBurst, m.getRefill, now)
	topUp(b, m.getBurst, m.getRefill, now)
	topUp(m.getGlobal, m.getGlobalBurst, m.getGlobalRefill, now)
	if b.tokens < 1 || m.getGlobal.tokens < 1 {
		return false
	}
	b.tokens--
	m.getGlobal.tokens--
	return true
}

// replyEmptyFetch contesta una retirada limitada **siguiendo el protocolo**: fin de lista y
// lectura del ack (que llegará vacío). Cerrar sin más haría que el cliente viera un error de
// escritura al ack'ear y contara el nodo como caído; así solo ve un buzón sin novedades, y lo
// pendiente sale en la siguiente retirada. No toca el disco.
func replyEmptyFetch(s network.Stream) {
	if _, err := fmt.Fprintln(s, `{"done":true}`); err != nil {
		return
	}
	_, _ = readLine(io.LimitReader(s, 128<<10))
}

// bucketFor devuelve el cubo de `key`, creándolo lleno si no existía. Limpieza perezosa: sin
// ella el mapa crecería con cada PeerID que haya aparecido alguna vez, y eso lo controla quien
// ataca, no el nodo.
func bucketFor(buckets map[string]*bucket, key string, burst int, refill time.Duration, now time.Time) *bucket {
	b, ok := buckets[key]
	if !ok {
		if len(buckets) >= maxTrackedSenders {
			pruneMap(buckets, burst, refill, now)
		}
		b = &bucket{tokens: float64(burst), last: now}
		buckets[key] = b
	}
	return b
}

// topUp repone las fichas que tocan por el tiempo transcurrido, sin pasar de la ráfaga. Un
// cubo recién creado con `last` a cero se llena del todo en la primera consulta.
func topUp(b *bucket, burst int, refill time.Duration, now time.Time) {
	if elapsed := now.Sub(b.last); elapsed > 0 {
		b.tokens += elapsed.Seconds() / refill.Seconds()
		if b.tokens > float64(burst) {
			b.tokens = float64(burst)
		}
		b.last = now
	}
}

// pruneBuckets olvida a los remitentes cuyo cubo ya está lleno (o sea, llevan sin depositar el
// tiempo suficiente): reconstruirlo cuesta nada y no cambia lo que se les permite.
func (m *mailbox) pruneBuckets(now time.Time) {
	pruneMap(m.buckets, m.burst, m.refill, now)
}

func pruneMap(buckets map[string]*bucket, burst int, refill time.Duration, now time.Time) {
	for key, b := range buckets {
		if now.Sub(b.last) >= time.Duration(burst)*refill {
			delete(buckets, key)
		}
	}
}

// maxTrackedSenders dispara la limpieza del mapa de cubos.
const maxTrackedSenders = 10000

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

// handlePutV2 recibe un depósito ciego: el cliente dice bajo QUÉ ETIQUETA deja el blob, y el
// nodo no llega a saber para quién es. Lo único que sigue viendo es quién deposita (libp2p
// autentica todos los streams), y eso se usa solo para el límite de ritmo — no se guarda.
func (m *mailbox) handlePutV2(s network.Stream) {
	defer s.Close()
	reply := func(errMsg string) {
		out, _ := json.Marshal(mbxEnvelopeV2{Err: errMsg})
		if errMsg == "" {
			out = []byte(`{"ok":true}`)
		}
		fmt.Fprintf(s, "%s\n", out)
	}

	line, err := readLine(io.LimitReader(s, 128<<10))
	if err != nil {
		reply("petición ilegible: " + err.Error())
		return
	}
	var req mbxPutReqV2
	if err := json.Unmarshal(line, &req); err != nil {
		reply("JSON inválido: " + err.Error())
		return
	}
	if !labelPattern.MatchString(req.Label) {
		reply("etiqueta inválida (64 hex)")
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

	env := mbxEnvelope{ID: newMbxID(), Ts: time.Now().UnixMilli(), Blob: req.Blob}
	if err := m.storeBlind(req.Label, env, s.Conn().RemotePeer().String()); err != nil {
		reply(err.Error())
		return
	}
	reply("")
	if m.notify != nil {
		m.notify(req.Label)
	}
}

// handleGetV2 entrega lo que haya bajo las etiquetas que pida el cliente. La etiqueta es una
// credencial al portador: quien la presenta, retira. Como solo la pareja puede derivarla, el
// efecto es el mismo que autenticar por identidad de stream en v1, pero sin que el nodo tenga
// que saber a quién está sirviendo.
func (m *mailbox) handleGetV2(s network.Stream) {
	defer s.Close()

	line, err := readLine(io.LimitReader(s, 128<<10))
	if err != nil {
		return
	}
	var req mbxGetReqV2
	if err := json.Unmarshal(line, &req); err != nil {
		return
	}
	if len(req.Labels) > maxLabelsPerRequest {
		req.Labels = req.Labels[:maxLabelsPerRequest]
	}
	if !m.allowGet(s.Conn().RemotePeer().String()) {
		replyEmptyFetch(s)
		return
	}

	w := bufio.NewWriter(s)
	for _, label := range req.Labels {
		if !labelPattern.MatchString(label) {
			continue
		}
		envs, err := m.list(label)
		if err != nil {
			continue
		}
		for _, env := range envs {
			out, err := json.Marshal(mbxEnvelopeV2{
				Label: label, ID: env.ID, Ts: env.Ts, Blob: env.Blob,
			})
			if err != nil {
				continue
			}
			fmt.Fprintf(w, "%s\n", out)
		}
	}
	fmt.Fprintln(w, `{"done":true}`)
	if err := w.Flush(); err != nil {
		return
	}

	// El ack llega como {"ack":{"<etiqueta>":["id",…]}}: hay que decir de qué buzón se borra
	// cada id, porque en v2 el nodo no sabe cuál es "el buzón de este cliente".
	line, err = readLine(io.LimitReader(s, 128<<10))
	if err != nil {
		return
	}
	var ack struct {
		Ack map[string][]string `json:"ack"`
	}
	if err := json.Unmarshal(line, &ack); err != nil {
		return
	}
	for label, ids := range ack.Ack {
		if labelPattern.MatchString(label) {
			m.delete(label, ids)
		}
	}
}

// storeBlind es el store de v2: mismo límite de ritmo y misma cuota, pero por **etiqueta** en
// vez de por destinatario, y sin reparto entre remitentes — no hace falta, porque a una
// etiqueta solo puede escribir quien conoce el secreto de esa pareja. Un desconocido ya no
// puede llenarle el buzón a nadie: no sabe calcular su etiqueta.
func (m *mailbox) storeBlind(label string, env mbxEnvelope, from string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if !m.allow(from) {
		return errors.New("demasiados depósitos seguidos; inténtalo en unos segundos")
	}
	data, err := json.Marshal(env)
	if err != nil {
		return err
	}
	dir := filepath.Join(m.dir, label)
	_, count, size := usage(scanBox(dir))
	if count+1 > m.maxMsgs || size+int64(len(data)) > m.maxBytes {
		return fmt.Errorf("buzón lleno para esa etiqueta (%d msgs, %d bytes)", count, size)
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(dir, env.ID+".json"), data, 0o600)
}

// store guarda el sobre en mailboxdir/<to>/<id>.<tag>.json aplicando la cuota global del
// destinatario y el reparto justo entre remitentes (ver la cabecera del fichero).
func (m *mailbox) store(to string, env mbxEnvelope) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	// Ritmo antes que nada: rechazar barato es justo el objetivo, para que una avalancha no se
	// traduzca en trabajo de disco.
	if !m.allow(env.From) {
		return errors.New("demasiados depósitos seguidos; inténtalo en unos segundos")
	}

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

// enforceTotal aplica el tope global de disco desalojando lo más antiguo. Se llama desde el
// barrido y no en cada depósito: recorrer el almacén entero por cada mensaje sería caro, y el
// disco no se llena de golpe.
func (m *mailbox) enforceTotal() {
	type item struct {
		path string
		mod  time.Time
		size int64
	}
	var all []item
	total := int64(0)
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
		for _, f := range files {
			info, err := f.Info()
			if err != nil {
				continue
			}
			all = append(all, item{filepath.Join(dir, f.Name()), info.ModTime(), info.Size()})
			total += info.Size()
		}
	}
	if total <= m.maxTotal {
		return
	}
	sort.Slice(all, func(i, j int) bool { return all[i].mod.Before(all[j].mod) })
	for _, it := range all {
		if total <= m.maxTotal {
			return
		}
		if os.Remove(it.path) == nil {
			total -= it.size
		}
	}
}

// sweep borra los sobres más viejos que el TTL (por mtime) y los buzones vacíos.
func (m *mailbox) sweep() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.enforceTotal()
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

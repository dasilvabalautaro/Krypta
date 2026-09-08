package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// put deposita un sobre de `from` para `to` como lo haría handlePut (que es quien fija el
// remitente desde la identidad del stream).
func put(m *mailbox, from, to, blob string) error {
	return m.store(to, mbxEnvelope{ID: newMbxID(), From: from, Ts: 1, Blob: blob})
}

func countFor(t *testing.T, m *mailbox, to string) int {
	t.Helper()
	envs, err := m.list(to)
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	return len(envs)
}

// TestMailboxSingleSenderMayFillTheBox: el reparto justo NO debe estropear el caso legítimo
// más exigente — un archivo troceado (decenas de sobres del mismo remitente) hacia un
// contacto desconectado. Mientras nadie más haya depositado, un remitente puede ocupar el
// buzón entero.
func TestMailboxSingleSenderMayFillTheBox(t *testing.T) {
	m := newMailbox(t.TempDir())
	m.maxMsgs = 6
	for i := 0; i < 6; i++ {
		if err := put(m, "remitenteA", "destino", "x"); err != nil {
			t.Fatalf("sobre %d rechazado: %v", i, err)
		}
	}
	if got := countFor(t, m, "destino"); got != 6 {
		t.Fatalf("esperaba 6 sobres, hay %d", got)
	}
}

// TestMailboxFairShareBetweenSenders: en cuanto hay correo de otro remitente, ninguno puede
// pasar de su mitad de la cuota.
func TestMailboxFairShareBetweenSenders(t *testing.T) {
	m := newMailbox(t.TempDir())
	m.maxMsgs = 4 // reparto = 2 por remitente

	for i := 0; i < 2; i++ {
		if err := put(m, "remitenteA", "destino", "x"); err != nil {
			t.Fatalf("A %d: %v", i, err)
		}
	}
	if err := put(m, "remitenteB", "destino", "x"); err != nil {
		t.Fatalf("B debía caber: %v", err)
	}
	err := put(m, "remitenteA", "destino", "x")
	if err == nil || !strings.Contains(err.Error(), "reparto justo") {
		t.Fatalf("A pasa de su mitad y debía rechazarse, err=%v", err)
	}
}

// TestMailboxFloodDoesNotBlockOtherSenders es el hallazgo A-11 en una prueba: un desconocido
// que llena el buzón de la víctima (el PeerID se comparte abiertamente, así que puede) NO
// debe conseguir que los contactos de verdad dejen de entregar. El que se pasó de su reparto
// pierde su correo más antiguo para hacer sitio.
func TestMailboxFloodDoesNotBlockOtherSenders(t *testing.T) {
	m := newMailbox(t.TempDir())
	m.maxMsgs = 4

	for i := 0; i < 4; i++ { // el atacante llena el buzón él solo
		if err := put(m, "inundador", "destino", "basura"); err != nil {
			t.Fatalf("inundador %d: %v", i, err)
		}
	}
	if err := put(m, "contactoDeVerdad", "destino", "hola"); err != nil {
		t.Fatalf("el contacto legítimo quedó bloqueado por el inundador: %v", err)
	}

	envs, err := m.list("destino")
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(envs) != 4 {
		t.Fatalf("el buzón debe seguir en su tope (4), hay %d", len(envs))
	}
	var legit, flood int
	for _, e := range envs {
		if e.From == "contactoDeVerdad" {
			legit++
		} else {
			flood++
		}
	}
	if legit != 1 {
		t.Fatalf("el mensaje legítimo no está en el buzón (%d)", legit)
	}
	if flood != 3 {
		t.Fatalf("debía desalojarse UN sobre del inundador, quedan %d", flood)
	}
}

// TestMailboxDeleteAcceptsLegacyNames: un nodo que se actualiza tiene en disco sobres con el
// nombre antiguo `<id>.json` (sin remitente). Deben poder seguir listándose y borrándose.
func TestMailboxDeleteAcceptsLegacyNames(t *testing.T) {
	dir := t.TempDir()
	m := newMailbox(dir)
	box := filepath.Join(dir, "destino")
	if err := os.MkdirAll(box, 0o700); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	legacyID := newMbxID()
	legacy := `{"id":"` + legacyID + `","from":"viejo","ts":1,"blob":"eA=="}`
	if err := os.WriteFile(filepath.Join(box, legacyID+".json"), []byte(legacy), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	if err := put(m, "nuevo", "destino", "eA=="); err != nil {
		t.Fatalf("put: %v", err)
	}

	envs, err := m.list("destino")
	if err != nil || len(envs) != 2 {
		t.Fatalf("esperaba 2 sobres (uno antiguo, uno nuevo): %v %+v", err, envs)
	}
	ids := []string{envs[0].ID, envs[1].ID}
	m.delete("destino", ids)
	if got := countFor(t, m, "destino"); got != 0 {
		t.Fatalf("los dos formatos debían borrarse, quedan %d", got)
	}
}

// --- Límite de ritmo por remitente ---------------------------------------------------------

// clock manejable a mano: el ritmo se prueba moviendo el tiempo, no durmiendo.
type fakeClock struct{ t time.Time }

func (c *fakeClock) now() time.Time      { return c.t }
func (c *fakeClock) add(d time.Duration) { c.t = c.t.Add(d) }

func newRatedMailbox(t *testing.T) (*mailbox, *fakeClock) {
	t.Helper()
	m := newMailbox(t.TempDir())
	m.maxMsgs = 100000 // que el tope de ocupación no enmascare el de ritmo
	m.maxBytes = 1 << 30
	clock := &fakeClock{t: time.Unix(1_700_000_000, 0)}
	m.now = clock.now
	return m, clock
}

// TestRateLimitPermiteUnArchivoTroceado: el peor caso legítimo es una ráfaga seguida de
// decenas de trozos. Si el límite de ritmo rompiera eso, rompería el envío de archivos.
func TestRateLimitPermiteUnArchivoTroceado(t *testing.T) {
	m, _ := newRatedMailbox(t)
	// 110 trozos ≈ un archivo que llena el buzón (5 MiB en trozos de 48 KiB), sin pausa.
	for i := 0; i < 110; i++ {
		if err := put(m, "remitente", "destino", "trozo"); err != nil {
			t.Fatalf("el trozo %d fue rechazado por el límite de ritmo: %v", i, err)
		}
	}
}

// TestRateLimitCortaLaAvalancha: agotada la ráfaga, se rechaza — y barato, sin tocar disco.
func TestRateLimitCortaLaAvalancha(t *testing.T) {
	m, _ := newRatedMailbox(t)
	for i := 0; i < m.burst; i++ {
		if err := put(m, "inundador", "destino", "x"); err != nil {
			t.Fatalf("depósito %d dentro de la ráfaga: %v", i, err)
		}
	}
	err := put(m, "inundador", "destino", "x")
	if err == nil || !strings.Contains(err.Error(), "seguidos") {
		t.Fatalf("pasada la ráfaga debía rechazarse, err=%v", err)
	}
	// Y no debe afectar a OTRO remitente: el cubo es por remitente.
	if err := put(m, "otro", "destino", "x"); err != nil {
		t.Fatalf("un remitente no puede consumir el ritmo de los demás: %v", err)
	}
}

// TestRateLimitSeRepone: pasado el tiempo vuelven las fichas.
func TestRateLimitSeRepone(t *testing.T) {
	m, clock := newRatedMailbox(t)
	for i := 0; i < m.burst; i++ {
		_ = put(m, "remitente", "destino", "x")
	}
	if err := put(m, "remitente", "destino", "x"); err == nil {
		t.Fatal("debía estar agotado")
	}

	clock.add(3 * time.Second) // tres fichas
	for i := 0; i < 3; i++ {
		if err := put(m, "remitente", "destino", "x"); err != nil {
			t.Fatalf("ficha repuesta %d: %v", i, err)
		}
	}
	if err := put(m, "remitente", "destino", "x"); err == nil {
		t.Fatal("solo debían reponerse tres fichas")
	}
}

// TestRateLimitOlvidaRemitentesInactivos: el mapa de cubos no puede crecer sin fin, porque
// quién aparece en él lo decide quien deposita.
func TestRateLimitOlvidaRemitentesInactivos(t *testing.T) {
	m, clock := newRatedMailbox(t)
	for i := 0; i < 50; i++ {
		_ = put(m, fmt.Sprintf("remitente-%d", i), "destino", "x")
	}
	if len(m.buckets) != 50 {
		t.Fatalf("esperaba 50 cubos, hay %d", len(m.buckets))
	}
	clock.add(time.Duration(m.burst)*m.refill + time.Second) // todos vuelven a estar llenos
	m.pruneBuckets(clock.now())
	if len(m.buckets) != 0 {
		t.Fatalf("los cubos llenos debían olvidarse, quedan %d", len(m.buckets))
	}
}

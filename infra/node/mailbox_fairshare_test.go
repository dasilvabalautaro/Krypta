package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
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

package bridge

import (
	"bytes"
	"testing"
)

// El par efímero del ratchet: que el acuerdo sea simétrico (es lo único que el ratchet
// necesita del puente) y que un punto degenerado se rechace en vez de dar ceros.
func TestRatchetKeyPairAgreement(t *testing.T) {
	a, err := RatchetKeyPair()
	if err != nil {
		t.Fatalf("RatchetKeyPair: %v", err)
	}
	b, err := RatchetKeyPair()
	if err != nil {
		t.Fatalf("RatchetKeyPair: %v", err)
	}
	if len(a) != 64 || len(b) != 64 {
		t.Fatalf("esperaba privada||pública de 64 bytes, obtuve %d/%d", len(a), len(b))
	}
	if bytes.Equal(a[:32], b[:32]) {
		t.Fatal("dos pares seguidos con la misma privada: el sorteo no es aleatorio")
	}

	ab, err := RatchetAgree(a[:32], b[32:])
	if err != nil {
		t.Fatalf("RatchetAgree: %v", err)
	}
	ba, err := RatchetAgree(b[:32], a[32:])
	if err != nil {
		t.Fatalf("RatchetAgree: %v", err)
	}
	if !bytes.Equal(ab, ba) {
		t.Fatal("el acuerdo no es simétrico")
	}

	if _, err := RatchetAgree(a[:32], make([]byte, 32)); err == nil {
		t.Fatal("un punto de orden bajo debería fallar, no devolver ceros")
	}
	if _, err := RatchetAgree(a[:16], b[32:]); err == nil {
		t.Fatal("una privada de 16 bytes debería fallar")
	}
}

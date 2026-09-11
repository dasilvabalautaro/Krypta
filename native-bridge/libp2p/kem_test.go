package bridge

import (
	"bufio"
	"bytes"
	"crypto/mlkem"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"strings"
	"testing"
)

// El vector de regresión de testdata/mlkem-kat.txt, y por qué existe:
//
// **Ser híbrido esconde los errores.** Si ML-KEM estuviera mal implementado —o una ABI del AAR
// saliera rota— el resultado sería una app que funciona perfectamente y es exactamente igual de
// segura que hoy: nada falla, nada avisa, y la propiedad post-cuántica que se creía comprada no
// existe. Este vector es lo único que distingue "híbrido" de "clásico con 2 KB de relleno caro"
// (docs/DISENO-postcuantico.md §9).
//
// Y fija además la **interoperabilidad**: el `ct` lo produjo el JDK 25 encapsulando contra la
// clave cruda que genera Go desde esta semilla, así que si el formato del cable dejara de
// coincidir entre las dos implementaciones, esto falla.
func leerKat(t *testing.T) map[string]string {
	t.Helper()
	f, err := os.Open("testdata/mlkem-kat.txt")
	if err != nil {
		t.Fatalf("abrir el vector: %v", err)
	}
	defer f.Close()

	kat := map[string]string{}
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 8192), 1<<16) // el ct son 2176 caracteres hex
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		campo, valor, ok := strings.Cut(line, " ")
		if !ok {
			t.Fatalf("línea sin valor: %q", line)
		}
		kat[campo] = valor
	}
	if err := sc.Err(); err != nil {
		t.Fatalf("leer el vector: %v", err)
	}
	for _, campo := range []string{"seed", "ek_sha256", "ct", "ss"} {
		if kat[campo] == "" {
			t.Fatalf("al vector le falta el campo %q", campo)
		}
	}
	return kat
}

func debeHex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatalf("hex inválido en el vector: %v", err)
	}
	return b
}

// TestKemVectorConocido es el test que importa: la semilla fija tiene que dar exactamente la
// misma clave pública, y el ciphertext del JDK tiene que abrirse con exactamente el mismo
// secreto.
func TestKemVectorConocido(t *testing.T) {
	kat := leerKat(t)
	seed := debeHex(t, kat["seed"])

	// 1) Generación determinista: semilla -> pública. Detecta una implementación cambiada, un
	// conjunto de parámetros equivocado (768 vs 512) o un puente que devuelve basura.
	par, err := KemKeyPair() // primero, que la forma del par sea la esperada
	if err != nil {
		t.Fatalf("KemKeyPair: %v", err)
	}
	if len(par) != kemSeedBytes+kemPublicKeyBytes {
		t.Fatalf("par de %d bytes, se esperaban %d", len(par), kemSeedBytes+kemPublicKeyBytes)
	}

	pub, err := kemPublicaDesdeSemilla(seed)
	if err != nil {
		t.Fatalf("reconstruir desde la semilla del vector: %v", err)
	}
	suma := sha256.Sum256(pub)
	if got := hex.EncodeToString(suma[:]); got != kat["ek_sha256"] {
		t.Fatalf("la semilla fija ya no da la misma pública:\n  esperado %s\n  obtenido %s\n"+
			"Si esto falla, ML-KEM no está haciendo lo que este proyecto cree.",
			kat["ek_sha256"], got)
	}

	// 2) Interoperabilidad: el ciphertext lo hizo el JDK contra esa misma pública.
	secreto, err := KemDecapsulate(seed, debeHex(t, kat["ct"]))
	if err != nil {
		t.Fatalf("KemDecapsulate del vector: %v", err)
	}
	if !bytes.Equal(secreto, debeHex(t, kat["ss"])) {
		t.Fatalf("el secreto no coincide con el que sacó el JDK:\n  esperado %s\n  obtenido %s\n"+
			"El formato del cable ha dejado de coincidir entre Go y el JDK.",
			kat["ss"], hex.EncodeToString(secreto))
	}
}

// kemPublicaDesdeSemilla reconstruye la pública de una semilla. Existe **solo para el vector**:
// en producción la pública sale de KemKeyPair y se guarda en el estado del ratchet, así que no
// hace falta exponer esto por el puente.
func kemPublicaDesdeSemilla(seed []byte) ([]byte, error) {
	dk, err := mlkem.NewDecapsulationKey768(seed)
	if err != nil {
		return nil, err
	}
	return dk.EncapsulationKey().Bytes(), nil
}

func TestKemIdaYVuelta(t *testing.T) {
	par, err := KemKeyPair()
	if err != nil {
		t.Fatalf("KemKeyPair: %v", err)
	}
	seed, pub := par[:kemSeedBytes], par[kemSeedBytes:]

	encaps, err := KemEncapsulate(pub)
	if err != nil {
		t.Fatalf("KemEncapsulate: %v", err)
	}
	if len(encaps) != kemSecretBytes+kemCiphertextBytes {
		t.Fatalf("encapsulado de %d bytes, se esperaban %d",
			len(encaps), kemSecretBytes+kemCiphertextBytes)
	}
	secreto, ct := encaps[:kemSecretBytes], encaps[kemSecretBytes:]

	vuelta, err := KemDecapsulate(seed, ct)
	if err != nil {
		t.Fatalf("KemDecapsulate: %v", err)
	}
	if !bytes.Equal(secreto, vuelta) {
		t.Fatal("el que encapsula y el que desencapsula no sacan el mismo secreto")
	}
}

// TestKemRechazoImplicito fija la trampa conceptual de ML-KEM, para que nadie escriba luego un
// `if err != nil` creyendo que así valida el ciphertext.
func TestKemRechazoImplicito(t *testing.T) {
	par, err := KemKeyPair()
	if err != nil {
		t.Fatalf("KemKeyPair: %v", err)
	}
	seed, pub := par[:kemSeedBytes], par[kemSeedBytes:]
	encaps, err := KemEncapsulate(pub)
	if err != nil {
		t.Fatalf("KemEncapsulate: %v", err)
	}
	secreto, ct := encaps[:kemSecretBytes], encaps[kemSecretBytes:]

	tocado := bytes.Clone(ct)
	tocado[0] ^= 1
	otro, err := KemDecapsulate(seed, tocado)
	if err != nil {
		t.Fatalf("un ciphertext manipulado NO debe dar error (rechazo implícito): %v", err)
	}
	if bytes.Equal(otro, secreto) {
		t.Fatal("un ciphertext manipulado ha dado el mismo secreto")
	}
}

func TestKemLongitudesInvalidas(t *testing.T) {
	if _, err := KemEncapsulate(make([]byte, 32)); err == nil {
		t.Error("una pública de 32 bytes debía rechazarse")
	}
	if _, err := KemDecapsulate(make([]byte, 32), make([]byte, kemCiphertextBytes)); err == nil {
		t.Error("una semilla de 32 bytes debía rechazarse")
	}
	if _, err := KemDecapsulate(make([]byte, kemSeedBytes), make([]byte, 10)); err == nil {
		t.Error("un ciphertext de 10 bytes debía rechazarse")
	}
}

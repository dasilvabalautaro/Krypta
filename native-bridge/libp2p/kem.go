package bridge

import (
	"crypto/mlkem"
	"fmt"
)

// Parte post-cuántica del ratchet: ML-KEM-768 (FIPS 203) desde la librería estándar de Go.
//
// Vive aquí y no en Kotlin por la misma razón que X25519, solo más marcada: **Android no trae
// ML-KEM a ninguna API**. En la JVM de los tests se usa el del JDK 25, que sí lo trae nativo, así
// que la lógica del protocolo se prueba sin dispositivo y esto queda como la implementación de
// dispositivo. Diseño en docs/DISENO-postcuantico.md.
//
// Los tamaños de FIPS 203 para ML-KEM-768, que son el presupuesto del diseño:
const (
	kemSeedBytes       = 64   // semilla (d ‖ z): es lo que se guarda como privada
	kemPublicKeyBytes  = 1184 // clave de encapsulado: va en la cabecera
	kemCiphertextBytes = 1088 // ciphertext de encapsulado: también
	kemSecretBytes     = 32   // secreto compartido
)

// KemKeyPair sortea un par ML-KEM-768 y lo devuelve como semilla(64) ‖ pública(1184).
//
// Concatenado, como RatchetKeyPair, porque gomobile no sabe cruzar un struct con dos slices.
// La **semilla** hace de privada: son 64 bytes en vez de los 2400 de la clave expandida, y
// `mlkem.NewDecapsulationKey768` la reconstruye entera (verificado). Que la privada tenga un
// formato distinto del que usa el JDK da igual: el estado del ratchet nunca sale del dispositivo
// que lo escribió. Lo que sí es formato de cable es la pública, y esa va cruda.
func KemKeyPair() ([]byte, error) {
	dk, err := mlkem.GenerateKey768()
	if err != nil {
		return nil, fmt.Errorf("generar par ML-KEM-768: %w", err)
	}
	seed := dk.Bytes()
	if len(seed) != kemSeedBytes {
		return nil, fmt.Errorf("semilla ML-KEM de %d bytes, se esperaban %d", len(seed), kemSeedBytes)
	}
	return append(seed, dk.EncapsulationKey().Bytes()...), nil
}

// KemEncapsulate sortea un secreto y lo encapsula contra publicKey (cruda, 1184 bytes).
// Devuelve secreto(32) ‖ ciphertext(1088).
func KemEncapsulate(publicKey []byte) ([]byte, error) {
	if len(publicKey) != kemPublicKeyBytes {
		return nil, fmt.Errorf("pública ML-KEM-768 de %d bytes, se esperaban %d",
			len(publicKey), kemPublicKeyBytes)
	}
	ek, err := mlkem.NewEncapsulationKey768(publicKey)
	if err != nil {
		return nil, fmt.Errorf("pública ML-KEM-768 inválida: %w", err)
	}
	secret, ciphertext := ek.Encapsulate()
	return append(secret, ciphertext...), nil
}

// KemDecapsulate recupera el secreto de ciphertext con la semilla de este par.
//
// Ojo con lo que NO hace: ML-KEM es de **rechazo implícito**, así que un ciphertext manipulado
// no da error, da un secreto *distinto*. Quien detecta el engaño es el AEAD que use ese secreto.
func KemDecapsulate(seed []byte, ciphertext []byte) ([]byte, error) {
	if len(seed) != kemSeedBytes {
		return nil, fmt.Errorf("semilla ML-KEM de %d bytes, se esperaban %d", len(seed), kemSeedBytes)
	}
	if len(ciphertext) != kemCiphertextBytes {
		return nil, fmt.Errorf("ciphertext ML-KEM-768 de %d bytes, se esperaban %d",
			len(ciphertext), kemCiphertextBytes)
	}
	dk, err := mlkem.NewDecapsulationKey768(seed)
	if err != nil {
		return nil, fmt.Errorf("semilla ML-KEM inválida: %w", err)
	}
	secret, err := dk.Decapsulate(ciphertext)
	if err != nil {
		return nil, fmt.Errorf("desencapsular ML-KEM-768: %w", err)
	}
	return secret, nil
}

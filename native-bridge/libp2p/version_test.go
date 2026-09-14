package bridge

import "testing"

// Sin -ldflags -X el puente no puede saber de qué commit sale, y tiene que decirlo en vez de
// inventar una versión: un número escrito a mano es exactamente lo que dejó de corresponder al
// binario (ver buildCommit). La inyección real la comprueba build-aar.sh, que falla si el commit
// no aparece dentro de la librería compilada.
func TestVersionSinInyeccionNoInventaNada(t *testing.T) {
	if got := Version(); got != "desconocido" {
		t.Fatalf("Version() = %q; sin -ldflags -X debe ser \"desconocido\"", got)
	}
}

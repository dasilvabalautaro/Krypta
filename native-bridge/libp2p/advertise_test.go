package bridge

import (
	"runtime"
	"testing"
	"time"
)

// TestAdvertiseIsSinglePass fija la corrección del hallazgo A-1 de la auditoría: publicar
// el rendezvous NO debe dejar nada corriendo por detrás.
//
// La versión anterior llamaba a dutil.Advertise, que lanza una goroutine de re-anuncio
// atada a la vida del nodo. Como el bucle WAN llama aquí una vez por contacto y por ciclo,
// cada llamada dejaba una goroutine viva para siempre — y con ella seguía publicando en la
// DHT el rendezvous de días ya pasados, rompiendo de hecho la rotación diaria.
//
// El test cuenta goroutines antes y después de N publicaciones: con el fallo, el número
// crece ~N; con la corrección, se queda plano.
func TestAdvertiseIsSinglePass(t *testing.T) {
	n, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode: %v", err)
	}
	defer n.Close()
	if err := n.StartDHT("", true); err != nil {
		t.Fatalf("StartDHT: %v", err)
	}

	const key = "krypta-test-advertise-leak"
	// Una primera llamada para que se asienten las goroutines transitorias de la DHT.
	n.Advertise(key)
	settle()
	before := runtime.NumGoroutine()

	const calls = 30
	for i := 0; i < calls; i++ {
		n.Advertise(key)
	}
	settle()
	after := runtime.NumGoroutine()

	// Margen amplio: solo se busca distinguir "plano" de "crece con cada llamada".
	if grown := after - before; grown > calls/3 {
		t.Fatalf("Advertise deja goroutines vivas: %d antes, %d después de %d llamadas (+%d)",
			before, after, calls, grown)
	}
}

// TestAdvertiseWithoutDhtIsNoop: antes de StartDHT (modo solo LAN, o en el arranque) no hay
// routing discovery. Publicar no debe entrar en pánico, solo no hacer nada.
func TestAdvertiseWithoutDhtIsNoop(t *testing.T) {
	n, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode: %v", err)
	}
	defer n.Close()
	n.Advertise("krypta-test-sin-dht") // no debe entrar en pánico
}

func settle() {
	for i := 0; i < 20; i++ {
		runtime.Gosched()
		time.Sleep(50 * time.Millisecond)
	}
}

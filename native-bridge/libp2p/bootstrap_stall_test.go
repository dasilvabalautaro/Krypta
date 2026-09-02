package bridge

import (
	"fmt"
	"net"
	"testing"
	"time"
)

// blackHole abre un listener TCP que acepta la conexión y **no responde nunca**: reproduce
// el caso que mató la entrega en vivo el 2 sep 2026 (un nodo que completa el TCP pero deja
// colgado el handshake de libp2p). Devuelve un multiaddr con una identidad válida.
func blackHole(t *testing.T) string {
	t.Helper()
	ghost, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode ghost: %v", err)
	}
	ghostID := ghost.PeerID()
	ghost.Close()

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			// Aceptar y no hablar: el dial se queda esperando el handshake.
			t.Cleanup(func() { c.Close() })
		}
	}()
	return fmt.Sprintf("/ip4/127.0.0.1/tcp/%d/p2p/%s", ln.Addr().(*net.TCPAddr).Port, ghostID)
}

// TestStartDHTNotBlockedByStalledBootstrap fija la propiedad que faltaba: un bootstrap que
// se cuelga NO puede retrasar el ciclo de entrega. Antes los dials iban en serie sobre el
// contexto de vida del nodo (sin plazo), así que un nodo colgado congelaba el wanLoop —y con
// él la retirada del buzón— indefinidamente. Ahora van en paralelo y StartDHT vuelve en
// cuanto UNO conecta.
func TestStartDHTNotBlockedByStalledBootstrap(t *testing.T) {
	live, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode live: %v", err)
	}
	defer live.Close()
	if err := live.StartDHT("", true); err != nil {
		t.Fatalf("live StartDHT: %v", err)
	}

	client, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode client: %v", err)
	}
	defer client.Close()

	// El colgado va PRIMERO en la lista: en serie habría bloqueado al vivo.
	list := blackHole(t) + "\n" + loopbackBootstrap(t, live)

	start := time.Now()
	if err := client.StartDHT(list, false); err != nil {
		t.Fatalf("StartDHT debería tener éxito por el nodo vivo, got: %v", err)
	}
	elapsed := time.Since(start)

	// Con el fallo original esto tardaba lo que el plazo del dial colgado (o más).
	if elapsed > 10*time.Second {
		t.Fatalf("StartDHT tardó %v: un bootstrap colgado sigue frenando el ciclo", elapsed)
	}
	t.Logf("OK: conectado en %v pese al bootstrap colgado", elapsed)
}

// TestStartDHTAllStalledRespectsTimeout comprueba que, si TODOS cuelgan, StartDHT no se queda
// esperando para siempre: vuelve con error dentro del plazo (antes, sin plazo propio, podía
// no volver nunca y dejaba el bucle WAN muerto).
func TestStartDHTAllStalledRespectsTimeout(t *testing.T) {
	client, err := NewNode()
	if err != nil {
		t.Fatalf("NewNode: %v", err)
	}
	defer client.Close()

	list := blackHole(t) + "\n" + blackHole(t)
	start := time.Now()
	err = client.StartDHT(list, false)
	elapsed := time.Since(start)

	if err == nil {
		t.Fatalf("con todos los bootstraps colgados StartDHT debe fallar")
	}
	if elapsed > BootstrapDialTimeout+15*time.Second {
		t.Fatalf("StartDHT tardó %v, por encima del plazo %v", elapsed, BootstrapDialTimeout)
	}
	t.Logf("OK: error tras %v (plazo %v)", elapsed, BootstrapDialTimeout)
}

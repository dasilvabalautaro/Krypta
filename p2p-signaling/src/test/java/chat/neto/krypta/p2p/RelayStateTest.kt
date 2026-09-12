package chat.neto.krypta.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La línea `relay:` del diagnóstico se escribe solo cuando cambia, así que de lo que devuelve
 * el puente hay que quedarse con lo estable: las conexiones por nodo y las WebSocket podadas
 * (`conn_prune.go`), no la hora de expiración de la reserva.
 */
class RelayStateTest {

    @Test
    fun `conserva las conexiones por nodo y descarta la hora de expiracion`() {
        assertEquals(
            "OK (alcanzable por circuit; 1 conn: tcp | 1 conn: tcp)",
            relayState("OK (2 addrs, exp 07:20:11, 1 conn: tcp) | OK (2 addrs, exp 07:20:12, 1 conn: tcp)"),
        )
        // Misma situación un ciclo después: la hora cambia, el estado no → no se vuelve a loguear.
        assertEquals(
            relayState("OK (2 addrs, exp 07:20:11, 1 conn: tcp) | OK (2 addrs, exp 07:20:12, 1 conn: tcp)"),
            relayState("OK (2 addrs, exp 07:23:11, 1 conn: tcp) | OK (2 addrs, exp 07:23:12, 1 conn: tcp)"),
        )
    }

    @Test
    fun `una segunda conexion por Caddy y una poda se ven`() {
        assertEquals(
            "OK (alcanzable por circuit; 2 conns: tcp+ws | 1 conn: tcp) · wss redundantes cerradas: 1",
            relayState("OK (2 addrs, exp 07:20:11, 2 conns: tcp+ws) | OK (2 addrs, exp 07:20:12, 1 conn: tcp) | wss redundantes cerradas: 1"),
        )
    }

    @Test
    fun `sin resumen de conexiones (puente anterior) queda como antes`() {
        assertEquals("OK (alcanzable por circuit)", relayState("OK (2 addrs, exp 07:20:11)"))
    }

    @Test
    fun `un fallo se pasa tal cual`() {
        assertEquals("connect relay: sin ruta", relayState("connect relay: sin ruta"))
    }
}

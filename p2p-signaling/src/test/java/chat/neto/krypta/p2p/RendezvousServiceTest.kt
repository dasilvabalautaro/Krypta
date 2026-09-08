package chat.neto.krypta.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RendezvousServiceTest {

    private val service = RendezvousService()
    private val secret = "shared-secret-abc".toByteArray()

    @Test
    fun `mismo secreto y dia produce el mismo rendezvous`() {
        val day = LocalDate.of(2026, 6, 25)
        assertTrue(
            service.rendezvousFor(secret, day)
                .contentEquals(service.rendezvousFor(secret, day))
        )
    }

    @Test
    fun `el rendezvous rota cada dia`() {
        val a = service.rendezvousFor(secret, LocalDate.of(2026, 6, 25))
        val b = service.rendezvousFor(secret, LocalDate.of(2026, 6, 26))
        assertFalse("debe rotar al cambiar de dia", a.contentEquals(b))
    }

    @Test
    fun `secretos distintos producen rendezvous distintos el mismo dia`() {
        val day = LocalDate.of(2026, 6, 25)
        val a = service.rendezvousFor("alice-bob".toByteArray(), day)
        val b = service.rendezvousFor("alice-carol".toByteArray(), day)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `la longitud del rendezvous es de 32 bytes`() {
        assertEquals(32, service.rendezvousFor(secret, LocalDate.of(2026, 6, 25)).size)
    }

    // --- Ventana de solape al cambiar de día (Fase 3 del plan; hallazgo A-8) ---

    private fun windowAt(iso: String) =
        service.rendezvousWindow(secret, java.time.Instant.parse(iso))

    @Test
    fun `a media tarde solo se usa la clave del dia`() {
        val keys = windowAt("2026-06-25T12:00:00Z")
        assertEquals(1, keys.size)
        assertTrue(keys[0].contentEquals(service.rendezvousFor(secret, LocalDate.of(2026, 6, 25))))
    }

    @Test
    fun `justo despues de medianoche se sigue atendiendo la clave de ayer`() {
        val keys = windowAt("2026-06-25T00:30:00Z")
        assertEquals(2, keys.size)
        assertTrue(keys[0].contentEquals(service.rendezvousFor(secret, LocalDate.of(2026, 6, 25))))
        assertTrue(
            "quien aún no ha rotado debe poder encontrarnos",
            keys[1].contentEquals(service.rendezvousFor(secret, LocalDate.of(2026, 6, 24))),
        )
    }

    @Test
    fun `justo antes de medianoche ya se atiende la clave de manana`() {
        val keys = windowAt("2026-06-25T23:30:00Z")
        assertEquals(2, keys.size)
        assertTrue(
            "quien tenga el reloj algo adelantado ya usa la de mañana",
            keys[1].contentEquals(service.rendezvousFor(secret, LocalDate.of(2026, 6, 26))),
        )
    }

    @Test
    fun `la ventana nunca pasa de dos claves`() {
        for (hour in 0..23) {
            val keys = windowAt(String.format("2026-06-25T%02d:00:00Z", hour))
            assertTrue("hora $hour: ${keys.size} claves", keys.size in 1..2)
        }
    }
}

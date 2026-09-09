package chat.neto.krypta.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MailboxLabelTest {

    private val secret = "secreto-compartido-de-la-pareja".toByteArray()
    private val ana = "12D3KooWAna"
    private val beto = "12D3KooWBeto"
    private val martes = Instant.parse("2026-09-09T10:00:00Z")

    @Test
    fun `los dos extremos calculan la misma etiqueta para el mismo sentido`() {
        // Ana deposita para Beto; Beto mira ese mismo buzón. Sin negociar nada.
        val deposita = MailboxLabel.outbox(secret, myPeerId = ana, theirPeerId = beto, at = martes)
        val recoge = MailboxLabel.inbox(secret, myPeerId = beto, theirPeerId = ana, at = martes)
        assertTrue("Beto debe poder recoger lo que Ana depositó", recoge.any { it.contentEquals(deposita) })
    }

    @Test
    fun `cada sentido tiene su etiqueta, para no recogerse el correo propio`() {
        val anaHaciaBeto = MailboxLabel.outbox(secret, ana, beto, martes)
        val betoHaciaAna = MailboxLabel.outbox(secret, beto, ana, martes)
        assertFalse("los dos sentidos no pueden compartir buzón", anaHaciaBeto.contentEquals(betoHaciaAna))

        // Y lo que Ana consulta es lo que Beto deposita, no lo suyo.
        val anaRecoge = MailboxLabel.inbox(secret, myPeerId = ana, theirPeerId = beto, at = martes)
        assertTrue(anaRecoge.any { it.contentEquals(betoHaciaAna) })
        assertFalse(anaRecoge.any { it.contentEquals(anaHaciaBeto) })
    }

    @Test
    fun `la etiqueta es estable durante los siete dias y cambia al pasarlos`() {
        // El corte no es "lunes a domingo" sino una rejilla fija de 7 días desde la época:
        // lo que importa es que ambos extremos caigan siempre en el mismo tramo, y eso se
        // cumple porque los dos hacen la misma cuenta. El test se apoya en el inicio real del
        // tramo para no depender de en qué día de la semana cae la frontera.
        val dia = java.time.LocalDate.ofInstant(martes, java.time.ZoneOffset.UTC).toEpochDay()
        val inicioDelTramo = java.time.LocalDate.ofEpochDay(dia - dia % 7)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant()

        val alPrincipio = MailboxLabel.outbox(secret, ana, beto, inicioDelTramo)
        val casiAlFinal = MailboxLabel.outbox(secret, ana, beto, inicioDelTramo.plusSeconds(6 * 24 * 3600 + 3600))
        assertTrue("no debe cambiar dentro del tramo", alPrincipio.contentEquals(casiAlFinal))

        val tramoSiguiente = MailboxLabel.outbox(secret, ana, beto, inicioDelTramo.plusSeconds(7 * 24 * 3600))
        assertFalse("debe rotar al pasar los siete días", alPrincipio.contentEquals(tramoSiguiente))
    }

    @Test
    fun `se consultan dos etiquetas, la semana en curso y la anterior`() {
        val ahora = MailboxLabel.inbox(secret, ana, beto, martes)
        assertEquals("la ventana cubre el TTL de 7 días con dos etiquetas", 2, ahora.size)

        // Lo depositado hace seis días debe seguir estando en una de las dos consultadas.
        val haceSeisDias = MailboxLabel.outbox(secret, beto, ana, martes.minusSeconds(6 * 24 * 3600))
        assertTrue(
            "un mensaje dentro del TTL no puede quedarse sin recoger",
            ahora.any { it.contentEquals(haceSeisDias) },
        )
    }

    @Test
    fun `sin el secreto no se puede calcular la etiqueta`() {
        val otro = MailboxLabel.outbox("otro-secreto".toByteArray(), ana, beto, martes)
        assertFalse(otro.contentEquals(MailboxLabel.outbox(secret, ana, beto, martes)))
    }

    @Test
    fun `la etiqueta mide 32 bytes y viaja en hexadecimal`() {
        val label = MailboxLabel.outbox(secret, ana, beto, martes)
        assertEquals(32, label.size)
        assertEquals(64, MailboxLabel.toHex(label).length)
        assertTrue(MailboxLabel.toHex(label).all { it in "0123456789abcdef" })
    }
}

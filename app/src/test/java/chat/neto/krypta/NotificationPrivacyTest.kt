package chat.neto.krypta

import org.junit.Assert.assertEquals
import org.junit.Test

/** O-4 de docs/AUDITORIA-2026-10-12.md: cuánto enseña un aviso, y que el bloqueo de la app manda. */
class NotificationPrivacyTest {

    @Test
    fun `sin bloqueo de la app se respeta lo elegido`() {
        for (mode in NotificationContent.entries) {
            assertEquals(mode, NotificationPrivacy.effective(mode, appLockEnabled = false))
        }
    }

    /** El bloqueo promete que nadie lee tus conversaciones sin desbloquear: la cortina tampoco. */
    @Test
    fun `con bloqueo de la app el texto nunca sale`() {
        assertEquals(
            NotificationContent.NAME_ONLY,
            NotificationPrivacy.effective(NotificationContent.NAME_AND_MESSAGE, appLockEnabled = true),
        )
        assertEquals(
            NotificationContent.NAME_ONLY,
            NotificationPrivacy.effective(NotificationContent.NAME_ONLY, appLockEnabled = true),
        )
        assertEquals(
            "quien eligió no enseñar nada no gana un nombre por activar el bloqueo",
            NotificationContent.NOTHING,
            NotificationPrivacy.effective(NotificationContent.NOTHING, appLockEnabled = true),
        )
    }

    @Test
    fun `una preferencia ausente o corrupta cae a la de siempre`() {
        assertEquals(NotificationContent.NAME_AND_MESSAGE, NotificationPrivacy.read(null))
        assertEquals(NotificationContent.NAME_AND_MESSAGE, NotificationPrivacy.read("otra-cosa"))
        assertEquals(NotificationContent.NOTHING, NotificationPrivacy.read("NOTHING"))
    }

    @Test
    fun `el conteo se escribe en castellano`() {
        assertEquals("Mensaje nuevo", KryptaNotifications.countText(1))
        assertEquals("3 mensajes nuevos", KryptaNotifications.countText(3))
    }
}

package chat.neto.krypta.p2p

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deriva el punto de encuentro (rendezvous) diario entre dos contactos a partir del
 * secreto compartido que ya comparten tras intercambiar claves públicas:
 *
 *     rendezvous = HKDF(shared_secret, info = "krypta-rdv:" + fecha)
 *
 * Propiedades (ver docs/PLAN-senalizacion-descentralizada.md, Riesgo 1 / Fase 3):
 *  - Rotativo por día → no correlacionable a largo plazo.
 *  - No enumerable sin el secreto → la DHT nunca ve identificadores reales.
 */
@Singleton
class RendezvousService @Inject constructor() {

    /** Clave de rendezvous (32 bytes) para [sharedSecret] en la [date] dada (UTC por defecto). */
    fun rendezvousFor(
        sharedSecret: ByteArray,
        date: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): ByteArray {
        val info = "krypta-rdv:$date".toByteArray(Charsets.UTF_8)
        return Hkdf.derive(ikm = sharedSecret, salt = ByteArray(0), info = info, length = 32)
    }

    /**
     * Claves **vigentes ahora mismo**: la del día en curso y, dentro de la ventana de solape
     * ([OVERLAP_HOURS] a cada lado de la medianoche UTC), también la del día contiguo.
     *
     * Es la "ventana de solape al cambiar de día" que pedía la Fase 3 del plan y que faltaba.
     * Sin ella, en el instante en que rota la fecha los dos móviles anuncian y buscan claves
     * distintas hasta que ambos completan un ciclo, así que dejan de encontrarse: basta un
     * reloj ligeramente desfasado, un móvil apagado a esa hora o el intervalo relajado de
     * 3 min del bucle WAN. Hasta ahora el agujero estaba **tapado por un fallo** —los
     * anuncios viejos nunca morían (ver A-1 en la auditoría)—, así que al arreglar aquello
     * había que abrir esta ventana en el mismo cambio.
     *
     * La ventana es deliberadamente estrecha: cada clave extra es una publicación más en la
     * DHT y otro punto de cita simultáneo, y la rotación diaria existe justo para que no se
     * acumulen.
     */
    fun rendezvousWindow(
        sharedSecret: ByteArray,
        at: Instant = Instant.now(),
    ): List<ByteArray> {
        val utc = at.atOffset(ZoneOffset.UTC)
        val today = utc.toLocalDate()
        val hour = utc.hour
        val neighbour = when {
            hour < OVERLAP_HOURS -> today.minusDays(1)          // acabamos de entrar en el día
            hour >= 24 - OVERLAP_HOURS -> today.plusDays(1)     // el otro puede haber rotado ya
            else -> null
        }
        return listOfNotNull(
            rendezvousFor(sharedSecret, today),
            neighbour?.let { rendezvousFor(sharedSecret, it) },
        )
    }

    private companion object {
        /** Horas a cada lado de la medianoche UTC en las que se usan dos claves. */
        const val OVERLAP_HOURS = 2
    }
}

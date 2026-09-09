package chat.neto.krypta.p2p

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Dirección **ciega** del buzón: en vez de depositar "para el PeerID de Lucía", se deposita
 * bajo una etiqueta derivada del secreto que solo comparten los dos. Es el mismo truco del
 * rendezvous, aplicado al buzón (ver [docs/DISENO-buzon-ciego.md]).
 *
 *     etiqueta = HKDF(secreto_compartido, "krypta-mbx:" ‖ semana ‖ ":" ‖ sentido)
 *
 * Lo que se gana es que **el grafo social deje de quedar escrito en el disco del nodo**: hoy el
 * nombre del directorio es el PeerID del destinatario y dentro va el del remitente, así que
 * quien se lleve ese disco obtiene quién habla con quién y a qué hora. Con etiquetas obtiene
 * números sin dueño. Ojo: el relay sigue viendo ambos extremos de una conexión en vivo, eso no
 * lo arregla esto.
 *
 * **Por sentido**: si la etiqueta fuera solo de la pareja, cada uno se retiraría su propio
 * correo. El sentido se fija ordenando los dos PeerID, que ambos conocen, así que los dos
 * calculan lo mismo sin negociar nada.
 *
 * **Semanal** (decisión del 9 sep 2026): rota, para que nada sea correlacionable a largo plazo,
 * pero no tan rápido como para que recibir salga caro — con el TTL de 7 días del buzón basta
 * con mirar la semana en curso y la anterior, o sea **dos etiquetas por contacto**. Con rotación
 * diaria habría que preguntar por siete.
 */
object MailboxLabel {

    /** Etiquetas que hay que consultar para recibir lo que [theirPeerId] haya dejado. */
    fun inbox(
        sharedSecret: ByteArray,
        myPeerId: String,
        theirPeerId: String,
        at: Instant = Instant.now(),
    ): List<ByteArray> = weeksInWindow(at).map { week ->
        derive(sharedSecret, senderPeerId = theirPeerId, recipientPeerId = myPeerId, week = week)
    }

    /** Etiqueta bajo la que depositar ahora un mensaje para [theirPeerId]. */
    fun outbox(
        sharedSecret: ByteArray,
        myPeerId: String,
        theirPeerId: String,
        at: Instant = Instant.now(),
    ): ByteArray = derive(
        sharedSecret,
        senderPeerId = myPeerId,
        recipientPeerId = theirPeerId,
        week = weekOf(at),
    )

    /**
     * Semanas vigentes: la actual y la anterior. El buzón guarda 7 días, así que un mensaje
     * depositado justo antes del cambio de semana sigue estando dentro del plazo cuando ya
     * corre la semana siguiente — sin la anterior, ese correo se quedaría sin recoger.
     */
    private fun weeksInWindow(at: Instant): List<Long> = weekOf(at).let { listOf(it, it - 1) }

    /**
     * Número de semana desde la época, en UTC. Se cuenta en días partidos por siete y no por
     * semana ISO a propósito: no depende de la configuración regional ni tiene rarezas en el
     * cambio de año, y ambos extremos obtienen el mismo número sin hablarlo. El corte, por
     * tanto, **no cae en lunes** sino en una rejilla fija desde la época; da igual dónde caiga
     * mientras los dos hagan la misma cuenta.
     */
    private fun weekOf(at: Instant): Long =
        LocalDate.ofInstant(at, ZoneOffset.UTC).toEpochDay() / ROTATION_DAYS

    private fun derive(
        sharedSecret: ByteArray,
        senderPeerId: String,
        recipientPeerId: String,
        week: Long,
    ): ByteArray {
        // Orden canónico de los dos PeerID → un sentido que ambos calculan igual.
        val direction = if (senderPeerId < recipientPeerId) 0 else 1
        val info = "krypta-mbx:$week:$direction".toByteArray(Charsets.UTF_8)
        return Hkdf.derive(ikm = sharedSecret, salt = ByteArray(0), info = info, length = LABEL_BYTES)
    }

    /** La etiqueta viaja en hexadecimal: es el nombre de un directorio en el nodo. */
    fun toHex(label: ByteArray): String = label.joinToString("") { "%02x".format(it) }

    private const val ROTATION_DAYS = 7L
    private const val LABEL_BYTES = 32
}

package chat.neto.krypta.p2p

import chat.neto.krypta.core.KeyExchange
import chat.neto.krypta.core.RatchetStore
import chat.neto.krypta.core.TransactionRunner
import chat.neto.krypta.core.model.Contact
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Une el [Ratchet] (que no guarda nada) con su almacén, y **es el sitio donde vive la garantía
 * de atomicidad** del §4.2 del diseño: descifrar consume la clave del mensaje, así que si el
 * estado quedara guardado y el mensaje no, la reentrega del buzón ya no se podría abrir y el
 * mensaje se perdería para siempre — la misma familia de fallo que costó las notas de voz del
 * 5 de julio.
 *
 * Por eso [receive] y [send] no devuelven el texto para que el llamante lo guarde por su cuenta:
 * **reciben el guardado como lambda** y lo ejecutan dentro de la misma transacción que el avance
 * del ratchet. Así no hay forma de escribir uno sin el otro sin darse cuenta.
 */
@Singleton
class RatchetSessions @Inject constructor(
    private val ratchet: Ratchet,
    private val store: RatchetStore,
    private val transactions: TransactionRunner,
    private val keyExchange: KeyExchange,
) {

    /** Resultado de [receive]: o se abrió, o era una reentrega que ya se procesó. */
    sealed interface Received<out T> {
        data class Opened<T>(val value: T) : Received<T>

        /**
         * Ciphertext ya visto. El buzón reentrega lo que no se acusa, y su clave ya está
         * borrada: hay que reconocerlo aquí y **acusarlo** en el nodo, no tratarlo como basura.
         */
        data object Duplicate : Received<Nothing>
    }

    /**
     * Cifra [plaintext] para [contact] y persiste con [persist] (que recibe el ciphertext ya
     * listo para la red) en la misma transacción que el avance del ratchet.
     *
     * [pad] rellena el texto en claro por tramos para que su tamaño no delate qué es (ver
     * [Padding]); al recibir no hace falta decirlo, porque lo dice la cabecera del mensaje.
     */
    suspend fun <T> send(
        contact: Contact,
        plaintext: ByteArray,
        pad: Boolean = false,
        persist: suspend (ciphertext: ByteArray) -> T,
    ): T {
        val sealed = ratchet.encrypt(stateFor(contact), plaintext, pad)
        return transactions.inTransaction {
            val value = persist(sealed.ciphertext)
            store.save(contact.id, sealed.state.encode())
            value
        }
    }

    /**
     * Descifra [wire] de [contact] y persiste con [persist] (que recibe el texto en claro) en la
     * misma transacción que el avance del ratchet. Lanza [RatchetException] si no se puede
     * abrir; en ese caso no se ha guardado nada y el mensaje se puede volver a intentar.
     */
    suspend fun <T> receive(
        contact: Contact,
        wire: ByteArray,
        persist: suspend (plaintext: ByteArray) -> T,
    ): Received<T> {
        val digest = digestOf(wire)
        if (store.seen(contact.id, digest)) return Received.Duplicate

        // Fuera de la transacción a propósito: descifrar es una función pura y puede costar un
        // X25519. Lo que no puede quedar a medias es lo de dentro.
        val opened = ratchet.decrypt(stateFor(contact), secretOf(contact), wire)
        return transactions.inTransaction {
            val value = persist(opened.plaintext)
            store.save(contact.id, opened.state.encode())
            store.markSeen(contact.id, digest, System.currentTimeMillis())
            Received.Opened(value)
        }
    }

    /** Olvida la sesión (al borrar el contacto). Volver a añadirlo arranca un linaje nuevo. */
    suspend fun forget(contact: Contact) = store.deleteSession(contact.id)

    /**
     * Estado guardado o, si no hay —o no se puede leer—, una sesión nueva en la época 0.
     *
     * Que un estado ilegible **no** sea un error fatal es la propiedad que Signal no puede
     * tener: la época 0 se deriva del secreto compartido, así que el otro extremo siempre sabe
     * abrir lo que se envíe desde ella, y como el linaje nuevo es la hora actual —mayor que el
     * suyo— lo adopta y la conversación se reengancha sola.
     */
    private suspend fun stateFor(contact: Contact): RatchetState {
        val stored = store.load(contact.id)
        if (stored != null) {
            runCatching { RatchetState.decode(stored) }.getOrNull()?.let { return it }
        }
        return ratchet.initial(secretOf(contact), keyExchange.localPeerId(), contact.peerId)
    }

    private fun secretOf(contact: Contact): ByteArray =
        requireNotNull(contact.sharedSecret) { "contacto ${contact.id} sin secreto compartido" }

    /** Huella del ciphertext tal cual llegó, que es lo único común entre una entrega y su copia. */
    private fun digestOf(wire: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(wire).joinToString("") { "%02x".format(it) }
}

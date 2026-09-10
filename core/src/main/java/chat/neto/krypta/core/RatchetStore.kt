package chat.neto.krypta.core

/**
 * Almacén del estado del ratchet de cada conversación y de los mensajes ya vistos.
 *
 * El estado viaja como **blob opaco**: la forma la conoce solo `:p2p-signaling` (que es donde
 * vive el ratchet), y así `:data` no depende de la criptografía para guardarla. Va dentro de la
 * base cifrada, que es lo que corresponde a un material que abre lo que está por llegar.
 *
 * Los **vistos** son la otra mitad del diseño y no un extra: el buzón reentrega lo que no se
 * acusa, y con ratchet la clave de un mensaje se borra al usarla, así que una reentrega legítima
 * ya no se puede descifrar. Hay que reconocerla **antes** de intentarlo, por huella del
 * ciphertext, o parecería basura (ver `docs/DISENO-ratchet.md` §4.2).
 */
interface RatchetStore {

    /** Estado guardado de esa conversación, o null si aún no hay (o se borró). */
    suspend fun load(conversationId: String): ByteArray?

    /** Guarda el estado. Debe ir en la misma transacción que el mensaje al que corresponde. */
    suspend fun save(conversationId: String, state: ByteArray)

    /** Olvida la sesión y sus huellas (al borrar el contacto). Reencontrarse arranca de cero. */
    suspend fun deleteSession(conversationId: String)

    /** ¿Se procesó ya este ciphertext? Se consulta **antes** de descifrar. */
    suspend fun seen(conversationId: String, digest: String): Boolean

    /** Apunta la huella como procesada, podando las más viejas de esa conversación. */
    suspend fun markSeen(conversationId: String, digest: String, at: Long)
}

/**
 * Ejecuta un bloque dentro de una transacción de la base. Existe para una sola garantía, la del
 * §4.2 del diseño del ratchet: **el avance del ratchet y la persistencia del mensaje se
 * confirman juntos o no se confirman**. Descifrar consume la clave del mensaje; si el estado
 * quedara guardado y el mensaje no, la reentrega del buzón ya no se podría abrir y el mensaje
 * se perdería para siempre.
 */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

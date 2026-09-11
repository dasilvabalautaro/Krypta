package chat.neto.krypta.data.repository

import androidx.room.withTransaction
import chat.neto.krypta.core.RatchetStore
import chat.neto.krypta.core.TransactionRunner
import chat.neto.krypta.data.KryptaDatabase
import chat.neto.krypta.data.dao.RatchetDao
import chat.neto.krypta.data.entity.RatchetSeenEntity
import chat.neto.krypta.data.entity.RatchetSessionEntity
import javax.inject.Inject

class RoomRatchetStore @Inject constructor(
    private val dao: RatchetDao,
) : RatchetStore {

    override suspend fun load(conversationId: String): ByteArray? =
        dao.findSession(conversationId)?.state

    override suspend fun save(conversationId: String, state: ByteArray) =
        dao.upsertSession(RatchetSessionEntity(conversationId, state, System.currentTimeMillis()))

    override suspend fun deleteSession(conversationId: String) {
        dao.deleteSession(conversationId)
        dao.deleteSeen(conversationId)
    }

    override suspend fun seen(conversationId: String, digest: String): Boolean =
        dao.countSeen(conversationId, digest) > 0

    /** Inserciones desde la última poda, por conversación (ver [PRUNE_EVERY]). */
    private val sincePrune = mutableMapOf<String, Int>()

    override suspend fun markSeen(conversationId: String, digest: String, at: Long) {
        dao.insertSeen(RatchetSeenEntity(conversationId, digest, at))
        // Podar en CADA mensaje recibido es demasiado caro: el DELETE lleva un
        // `ORDER BY seenAt DESC LIMIT 500` dentro, y se paga por mensaje. Una ráfaga de 600
        // (un archivo troceado) **mató el proceso** en el TECNO el 11 sep 2026 — o sea que
        // esto no era cosmética de test, lo pagaba el usuario. Se poda una de cada
        // [PRUNE_EVERY], que no afecta a la corrección: la ventana es "8 días o 500 últimas",
        // y unas pocas filas de más entre podas no cambian lo que se conserva.
        val n = (sincePrune[conversationId] ?: 0) + 1
        if (n < PRUNE_EVERY) {
            sincePrune[conversationId] = n
            return
        }
        sincePrune[conversationId] = 0
        dao.pruneSeen(conversationId, at - SEEN_RETENTION_MS, SEEN_PER_CONVERSATION)
    }

    private companion object {
        /**
         * Huellas que se conservan por conversación **por cantidad**. Cubren lo que el buzón
         * puede reentregar (su cupo por destinatario son 200 sobres) más el margen de una
         * ráfaga de trozos de archivo.
         */
        const val SEEN_PER_CONVERSATION = 500

        /**
         * Y **por tiempo**: 8 días, un margen sobre el TTL de 7 del buzón, que es lo que de
         * verdad acota la reentrega legítima.
         *
         * Esto no es solo higiene de disco: esta deduplicación es lo único que impide
         * **reproducir** un mensaje de la época 0, porque esa época se re-deriva del secreto
         * compartido y el ratchet la vuelve a abrir (lo encontró `RatchetPropertyTest`, ver
         * `docs/DISENO-ratchet.md` §1.9). Con la poda solo por cantidad, una pareja muy
         * activa dejaba de estar protegida en medio día; con la unión de las dos reglas, la
         * ventana es la del buzón para todo el mundo.
         */
        const val SEEN_RETENTION_MS = 8L * 24 * 60 * 60 * 1000

        /**
         * Cada cuántas inserciones se poda. Con 64, una ráfaga de 600 trozos hace 9 podas en
         * vez de 600, y la tabla nunca crece más de 64 filas por encima del tope.
         */
        const val PRUNE_EVERY = 64
    }
}

/**
 * [TransactionRunner] sobre Room. `withTransaction` es lo que hace que guardar el mensaje y
 * guardar el estado del ratchet sean una sola cosa: si el bloque lanza —o el proceso muere— no
 * se confirma ninguno de los dos, la reentrega del buzón vuelve a llegar y se descifra otra vez
 * con el estado de antes.
 */
class RoomTransactionRunner @Inject constructor(
    private val database: KryptaDatabase,
) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.withTransaction { block() }
}

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

    override suspend fun markSeen(conversationId: String, digest: String, at: Long) {
        dao.insertSeen(RatchetSeenEntity(conversationId, digest, at))
        dao.pruneSeen(conversationId, SEEN_PER_CONVERSATION)
    }

    private companion object {
        /**
         * Huellas que se conservan por conversación. Solo tienen que cubrir lo que el buzón
         * puede reentregar (su cupo por destinatario son 200 sobres) más el margen de una
         * ráfaga de trozos de archivo; pasado eso, un duplicado tan viejo ya no llega.
         */
        const val SEEN_PER_CONVERSATION = 500
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

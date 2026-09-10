package chat.neto.krypta.p2p

import chat.neto.krypta.core.KeyExchange
import chat.neto.krypta.core.RatchetStore
import chat.neto.krypta.core.TransactionRunner

/** Almacén de ratchet en memoria, con la misma semántica que el de Room. */
class FakeRatchetStore : RatchetStore {
    val sessions = mutableMapOf<String, ByteArray>()
    val seen = mutableSetOf<Pair<String, String>>()

    override suspend fun load(conversationId: String) = sessions[conversationId]
    override suspend fun save(conversationId: String, state: ByteArray) {
        sessions[conversationId] = state
    }

    override suspend fun deleteSession(conversationId: String) {
        sessions.remove(conversationId)
        seen.removeAll { it.first == conversationId }
    }

    override suspend fun seen(conversationId: String, digest: String) =
        (conversationId to digest) in seen

    override suspend fun markSeen(conversationId: String, digest: String, at: Long) {
        seen += conversationId to digest
    }
}

/**
 * Transacción de mentira con la propiedad que importa: si el bloque lanza, **deshace lo
 * escrito**. Sin eso, los tests del rollback pasarían por accidente.
 */
class RollbackTransactionRunner(private val store: FakeRatchetStore) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        val sessions = store.sessions.toMap()
        val seen = store.seen.toSet()
        return runCatching { block() }.getOrElse {
            store.sessions.clear(); store.sessions.putAll(sessions)
            store.seen.clear(); store.seen.addAll(seen)
            throw it
        }
    }
}

/** [RatchetSessions] listo para un test, con X25519 del JDK y almacén en memoria. */
fun testSessions(
    keyExchange: KeyExchange,
    store: FakeRatchetStore = FakeRatchetStore(),
): RatchetSessions = RatchetSessions(
    ratchet = Ratchet(JdkCurve25519()),
    store = store,
    transactions = RollbackTransactionRunner(store),
    keyExchange = keyExchange,
)

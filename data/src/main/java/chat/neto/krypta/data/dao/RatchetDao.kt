package chat.neto.krypta.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import chat.neto.krypta.data.entity.RatchetSeenEntity
import chat.neto.krypta.data.entity.RatchetSessionEntity

@Dao
interface RatchetDao {

    @Query("SELECT * FROM ratchet_sessions WHERE conversationId = :conversationId")
    suspend fun findSession(conversationId: String): RatchetSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: RatchetSessionEntity)

    @Query("DELETE FROM ratchet_sessions WHERE conversationId = :conversationId")
    suspend fun deleteSession(conversationId: String)

    @Query("SELECT COUNT(*) FROM ratchet_seen WHERE conversationId = :conversationId AND digest = :digest")
    suspend fun countSeen(conversationId: String, digest: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeen(seen: RatchetSeenEntity)

    /**
     * Poda: borra una huella solo si es **a la vez** más vieja que [cutoff] y está fuera de las
     * [keep] más recientes. O sea que se conserva lo reciente en el tiempo **o** lo reciente en
     * cantidad, la unión de las dos.
     *
     * Por qué las dos y no una (10 sep 2026): solo por cantidad se comporta al revés de lo que
     * hace falta — en una conversación muy activa 500 mensajes pueden ser medio día, y en una
     * tranquila pueden ser meses. Lo que acota la reentrega legítima es el TTL del buzón. Y solo
     * por tiempo, una ráfaga larga podría dejar fuera huellas que el buzón aún puede reentregar.
     */
    @Query(PRUNE_SEEN_SQL)
    suspend fun pruneSeen(conversationId: String, cutoff: Long, keep: Int)

    companion object {
        /**
         * El SQL de la poda, en una constante para que el test de la JVM
         * (`RatchetSeenPruneSqlTest`) ejecute **la misma cadena** que se envía, y no una copia
         * que pueda divergir sin que nadie se entere.
         */
        const val PRUNE_SEEN_SQL =
            "DELETE FROM ratchet_seen WHERE conversationId = :conversationId AND seenAt < :cutoff " +
                "AND digest NOT IN " +
                "(SELECT digest FROM ratchet_seen WHERE conversationId = :conversationId " +
                "ORDER BY seenAt DESC LIMIT :keep)"
    }

    @Query("DELETE FROM ratchet_seen WHERE conversationId = :conversationId")
    suspend fun deleteSeen(conversationId: String)
}

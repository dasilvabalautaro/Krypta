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

    /** Poda: deja solo las [keep] huellas más recientes de la conversación. */
    @Query(
        "DELETE FROM ratchet_seen WHERE conversationId = :conversationId AND digest NOT IN " +
            "(SELECT digest FROM ratchet_seen WHERE conversationId = :conversationId " +
            "ORDER BY seenAt DESC LIMIT :keep)"
    )
    suspend fun pruneSeen(conversationId: String, keep: Int)

    @Query("DELETE FROM ratchet_seen WHERE conversationId = :conversationId")
    suspend fun deleteSeen(conversationId: String)
}

package chat.neto.krypta.data.dao

import androidx.room.Dao
import androidx.room.Query
import chat.neto.krypta.data.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {

    @Query("SELECT * FROM contacts ORDER BY displayName")
    fun observeAll(): Flow<List<ContactEntity>>

    /**
     * Alta o sustitución del contacto **sin bajar nunca `peerProtocol`** (el contrato está en
     * `ContactRepository.upsert`).
     *
     * Sustituye al `@Insert(onConflict = REPLACE)` de antes, que escribía la fila tal como
     * llegara: verificar o bloquear desde una copia leída antes de que llegara el anuncio del
     * contacto lo devolvía a v1, y como el anuncio sale una sola vez por versión, para siempre
     * (hallazgo H-2 de `docs/REVISION-protocolo-2026-09-14.md`). Va en una sola sentencia para
     * que no haya ventana entre leer la versión guardada y escribir la nueva.
     */
    @Query(UPSERT_SQL)
    suspend fun upsert(
        id: String,
        displayName: String,
        peerId: String,
        publicKey: ByteArray,
        verified: Boolean,
        blocked: Boolean,
        peerProtocol: Int,
        announcedProtocol: Int,
    )

    /** Sube `peerProtocol` si [protocol] es mayor; no toca nada más. */
    @Query(RAISE_PEER_PROTOCOL_SQL)
    suspend fun raisePeerProtocol(id: String, protocol: Int)

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun findById(id: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE peerId = :peerId LIMIT 1")
    suspend fun findByPeerId(peerId: String): ContactEntity?

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun delete(id: String)

    companion object {
        /**
         * El SQL del alta, en una constante para que el test de la JVM (`ContactUpsertSqlTest`)
         * ejecute **la misma cadena** que se envía (el patrón de `RatchetDao.PRUNE_SEEN_SQL`).
         * La subconsulta se evalúa antes de que `REPLACE` borre la fila vieja, así que lee la
         * versión que había.
         */
        const val UPSERT_SQL =
            "INSERT OR REPLACE INTO contacts " +
                "(id, displayName, peerId, publicKey, verified, blocked, peerProtocol, announcedProtocol) " +
                "VALUES (:id, :displayName, :peerId, :publicKey, :verified, :blocked, " +
                "MAX(:peerProtocol, IFNULL((SELECT peerProtocol FROM contacts WHERE id = :id), 0)), " +
                ":announcedProtocol)"

        const val RAISE_PEER_PROTOCOL_SQL =
            "UPDATE contacts SET peerProtocol = MAX(peerProtocol, :protocol) WHERE id = :id"
    }
}

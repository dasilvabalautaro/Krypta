package chat.neto.krypta.core.repository

import chat.neto.krypta.core.model.Message
import chat.neto.krypta.core.model.MessageStatus
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    /** La conversación **entera**. Para pintar el chat, [observeRecent]. */
    fun observeConversation(conversationId: String): Flow<List<Message>>

    /**
     * Los [limit] mensajes más recientes de la conversación, en orden cronológico. Es lo que pinta
     * el chat: cargarla entera, con las fotos en línea, en cada cambio de la tabla hacía que el
     * coste de abrir un chat creciera con su historia (O-9 de docs/AUDITORIA-2026-10-12.md).
     */
    fun observeRecent(conversationId: String, limit: Int): Flow<List<Message>>

    /**
     * Cuántos mensajes hay desde [id] (incluido) hasta el último: el tamaño de ventana que hace
     * falta para verlo (saltar a una cita antigua). 0 si [id] no es de esa conversación.
     */
    suspend fun countSince(conversationId: String, id: String): Int

    /** Último mensaje de cada conversación (para la lista de conversaciones). */
    fun observeLastMessages(): Flow<List<Message>>

    /** Nº de mensajes entrantes aún no vistos, por conversationId (badge de no leídos). */
    fun observeUnreadCounts(): Flow<Map<String, Int>>

    suspend fun save(message: Message)

    /** Devuelve un mensaje por su id, o null (p. ej. para reintentar uno FALLIDO). */
    suspend fun findById(id: String): Message?

    /**
     * Hasta [limit] mensajes en un estado dado, del más reciente al más antiguo. Lo usa la
     * reconciliación del bucle WAN para reintentar los FALLIDOS al recuperar la conexión.
     */
    suspend fun findByStatus(status: MessageStatus, limit: Int): List<Message>

    /**
     * Hasta [limit] mensajes que aún se guardan cifrados con la clave estática (`encrypted`),
     * saltando los [offset] primeros, para convertirlos a sobre en claro dentro de la base
     * cifrada. El desplazamiento existe para poder **dejar atrás** los que no se puedan
     * convertir; sin él, uno solo bloquearía el resto del historial. Ver [Message].
     */
    suspend fun findEncrypted(limit: Int, offset: Int = 0): List<Message>

    /** Guarda varios de una vez, en una sola transacción (la conversión del historial). */
    suspend fun saveAll(messages: List<Message>)

    suspend fun updateStatus(id: String, status: MessageStatus)

    /**
     * Marca como vistos (READ local) los mensajes **entrantes** de una conversación, al
     * abrir su chat: limpia el badge de no leídos. En los salientes READ significa "el
     * otro lo leyó"; en los entrantes se reutiliza como "yo lo vi" (sin cambio de esquema).
     *
     * Devuelve los ids que **acaba** de marcar (los que estaban sin leer), leídos y marcados
     * en una sola transacción: son exactamente los que hay que acusar al otro extremo. Antes
     * el acuse se calculaba sobre el historial entero (O-2 de docs/AUDITORIA-2026-10-12.md).
     */
    suspend fun markIncomingRead(conversationId: String): List<String>

    /**
     * Pone en READ los mensajes de [ids] que sean de [conversationId] y los haya enviado
     * [senderId] (el marcador de "propio"): lo que el otro extremo acusa haber leído. Los ids
     * los elige quien acusa, así que se exige la conversación (auditoría A-9). Una sentencia
     * por lote, no una consulta por id.
     */
    suspend fun markOutgoingRead(conversationId: String, senderId: String, ids: List<String>)

    /** Borra TODOS los mensajes de una conversación (vaciar chat / eliminar contacto). */
    suspend fun deleteConversation(conversationId: String)
}

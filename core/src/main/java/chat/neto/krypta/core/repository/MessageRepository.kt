package chat.neto.krypta.core.repository

import chat.neto.krypta.core.model.Message
import chat.neto.krypta.core.model.MessageStatus
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun observeConversation(conversationId: String): Flow<List<Message>>

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
     */
    suspend fun markIncomingRead(conversationId: String)

    /** Borra TODOS los mensajes de una conversación (vaciar chat / eliminar contacto). */
    suspend fun deleteConversation(conversationId: String)
}

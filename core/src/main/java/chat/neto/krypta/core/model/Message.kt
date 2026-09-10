package chat.neto.krypta.core.model

/**
 * Mensaje persistido. [payload] son los bytes del **sobre** (`MessageEnvelope`) del mensaje.
 *
 * Hasta la v7 de la base aquí se guardaba el ciphertext **de la red** y cada lectura lo
 * descifraba al vuelo con la clave estática del contacto. Con el ratchet eso deja de ser
 * posible: la clave de cada mensaje **se borra al usarla**, así que un ciphertext guardado no se
 * podría abrir un minuto después — ni el entrante ni el propio. El secreto hacia adelante obliga
 * a separar la clave de transporte de la de reposo, y lo que protege el historial pasa a ser el
 * cifrado de la base (SQLCipher, clave envuelta en el Keystore).
 *
 * [encrypted] marca las filas que **todavía** son de antes: su [payload] es ciphertext bajo la
 * clave estática y hay que descifrarlo al leerlo. Se convierten solas en segundo plano; mientras
 * tanto, se leen igual. Los mensajes escritos desde la v8 nacen ya con `encrypted = false`.
 */
data class Message(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val payload: ByteArray,
    val timestamp: Long,
    val status: MessageStatus,
    val encrypted: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Message) return false
        return id == other.id &&
            conversationId == other.conversationId &&
            senderId == other.senderId &&
            payload.contentEquals(other.payload) &&
            timestamp == other.timestamp &&
            status == other.status &&
            encrypted == other.encrypted
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + conversationId.hashCode()
        result = 31 * result + senderId.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + encrypted.hashCode()
        return result
    }
}

package chat.neto.krypta.p2p

import chat.neto.krypta.core.ISignalingService
import chat.neto.krypta.core.KeyExchange
import chat.neto.krypta.core.MessageCipher
import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.core.model.Message
import chat.neto.krypta.core.model.MessageStatus
import chat.neto.krypta.core.repository.MessageRepository
import chat.neto.krypta.p2p.ChatService.Companion.BLIND_DEPOSIT
import chat.neto.krypta.p2p.ChatService.Companion.BLIND_MIN_PROTOCOL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * **Salida** de [ChatService]: cifrar con la forma que hable cada contacto (v1 o ratchet), guardar
 * el mensaje junto con el avance del ratchet, y entregar directo o, si no hay ruta, al buzón.
 *
 * Todo lo que sale pasa por aquí (textos, fotos, trozos de archivo, señales de llamada, acuses y
 * anuncios), así que "¿con qué se cifra para este contacto?" es una sola decisión y no siete. Se
 * separó de `ChatService` el 12 oct 2026 (§5 de docs/AUDITORIA-2026-10-12.md) sin cambiar nada de
 * lo que hace.
 */
internal class Outbox(
    private val signaling: ISignalingService,
    private val cipher: MessageCipher,
    private val messages: MessageRepository,
    private val keyExchange: KeyExchange,
    private val sessions: RatchetSessions,
    private val cpu: CpuContext,
    private val log: DiagnosticsLog,
) {
    private fun logLine(msg: String) = log.line(msg)

    /**
     * Salvaguarda de dominio: a un contacto bloqueado no se le envía **nada** (mensajes,
     * trozos de archivo o señales de llamada). La UI ya lo impide, pero el corte vive aquí
     * para que ningún camino de envío se lo salte.
     */
    fun requireNotBlocked(contact: Contact) {
        require(!contact.blocked) { "${contact.displayName} está bloqueado" }
    }

    /** Un envío ya cifrado: el mensaje persistido (si lo hay) y los bytes que van por la red. */
    class Outgoing(val message: Message, val wire: ByteArray)

    /**
     * Cifra [envelope] con la forma que hable [contact].
     *
     * Con ratchet, **el estado avanzado se guarda antes de que los bytes salgan**. El orden no
     * es un detalle: si se enviara primero y el estado no llegara a guardarse, el siguiente
     * mensaje reutilizaría la misma clave de mensaje —y con ella el mismo nonce de AES-GCM—,
     * que es la forma clásica de romper del todo un cifrado autenticado.
     */
    suspend fun seal(contact: Contact, envelope: ByteArray): ByteArray = withContext(cpu.context) {
        // En [cpu] (`Dispatchers.Default` en la app) y no en el hilo de quien llama: la UI
        // llama desde `viewModelScope` (el hilo principal) y aquí hay AES-GCM, HMAC y, al
        // avanzar de época, un X25519 por JNI — por trozo, en un archivo de 170 (O-8).
        if (!usesRatchet(contact)) {
            return@withContext cipher.encrypt(requireNotNull(contact.sharedSecret), envelope)
        }
        lateinit var wire: ByteArray
        sessions.send(contact, envelope, pads(contact), kdf4(contact)) { wire = it }
        wire
    }

    /**
     * Como [seal], pero además persiste el mensaje que construya [build] **en la misma
     * transacción** que el avance del ratchet: o se guardan los dos, o ninguno.
     */
    suspend fun sealAndPersist(
        contact: Contact,
        envelope: ByteArray,
        build: () -> Message,
    ): Outgoing = withContext(cpu.context) {
        // Fuera del hilo de quien llama, como [seal] (O-8).
        if (!usesRatchet(contact)) {
            val wire = cipher.encrypt(requireNotNull(contact.sharedSecret), envelope)
            val message = build()
            messages.save(message)
            return@withContext Outgoing(message, wire)
        }
        lateinit var wire: ByteArray
        val message = sessions.send(contact, envelope, pads(contact), kdf4(contact)) { ct ->
            wire = ct
            build().also { messages.save(it) }
        }
        Outgoing(message, wire)
    }

    /** Cifra y envía un sobre "en crudo" (meta/trozo) sin crear un Message: directo → buzón. */
    suspend fun sendRaw(contact: Contact, envelope: ByteArray) {
        requireNotBlocked(contact)
        deliver(contact, seal(contact, envelope))
    }

    /**
     * Como [sendRaw] pero **siempre con la clave estática**, hable o no ratchet el contacto.
     * Solo para anuncios de capacidad dirigidos a quien puede haber perdido la sesión, porque es
     * lo único que seguro puede abrir; nunca lleva contenido del usuario.
     */
    suspend fun sendStatic(contact: Contact, envelope: ByteArray) {
        requireNotBlocked(contact)
        deliver(contact, cipher.encrypt(requireNotNull(contact.sharedSecret), envelope))
    }

    /** Directo y, si no hay ruta, al buzón; si también falla, propaga. */
    suspend fun deliver(contact: Contact, ciphertext: ByteArray) {
        try {
            signaling.send(contact, ciphertext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            signaling.sendOffline(contact, ciphertext, outboxLabel(contact))
        }
    }

    /**
     * Etiqueta bajo la que depositar para [contact], o cadena vacía para usar el camino
     * antiguo (direccionado por PeerID).
     *
     * Depositar a ciegas solo sirve si **el destinatario** retira por etiquetas: un cliente que
     * aún no lo haga jamás miraría ese buzón y el mensaje se quedaría ahí hasta caducar. Que el
     * *nodo* hable v2 no basta. Por eso la decisión es **por contacto**, como la del ratchet:
     * la retirada por etiquetas entró en el cliente antes que el anuncio de capacidad, así que
     * quien haya anunciado [BLIND_MIN_PROTOCOL] o más ya sabe recibir a ciegas. Con el resto
     * se sigue depositando por PeerID, que es lo que el nodo escribe en disco.
     */
    fun outboxLabel(contact: Contact): String {
        if (!BLIND_DEPOSIT || contact.peerProtocol < BLIND_MIN_PROTOCOL) return ""
        val secret = contact.sharedSecret ?: return ""
        val me = runCatching { keyExchange.localPeerId() }.getOrNull() ?: return ""
        return MailboxLabel.toHex(MailboxLabel.outbox(secret, me, contact.peerId))
    }

    /** Transmite un mensaje ya persistido: directo → buzón (offline) → FAILED. Nunca lanza. */
    suspend fun transmit(contact: Contact, message: Message, ciphertext: ByteArray): Message =
        try {
            signaling.send(contact, ciphertext)
            messages.updateStatus(message.id, MessageStatus.SENT)
            logLine("→ enviado a ${short(contact.peerId)}")
            message.copy(status = MessageStatus.SENT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sendViaMailbox(contact, message, ciphertext, e)
        }

    /** Fallback offline: deposita el ciphertext en el buzón del nodo → SENT, o FAILED. */
    private suspend fun sendViaMailbox(
        contact: Contact,
        message: Message,
        ciphertext: ByteArray,
        directError: Exception,
    ): Message = try {
        signaling.sendOffline(contact, ciphertext, outboxLabel(contact))
        messages.updateStatus(message.id, MessageStatus.SENT)
        logLine("→ buzón para ${short(contact.peerId)} (offline)")
        message.copy(status = MessageStatus.SENT)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        messages.updateStatus(message.id, MessageStatus.FAILED)
        logLine(
            "✗ no enviado a ${short(contact.peerId)}: ${sendErrorReason(directError)}; " +
                "buzón: ${sendErrorReason(e)}"
        )
        message.copy(status = MessageStatus.FAILED)
    }

    /** Traduce el error de libp2p a algo legible en el panel de diagnóstico. */
    fun sendErrorReason(e: Throwable): String {
        val msg = e.message ?: e.toString()
        return when {
            "no addresses" in msg || "all dials failed" in msg || "failed to dial" in msg ->
                "sin ruta al contacto (NAT/relay pendiente)"
            else -> msg.take(120)
        }
    }

    /**
     * Bytes listos para la red de un mensaje ya persistido. Una fila de la v8 en adelante
     * guarda el sobre en claro y se cifra aquí; una anterior guarda ya el ciphertext de la
     * clave estática y se reenvía tal cual, que es exactamente lo que se hacía antes.
     */
    suspend fun wireBytes(contact: Contact, message: Message): ByteArray =
        if (message.encrypted) message.payload else seal(contact, message.payload)

    /** El sobre en claro de un mensaje persistido, descifrando solo si es una fila antigua. */
    fun envelopeOf(contact: Contact, message: Message): ByteArray =
        if (message.encrypted) cipher.decrypt(requireNotNull(contact.sharedSecret), message.payload)
        else message.payload
}

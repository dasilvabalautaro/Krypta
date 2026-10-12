package chat.neto.krypta.p2p

import chat.neto.krypta.core.FileStore
import chat.neto.krypta.core.KeyExchange
import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.core.model.Message
import chat.neto.krypta.core.model.MessageContent
import chat.neto.krypta.core.repository.ContactRepository
import chat.neto.krypta.core.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Lo que [ContactService] necesita de [ChatService] al cambiar un contacto. */
internal interface ContactHooks {
    /** Rehace la lista de quién puede abrirnos conexión (altas, bajas y bloqueos). */
    suspend fun refreshAllowedPeers()

    /** Adelanta el ciclo WAN (para anunciarse cuanto antes a un contacto nuevo). */
    fun kickWan()

    /** Olvida que [peerId] estaba en línea: sin rendezvous con él, el punto verde se quedaría. */
    fun forgetPresence(peerId: String)

    /** El contenido de un mensaje guardado (para borrar los archivos de un chat). */
    fun content(contact: Contact, message: Message): MessageContent
}

/**
 * **Contactos**: alta, verificación, bloqueo, vaciar el chat y borrar. Todo local. Se separó de
 * `ChatService` el 12 oct 2026 (§5 de docs/AUDITORIA-2026-10-12.md), junto con el arreglo de O-3:
 * verificar y bloquear escriben solo su columna.
 */
internal class ContactService(
    private val contacts: ContactRepository,
    private val messages: MessageRepository,
    private val keyExchange: KeyExchange,
    private val fileStore: FileStore,
    private val sessions: RatchetSessions,
    private val log: DiagnosticsLog,
    private val hooks: ContactHooks,
) {
    private fun logLine(msg: String) = log.line(msg)

    fun observeContacts(): Flow<List<Contact>> = contacts.observeAll()

    /** PeerID de este dispositivo (para compartir con quien te quiera añadir). */
    fun myPeerId(): String = keyExchange.localPeerId()

    /** Contacto guardado con ese PeerID, si lo hay (el PeerID es la clave primaria). */
    suspend fun findContact(peerId: String): Contact? = contacts.findById(peerId)

    /**
     * Da de alta (o actualiza) un contacto. El secreto compartido se calcula por ECDH
     * (X25519) a partir de nuestra identidad y la clave pública embebida en su PeerID, así
     * que basta con el PeerID — sin passphrase ni claves que pegar. Si el PeerID cambia
     * respecto a uno ya guardado, la verificación previa deja de valer (se resetea).
     *
     * Añadirse a **uno mismo** se rechaza: el PeerID propio y el del contacto se copian y
     * pegan por el mismo canal, así que pegar el propio por error es fácil — y el resultado
     * era una conversación con uno mismo indistinguible de un contacto real (pasó en vivo el
     * 23 jul 2026: un contacto nuevo heredó el chat del auto-envío de pruebas porque el
     * PeerID pegado era el del propio teléfono, y los mensajes nunca llegaban a nadie).
     */
    suspend fun addContact(displayName: String, peerId: String): Contact {
        require(peerId != keyExchange.localPeerId()) {
            "Ese es tu propio PeerID: pide a tu contacto el suyo"
        }
        val existing = contacts.findById(peerId)
        val contact = Contact(
            id = peerId, // 1:1: el PeerID identifica la conversación
            displayName = displayName,
            peerId = peerId,
            publicKey = ByteArray(0),
            sharedSecret = keyExchange.sharedSecretWith(peerId),
            verified = existing?.verified ?: false,
            // Volver a dar de alta un PeerID que ya estaba (renombrar) no es empezar de cero: ni
            // desbloquea a quien se bloqueó (H-6) ni olvida qué versión anunció o qué se le
            // anunció (H-2, docs/REVISION-protocolo-2026-09-14.md). Solo borrar el contacto lo hace.
            blocked = existing?.blocked ?: false,
            peerProtocol = existing?.peerProtocol ?: 0,
            announcedProtocol = existing?.announcedProtocol ?: 0,
        )
        contacts.upsert(contact)
        // Para que pueda marcarnos ya, sin esperar al próximo ciclo WAN.
        hooks.refreshAllowedPeers()
        // Y para anunciarnos cuanto antes: si es alguien a quien borramos y volvemos a añadir, lo
        // que nos escriba se pierde hasta que ese anuncio salga y nos conteste (H-1).
        hooks.kickWan()
        return contact
    }

    /**
     * Número de seguridad anti-MITM con [contact] (estilo Signal): hash simétrico de ambos
     * PeerIDs. Los dos contactos deben ver el mismo; cotejarlo fuera de banda descarta que
     * alguien haya sustituido un PeerID en el canal por el que se compartió. Ver [SafetyNumber].
     */
    fun safetyNumber(contact: Contact): String =
        SafetyNumber.compute(keyExchange.localPeerId(), contact.peerId)

    /** Marca (o desmarca) [contact] como verificado tras cotejar el número de seguridad. */
    suspend fun setVerified(contact: Contact, verified: Boolean) {
        contacts.setVerified(contact.id, verified)
    }

    /**
     * Bloquea (o desbloquea) a [contact]. Todo local y sin cambio de protocolo: se deja de
     * anunciar su rendezvous ([announceAndFind]), lo que llegue de él se descarta sin
     * persistir ni avisar ([onReceived]) y no se le envía nada ([requireNotBlocked]). El
     * bloqueado **no recibe ninguna señal**: sus mensajes le quedan como enviados, igual que
     * si este dispositivo estuviera apagado. El chat y el contacto se conservan — para
     * borrarlos están [clearConversation] y [deleteContact].
     */
    suspend fun setBlocked(contact: Contact, blocked: Boolean) {
        contacts.setBlocked(contact.id, blocked)
        // Un bloqueado sale de la lista en el acto: deja de poder abrirnos conexión.
        hooks.refreshAllowedPeers()
        if (blocked) {
            // Sin rendezvous ya no se le va a ver: que el punto verde no se quede pegado.
            hooks.forgetPresence(contact.peerId)
        }
        val what = if (blocked) "🚫 contacto bloqueado" else "contacto desbloqueado"
        logLine("$what: ${short(contact.peerId)}")
    }

    /**
     * Vacía el chat de [contact] **solo en este dispositivo**: borra sus mensajes de Room y
     * los archivos locales asociados (adjuntos recibidos, copias de notas de voz enviadas).
     * El contacto se conserva. Sin cambio de protocolo: al otro lado no le afecta.
     */
    suspend fun clearConversation(contact: Contact) {
        val all = runCatching { messages.observeConversation(contact.id).first() }
            .getOrDefault(emptyList())
        for (m in all) {
            val c = runCatching { hooks.content(contact, m) }.getOrNull()
            if (c is MessageContent.File) {
                runCatching { fileStore.deleteLocal(m.id, c.localPath) }
            }
        }
        messages.deleteConversation(contact.id)
        logLine("🗑 chat vaciado (${all.size} mensaje(s)) de ${short(contact.peerId)}")
    }

    /**
     * Elimina [contact] y todo su chat (mensajes + archivos) de este dispositivo. El bucle
     * WAN relee los contactos de Room en cada ciclo ([announceAndFind]), así que el
     * rendezvous del contacto cesa solo, sin reiniciar el host. Re-añadirlo por PeerID
     * vuelve a derivar el mismo secreto (la verificación habrá que repetirla).
     */
    suspend fun deleteContact(contact: Contact) {
        clearConversation(contact)
        // La sesión del ratchet se va con el contacto: volver a añadirlo arranca un linaje
        // nuevo, y quedársela sería guardar material de una conversación que ya no existe.
        // Vaciar el chat, en cambio, NO la toca: vaciar no es romper la sesión.
        runCatching { sessions.forget(contact) }
        contacts.delete(contact.id)
        hooks.refreshAllowedPeers()
        hooks.forgetPresence(contact.peerId)
        logLine("🗑 contacto eliminado: ${short(contact.peerId)}")
    }
}

package chat.neto.krypta.p2p

import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.p2p.ChatService.Companion.KDF4_MIN_PROTOCOL
import chat.neto.krypta.p2p.ChatService.Companion.PADDING_MIN_PROTOCOL
import chat.neto.krypta.p2p.ChatService.Companion.RATCHET_MIN_PROTOCOL
import chat.neto.krypta.p2p.ChatService.Companion.RATCHET_SEND

// Qué forma de cifrado lleva cada contacto. Son funciones puras de lo que el contacto anunció, y
// las usan el envío ([Outbox]), la negociación ([CapabilityNegotiator]) y la recepción
// ([ChatService]): por eso viven aparte y no dentro de ninguno.

/**
 * ¿Se le escribe a este contacto con ratchet? Depende de **él**, no de la versión que haya
 * publicada: solo si ha anunciado que sabe recibirlo ([Contact.peerProtocol], sobre `V`).
 * Así el encendido no necesita que actualice todo el mundo a la vez ni una publicación de
 * seguimiento que cambie una constante — que es lo que sí necesita el depósito ciego.
 *
 * [RATCHET_SEND] queda por encima como interruptor de emergencia.
 */
internal fun usesRatchet(contact: Contact): Boolean =
    RATCHET_SEND && contact.sharedSecret != null && contact.peerProtocol >= RATCHET_MIN_PROTOCOL

/**
 * ¿Se le **rellena** el tamaño a este contacto? (ver [Padding]). Mismo criterio que el
 * ratchet pero con su propio mínimo: el relleno llegó después, y enviárselo a quien no sabe
 * quitarlo le entregaría el relleno pegado al final del mensaje.
 *
 * Solo tiene sentido con ratchet: el camino v1 no tiene dónde marcar que va relleno.
 */
internal fun pads(contact: Contact): Boolean =
    usesRatchet(contact) && contact.peerProtocol >= PADDING_MIN_PROTOCOL

/**
 * ¿Las propuestas nuevas para [contact] son **v4**, y se ata la época 0? (protocolo v4,
 * `docs/DISENO-ratchet.md` §1.12). Con su propio mínimo, como el relleno: a quien anuncie 3 los
 * bits nuevos le llegarían ignorados y derivaría con el KDF viejo.
 */
internal fun kdf4(contact: Contact): Boolean =
    usesRatchet(contact) && contact.peerProtocol >= KDF4_MIN_PROTOCOL

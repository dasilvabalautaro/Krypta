package chat.neto.krypta.p2p

import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.core.repository.ContactRepository
import chat.neto.krypta.p2p.ChatService.Companion.KDF4_MIN_PROTOCOL
import chat.neto.krypta.p2p.ChatService.Companion.PADDING_MIN_PROTOCOL
import chat.neto.krypta.p2p.ChatService.Companion.PROTOCOL_VERSION
import chat.neto.krypta.p2p.ChatService.Companion.RATCHET_MIN_PROTOCOL
import chat.neto.krypta.p2p.ChatService.Companion.REHOOK_MIN_INTERVAL_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * **Negociación de capacidades por contacto**: anunciar qué versión hablamos (sobre `V`), apuntar
 * la que anuncia o demuestra el otro, y volver a enganchar una sesión de ratchet que se
 * desincronizó (reengache y relevo de linaje). Ver `docs/ESPECIFICACION-protocolo.md` §7 y
 * `docs/DISENO-ratchet.md` §1.13. Se separó de `ChatService` el 12 oct 2026 (§5 de
 * docs/AUDITORIA-2026-10-12.md) sin cambiar nada de lo que hace.
 *
 * Las respuestas a lo recibido van **lanzadas** en [scope] y con tope por contacto: el camino del
 * buzón es síncrono (el acuse espera a que se procese el sobre) y cualquier contacto podría
 * provocarlas a propósito.
 */
internal class CapabilityNegotiator(
    private val contacts: ContactRepository,
    private val sessions: RatchetSessions,
    private val outbox: Outbox,
    private val scope: CoroutineScope,
    private val log: DiagnosticsLog,
) {
    private fun logLine(msg: String) = log.line(msg)

    /**
     * Anuncia a cada contacto qué versión de protocolo habla este cliente (sobre `V`), **una
     * sola vez por contacto y por versión**: al recibir el suyo se apunta en `peerProtocol`, y
     * eso es lo que permitirá encender el ratchet contacto a contacto en vez de esperar a que
     * todo el mundo actualice (ver `docs/DISENO-ratchet.md` §5).
     *
     * Se marca como anunciado **solo si el envío salió** (directo o buzón). Si falla por las
     * dos vías se reintenta en el próximo ciclo; si sale por buzón, ya está dicho y no se
     * vuelve a depositar — repetirlo en cada arranque gastaría el cupo del destinatario.
     *
     * Un cliente anterior recibe el sobre como `Unsupported` y lo ignora sin pintar nada, que
     * es lo que hace seguro empezar a anunciarlo desde ya.
     */
    suspend fun announceCapabilities() {
        val pendientes = runCatching { contacts.observeAll().first() }.getOrDefault(emptyList())
            .filter { !it.blocked && it.sharedSecret != null && it.announcedProtocol < PROTOCOL_VERSION }
        var anunciados = 0
        for (contact in pendientes) {
            // Con la versión que tenemos apuntada de él: si nos consta por debajo de lo que ya
            // nos anunció, somos nosotros quienes la perdimos, y así nos la repite (H-1).
            val hello = MessageEnvelope.encodeHello(PROTOCOL_VERSION, knows = contact.peerProtocol)
            val enviado = runCatching { outbox.sendRaw(contact, hello) }
                .isSuccess
            if (enviado) {
                // Solo esa columna: la copia es de antes de enviar y reescribirla entera deshacía
                // lo que el usuario hiciera entretanto (verificar, bloquear; O-3).
                contacts.markAnnounced(contact.id, PROTOCOL_VERSION)
                anunciados++
            }
        }
        // Se registran los dos desenlaces: sin la segunda línea, "no aparece nada" tanto puede
        // significar "ya estaba dicho" como "falla siempre en silencio", y en el diagnóstico
        // eso no se puede distinguir.
        if (anunciados > 0) {
            logLine("↔ protocolo v$PROTOCOL_VERSION anunciado a $anunciados contacto(s)")
        } else if (pendientes.isNotEmpty()) {
            logLine("↔ anuncio de protocolo pendiente para ${pendientes.size} contacto(s)")
        }
    }

    /** Último reengache por contacto, para no convertir un fallo repetido en una ráfaga. */
    // Concurrente, como sus hermanos: `onReceived` corre en hilos de Go (buzón) y en el scope
    // (directo) a la vez, y un HashMap normal no aguanta escrituras cruzadas (O-10).
    private val lastRehook = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Último relevo por contacto: el disparador no está autenticado, así que va con tope de ritmo. */
    private val lastRelay = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * **Relevo** (R2, `docs/DISENO-ratchet.md` §1.13): [wire] no abrió y reclama una época ≥ 1 de un
     * linaje mayor que el nuestro, así que el otro está en una sesión que aquí no existe. Se crea una
     * por encima y se le manda el anuncio por ella, que es lo que hará que la adopte. Cubre W-6 (nos
     * restauramos con el reloj atrasado) y quita el «sin vuelta atrás» de W-4 (alguien con S lo metió
     * en un linaje forjado). Como mucho uno por contacto cada [REHOOK_MIN_INTERVAL_MS]: la cabecera
     * no está autenticada y un nodo podría fabricarla. Solo con contactos v4 (ver dentro). Devuelve
     * si hubo relevo.
     */
    suspend fun relayIfBehind(contact: Contact, wire: ByteArray): Boolean {
        // Solo con contactos v4. Tras perder el estado, un segundo relevo puede caer en el mismo
        // linaje que uno anterior (cabecera + 1, si el otro sigue en la suya); en v3 la cadena de
        // la época 0 depende solo de S, el linaje y el sentido, así que el primer mensaje repetiría
        // clave y nonce. En v4 va atada a una propuesta nueva y no pasa. Lo encontró
        // `RatchetPropertyTest` (semilla 504) el 11 oct 2026.
        if (!kdf4(contact)) return false
        val now = System.currentTimeMillis()
        if (now - (lastRelay[contact.id] ?: 0L) < REHOOK_MIN_INTERVAL_MS) return false
        val lineage = runCatching { sessions.relay(contact, wire, kdf4(contact), now) }.getOrNull() ?: return false
        lastRelay[contact.id] = now
        logLine("↔ relevo con ${short(contact.peerId)}: escribe en un linaje que aquí no está; sesión nueva $lineage")
        launchHello(
            contact, estatico = false, limiter = lastRelayHello,
            enviado = "↔ anuncio por la sesión nueva a ${short(contact.peerId)}",
            fallido = "↔ anuncio por la sesión nueva a ${short(contact.peerId)} no salió",
        )
        return true
    }

    /** El anuncio del relevo tiene su propio tope: si compartiera el del reengache, podría no salir. */
    private val lastRelayHello = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * **Reengancha** una sesión de ratchet desincronizada mandando cualquier cosa nuestra.
     *
     * El caso (encontrado por `RatchetPropertyTest`, ver `docs/DISENO-ratchet.md` §1.9): si uno
     * de los dos pierde el estado, su linaje nuevo es mayor, y la regla del §1.6 dice que un
     * linaje **menor se descarta**. El que no se ha enterado sigue escribiendo en el viejo y
     * **sus mensajes se pierden** hasta que el que reinstaló escriba algo. Aquí somos justo el
     * que lo sabe —acabamos de fallar al abrir su mensaje—, así que no hace falta esperar a que
     * el usuario escriba: se le manda el anuncio de capacidades, que ya viaja por el ratchet con
     * nuestro linaje, y con eso el otro extremo lo adopta y vuelve a ser legible.
     *
     * Tres decisiones:
     * - **Se reutiliza el sobre `V`** en vez de inventar uno: un cliente anterior ya lo ignora
     *   limpiamente como `Unsupported`, así que no hay nada que negociar.
     * - **Va lanzado en el `scope`**, no en línea: el camino del buzón es síncrono (el acuse
     *   depende de que esto vuelva), y bloquearlo con una llamada de red retrasaría la entrega.
     * - **Un reengache por contacto cada [REHOOK_MIN_INTERVAL_MS]**: cualquiera de tus contactos
     *   podría mandar basura a propósito, y sin tope eso nos haría emitir un mensaje por cada
     *   una. Uno cada pocos minutos basta para el caso real y no se puede usar como altavoz.
     */
    fun rehook(contact: Contact) {
        if (!usesRatchet(contact)) {
            if (contact.peerProtocol < RATCHET_MIN_PROTOCOL) {
                // Nos escribe por ratchet y aquí consta que no lo habla: la versión que nos
                // anunció la **perdimos nosotros** (borramos el contacto y lo volvimos a añadir,
                // importamos un .krbk). Hasta el 14 sep 2026 esto se callaba, y sus mensajes se
                // perdían para siempre (H-1 de docs/REVISION-protocolo-2026-09-14.md). Se le
                // dice qué tenemos apuntado, por la clave estática —sin sesión es lo único que
                // seguro abre—, y con eso nos repite su anuncio.
                launchHello(
                    contact, estatico = true, limiter = lastRehook,
                    enviado = "↔ anuncio a ${short(contact.peerId)}: escribe por ratchet y constaba v${contact.peerProtocol}",
                    fallido = "↔ anuncio a ${short(contact.peerId)} no salió; se reintenta al próximo fallo",
                )
            } else {
                // Se registra el motivo: "no salió ningún reengache" tanto puede ser esto como el
                // tope de abajo, y sin distinguirlos no hay forma de diagnosticarlo.
                logLine("↔ sin reengache para ${short(contact.peerId)}: el envío por ratchet está apagado")
            }
            return
        }
        launchHello(
            contact, estatico = false, limiter = lastRehook,
            enviado = "↔ reengache enviado a ${short(contact.peerId)} (su linaje no cuadraba)",
            fallido = "↔ reengache a ${short(contact.peerId)} no salió; se reintenta al próximo fallo",
        )
    }

    /**
     * Lo que **demuestra** un sobre de ratchet que abre: que el contacto habla al menos la v2, y
     * si venía relleno, la v3. La cabecera ya está autenticada —es el AAD del AEAD que acaba de
     * validar—, así que sin el secreto compartido no hay nada que fingir.
     *
     * Cura a quien se quedó apuntado por debajo por el hallazgo H-2 antes del arreglo (una copia
     * vieja del contacto guardada encima de su anuncio): sin esto, esa pareja seguiría en la clave
     * estática hasta la siguiente versión del protocolo, porque el anuncio no se repite.
     */
    suspend fun learnFromRatchet(contact: Contact, wire: ByteArray) {
        val header = Ratchet.Header.decode(wire) ?: return
        val demostrada = when {
            // Un sobre que abre con los bits v4 demuestra v4: van en el AAD, no se pueden fingir sin S.
            header.nextV4 || header.epochV4 -> KDF4_MIN_PROTOCOL
            header.padded -> PADDING_MIN_PROTOCOL
            else -> RATCHET_MIN_PROTOCOL
        }
        // Releída: si el propio sobre era un anuncio, ya la ha subido dentro de la transacción.
        val apuntada = contacts.findById(contact.id)?.peerProtocol ?: return
        if (apuntada >= demostrada) return
        contacts.raisePeerProtocol(contact.id, demostrada)
        logLine("↔ ${short(contact.peerId)} escribe por ratchet: se apunta v$demostrada")
    }

    /** Última vez que se le repitió el anuncio a cada contacto (ver [onHello]); acota el eco. */
    private val lastHello = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Lo que dice un anuncio `V` del contacto, y qué hacer con ello. Tres reglas, de la revisión
     * del protocolo del 14 sep 2026 (`docs/REVISION-protocolo-2026-09-14.md`):
     *
     * - **La versión apuntada solo sube** (H-3). Un anuncio con una versión menor se ignora: si
     *   no, quien tenga el secreto compartido podría devolver la conversación a la clave
     *   estática con un solo sobre y **leer en pasivo** todo lo que viniera después, sin romper
     *   nada que se notara. No hay caso legítimo que lo necesite: Android no instala una versión
     *   menor encima sin desinstalar, y desinstalar cambia la identidad.
     * - **Si nos tiene apuntados por debajo de lo que ya le dijimos, se le repite** (H-1). Es la
     *   señal de que lo perdió: borró el contacto y lo volvió a añadir, o importó un `.krbk`. El
     *   anuncio sale una vez por versión, así que antes no había forma de recuperarlo: seguíamos
     *   escribiéndole por un ratchet cuya sesión ya no tenía y **todo se perdía**. Se repite por
     *   la **clave estática**, que es lo único que seguro puede abrir sin sesión.
     * - **Si acabamos de saber que habla ratchet, se le escribe algo por ratchet** (H-1, segunda
     *   mitad): quien perdió la sesión tiene que adoptar nuestro linaje nuevo **antes** de
     *   escribirnos, o lo primero que mande irá por su sesión vieja.
     *
     * Las respuestas van lanzadas (el camino del buzón es síncrono) y con tope por contacto.
     */
    suspend fun onHello(contact: Contact, hello: MessageEnvelope.Decoded.Hello) {
        val antes = contact.peerProtocol
        val ahora = maxOf(antes, hello.protocol)
        when {
            hello.protocol > antes -> {
                contacts.raisePeerProtocol(contact.id, hello.protocol)
                logLine("↔ ${short(contact.peerId)} habla protocolo v${hello.protocol}")
            }
            hello.protocol < antes -> logLine(
                "↔ ${short(contact.peerId)} anuncia v${hello.protocol} y consta v$antes: se ignora (la versión no baja)",
            )
        }
        val actualizado = contact.copy(peerProtocol = ahora)
        val conocida = hello.knows
        when {
            conocida != null && conocida < PROTOCOL_VERSION && contact.announcedProtocol >= PROTOCOL_VERSION ->
                launchHello(
                    actualizado, estatico = true, limiter = lastHello,
                    enviado = "↔ anuncio repetido a ${short(contact.peerId)} (nos tenía como v$conocida)",
                    fallido = "↔ anuncio a ${short(contact.peerId)} no salió; se repite al próximo aviso",
                )
            antes < RATCHET_MIN_PROTOCOL && ahora >= RATCHET_MIN_PROTOCOL && usesRatchet(actualizado) ->
                launchHello(
                    actualizado, estatico = false, limiter = lastHello,
                    enviado = "↔ primer sobre por ratchet a ${short(contact.peerId)}",
                    fallido = "↔ primer sobre por ratchet a ${short(contact.peerId)} no salió",
                )
        }
    }

    /**
     * Un invite sin mitad de clave de [contact], que consta que negocia: nos tiene apuntados como
     * antiguos. Se le repite nuestro anuncio por la clave estática, con el mismo tope que el resto
     * de anuncios, para que la próxima llamada negocie.
     */
    fun reannounceForCall(contact: Contact) = launchHello(
        contact, estatico = true, limiter = lastHello,
        enviado = "↔ anuncio repetido a ${short(contact.peerId)} (llamó sin negociar la clave)",
        fallido = "↔ anuncio a ${short(contact.peerId)} no salió; se repite en la próxima llamada",
    )

    /**
     * Envía nuestro anuncio `V` —con la versión que tenemos apuntada de [contact]— **lanzado**, y
     * como mucho uno por contacto cada [REHOOK_MIN_INTERVAL_MS] según [limiter]: cualquiera de
     * tus contactos podría provocarlo a propósito, y sin tope serviría de altavoz. [estatico] =
     * por la clave estática, que el otro abre aunque haya perdido la sesión del ratchet.
     */
    private fun launchHello(
        contact: Contact,
        estatico: Boolean,
        limiter: MutableMap<String, Long>,
        enviado: String,
        fallido: String,
    ) {
        val now = System.currentTimeMillis()
        val last = limiter[contact.id] ?: 0L
        if (now - last < REHOOK_MIN_INTERVAL_MS) return
        limiter[contact.id] = now
        scope.launch {
            val hello = MessageEnvelope.encodeHello(PROTOCOL_VERSION, knows = contact.peerProtocol)
            val ok = runCatching {
                if (estatico) outbox.sendStatic(contact, hello) else outbox.sendRaw(contact, hello)
            }.isSuccess
            logLine(if (ok) enviado else fallido)
            // Si no salió, que el próximo aviso pueda volver a intentarlo en vez de esperar.
            if (!ok) limiter.remove(contact.id)
        }
    }
}

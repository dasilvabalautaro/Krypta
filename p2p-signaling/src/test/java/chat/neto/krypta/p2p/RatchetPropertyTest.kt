package chat.neto.krypta.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/**
 * El ratchet bajo caos: en vez de escenarios escritos a mano (eso es [RatchetTest]), aquí se
 * sortean miles de secuencias de envíos, entregas desordenadas, pérdidas, duplicados y pérdidas
 * de estado, y se comprueba que ciertas cosas **nunca** pasan.
 *
 * Por qué esto y no más casos concretos: el propio diseño dice que los protocolos se rompen en
 * los casos límite ([docs/DISENO-ratchet.md] §2), y los casos límite de un ratchet son
 * combinatorios — el orden de entrega cruzado con el cambio de época cruzado con un reinicio de
 * linaje. Nadie los escribe todos a mano. Además el envío ya está encendido en producción
 * (`RATCHET_SEND = true`) sin la prueba de dos móviles que el diseño pedía, así que esta es la
 * red que se puede tender sin depender de nadie.
 *
 * Las semillas son fijas: si algo falla, el fallo es **reproducible** y el mensaje trae la
 * semilla y el guion de acciones. Una propiedad que falla sin decir la secuencia no sirve.
 */
class RatchetPropertyTest {

    private val ratchet = Ratchet(JdkCurve25519())
    private val secret = ByteArray(32) { (it * 11 + 3).toByte() }
    private val alice = "12D3KooWAaaa"
    private val bob = "12D3KooWBbbb"

    /** Un mensaje que ya salió al cable, con lo que hace falta para juzgar su entrega. */
    private class Wire(val fromAlice: Boolean, val bytes: ByteArray, val plaintext: String)

    /**
     * Una corrida: dos extremos, cola de mensajes en vuelo y un guion de lo que fue pasando
     * (para el mensaje de error). Las aserciones viven aquí para que las tres pruebas compartan
     * exactamente las mismas invariantes.
     */
    private inner class Run(val seed: Long, val allowStateLoss: Boolean) {
        private val rnd = Random(seed)
        private var a = ratchet.initial(secret, alice, bob, lineage = 1_000L)
        private var b = ratchet.initial(secret, bob, alice, lineage = 1_000L)
        private val inFlight = mutableListOf<Wire>()
        private val delivered = mutableListOf<Wire>()
        private val script = mutableListOf<String>()
        private var sent = 0
        var opened = 0
            private set
        private var stateLosses = 0

        /** Duplicados que se reabrieron por ser de la época 0 (comportamiento documentado). */
        var reopenedEpochZero = 0
            private set

        /** Veces que la recuperación necesitó que hablara **el otro** (ver [assertStillAlive]). */
        var needsPeerTurn = 0
            private set

        /** `(linaje, época, N, sentido)` de cada mensaje emitido: **no puede repetirse ninguno**. */
        private val headers = mutableSetOf<String>()
        private val wires = mutableSetOf<String>()

        private fun oops(what: String): Nothing {
            fail("$what\n  semilla=$seed\n  guion=${script.joinToString(" · ")}")
            error("inalcanzable")
        }

        private fun send(fromAlice: Boolean) {
            val who = if (fromAlice) "A" else "B"
            val text = "$who#${sent++}"
            val sealed = if (fromAlice) ratchet.encrypt(a, text.toByteArray()) else ratchet.encrypt(b, text.toByteArray())
            if (fromAlice) a = sealed.state else b = sealed.state
            script += "$who envía $text"

            // Propiedad 2: ninguna clave de mensaje se usa dos veces. La forma observable es que
            // la terna (linaje, época, N) de un mismo emisor sea única — es lo que identifica la
            // clave dentro de su cadena. Si el linaje dejara de entrar en la derivación de la
            // época 0, dos sesiones reiniciadas chocarían aquí.
            val h = Ratchet.Header.decode(sealed.ciphertext) ?: oops("cabecera ilegible al emitir $text")
            val id = "$who:${h.lineage}:${h.epoch}:${h.n}"
            if (!headers.add(id)) oops("clave reutilizada: $who repitió (linaje, época, N) = $id al enviar $text")
            if (!wires.add(sealed.ciphertext.joinToString(",") { it.toString() })) {
                oops("dos mensajes idénticos en el cable al enviar $text")
            }
            inFlight += Wire(fromAlice, sealed.ciphertext, text)
        }

        /** Entrega uno cualquiera de los que están en vuelo (de ahí el desorden). */
        private fun deliver() {
            if (inFlight.isEmpty()) return
            val w = inFlight.removeAt(rnd.nextInt(inFlight.size))
            val who = if (w.fromAlice) "B" else "A"
            script += "$who recibe ${w.plaintext}"
            try {
                val res = if (w.fromAlice) ratchet.decrypt(b, secret, w.bytes) else ratchet.decrypt(a, secret, w.bytes)
                if (w.fromAlice) b = res.state else a = res.state
                val got = String(res.plaintext)
                // Propiedad 1: si abre, abre lo suyo. Un texto ajeno sería lo más grave posible:
                // significaría cadenas cruzadas entre sentidos, épocas o linajes.
                if (got != w.plaintext) oops("mensaje cruzado: se envió '${w.plaintext}' y se abrió '$got'")
                delivered += w
                opened++
            } catch (e: RatchetException) {
                // Fallar es legítimo (época retirada, clave gastada, hueco enorme): lo que no es
                // legítimo es abrir otra cosa. Se anota para el guion.
                script += "  (no abre: ${e.message})"
            }
        }

        /**
         * El buzón reentrega algo ya abierto. Lo que se fija aquí es **hasta dónde llega** la
         * protección del ratchet contra repeticiones, que no es "siempre":
         *
         * - Dentro de la cadena viva (mismo linaje y época) **nunca** puede reabrirse: la clave
         *   se gastó. Si esto fallara sería un fallo de verdad.
         * - De una época retirada distinta de la 0, tampoco: su cadena se fue.
         * - De la **época 0**, sí puede reabrirse, y es deliberado: la época 0 es derivable del
         *   secreto compartido a los dos lados (por eso no tiene secreto hacia adelante) y
         *   `openOld` la re-deriva cuando su cadena ya no está entre las retiradas. O sea que
         *   **el ratchet no detecta la reproducción de un mensaje de época 0**: eso lo tiene que
         *   parar la deduplicación previa (`ratchet_seen`), que es por tanto una pieza de
         *   seguridad y no solo una comodidad. Lo encontró esta prueba.
         *
         * El estado que devuelve un duplicado se **descarta**: en producción la deduplicación va
         * antes de descifrar, así que una repetición no debe mover el ratchet.
         */
        private fun duplicate() {
            if (delivered.isEmpty()) return
            val w = delivered[rnd.nextInt(delivered.size)]
            val h = Ratchet.Header.decode(w.bytes) ?: oops("cabecera ilegible al duplicar ${w.plaintext}")
            val receiver = if (w.fromAlice) b else a
            val enCadenaViva = h.lineage == receiver.lineage && h.epoch == receiver.epoch
            script += "duplicado de ${w.plaintext}"
            try {
                val res = if (w.fromAlice) ratchet.decrypt(b, secret, w.bytes) else ratchet.decrypt(a, secret, w.bytes)
                if (enCadenaViva) {
                    oops("un duplicado de '${w.plaintext}' se reabrió dentro de la cadena viva (época ${h.epoch}): la clave debía estar gastada")
                }
                if (h.epoch != 0) {
                    oops("un duplicado de '${w.plaintext}' se reabrió en la época retirada ${h.epoch}: solo la 0 es re-derivable")
                }
                if (String(res.plaintext) != w.plaintext) {
                    oops("duplicado cruzado: '${w.plaintext}' se reabrió como '${String(res.plaintext)}'")
                }
                reopenedEpochZero++
                script += "  (se reabre: época 0 re-derivable del secreto compartido)"
            } catch (e: RatchetException) {
                script += "  (no se reabre: ${e.message})"
            }
        }

        private fun drop() {
            if (inFlight.isEmpty()) return
            val w = inFlight.removeAt(rnd.nextInt(inFlight.size))
            script += "se pierde ${w.plaintext}"
        }

        /** Reinstalación: el estado se va y se arranca un linaje nuevo (mayor). */
        private fun loseState(fromAlice: Boolean) {
            stateLosses++
            val lineage = 2_000L + stateLosses * 1_000L
            if (fromAlice) {
                a = ratchet.initial(secret, alice, bob, lineage)
                script += "A pierde el estado (linaje $lineage)"
            } else {
                b = ratchet.initial(secret, bob, alice, lineage)
                script += "B pierde el estado (linaje $lineage)"
            }
        }

        fun go(steps: Int) {
            repeat(steps) {
                when (rnd.nextInt(if (allowStateLoss) 22 else 20)) {
                    in 0..6 -> send(fromAlice = true)
                    in 7..12 -> send(fromAlice = false)
                    in 13..17 -> deliver()
                    18 -> duplicate()
                    19 -> drop()
                    else -> loseState(fromAlice = rnd.nextBoolean())
                }
                // Propiedad 5: sin reinicios de linaje, los dos extremos nunca se separan más de
                // una época. Es la invariante sobre la que `decrypt` está escrito (solo sabe
                // alcanzar `epoch + 1`); si se rompiera, habría mensajes indescifrables.
                if (!allowStateLoss && a.lineage == b.lineage && Math.abs(a.epoch - b.epoch) > 1) {
                    oops("las épocas se separaron: A=${a.epoch} B=${b.epoch}")
                }
            }
        }

        /**
         * Propiedad 3: después del caos, la conversación **se recupera en una ronda acotada**.
         *
         * Ojo con lo que se puede exigir aquí, porque la primera versión de esta prueba exigía
         * demasiado y "encontró" un fallo que no lo era. Si el otro extremo perdió el estado
         * **después** que nosotros, su linaje es mayor, y la regla del §1.6 dice que un linaje
         * menor **se descarta**: nuestros mensajes no son legibles para él hasta que él hable y
         * nosotros adoptemos su linaje. Eso no es una sesión rota, es la regla funcionando.
         *
         * Lo que sí se exige: que baste con **una ronda** — A escribe; si no se le lee, habla B
         * (que es quien tiene el linaje que manda) y entonces A tiene que poder ser leído. Si ni
         * así, la sesión está rota de verdad y el §1.6 no se cumple.
         *
         * La consecuencia de producción de esa asimetría está en el §1.9 del diseño: los
         * mensajes enviados a alguien que acaba de reinstalar **se pierden** hasta que esa
         * persona escribe.
         */
        fun assertStillAlive() {
            while (inFlight.isNotEmpty()) deliver()

            var legibleDeEntrada = true
            val fromA = ratchet.encrypt(a, "¿me lees?".toByteArray()); a = fromA.state
            try {
                val atB = ratchet.decrypt(b, secret, fromA.ciphertext)
                b = atB.state
                if (String(atB.plaintext) != "¿me lees?") {
                    oops("mensaje cruzado en la recuperación: se abrió '${String(atB.plaintext)}'")
                }
            } catch (e: RatchetException) {
                legibleDeEntrada = false
                script += "  (a A no se le lee todavía: ${e.message})"
            }

            // Habla B. Si B tiene el linaje mayor, esto es lo que reengancha; y si lo tiene A,
            // el mensaje de B se abre igual porque su época 0 es derivable del secreto.
            val fromB = ratchet.encrypt(b, "te leo".toByteArray()); b = fromB.state
            val atA = try {
                ratchet.decrypt(a, secret, fromB.ciphertext)
            } catch (e: RatchetException) {
                oops("la conversación quedó rota: A no pudo abrir un mensaje nuevo de B (${e.message})")
            }
            a = atA.state
            assertEquals("te leo", String(atA.plaintext))

            if (!legibleDeEntrada) {
                needsPeerTurn++
                val retry = ratchet.encrypt(a, "otra vez".toByteArray()); a = retry.state
                val atB2 = try {
                    ratchet.decrypt(b, secret, retry.ciphertext)
                } catch (e: RatchetException) {
                    oops("la conversación quedó rota: ni después de que B hablara se pudo leer a A (${e.message})")
                }
                b = atB2.state
                assertEquals("otra vez", String(atB2.plaintext))
            }
        }

        fun stats() = "enviados=$sent abiertos=$opened reabiertos(época 0)=$reopenedEpochZero " +
            "pérdidas de estado=$stateLosses"
    }

    @Test
    fun `mil secuencias al azar - nada se abre como otro mensaje y ninguna clave se repite`() {
        var totalOpened = 0
        for (seed in 1L..40L) {
            val run = Run(seed, allowStateLoss = false)
            run.go(steps = 120)
            run.assertStillAlive()
            totalOpened += run.opened
        }
        // Que el simulador haya ejercitado algo de verdad: sin esto, un cambio que dejara todos
        // los mensajes sin entregar convertiría la prueba en un test que no prueba nada.
        assertTrue("el simulador apenas entregó mensajes ($totalOpened)", totalOpened > 800)
    }

    @Test
    fun `con perdidas de estado, la conversacion siempre se recupera`() {
        for (seed in 101L..130L) {
            val run = Run(seed, allowStateLoss = true)
            run.go(steps = 120)
            // Lo que importa aquí no es cuántos mensajes sobreviven a una reinstalación (algunos
            // no pueden: su época se fue con el estado), sino que la sesión no quede rota.
            run.assertStillAlive()
        }
    }

    @Test
    fun `rafagas largas en un solo sentido no rompen la cadena ni repiten claves`() {
        // El caso del archivo troceado, pero con el orden de entrega sorteado y muchas más
        // ráfagas de las que nadie escribiría a mano.
        for (seed in 201L..210L) {
            val run = Run(seed, allowStateLoss = false)
            run.go(steps = 400)
            run.assertStillAlive()
        }
    }
}

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
    /** Cómo usa cada extremo el protocolo v4 en una corrida (ver `DISENO-ratchet.md` §1.12). */
    enum class V4 { OFF, ON, SWITCH }

    private inner class Run(
        val seed: Long,
        val allowStateLoss: Boolean,
        val v4: V4 = V4.OFF,
        /**
         * Rediseño del linaje (`DISENO-ratchet.md` §1.13): reloj simulado, pérdidas de estado con el
         * reloj atrasado (W-6), linajes forjados por quien tiene S (W-4) y el relevo (R2) cuando un
         * sobre de un linaje mayor no abre. Lo que se exige pasa a ser «se recupera en pocas rondas».
         */
        val relojes: Boolean = false,
    ) {
        private val rnd = Random(seed)
        /** Reloj simulado, que avanza en cada paso; solo cuenta con [relojes]. */
        private var now = 1_000_000L
        private fun ahora() = if (relojes) now else System.currentTimeMillis()
        // Con SWITCH cada extremo empieza sin saber que el otro es v4 y se entera en un paso al azar,
        // por separado: es la transición real, con las dos puntas un tiempo en desacuerdo.
        private var aV4 = v4 == V4.ON
        private var bV4 = v4 == V4.ON
        private var a = ratchet.initial(secret, alice, bob, lineage = 1_000L, v4 = aV4)
        private var b = ratchet.initial(secret, bob, alice, lineage = 1_000L, v4 = bV4)

        /** ¿Las dos sesiones acabaron en una época v4? */
        fun bothInV4() = a.epochV4 && b.epochV4
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
            // La mitad de los mensajes van **rellenos** (fase 2.3 del plan), sorteado por
            // mensaje: así el caos cubre el relleno donde de verdad puede romperse —entregas
            // desordenadas, duplicados, una época ya retirada, una pérdida de estado— y no solo
            // el ida y vuelta feliz de [RatchetPaddingTest]. Entra en el guion porque si falla
            // hay que poder repetirlo tal cual.
            val pad = rnd.nextBoolean()
            val sealed =
                if (fromAlice) ratchet.encrypt(a, text.toByteArray(), pad, aV4)
                else ratchet.encrypt(b, text.toByteArray(), pad, bV4)
            if (fromAlice) a = sealed.state else b = sealed.state
            script += "$who envía $text${if (pad) " (relleno)" else ""}"

            // Propiedad 2: ninguna clave de mensaje se usa dos veces. La forma observable es que
            // la terna (linaje, época, N) de un mismo emisor sea única — es lo que identifica la
            // clave dentro de su cadena. Si el linaje dejara de entrar en la derivación de la
            // época 0, dos sesiones reiniciadas chocarían aquí.
            val h = Ratchet.Header.decode(sealed.ciphertext) ?: oops("cabecera ilegible al emitir $text")
            val id = keyId(who, h)
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
                val res = if (w.fromAlice) ratchet.decrypt(b, secret, w.bytes, bV4, ahora()) else ratchet.decrypt(a, secret, w.bytes, aV4, ahora())
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
                if (relojes) relayIfBehind(receiverIsB = w.fromAlice, wire = w.bytes)
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
                val res = if (w.fromAlice) ratchet.decrypt(b, secret, w.bytes, bV4, ahora()) else ratchet.decrypt(a, secret, w.bytes, aV4, ahora())
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

        /**
         * Lo que identifica de verdad la clave de un mensaje. Sin [relojes] basta la terna
         * `(linaje, época, N)` del emisor, que es lo que esta prueba exigió siempre. Con relojes, un
         * extremo que restaura con el reloj atrasado puede **volver a entrar en un linaje propio ya
         * usado** (adopta el vigente de la pareja), y entonces la terna se repite sin que se repita
         * la clave: en una época ≥ 1 la clave sale de un DH con una privada nueva (la identifica
         * su pública, `cur_pub`), y en la época 0 v4, de la propuesta (`next_pub`). En la época 0
         * v3 sí se repetiría, y por eso con relojes solo se corre v4 (el caso v3 lo fija aparte
         * `RatchetCompromisoTest`).
         */
        private fun keyId(who: String, h: Ratchet.Header): String {
            val base = "$who:${h.lineage}:${h.epoch}:${h.n}"
            if (!relojes) return base
            val binding = when {
                h.epoch >= 1 -> h.curPub
                h.epochV4 -> h.nextPub
                else -> return base
            }
            return base + ":" + binding.joinToString("") { "%02x".format(it) }
        }

        /** R2, como `RatchetSessions.relay`: un sobre de un linaje mayor en época ≥ 1 que no abre. */
        private fun relayIfBehind(receiverIsB: Boolean, wire: ByteArray) {
            // Como en producción, solo con v4: en v3 dos relevos al mismo linaje repetirían clave
            // y nonce en la época 0 (lo encontró esta prueba, semilla 504, corrida en v3).
            if (!(if (receiverIsB) bV4 else aV4)) return
            val h = Ratchet.Header.decode(wire) ?: return
            val receiver = if (receiverIsB) b else a
            if (h.epoch < 1 || !Ratchet.peerIsAhead(h.lineage, h.epoch, receiver)) return
            val lineage = Ratchet.relayLineage(h.lineage, ahora()) ?: return
            val restarted = ratchet.restart(receiver, secret, lineage, if (receiverIsB) bV4 else aV4)
            if (receiverIsB) b = restarted else a = restarted
            script += "  (relevo de ${if (receiverIsB) "B" else "A"} al linaje $lineage)"
        }

        /** W-4: quien tiene S le mete a uno un sobre de época 0 con un linaje forjado, dentro del tope. */
        private fun forge() {
            val toB = rnd.nextBoolean()
            val lineage = ahora() + rnd.nextInt(Ratchet.LINEAGE_MAX_AHEAD_MS.toInt())
            val mallory = if (toB) ratchet.initial(secret, alice, bob, lineage) else ratchet.initial(secret, bob, alice, lineage)
            val wire = ratchet.encrypt(mallory, "forjado".toByteArray()).ciphertext
            script += "el atacante le mete a ${if (toB) "B" else "A"} el linaje $lineage"
            try {
                val res = if (toB) ratchet.decrypt(b, secret, wire, bV4, ahora()) else ratchet.decrypt(a, secret, wire, aV4, ahora())
                if (String(res.plaintext) != "forjado") oops("el forjado se abrió como otra cosa")
                if (toB) b = res.state else a = res.state
            } catch (e: RatchetException) {
                script += "  (no abre: ${e.message})"
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
            // Con relojes, la hora; y la mitad de las veces, una hora atrasada (W-6). Atrasada pero
            // nueva: repetir un linaje ya usado es W-16, cuya precondición está fuera del modelo.
            val lineage = when {
                !relojes -> 2_000L + stateLosses * 1_000L
                rnd.nextBoolean() -> ahora() - 500_000L
                else -> ahora()
            }
            if (fromAlice) {
                a = ratchet.initial(secret, alice, bob, lineage, v4 = aV4)
                script += "A pierde el estado (linaje $lineage)"
            } else {
                b = ratchet.initial(secret, bob, alice, lineage, v4 = bV4)
                script += "B pierde el estado (linaje $lineage)"
            }
        }

        fun go(steps: Int) {
            repeat(steps) {
                if (v4 == V4.SWITCH) {
                    if (!aV4 && rnd.nextInt(40) == 0) { aV4 = true; script += "A sabe que B es v4" }
                    if (!bV4 && rnd.nextInt(40) == 0) { bV4 = true; script += "B sabe que A es v4" }
                }
                now += 1_000L
                when (rnd.nextInt(if (relojes) 23 else if (allowStateLoss) 22 else 20)) {
                    in 0..6 -> send(fromAlice = true)
                    in 7..12 -> send(fromAlice = false)
                    in 13..17 -> deliver()
                    18 -> duplicate()
                    19 -> drop()
                    22 -> forge()
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
        /**
         * Con [relojes] la exigencia cambia: tras pérdidas con el reloj atrasado y linajes forjados,
         * la conversación tiene que volver a ir **en los dos sentidos en pocas rondas**, con el relevo
         * haciendo su parte. Sin relevo, el W-6 y el W-4 la dejaban rota para siempre.
         */
        fun assertRecovers(rounds: Int = 4) {
            while (inFlight.isNotEmpty()) deliver()
            repeat(rounds) { r ->
                now += Ratchet.LINEAGE_MAX_AHEAD_MS // tiempo de sobra entre rondas: que el tope no estorbe
                val okAB = roundTrip(fromAlice = true, tag = "ronda $r A→B")
                val okBA = roundTrip(fromAlice = false, tag = "ronda $r B→A")
                if (okAB && okBA) return
            }
            oops("la conversación no se recuperó en $rounds rondas")
        }

        private fun roundTrip(fromAlice: Boolean, tag: String): Boolean {
            val sealed = if (fromAlice) ratchet.encrypt(a, tag.toByteArray(), false, aV4) else ratchet.encrypt(b, tag.toByteArray(), false, bV4)
            if (fromAlice) a = sealed.state else b = sealed.state
            val h = Ratchet.Header.decode(sealed.ciphertext)!!
            val id = keyId(if (fromAlice) "A" else "B", h)
            if (!headers.add(id)) oops("clave reutilizada en la recuperación: $id")
            return try {
                val res = if (fromAlice) ratchet.decrypt(b, secret, sealed.ciphertext, bV4, ahora()) else ratchet.decrypt(a, secret, sealed.ciphertext, aV4, ahora())
                if (String(res.plaintext) != tag) oops("mensaje cruzado en la recuperación: '${String(res.plaintext)}'")
                if (fromAlice) b = res.state else a = res.state
                true
            } catch (e: RatchetException) {
                script += "  ($tag no abre: ${e.message})"
                relayIfBehind(receiverIsB = fromAlice, wire = sealed.ciphertext)
                false
            }
        }

        fun assertStillAlive() {
            while (inFlight.isNotEmpty()) deliver()

            var legibleDeEntrada = true
            val fromA = ratchet.encrypt(a, "¿me lees?".toByteArray(), false, aV4); a = fromA.state
            try {
                val atB = ratchet.decrypt(b, secret, fromA.ciphertext, bV4)
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
            val fromB = ratchet.encrypt(b, "te leo".toByteArray(), false, bV4); b = fromB.state
            val atA = try {
                ratchet.decrypt(a, secret, fromB.ciphertext, aV4)
            } catch (e: RatchetException) {
                oops("la conversación quedó rota: A no pudo abrir un mensaje nuevo de B (${e.message})")
            }
            a = atA.state
            assertEquals("te leo", String(atA.plaintext))

            if (!legibleDeEntrada) {
                needsPeerTurn++
                val retry = ratchet.encrypt(a, "otra vez".toByteArray(), false, aV4); a = retry.state
                val atB2 = try {
                    ratchet.decrypt(b, secret, retry.ciphertext, bV4)
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

    /** Las mismas invariantes con el protocolo v4 encendido en los dos lados desde el principio. */
    @Test
    fun `con v4 nada se abre como otro mensaje, nada se repite y todas las epocas son v4`() {
        for (seed in 301L..340L) {
            val run = Run(seed, allowStateLoss = false, v4 = V4.ON)
            run.go(steps = 120)
            run.assertStillAlive()
            assertTrue("semilla $seed: con v4 en los dos lados toda época es v4", run.bothInV4())
        }
        for (seed in 341L..360L) {
            val run = Run(seed, allowStateLoss = true, v4 = V4.ON)
            run.go(steps = 120)
            run.assertStillAlive()
        }
    }

    /**
     * La transición: cada extremo se entera por su lado, en un paso al azar, de que el otro es v4.
     * Es donde puede romperse la regla de «v4 si las dos propuestas lo son», si alguna vez los dos
     * evaluaran cosas distintas.
     */
    @Test
    fun `pasar de v3 a v4 a mitad de conversacion no rompe nada`() {
        for (seed in 401L..460L) {
            val run = Run(seed, allowStateLoss = seed % 2 == 0L, v4 = V4.SWITCH)
            run.go(steps = 160)
            run.assertStillAlive()
        }
    }

    /**
     * El rediseño del linaje bajo caos (`DISENO-ratchet.md` §1.13): pérdidas de estado con el reloj
     * atrasado (W-6), linajes forjados por quien tiene S (W-4) y el relevo (R2). Las invariantes de
     * siempre (nada se abre como otro mensaje, ninguna terna se repite) y además que la conversación
     * vuelva a ir en los dos sentidos en pocas rondas. Sin el relevo, esta prueba falla.
     */
    @Test
    fun `con relojes atrasados y linajes forjados la conversacion se recupera con relevos`() {
        for (seed in 501L..580L) {
            val run = Run(seed, allowStateLoss = true, v4 = V4.ON, relojes = true)
            run.go(steps = 160)
            run.assertRecovers()
        }
    }
}

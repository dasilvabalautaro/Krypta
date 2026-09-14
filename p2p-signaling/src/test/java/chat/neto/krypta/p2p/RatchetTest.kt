package chat.neto.krypta.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El ratchet por épocas ([docs/DISENO-ratchet.md] §1). Los casos que importan no son el ida y
 * vuelta feliz sino los que rompen a los ratchets: los dos hablan a la vez, el desorden, el
 * cambio de época a mitad de vuelo, el duplicado del buzón y la pérdida de estado.
 */
class RatchetTest {

    private val ratchet = Ratchet(JdkCurve25519())
    private val secret = ByteArray(32) { (it * 7 + 1).toByte() }

    // PeerID reales solo en la forma: lo único que importa es su orden canónico.
    private val alice = "12D3KooWAaaa"
    private val bob = "12D3KooWBbbb"

    private fun sessions(lineage: Long = 1_000L): Pair<RatchetState, RatchetState> =
        ratchet.initial(secret, alice, bob, lineage) to ratchet.initial(secret, bob, alice, lineage)

    @Test
    fun `un mensaje de la epoca 0 va y vuelve`() {
        var (a, b) = sessions()
        val sealed = ratchet.encrypt(a, "hola".toByteArray())
        a = sealed.state
        val opened = ratchet.decrypt(b, secret, sealed.ciphertext)
        b = opened.state
        assertEquals("hola", String(opened.plaintext))
        assertTrue(Ratchet.looksLikeRatchet(sealed.ciphertext))
    }

    @Test
    fun `una ida y vuelta saca la conversacion de la epoca 0`() {
        var (a, b) = sessions()
        // La época 0 es derivable del secreto compartido: no tiene PFS, y por eso importa salir.
        assertEquals(0, a.epoch)

        val m1 = ratchet.encrypt(a, "uno".toByteArray()); a = m1.state
        b = ratchet.decrypt(b, secret, m1.ciphertext).state
        // Con la propuesta de A en la mano, B ya tiene las dos públicas de la época 1.
        assertEquals(1, b.epoch)

        val m2 = ratchet.encrypt(b, "dos".toByteArray()); b = m2.state
        val back = ratchet.decrypt(a, secret, m2.ciphertext); a = back.state
        assertEquals("dos", String(back.plaintext))

        // A sube dos de golpe: entra en la 1 con la pública que trae la cabecera y de ahí a la 2
        // con la propuesta que viene en el mismo mensaje. Es decir, **la época avanza por mensaje
        // recibido, no por turno de conversación** — cada mensaje trae una propuesta nueva y se
        // consume en el acto. Cuesta un X25519 por mensaje recibido y da un ratchet DH por
        // mensaje, que es más de lo que pedía el diseño, no menos.
        assertEquals(2, a.epoch)

        // Y nunca se separan más de una época: para avanzar hace falta una propuesta del otro, y
        // cada mensaje suyo trae exactamente una. De ahí que `decrypt` solo tenga que saber
        // alcanzar `epoch + 1`.
        assertTrue(kotlin.math.abs(a.epoch - b.epoch) <= 1)

        val m3 = ratchet.encrypt(a, "tres".toByteArray()); a = m3.state
        b = ratchet.decrypt(b, secret, m3.ciphertext).state
        assertTrue(b.epoch > 1)
        assertTrue(kotlin.math.abs(a.epoch - b.epoch) <= 1)
    }

    /**
     * Dos móviles que se añaden crean su sesión cada uno por su lado, con linajes distintos
     * por milisegundos. El que recibe primero tiene un linaje **mayor** que el del sobre: lo
     * abre por `openOld` (la época 0 de cualquier linaje es derivable) pero **no** adopta el
     * linaje menor ni consume la propuesta que venía, así que su primer mensaje sale en la
     * época 0 de su propio linaje, sin secreto hacia adelante, hasta que el otro responda.
     *
     * Se probó a adoptarlo cuando la sesión aún no había cifrado nada (12 sep 2026) y la prueba
     * de propiedades lo tumbó (semilla 102): tras una reinstalación la sesión también está
     * «virgen», y adoptar un linaje viejo del otro reutiliza ternas (linaje, época, N) que esta
     * identidad ya gastó antes de perder el estado. Los linajes tienen que ser monótonos por
     * identidad; este test fija que lo siguen siendo, y de paso documenta el coste.
     */
    @Test
    fun `una sesion nueva con linaje mayor abre el sobre pero no adopta el linaje menor`() {
        var a = ratchet.initial(secret, alice, bob, lineage = 1_000L)
        var b = ratchet.initial(secret, bob, alice, lineage = 2_000L) // nació después

        val m1 = ratchet.encrypt(a, "uno".toByteArray()); a = m1.state
        val en = ratchet.decrypt(b, secret, m1.ciphertext); b = en.state
        assertEquals("uno", String(en.plaintext))
        assertEquals("un linaje menor no se adopta nunca (monotonía por identidad)", 2_000L, b.lineage)
        assertEquals("y sin adoptarlo no hay con qué avanzar: sigue en la época 0", 0, b.epoch)

        // Coste: lo primero que escribe B va en la época 0 de su linaje. A lo adopta (es mayor)
        // y a partir de la respuesta de A los dos tienen material efímero.
        val m2 = ratchet.encrypt(b, "dos".toByteArray()); b = m2.state
        assertEquals(0, Ratchet.Header.decode(m2.ciphertext)!!.epoch)
        val back = ratchet.decrypt(a, secret, m2.ciphertext); a = back.state
        assertEquals("dos", String(back.plaintext))
        assertEquals(2_000L, a.lineage)
        assertEquals(1, a.epoch)
        val m3 = ratchet.encrypt(a, "tres".toByteArray()); a = m3.state
        b = ratchet.decrypt(b, secret, m3.ciphertext).state
        assertEquals(2, b.epoch)
    }

    /**
     * **Limitación conocida, fijada a propósito** (H-4 de `docs/REVISION-protocolo-2026-09-14.md`).
     * Quien tenga el secreto compartido —o sea, **cualquiera de las dos identidades**— puede
     * secuestrar la sesión con un solo sobre: uno de época 0 con un linaje mayor que el vigente.
     * El receptor lo adopta (es la misma regla que recupera una pérdida de estado, §1.6), avanza
     * con la propuesta que venía dentro, y todo lo que escriba desde ahí va con material que el
     * atacante conoce. Y **no hay vuelta atrás**: el extremo legítimo nunca crea un linaje mayor
     * que uno forjado muy alto, así que queda fuera hasta que alguien borre la sesión.
     *
     * No contradice lo que el diseño promete («forjar uno nuevo exige `S`, y quien tiene `S` ya
     * tiene la identidad»), pero sí lo que se suele entender por recuperación tras un compromiso:
     * con **un** sobre, el atacante activo pasa a leer en pasivo. Si algún cambio lo cierra, este
     * test tiene que cambiar con él.
     */
    @Test
    fun `con el secreto compartido un linaje forjado secuestra la sesion y no hay vuelta atras`() {
        var (a, b) = sessions()
        repeat(2) {
            val m = ratchet.encrypt(a, "a".toByteArray()); a = m.state
            b = ratchet.decrypt(b, secret, m.ciphertext).state
            val r = ratchet.encrypt(b, "b".toByteArray()); b = r.state
            a = ratchet.decrypt(a, secret, r.ciphertext).state
        }
        assertTrue("la sesión legítima debía ir ya por varias épocas", b.epoch >= 2)

        // El atacante solo tiene S (p. ej. la identidad de Alice sacada de un .krbk con su frase).
        val forjadoLinaje = Long.MAX_VALUE / 2
        var mallory = ratchet.initial(secret, alice, bob, lineage = forjadoLinaje)
        val forjado = ratchet.encrypt(mallory, "soy Alice".toByteArray()); mallory = forjado.state
        val abierto = ratchet.decrypt(b, secret, forjado.ciphertext); b = abierto.state
        assertEquals("soy Alice", String(abierto.plaintext))
        assertEquals("Bob adopta el linaje forjado", forjadoLinaje, b.lineage)

        // Lo que Bob escriba desde ahora lo lee el atacante, sin volver a intervenir...
        val deBob = ratchet.encrypt(b, "secreto".toByteArray()); b = deBob.state
        assertEquals("secreto", String(ratchet.decrypt(mallory, secret, deBob.ciphertext).plaintext))

        // ...Alice, la de verdad, no lo lee...
        assertThrows(RatchetException::class.java) { ratchet.decrypt(a, secret, deBob.ciphertext) }
        // ...ni Bob la lee a ella, porque su linaje es menor y su época ya no está.
        val deAlice = ratchet.encrypt(a, "¿sigues ahí?".toByteArray()); a = deAlice.state
        assertThrows(RatchetException::class.java) { ratchet.decrypt(b, secret, deAlice.ciphertext) }

        // Y aunque Alice pierda el estado y empiece de cero, su linaje (la hora) no alcanza al
        // forjado: Bob abre su época 0 —derivable— pero no lo adopta, y sigue en el del atacante.
        val aliceDeCero = ratchet.initial(secret, alice, bob, lineage = System.currentTimeMillis())
        val reintento = ratchet.encrypt(aliceDeCero, "empiezo de cero".toByteArray())
        b = ratchet.decrypt(b, secret, reintento.ciphertext).state
        assertEquals("no hay vuelta atrás: Bob sigue en el linaje forjado", forjadoLinaje, b.lineage)
    }

    @Test
    fun `los dos hablan a la vez sin que se rompa la sesion`() {
        var (a, b) = sessions()
        val fromA = ratchet.encrypt(a, "a1".toByteArray()); a = fromA.state
        val fromB = ratchet.encrypt(b, "b1".toByteArray()); b = fromB.state

        val atB = ratchet.decrypt(b, secret, fromA.ciphertext); b = atB.state
        val atA = ratchet.decrypt(a, secret, fromB.ciphertext); a = atA.state
        assertEquals("a1", String(atB.plaintext))
        assertEquals("b1", String(atA.plaintext))

        // Y la conversación sigue viva en los dos sentidos después del cruce.
        val fromA2 = ratchet.encrypt(a, "a2".toByteArray()); a = fromA2.state
        assertEquals("a2", String(ratchet.decrypt(b, secret, fromA2.ciphertext).plaintext))
    }

    @Test
    fun `primer contacto con linajes distintos - el mayor gana y nada se pierde`() {
        // Cada lado crea su sesión por su cuenta, así que los linajes casi nunca coinciden.
        var a = ratchet.initial(secret, alice, bob, lineage = 5_000L)
        var b = ratchet.initial(secret, bob, alice, lineage = 9_000L)

        val fromA = ratchet.encrypt(a, "hola desde el linaje viejo".toByteArray()); a = fromA.state
        val fromB = ratchet.encrypt(b, "hola desde el nuevo".toByteArray()); b = fromB.state

        // B recibe un linaje menor: lo abre derivando su época 0 al vuelo, sin adoptarlo.
        val atB = ratchet.decrypt(b, secret, fromA.ciphertext); b = atB.state
        assertEquals("hola desde el linaje viejo", String(atB.plaintext))
        assertEquals(9_000L, b.lineage)

        // A recibe un linaje mayor: lo adopta y tira el suyo.
        val atA = ratchet.decrypt(a, secret, fromB.ciphertext); a = atA.state
        assertEquals("hola desde el nuevo", String(atA.plaintext))
        assertEquals(9_000L, a.lineage)

        val next = ratchet.encrypt(a, "ya convergidos".toByteArray()); a = next.state
        assertEquals("ya convergidos", String(ratchet.decrypt(b, secret, next.ciphertext).plaintext))
    }

    @Test
    fun `mensajes desordenados dentro de una cadena`() {
        var (a, b) = sessions()
        val sent = (1..3).map { ratchet.encrypt(a, "m$it".toByteArray()).also { s -> a = s.state } }

        // Llegan 3, 1, 2 (el buzón y el envío directo no se ordenan entre sí).
        val third = ratchet.decrypt(b, secret, sent[2].ciphertext); b = third.state
        assertEquals("m3", String(third.plaintext))
        val first = ratchet.decrypt(b, secret, sent[0].ciphertext); b = first.state
        assertEquals("m1", String(first.plaintext))
        val second = ratchet.decrypt(b, secret, sent[1].ciphertext); b = second.state
        assertEquals("m2", String(second.plaintext))
    }

    @Test
    fun `un mensaje en vuelo sobrevive al cambio de epoca`() {
        var (a, b) = sessions()
        // A envía en la época 0 y ese mensaje se queda por el camino (buzón, reintento…).
        val enVuelo = ratchet.encrypt(a, "tarde pero llega".toByteArray()); a = enVuelo.state

        // Mientras tanto la conversación avanza dos épocas por el otro camino.
        val m1 = ratchet.encrypt(a, "uno".toByteArray()); a = m1.state
        b = ratchet.decrypt(b, secret, m1.ciphertext).state
        val m2 = ratchet.encrypt(b, "dos".toByteArray()); b = m2.state
        a = ratchet.decrypt(a, secret, m2.ciphertext).state
        val m3 = ratchet.encrypt(a, "tres".toByteArray()); a = m3.state
        b = ratchet.decrypt(b, secret, m3.ciphertext).state
        assertTrue(b.epoch >= 2)

        val tarde = ratchet.decrypt(b, secret, enVuelo.ciphertext)
        assertEquals("tarde pero llega", String(tarde.plaintext))
    }

    @Test
    fun `un archivo troceado cruza un cambio de epoca`() {
        var (a, b) = sessions()
        // Ráfaga de "trozos" y, a mitad, un mensaje de vuelta que hace girar la época.
        val chunks = (0 until 40).map { ratchet.encrypt(a, "trozo $it".toByteArray()).also { s -> a = s.state } }
        for (i in 0 until 20) b = ratchet.decrypt(b, secret, chunks[i].ciphertext).state

        val vuelta = ratchet.encrypt(b, "voy recibiendo".toByteArray()); b = vuelta.state
        a = ratchet.decrypt(a, secret, vuelta.ciphertext).state
        val resto = (40 until 60).map { ratchet.encrypt(a, "trozo $it".toByteArray()).also { s -> a = s.state } }

        for (i in 20 until 40) {
            val open = ratchet.decrypt(b, secret, chunks[i].ciphertext); b = open.state
            assertEquals("trozo $i", String(open.plaintext))
        }
        for ((k, chunk) in resto.withIndex()) {
            val open = ratchet.decrypt(b, secret, chunk.ciphertext); b = open.state
            assertEquals("trozo ${40 + k}", String(open.plaintext))
        }
    }

    @Test
    fun `un duplicado del buzon ya no se puede abrir - hay que deduplicar antes`() {
        var (a, b) = sessions()
        val sealed = ratchet.encrypt(a, "una vez".toByteArray()); a = sealed.state
        val opened = ratchet.decrypt(b, secret, sealed.ciphertext); b = opened.state

        // La clave del mensaje se gasta. Por eso la reentrega del buzón se descarta por hash
        // ANTES de descifrar (§4.2 del diseño): si no, una repetición legítima parecería basura.
        assertThrows(RatchetException::class.java) { ratchet.decrypt(b, secret, sealed.ciphertext) }
    }

    @Test
    fun `un hueco enorme se rechaza en vez de derivar millones de claves`() {
        var (a, b) = sessions()
        repeat(Ratchet.MAX_SKIP + 2) { a = ratchet.encrypt(a, "x".toByteArray()).state }
        val lejano = ratchet.encrypt(a, "muy lejos".toByteArray())
        assertThrows(RatchetException::class.java) { ratchet.decrypt(b, secret, lejano.ciphertext) }
    }

    @Test
    fun `tocar la cabecera rompe la autenticacion`() {
        var (a, b) = sessions()
        val sealed = ratchet.encrypt(a, "intacto".toByteArray()); a = sealed.state
        val manipulado = sealed.ciphertext.copyOf()
        manipulado[14] = (manipulado[14] + 1).toByte() // el contador N
        assertThrows(RatchetException::class.java) { ratchet.decrypt(b, secret, manipulado) }
    }

    @Test
    fun `una cabecera forjada no mueve el estado`() {
        val (a, b) = sessions()
        val sealed = ratchet.encrypt(a, "hola".toByteArray())
        val forjado = sealed.ciphertext.copyOf()
        forjado[2] = 0x7F // un linaje altísimo: pediría reiniciar la sesión
        assertThrows(RatchetException::class.java) { ratchet.decrypt(b, secret, forjado) }
        // El estado que el llamante guarda es el que devuelve decrypt, y no ha devuelto ninguno.
        assertEquals(1_000L, b.lineage)
    }

    @Test
    fun `otro secreto compartido no abre nada`() {
        val (a, _) = sessions()
        val ajeno = ratchet.initial(ByteArray(32) { 9 }, bob, alice, lineage = 1_000L)
        val sealed = ratchet.encrypt(a, "privado".toByteArray())
        assertThrows(RatchetException::class.java) { ratchet.decrypt(ajeno, ByteArray(32) { 9 }, sealed.ciphertext) }
    }

    @Test
    fun `perder el estado no rompe la conversacion para siempre`() {
        var (a, b) = sessions()
        val m1 = ratchet.encrypt(a, "antes".toByteArray()); a = m1.state
        b = ratchet.decrypt(b, secret, m1.ciphertext).state
        val m2 = ratchet.encrypt(b, "y otro".toByteArray()); b = m2.state
        a = ratchet.decrypt(a, secret, m2.ciphertext).state

        // A reinstala: estado a cero, linaje nuevo. Puede escribir sin negociar nada.
        a = ratchet.initial(secret, alice, bob, lineage = 50_000L)
        val despues = ratchet.encrypt(a, "después de reinstalar".toByteArray()); a = despues.state
        val atB = ratchet.decrypt(b, secret, despues.ciphertext); b = atB.state
        assertEquals("después de reinstalar", String(atB.plaintext))
        assertEquals(50_000L, b.lineage)

        val vuelta = ratchet.encrypt(b, "te leo".toByteArray()); b = vuelta.state
        assertEquals("te leo", String(ratchet.decrypt(a, secret, vuelta.ciphertext).plaintext))
    }

    @Test
    fun `lo viejo deja de poder abrirse cuando pasan las epocas`() {
        var (a, b) = sessions()
        // Primero salir de la época 0, que es derivable del secreto compartido y por definición
        // no tiene PFS: un mensaje suyo se puede abrir siempre, y así está documentado (§1.1).
        val arranque = ratchet.encrypt(b, "arranca".toByteArray()); b = arranque.state
        a = ratchet.decrypt(a, secret, arranque.ciphertext).state
        assertTrue(a.epoch >= 1)

        // Un mensaje que B sí recibe, y justo detrás otro que se pierde para siempre. Que el
        // perdido sea el **último** de su cadena es lo que hace la prueba honesta: si B hubiera
        // recibido alguno posterior, su clave quedaría guardada como saltada a propósito, que es
        // otra cosa distinta de que el ratchet la conserve.
        val recibido = ratchet.encrypt(a, "este llega".toByteArray()); a = recibido.state
        b = ratchet.decrypt(b, secret, recibido.ciphertext).state
        val viejo = ratchet.encrypt(a, "el pasado".toByteArray()); a = viejo.state

        // Unas cuantas épocas después su cadena ya no está en ninguna parte: ni entre las
        // retiradas ni entre las saltadas. Esa es la forma observable del secreto hacia adelante.
        repeat(Ratchet.MAX_PAST_CHAINS + 2) {
            val ping = ratchet.encrypt(b, "ping".toByteArray()); b = ping.state
            a = ratchet.decrypt(a, secret, ping.ciphertext).state
            val pong = ratchet.encrypt(a, "pong".toByteArray()); a = pong.state
            b = ratchet.decrypt(b, secret, pong.ciphertext).state
        }
        assertThrows(RatchetException::class.java) { ratchet.decrypt(b, secret, viejo.ciphertext) }
    }

    @Test
    fun `el estado va y vuelve de su forma serializada`() {
        var (a, b) = sessions()
        val m1 = ratchet.encrypt(a, "uno".toByteArray()); a = m1.state
        b = ratchet.decrypt(b, secret, m1.ciphertext).state
        val saltado = ratchet.encrypt(a, "se pierde".toByteArray()); a = saltado.state
        val siguiente = ratchet.encrypt(a, "llega antes".toByteArray()); a = siguiente.state
        b = ratchet.decrypt(b, secret, siguiente.ciphertext).state
        assertTrue(b.skipped.isNotEmpty())

        val revivido = RatchetState.decode(b.encode())
        assertEquals(b, revivido)
        // Y sirve para lo que existe: el mensaje que faltaba se abre con el estado resucitado.
        assertEquals("se pierde", String(ratchet.decrypt(revivido, secret, saltado.ciphertext).plaintext))
    }

    @Test
    fun `dos linajes no comparten las claves de la epoca 0`() {
        val uno = ratchet.initial(secret, alice, bob, lineage = 1L)
        val otro = ratchet.initial(secret, alice, bob, lineage = 2L)
        assertNotEquals(
            uno.sendChain.toList(),
            otro.sendChain.toList(),
        )
    }

    @Test
    fun `el sentido de la cadena es opuesto en cada extremo`() {
        val (a, b) = sessions()
        assertEquals(0, a.sendDir)
        assertEquals(1, b.sendDir)
        assertArrayEquals(a.sendChain, b.recvChain)
        assertArrayEquals(a.recvChain, b.sendChain)
    }
}

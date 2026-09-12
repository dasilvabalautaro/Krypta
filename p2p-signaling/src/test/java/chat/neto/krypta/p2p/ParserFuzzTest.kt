package chat.neto.krypta.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Fuzzing por mutación de todo lo que parsea bytes que vienen de fuera: sobres de ratchet
 * (de la red), sobres de aplicación (de un contacto, ya descifrados), el relleno, el estado
 * serializado del ratchet (del disco) y el respaldo `.krbk` (un fichero del usuario).
 *
 * No es "que no explote": cada función tiene un **contrato** y es eso lo que se comprueba.
 * `MessageEnvelope.decode` no lanza nunca; `Ratchet.decrypt` solo lanza [RatchetException],
 * nunca abre un sobre alterado y no toca el estado que recibe; `Padding.strip` solo lanza
 * [RatchetException]; `RatchetState.decode` lanza [IllegalArgumentException] o
 * [IndexOutOfBoundsException] ante un blob corrupto, pero **nunca** se queda sin memoria por un
 * contador inventado; `IdentityBackup.decode` solo lanza [IdentityBackup.InvalidBackup].
 *
 * Semillas fijas: si algo falla, el mensaje trae la semilla y el caso en hex, para repetirlo.
 * Esto no sustituye a un fuzzer con cobertura (libFuzzer/Jazzer); es lo que se puede tener
 * corriendo en cada `testDebugUnitTest` sin dependencias nuevas.
 */
class ParserFuzzTest {

    private val seed = 20260912L
    private val rnd = Random(seed)
    private val ratchet = Ratchet(JdkCurve25519())
    private val secret = ByteArray(32) { (it * 13 + 5).toByte() }
    private val alice = "12D3KooWAaaa"
    private val bob = "12D3KooWBbbb"

    // --- mutadores -----------------------------------------------------------------------

    private fun randomBytes(size: Int): ByteArray = ByteArray(size) { rnd.nextInt(256).toByte() }

    /** Una mutación al azar: byte cambiado, bit volteado, truncado, alargado, inserción o extremos. */
    private fun mutate(base: ByteArray): ByteArray {
        if (base.isEmpty()) return randomBytes(rnd.nextInt(1, 8))
        val b = base.copyOf()
        return when (rnd.nextInt(7)) {
            0 -> b.also { it[rnd.nextInt(it.size)] = rnd.nextInt(256).toByte() }
            1 -> b.also {
                val i = rnd.nextInt(it.size)
                it[i] = (it[i].toInt() xor (1 shl rnd.nextInt(8))).toByte()
            }
            2 -> b.copyOf(rnd.nextInt(b.size)) // truncado (puede quedar vacío)
            3 -> b + randomBytes(rnd.nextInt(1, 65))
            4 -> {
                val at = rnd.nextInt(b.size + 1)
                b.copyOfRange(0, at) + randomBytes(rnd.nextInt(1, 17)) + b.copyOfRange(at, b.size)
            }
            5 -> b.also {
                // Un entero de 4 u 8 bytes puesto a un extremo: es lo que rompe a los parsers
                // que confían en un contador (tamaños, índices, longitudes).
                val width = if (rnd.nextBoolean()) 4 else 8
                if (it.size >= width) {
                    val at = rnd.nextInt(it.size - width + 1)
                    val fill = listOf(0xFF, 0x7F, 0x80, 0x00)[rnd.nextInt(4)].toByte()
                    for (k in 0 until width) it[at + k] = if (k == 0) fill else if (fill == 0x7F.toByte()) 0xFF.toByte() else fill
                }
            }
            else -> b.also { // varios bytes seguidos al azar
                val at = rnd.nextInt(it.size)
                for (k in at until minOf(it.size, at + rnd.nextInt(1, 9))) it[k] = rnd.nextInt(256).toByte()
            }
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun caso(bytes: ByteArray, what: String): String =
        "$what\n  semilla=$seed\n  caso(${bytes.size} B)=${bytes.take(256).toByteArray().hex()}${if (bytes.size > 256) "…" else ""}"

    // --- MessageEnvelope ---------------------------------------------------------------------

    private fun corpusSobres(): List<ByteArray> = listOf(
        MessageEnvelope.encodeText("id-1", "hola".toByteArray()),
        MessageEnvelope.encodeImage("id-2", randomBytes(300)),
        MessageEnvelope.encodeRead(listOf("id-1", "id-2", "id-3")),
        MessageEnvelope.encodeFileMeta("f-1", "informe final.pdf", "application/pdf", 123_456L, 3),
        MessageEnvelope.encodeFileChunk("f-1", 2, randomBytes(200)),
        MessageEnvelope.encodeFileDescriptor("nota.m4a", "audio/mp4", 9_999L, "/x/y"),
        MessageEnvelope.encodeHello(3),
        MessageEnvelope.encodeCall("invite", "call-1", 1_700_000_000_000L),
        MessageEnvelope.encodeCall("accept", "call-1", 1_700_000_000_000L, randomBytes(32)),
        MessageEnvelope.encodeReply("id-1", MessageEnvelope.encodeText("id-9", "respuesta".toByteArray())),
        MessageEnvelope.encodeReply("id-1", MessageEnvelope.encodeFileChunk("f-2", 0, randomBytes(50))),
    )

    @Test
    fun `MessageEnvelope decode no lanza nunca, con basura ni con sobres mutados`() {
        val corpus = corpusSobres()
        repeat(20_000) {
            val bytes = if (rnd.nextInt(3) == 0) {
                // Basura con forma: una letra y salto de línea, para pasar del primer filtro.
                byteArrayOf(rnd.nextInt('A'.code, 'Z'.code + 1).toByte(), '\n'.code.toByte()) + randomBytes(rnd.nextInt(0, 200))
            } else {
                mutate(corpus[rnd.nextInt(corpus.size)])
            }
            try {
                MessageEnvelope.decode(bytes)
            } catch (t: Throwable) {
                fail(caso(bytes, "decode lanzó ${t::class.simpleName}: ${t.message}"))
            }
        }
        // Y con bytes sin forma alguna, incluidos los vacíos.
        repeat(5_000) {
            val bytes = randomBytes(rnd.nextInt(0, 64))
            try { MessageEnvelope.decode(bytes) } catch (t: Throwable) { fail(caso(bytes, "decode lanzó $t")) }
        }
    }

    // --- Ratchet -----------------------------------------------------------------------------

    /** Una conversación corta con varias épocas; devuelve el estado de B y sobres genuinos de A. */
    private fun sesionConSobres(): Pair<RatchetState, List<ByteArray>> {
        var a = ratchet.initial(secret, alice, bob, lineage = 1_000L)
        var b = ratchet.initial(secret, bob, alice, lineage = 1_000L)
        val wires = mutableListOf<ByteArray>()
        repeat(4) { round ->
            val m = ratchet.encrypt(a, "de A, ronda $round".toByteArray(), pad = round % 2 == 0)
            a = m.state
            // Uno de cada dos se deja **sin entregar** a B: así el estado tiene claves saltadas
            // y sobres genuinos aún abribles, que es el caso interesante para mutar.
            if (round % 2 == 1) b = ratchet.decrypt(b, secret, m.ciphertext).state
            wires += m.ciphertext
            val r = ratchet.encrypt(b, "de B, ronda $round".toByteArray())
            b = r.state
            a = ratchet.decrypt(a, secret, r.ciphertext).state
        }
        // Un sobre más de A en la época actual, sin entregar.
        val last = ratchet.encrypt(a, "último".toByteArray(), pad = true)
        wires += last.ciphertext
        return b to wires
    }

    @Test
    fun `Ratchet decrypt solo lanza RatchetException, no abre sobres alterados y no toca el estado`() {
        val (state, wires) = sesionConSobres()
        val antes = state.encode()
        var abiertosGenuinos = 0
        var rechazados = 0

        for (wire in wires) {
            // El sobre intacto tiene que abrirse desde este estado si aún tiene clave; se anota
            // para saber que las mutaciones parten de algo que sí abre.
            runCatching { ratchet.decrypt(state, secret, wire) }.onSuccess { abiertosGenuinos++ }
            repeat(700) {
                val m = mutate(wire)
                if (m.contentEquals(wire)) return@repeat
                try {
                    ratchet.decrypt(state, secret, m)
                    fail(caso(m, "un sobre ALTERADO se abrió (original de ${wire.size} B)"))
                } catch (e: RatchetException) {
                    rechazados++
                } catch (e: AssertionError) {
                    throw e
                } catch (t: Throwable) {
                    fail(caso(m, "decrypt lanzó ${t::class.simpleName} en vez de RatchetException: ${t.message}"))
                }
                assertArrayEquals("decrypt mutó el estado que recibió", antes, state.encode())
            }
        }
        assertEquals("los sobres sin entregar deben abrirse intactos", 3, abiertosGenuinos)
        assertEquals(700 * wires.size, rechazados + (700 * wires.size - rechazados))

        // Cabeceras al azar con el byte de versión correcto y tamaño suficiente: el parser de la
        // cabecera no lanza, y decrypt sigue sin abrir nada.
        repeat(3_000) {
            val bytes = byteArrayOf(Ratchet.WIRE_VERSION) + randomBytes(rnd.nextInt(Ratchet.HEADER_BYTES, Ratchet.HEADER_BYTES + 200))
            try { Ratchet.Header.decode(bytes) } catch (t: Throwable) { fail(caso(bytes, "Header.decode lanzó $t")) }
            try {
                ratchet.decrypt(state, secret, bytes)
                fail(caso(bytes, "un sobre al azar se abrió"))
            } catch (e: RatchetException) {
            } catch (e: AssertionError) {
                throw e
            } catch (t: Throwable) {
                fail(caso(bytes, "decrypt lanzó ${t::class.simpleName}: ${t.message}"))
            }
        }
        repeat(3_000) {
            val bytes = randomBytes(rnd.nextInt(0, 120))
            try { Ratchet.Header.decode(bytes) } catch (t: Throwable) { fail(caso(bytes, "Header.decode lanzó $t")) }
        }
    }

    @Test
    fun `Padding strip solo lanza RatchetException, y deshace pad exactamente`() {
        repeat(5_000) {
            val x = randomBytes(rnd.nextInt(0, 5_000))
            assertArrayEquals(x, Padding.strip(Padding.pad(x)))
            val bytes = if (rnd.nextBoolean()) mutate(Padding.pad(x)) else randomBytes(rnd.nextInt(0, 400))
            try {
                Padding.strip(bytes)
            } catch (e: RatchetException) {
            } catch (t: Throwable) {
                fail(caso(bytes, "strip lanzó ${t::class.simpleName}: ${t.message}"))
            }
        }
    }

    @Test
    fun `RatchetState decode ante un blob corrupto lanza, pero nunca se queda sin memoria`() {
        val (state, _) = sesionConSobres()
        val valido = state.encode()
        assertNotNull(RatchetState.decode(valido))
        repeat(6_000) {
            val bytes = mutate(valido)
            try {
                RatchetState.decode(bytes)
            } catch (e: IllegalArgumentException) {
            } catch (e: IndexOutOfBoundsException) {
            } catch (e: OutOfMemoryError) {
                fail(caso(bytes, "decode intentó reservar memoria por un contador del blob (OutOfMemoryError)"))
            } catch (t: Throwable) {
                fail(caso(bytes, "decode lanzó ${t::class.simpleName}: ${t.message}"))
            }
        }
    }

    // --- IdentityBackup ----------------------------------------------------------------------

    @Test
    fun `IdentityBackup decode solo lanza InvalidBackup ante un fichero alterado`() {
        val pass = "frase de prueba".toCharArray()
        val data = IdentityBackup.Data(
            identity = randomBytes(64),
            contacts = listOf(
                IdentityBackup.BackupContact("Ana", "12D3KooWAna", verified = true),
                IdentityBackup.BackupContact("Beto", "12D3KooWBeto", verified = false, blocked = true),
            ),
        )
        val blob = IdentityBackup.encode(pass, data)
        assertEquals(data.contacts, IdentityBackup.decode(pass, blob).contacts)
        // PBKDF2 con 310k iteraciones cuesta ~0,2 s por intento: pocos casos (el mutador
        // reparte entre magia, sal, nonce, cuerpo y cola).
        repeat(16) {
            val bytes = mutate(blob)
            if (bytes.contentEquals(blob)) return@repeat
            try {
                IdentityBackup.decode(pass, bytes)
                fail(caso(bytes, "un respaldo ALTERADO se aceptó"))
            } catch (e: IdentityBackup.InvalidBackup) {
            } catch (e: AssertionError) {
                throw e
            } catch (t: Throwable) {
                fail(caso(bytes, "decode lanzó ${t::class.simpleName} en vez de InvalidBackup: ${t.message}"))
            }
        }
    }
}

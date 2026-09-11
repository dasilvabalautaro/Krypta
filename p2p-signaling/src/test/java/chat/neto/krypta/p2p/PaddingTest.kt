package chat.neto.krypta.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El relleno por tramos ([Padding]). Lo que importa aquí no es el ida y vuelta —que es lo
 * fácil— sino las dos familias de fallo que tiene este esquema: **contenido que se parece al
 * relleno** (un texto que acaba en ceros, o justo en el byte del terminador) y los **bordes de
 * tramo**, donde un error de uno delata el tamaño real que se intentaba esconder.
 */
class PaddingTest {

    private fun ida(bytes: ByteArray) = Padding.strip(Padding.pad(bytes))

    @Test
    fun `el texto vuelve intacto en cualquier tamano`() {
        // Los bordes de cada tramo y unos cuantos de por medio, incluido el vacío.
        val tamanos = listOf(
            0, 1, 2, 63, 158, 159, 160, 161, 320,
            4 * 1024 - 2, 4 * 1024 - 1, 4 * 1024, 4 * 1024 + 1,
            48 * 1024, 58 * 1024, 64 * 1024 - 1, 64 * 1024, 64 * 1024 + 1, 70 * 1024,
        )
        for (n in tamanos) {
            val original = ByteArray(n) { (it * 31 + 7).toByte() }
            assertArrayEquals("no volvió intacto con $n bytes", original, ida(original))
        }
    }

    /**
     * El caso que rompe un esquema mal hecho: el texto **acaba en ceros**. Los suyos quedan
     * antes del terminador, así que el barrido hacia atrás tiene que pararse en el `0x80` y no
     * comerse los del contenido.
     */
    @Test
    fun `un texto que acaba en ceros no pierde sus ceros`() {
        val original = byteArrayOf(1, 2, 3, 0, 0, 0)
        assertArrayEquals(original, ida(original))
    }

    /** Y el gemelo: el texto acaba justo en el byte que se usa de terminador. */
    @Test
    fun `un texto que acaba en el byte del terminador vuelve intacto`() {
        val original = byteArrayOf(1, 2, -0x80)
        assertArrayEquals(original, ida(original))
        // El caso peor de todos: solo el terminador, y después ceros del contenido.
        val duro = byteArrayOf(-0x80, 0, 0)
        assertArrayEquals(duro, ida(duro))
    }

    /**
     * El objetivo de todo esto: dos mensajes de tamaños distintos dentro del mismo tramo tienen
     * que salir **idénticos en longitud**. Es lo que borra la diferencia entre un acuse de
     * lectura, un anuncio de capacidad y un «vale».
     */
    @Test
    fun `mensajes de control distintos acaban midiendo lo mismo`() {
        val acuse = MessageEnvelope.encodeRead(List(2) { "11111111-2222-3333-4444-55555555555$it" })
        val hola = MessageEnvelope.encodeHello(3)
        val corto = MessageEnvelope.encodeText("11111111-2222-3333-4444-555555555555", "vale".toByteArray())

        // Sin relleno se distinguen por el tamaño, que es justo el problema.
        assertTrue("el test no probaría nada si ya midieran igual", acuse.size != hola.size)

        assertEquals(160, Padding.pad(hola).size)
        assertEquals(160, Padding.pad(corto).size)
        assertEquals(160, Padding.pad(acuse).size)
    }

    @Test
    fun `el tamano siempre es multiplo del tramo que le toca`() {
        for (n in 0..(5 * 1024)) {
            val size = Padding.pad(ByteArray(n)).size
            val tramo = if (n + 1 <= 4 * 1024) 160 else 1024
            assertEquals("con $n bytes el tamaño no cae en un borde de tramo", 0, size % tramo)
            assertTrue("con $n bytes el relleno no llega ni al terminador", size > n)
        }
    }

    @Test
    fun `el sobrecoste esta acotado`() {
        // Tramo fino: nunca más de 160 bytes de más.
        for (n in listOf(0, 1, 100, 1000, 4000)) {
            assertTrue("$n", Padding.pad(ByteArray(n)).size - n <= 160)
        }
        // Tramo grueso: nunca más de 1 KiB. Para un trozo de archivo son <2 %.
        for (n in listOf(5 * 1024, 48 * 1024, 58 * 1024)) {
            assertTrue("$n", Padding.pad(ByteArray(n)).size - n <= 1024)
        }
        // Y por encima del límite del buzón no se rellena: solo el terminador.
        assertEquals(70 * 1024 + 1, Padding.pad(ByteArray(70 * 1024)).size)
    }

    /**
     * Un trozo de archivo rellenado tiene que seguir cabiendo en el buzón (64 KiB de blob),
     * contando la cabecera del ratchet y el tag de GCM. Si esto se rompiera, los archivos
     * dejarían de entregarse offline — y sería por el relleno.
     */
    @Test
    fun `un trozo de archivo relleno sigue cabiendo en el buzon`() {
        val sobre = MessageEnvelope.encodeFileChunk(
            "11111111-2222-3333-4444-555555555555", 999, ByteArray(ChatService.CHUNK_SIZE),
        )
        val enLaRed = Padding.pad(sobre).size + Ratchet.HEADER_BYTES + 16 // 16 = tag de GCM
        assertTrue("$enLaRed bytes no caben en el blob de 64 KiB del buzón", enLaRed <= 64 * 1024)
    }

    @Test
    fun `sin terminador no se abre`() {
        // Todo ceros: nunca aparece el 0x80, así que no hay forma de saber dónde acaba el texto.
        assertThrows(RatchetException::class.java) { Padding.strip(ByteArray(160)) }
        assertThrows(RatchetException::class.java) { Padding.strip(ByteArray(0)) }
        // Y un último byte que no es ni cero ni el terminador.
        assertThrows(RatchetException::class.java) { Padding.strip(byteArrayOf(1, 2, 3)) }
    }
}

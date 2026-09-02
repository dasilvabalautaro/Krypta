package chat.neto.krypta.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * Cubre el troceado de [linkifyText]: los desplazamientos al intercalar enlaces y texto son
 * justo donde se cuela un off-by-one que se comería o duplicaría caracteres del mensaje.
 *
 * Se inyecta un patrón propio porque `Patterns.WEB_URL` es de Android y aquí no existe.
 */
class MessageTextTest {

    private val url = Pattern.compile("""(https?://)?[a-zA-Z0-9.-]+\.(com|org|net)(/\S*)?""")

    private fun render(text: String) = linkifyText(text, pattern = url).text

    @Test
    fun `texto sin enlaces se devuelve intacto`() {
        val plain = "Nos vemos a las 5 en el sitio de siempre."
        assertEquals(plain, render(plain))
    }

    @Test
    fun `el texto visible no cambia al detectar un enlace`() {
        val t = "Mira esto: https://ejemplo.com/foto y me dices."
        assertEquals(t, render(t))
    }

    @Test
    fun `varios enlaces conservan todo el texto intermedio`() {
        val t = "uno ejemplo.com dos otro.org tres"
        assertEquals(t, render(t))
    }

    @Test
    fun `un enlace al principio y otro al final no pierden caracteres`() {
        val t = "ejemplo.com en medio otro.net"
        assertEquals(t, render(t))
    }

    @Test
    fun `se anota un enlace por cada coincidencia`() {
        val a = linkifyText("uno ejemplo.com dos otro.org", pattern = url)
        // Las anotaciones de enlace son las que hacen tocable el texto.
        assertEquals(2, a.getLinkAnnotations(0, a.length).size)
    }

    @Test
    fun `a un enlace sin esquema se le antepone https`() {
        val a = linkifyText("ve a ejemplo.com", pattern = url)
        val link = a.getLinkAnnotations(0, a.length).single().item
        assertTrue("esperaba https://, fue $link", link.toString().contains("https://ejemplo.com"))
    }

    @Test
    fun `un enlace que ya trae esquema no se duplica`() {
        val a = linkifyText("ve a http://ejemplo.com", pattern = url)
        val link = a.getLinkAnnotations(0, a.length).single().item.toString()
        assertTrue("no debería reescribirse: $link", link.contains("http://ejemplo.com"))
        assertTrue("esquema duplicado: $link", !link.contains("https://http"))
    }
}

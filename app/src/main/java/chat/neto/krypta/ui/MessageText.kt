package chat.neto.krypta.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Patterns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/**
 * Copia texto de un mensaje al portapapeles **marcándolo como sensible**.
 *
 * El `EXTRA_IS_SENSITIVE` (API 33+) es lo que evita que Android muestre el contenido en la
 * previsualización del portapapeles al copiarlo. Sin él, copiar un mensaje de un chat cifrado
 * lo pinta en pantalla en un globo que puede ver quien tenga el móvil delante — justo lo que
 * la pantalla de chat evita con `FLAG_SECURE`.
 *
 * No se usa `LocalClipboardManager` de Compose (como sí hace Ajustes con el PeerID) porque su
 * API no permite adjuntar extras a la `ClipDescription`.
 *
 * Copiar saca el texto del cerco E2EE, igual que guardar una captura en la galería: a partir
 * de aquí lo custodia el sistema. Es una decisión del usuario, y por eso está tras una
 * pulsación larga y no a un toque.
 */
fun copyMessageText(context: Context, text: String) {
    runCatching {
        val clip = ClipData.newPlainText("Krypta", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }
}

/**
 * Convierte el texto del mensaje en un [AnnotatedString] con los enlaces tocables.
 *
 * Se detectan con `Patterns.WEB_URL`, del propio Android: no añade dependencias y es el mismo
 * criterio que usa el sistema. El estilo lo decide quien llama ([linkColor]) porque el color
 * legible depende del fondo de la burbuja, que es distinto en las propias (`primary`) y en las
 * recibidas (`surfaceContainerHighest`).
 *
 * Sin coincidencias devuelve el texto tal cual, así que el caso normal no paga nada.
 */
@Composable
fun rememberLinkifiedText(text: String, linkColor: Color): AnnotatedString =
    remember(text, linkColor) { linkifyText(text, linkColor) }

/**
 * Parte pura de [rememberLinkifiedText].
 *
 * [pattern] es inyectable solo para poder probar el troceado con tests JVM: `Patterns.WEB_URL`
 * es de Android y en un test de JVM pelado no está implementado. En producción nunca se pasa.
 */
fun linkifyText(
    text: String,
    linkColor: Color = Color.Unspecified,
    pattern: java.util.regex.Pattern = Patterns.WEB_URL,
): AnnotatedString {
    val matcher = pattern.matcher(text)
    if (!matcher.find()) return AnnotatedString(text)
    matcher.reset()

    val styles = TextLinkStyles(
        style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
    )
    return buildAnnotatedString {
        var last = 0
        while (matcher.find()) {
            append(text.substring(last, matcher.start()))
            val url = matcher.group()
            // Patterns.WEB_URL acepta "ejemplo.com" sin esquema; el navegador necesita uno.
            val href = if (url.contains("://")) url else "https://$url"
            withLink(LinkAnnotation.Url(href, styles)) { append(url) }
            last = matcher.end()
        }
        append(text.substring(last))
    }
}

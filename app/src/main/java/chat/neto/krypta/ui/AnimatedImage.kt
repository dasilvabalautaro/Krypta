package chat.neto.krypta.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Burbuja de **imagen animada** (GIF y WebP animado) a partir del archivo en disco.
 *
 * Usa solo el framework: `ImageDecoder` + [AnimatedImageDrawable] (API 28+, y `minSdk` es 30),
 * así que no entra ninguna dependencia nueva — se valoró Coil y no compensa por una burbuja.
 * Compose no sabe pintar un `Drawable`, así que se dibuja a mano sobre el `Canvas` nativo y se
 * fuerza un repintado por fotograma con [withFrameNanos]: `AnimatedImageDrawable.draw()` avanza
 * el fotograma según el tiempo transcurrido, de modo que redibujar es lo único que hace falta
 * para que la animación corra (no hace falta montar un `Drawable.Callback` con su scheduler).
 * La lectura de `tick` ocurre **dentro** del bloque de dibujo: invalida solo la fase de dibujo,
 * no recompone.
 *
 * El bucle vive con la composición: al salir de pantalla (scroll) se cancela y el drawable se
 * para, así que un chat con varios GIF no se queda animándolos todos a la vez.
 *
 * Si el archivo no existe o no se puede decodificar (p. ej. el adjunto se borró), pinta
 * [fallback] — la burbuja de archivo de siempre.
 */
@Composable
fun AnimatedImage(
    path: String,
    maxSize: Dp = 240.dp,
    fallback: @Composable () -> Unit,
) {
    // El adjunto está cifrado en reposo, así que se decodifica desde los bytes ya descifrados
    // (`ByteBuffer`) y no desde el fichero. Mientras llegan se pinta el fallback: el GIF
    // aparece cuando está listo, en vez de dejar un hueco.
    val reader = LocalAttachmentReader.current
    val bytes by produceState<ByteArray?>(initialValue = null, path) {
        value = reader(path)
    }
    val datos = bytes ?: run { fallback(); return }
    val drawable: Drawable? = remember(path, datos) {
        runCatching {
            val source = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(datos))
            ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                // Submuestreo: acota la memoria del bitmap (un GIF grande a pantalla completa
                // no aporta nada en una burbuja de ~240 dp).
                decoder.setTargetSampleSize(sampleSizeFor(info.size.width, info.size.height))
            }
        }.getOrNull()
    }
    if (drawable == null || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
        fallback()
        return
    }

    val animated = drawable as? AnimatedImageDrawable
    DisposableEffect(drawable) {
        animated?.start()
        onDispose { animated?.stop() }
    }

    var tick by remember(drawable) { mutableIntStateOf(0) }
    LaunchedEffect(drawable) {
        if (animated == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            tick++
        }
    }

    // Encaja el tamaño intrínseco en un cuadro de [maxSize] conservando la proporción.
    val w = drawable.intrinsicWidth
    val h = drawable.intrinsicHeight
    val scale = maxSize.value / max(w, h).toFloat()
    val boxW = (w * scale).roundToInt().coerceAtLeast(1).dp
    val boxH = (h * scale).roundToInt().coerceAtLeast(1).dp

    Canvas(
        Modifier
            .size(boxW, boxH)
            .clip(RoundedCornerShape(8.dp))
            .semantics { contentDescription = "GIF animado" },
    ) {
        tick // leído en la fase de DIBUJO: invalida el repintado, no la composición
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }
}

/** Potencia de 2 que deja el lado mayor por debajo de [MAX_DECODED_PX]. */
private fun sampleSizeFor(width: Int, height: Int): Int {
    var sample = 1
    while (max(width, height) / sample > MAX_DECODED_PX) sample *= 2
    return sample
}

private const val MAX_DECODED_PX = 720

/** Mimes que llegan como archivo pero se pintan como imagen animada. */
val ANIMATED_IMAGE_MIMES = setOf("image/gif", "image/webp")

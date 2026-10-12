package chat.neto.krypta.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Popup
import chat.neto.krypta.core.model.MessageStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// La burbuja de un mensaje y lo que va dentro (cita, archivo, nota de voz, estado). Se separó de
// ChatScreens.kt el 12 oct 2026 (§5 de docs/AUDITORIA-2026-10-12.md).

@Composable
internal fun MessageBubble(
    message: DisplayMessage,
    first: Boolean,
    onRetry: () -> Unit,
    onReply: () -> Unit = {},
    onQuoteClick: (String) -> Unit = {},
    highlighted: Boolean = false,
) {
    val align = if (message.mine) Alignment.CenterEnd else Alignment.CenterStart
    val failed = message.mine && message.status == MessageStatus.FAILED
    // Solo hay algo que copiar en las burbujas de texto: en imagen, archivo, audio y GIF el
    // `text` va vacío (lo pone ChatViewModel al mapear el contenido).
    val copyable = message.text.isNotBlank()
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    // Deslizar para responder: el gesto de toda la mensajería. Se arrastra hacia la derecha
    // (en las dos orillas, como WhatsApp) y al pasar el umbral vibra y abre la respuesta; al
    // soltar, la burbuja vuelve animada a su sitio. `detectHorizontalDragGestures` solo
    // consume tras superar el slop horizontal, así que el scroll vertical de la lista sigue
    // funcionando igual.
    val scope = rememberCoroutineScope()
    val dragX = remember(message.id) { Animatable(0f) }
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { SWIPE_REPLY_THRESHOLD.toPx() }
    // Un color de fondo por rol con su pareja "on" correcta. La burbuja **propia** usa
    // `primary` (color de marca saturado), no `primaryContainer`: primary es oscuro en tema
    // claro y brillante en oscuro, así queda con luminosidad **opuesta** a la recibida (un
    // gris neutro) en ambos temas — el contraste que necesita quien tiene baja visión para
    // distinguir de un vistazo quién escribió cada mensaje. Con `primaryContainer` (menta muy
    // claro) y la recibida (gris muy claro) tenían casi la misma luminosidad en tema claro.
    val (bg, onBg) = when {
        failed -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        message.mine -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurface
    }
    // Destello al saltar aquí desde una cita: el color se mezcla con el terciario y vuelve
    // solo. Sin él, tras el scroll no se sabe cuál de los mensajes a la vista era el citado.
    val bubbleBg by animateColorAsState(
        if (highlighted) lerp(bg, MaterialTheme.colorScheme.tertiary, 0.35f) else bg,
        label = "bubble-highlight",
    )
    // Esquina "cola" (menos redondeada) hacia el lado del emisor; en un grupo de mensajes
    // consecutivos solo el primero abre la esquina superior de ese lado. El radio **no es
    // fijo**: crece con la altura de la burbuja (ver [BubbleShape]).
    val shape = remember(message.mine, first) { BubbleShape(mine = message.mine, first = first) }
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    // BoxWithConstraints para conocer el ancho disponible de la fila: la burbuja se topa a
    // una fracción de él (ver BUBBLE_MAX_WIDTH_RATIO). Sin tope, la **silueta la decidía el
    // contenido**: un texto largo, una imagen ancha o —desde que hay respuestas— la cita de
    // un mensaje largo estiraban la burbuja de lado a lado, y dos mensajes seguidos salían
    // con anchos muy distintos. Con el tope, el contenido se ajusta dentro de una forma
    // estable en vez de definirla.
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(top = if (first) 6.dp else 0.dp)
            .pointerInput(message.id) {
                var passedThreshold = false
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragX.value >= swipeThresholdPx) onReply()
                        passedThreshold = false
                        scope.launch { dragX.animateTo(0f) }
                    },
                    onDragCancel = {
                        passedThreshold = false
                        scope.launch { dragX.animateTo(0f) }
                    },
                ) { change, amount ->
                    // Solo hacia la derecha, y con tope: el gesto es un tirón corto, no
                    // arrastrar la burbuja por la pantalla.
                    val next = (dragX.value + amount).coerceIn(0f, swipeThresholdPx * 1.3f)
                    change.consume()
                    scope.launch { dragX.snapTo(next) }
                    // Vibra al cruzar el umbral (una vez): confirma que soltando ya responde.
                    if (!passedThreshold && next >= swipeThresholdPx) {
                        passedThreshold = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
            },
        contentAlignment = align,
    ) {
        // Flecha que asoma detrás de la burbuja conforme se arrastra: la pista visual de que
        // el gesto va a responder (aparece solo mientras se desliza).
        if (dragX.value > 0f) {
            Icon(
                KryptaReplyIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(22.dp)
                    .alpha((dragX.value / swipeThresholdPx).coerceIn(0f, 1f)),
            )
        }
        Column(
            Modifier
                .widthIn(max = maxWidth * BUBBLE_MAX_WIDTH_RATIO)
                .offset { IntOffset(dragX.value.roundToInt(), 0) }
                // Sombra sutil: sin ella, dos mensajes seguidos del mismo lado (mismo color
                // exacto, solo 2dp de separación) se leían como un único bloque en vez de
                // burbujas distintas — el propio contraste "entre ellas" que hacía falta.
                // Va por `graphicsLayer` en vez de `Modifier.shadow` porque la elevación
                // **también escala con la altura**: dentro del bloque se lee el `size` ya
                // medido, así que el ajuste ocurre al dibujar, sin recomponer (con
                // `onSizeChanged` la burbuja se pintaría un fotograma con el valor corto).
                .graphicsLayer {
                    shadowElevation = bubbleShadowPx(size, this)
                    this.shape = shape
                    clip = false
                }
                .clip(shape)
                .background(bubbleBg)
                // Las burbujas recibidas son un gris neutro cercano al fondo; un borde fino
                // les da un segundo nivel de contraste con la pantalla (mismo criterio que
                // las tarjetas de Ajustes). Se pinta a mano —y no con `Modifier.border`—
                // por lo mismo que la sombra: el grosor sale del tamaño real.
                .then(
                    if (!message.mine && !failed) {
                        Modifier.drawWithCache {
                            val outline = shape.createOutline(size, layoutDirection, this)
                            // El trazo va **centrado** en el contorno y el `clip(shape)` de
                            // arriba se come la mitad exterior: por eso se dibuja al doble,
                            // para que quede visible justo el grosor pedido, por dentro.
                            val stroke = Stroke(bubbleBorderPx(size, this) * 2f)
                            onDrawWithContent {
                                drawContent()
                                drawOutline(outline, color = borderColor, style = stroke)
                            }
                        }
                    } else Modifier,
                )
                // Un toque reintenta si el mensaje falló; la pulsación larga abre las
                // acciones (responder/copiar). Toque y pulsación larga van en un solo
                // `combinedClickable` porque encadenar dos modificadores de gesto haría que
                // el segundo no llegue a ver el evento (el deslizar-para-responder sí puede
                // ir aparte: vive en el Box de fuera, no en este mismo nodo).
                // Se cablea en TODAS las burbujas, no solo en las de texto: copiar sigue
                // siendo cosa del texto, pero **responder** vale para foto, audio o archivo.
                .combinedClickable(
                    onClick = { if (failed) onRetry() },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            CompositionLocalProvider(LocalContentColor provides onBg) {
                message.quoted?.let { quoted ->
                    QuotedPreview(
                        quoted = quoted,
                        onBg = onBg,
                        modifier = Modifier.padding(bottom = 4.dp),
                        onClick = if (quoted.available) {
                            { onQuoteClick(quoted.id) }
                        } else null,
                    )
                }
                when {
                    message.image != null -> {
                        val bmp = remember(message.id) { ImageCodec.decode(message.image) }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = "Imagen",
                                modifier = Modifier
                                    .heightIn(max = 240.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                        } else {
                            Text("📷 [imagen no disponible]", color = onBg)
                        }
                    }
                    message.file != null ->
                        // Con copia en disco: un audio se reproduce en la burbuja (nota de voz)
                        // y un GIF/WebP se anima. Sin copia local (o mime que no sea de esos)
                        // cae a la burbuja de archivo genérica; AnimatedImage también cae ahí
                        // si el archivo ya no se puede decodificar.
                        if (message.file.localPath != null && message.file.mime.startsWith("audio/")) {
                            AudioNote(message.file)
                        } else if (message.file.localPath != null && message.file.mime in ANIMATED_IMAGE_MIMES) {
                            AnimatedImage(message.file.localPath) { FileAttachment(message.file) }
                        } else {
                            FileAttachment(message.file)
                        }
                    else -> Text(rememberLinkifiedText(message.text, onBg), color = onBg)
                }
                // Pie de burbuja: hora y, en las propias, el estado como icono (reloj/✓/✓✓).
                if (failed) {
                    Text(
                        "No enviado · toca para reintentar",
                        style = MaterialTheme.typography.labelSmall,
                        color = onBg,
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                    ) {
                        if (message.timestamp > 0) {
                            Text(
                                bubbleTime(message.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = onBg.copy(alpha = 0.75f),
                            )
                        }
                        if (message.mine) {
                            // Sobre el fondo `primary` de la burbuja propia: "leído" opaco
                            // (máximo contraste posible ahí) y el resto al 60%, para que
                            // ✓✓ leído siga distinguiéndose de ✓✓ entregado.
                            MessageStatusIcon(
                                message.status,
                                modifier = Modifier.padding(start = 3.dp),
                                mutedTint = onBg.copy(alpha = 0.6f),
                                readTint = onBg,
                            )
                        }
                    }
                }
            }
        }
        // Acciones en botones de icono, no en un menú. Un DropdownMenu ocupa el ancho mínimo
        // de menú de M3 (112 dp) más su relleno, y para una o dos acciones resultaba un cartel
        // enorme encima del mensaje. Aquí es una píldora con los iconos, pegada al lado de la
        // burbuja que se pulsó. Copiar solo aparece si hay texto que copiar.
        //
        // Color: la píldora se solapa con la burbuja que se pulsó, así que tiene que
        // distinguirse de las DOS orillas — el teal saturado de la propia y el gris neutro de
        // la recibida. Antes era `surfaceContainerHighest`, que es EXACTAMENTE el fondo de la
        // burbuja recibida (contraste 1.0: invisible sobre un mensaje recibido).
        //
        // Ningún relleno plano lo resuelve: en tema oscuro la burbuja propia es muy clara
        // (#53DBC9) y la recibida muy oscura (#303635), y el mejor color equidistante posible
        // se queda en 2.84:1 contra ambas — por debajo del 3:1 que pide WCAG para elementos
        // no textuales. Por eso el contraste va en **dos capas**: relleno `inverseSurface` +
        // **aro** `inverseOnSurface`, que son tonos inversos entre sí. Sobre la burbuja
        // oscura contrasta el relleno (9.5:1) y sobre la clara contrasta el aro (7.7:1); en
        // tema claro se intercambian los papeles (10.7:1 y 5.7:1). Siempre hay una de las dos
        // capas que separa la píldora del fondo, sea cual sea la burbuja de debajo.
        if (menuOpen) {
            Popup(
                alignment = if (message.mine) Alignment.TopEnd else Alignment.TopStart,
                offset = IntOffset(0, with(LocalDensity.current) { (-6).dp.roundToPx() }),
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.inverseOnSurface),
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Con el texto fuera, la descripción es lo único que queda para
                        // TalkBack: sin ella los botones serían iconos mudos.
                        IconButton(
                            onClick = {
                                menuOpen = false
                                onReply()
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                KryptaReplyIcon,
                                contentDescription = "Responder a este mensaje",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        if (copyable) {
                            IconButton(
                                onClick = {
                                    menuOpen = false
                                    copyMessageText(context, message.text)
                                },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    KryptaCopyIcon,
                                    contentDescription = "Copiar mensaje",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Cita de un mensaje anterior: franja de color + autor + resumen de una línea. Se pinta
 * dentro de la burbuja que responde y, si el citado sigue en el chat, es tocable para saltar
 * a él. El fondo es [onBg] muy transparente para que funcione sobre cualquier burbuja (la
 * propia es de color de marca y la recibida gris) sin fijar un color por rol.
 */
@Composable
internal fun QuotedPreview(
    quoted: QuotedMessage,
    onBg: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            // Ocupa todo el ancho de la burbuja: así la cita es una cabecera del mensaje y no
            // un recuadro flotante que deja el interior desigual. La burbuja ya tiene su
            // propio tope de ancho, así que esto no la deforma.
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(onBg.copy(alpha = 0.12f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            // Altura intrínseca mínima: la franja de color puede así estirarse hasta la
            // altura real del texto. Con una altura fija se quedaba corta cuando el resumen
            // ocupaba dos líneas, y la cita se veía descuadrada.
            .height(IntrinsicSize.Min)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(onBg.copy(alpha = 0.7f)),
        )
        Column(Modifier.padding(start = 8.dp, top = 5.dp, bottom = 5.dp)) {
            if (quoted.author.isNotBlank()) {
                Text(
                    quoted.author,
                    style = MaterialTheme.typography.labelMedium,
                    color = onBg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                quoted.preview.ifBlank { "Mensaje" },
                style = MaterialTheme.typography.bodySmall,
                color = onBg.copy(alpha = if (quoted.available) 0.8f else 0.6f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Burbuja de archivo adjunto: nombre + tamaño; tocable para abrir si está en disco. */
@Composable
private fun FileAttachment(file: FileInfo) {
    val context = LocalContext.current
    val openable = file.localPath != null
    val reader = LocalAttachmentReader.current
    val scope = rememberCoroutineScope()
    // Abrir con otra app implica dárselo **en claro**: no hay forma de que un visor externo
    // lea un adjunto cifrado. Se descifra a una copia en la caché, que se limpia al volver a
    // la app. El cifrado en reposo protege el almacén de Krypta, no lo que el usuario saque
    // de él a propósito.
    val abrirAdjunto: () -> Unit = {
        val path = file.localPath
        if (path != null) {
            scope.launch {
                val bytes = reader(path)
                val copia = bytes?.let { stageForExternalApp(context, file.name, it) }
                if (copia != null) FilePicker.open(context, copia.absolutePath, file.mime)
            }
        }
    }
    Row(
        modifier = Modifier
            .then(
                if (openable) Modifier.clickable { abrirAdjunto() }
                else Modifier,
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            KryptaAttachIcon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = LocalContentColor.current,
        )
        Column(Modifier.padding(start = 8.dp)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                humanSize(file.size) + if (openable) " · toca para abrir" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Burbuja de nota de voz: play/pausa + progreso + duración, reproducida desde la copia local
 * con un MediaPlayer propio de la burbuja (se libera al salir de pantalla).
 */
@Composable
private fun AudioNote(file: FileInfo) {
    val path = file.localPath!!
    var playing by remember(path) { mutableStateOf(false) }
    var progress by remember(path) { mutableFloatStateOf(0f) }
    var prepared by remember(path) { mutableStateOf(false) }
    var durationMs by remember(path) { mutableIntStateOf(0) }
    val player = remember(path) { android.media.MediaPlayer() }
    // El adjunto está cifrado en reposo: `setDataSource(path)` ya no vale. Los bytes se
    // descifran al pulsar play y se le sirven al MediaPlayer desde memoria, sin dejar una
    // copia en claro en disco (que es justo lo que este cifrado viene a evitar).
    val reader = LocalAttachmentReader.current
    val scope = rememberCoroutineScope()
    DisposableEffect(path) { onDispose { runCatching { player.release() } } }

    // Barra y contador avanzan solo mientras suena.
    LaunchedEffect(playing) {
        while (playing) {
            if (durationMs > 0) progress = player.currentPosition / durationMs.toFloat()
            delay(200)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        IconButton(onClick = {
            if (playing) {
                runCatching { player.pause() }
                playing = false
            } else {
                scope.launch {
                    runCatching {
                        if (!prepared) {
                            val bytes = reader(path) ?: return@runCatching
                            player.setDataSource(BytesMediaSource(bytes))
                            player.prepare()
                            durationMs = player.duration
                            player.setOnCompletionListener {
                                playing = false
                                progress = 1f
                            }
                            prepared = true
                        }
                        player.start()
                        playing = true
                    }
                }
            }
        }) {
            Icon(
                if (playing) KryptaPauseIcon else KryptaPlayIcon,
                contentDescription = if (playing) "Pausar" else "Reproducir",
                tint = LocalContentColor.current,
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.weight(1f, fill = false).padding(horizontal = 4.dp).size(width = 120.dp, height = 4.dp),
        )
        Text(
            if (durationMs > 0) formatSeconds(durationMs / 1000) else humanSize(file.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

internal fun formatSeconds(s: Int): String = "%d:%02d".format(s / 60, s % 60)

private fun humanSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** Etiqueta legible del estado de un mensaje propio. */
internal fun statusLabel(status: MessageStatus): String = when (status) {
    MessageStatus.PENDING -> "enviando…"
    MessageStatus.SENT -> "enviado"
    MessageStatus.DELIVERED -> "entregado"
    MessageStatus.READ -> "leído"
    MessageStatus.FAILED -> "no enviado"
}

/** Arrastre horizontal a partir del cual soltar la burbuja abre la respuesta. */
private val SWIPE_REPLY_THRESHOLD = 56.dp

/**
 * Ancho máximo de una burbuja como fracción del ancho de la lista. Es lo que mantiene la
 * **forma al margen del contenido**: sin él, un texto largo, una imagen ancha o la cita de un
 * mensaje largo estiraban la burbuja hasta el borde y cada mensaje salía con una silueta
 * distinta. También deja siempre visible un margen del lado contrario, que es lo que hace
 * legible de un vistazo quién escribió cada mensaje.
 */
/**
 * Forma de una burbuja de chat: rectángulo redondeado con la esquina "cola" (la del lado del
 * emisor) mucho menos redondeada, y —lo que la hace propia— un **radio que crece con la
 * altura**.
 *
 * El porqué: los 18 dp de esquina, el borde de 1 dp y la sombra de 1,5 dp se calibraron
 * sobre burbujas cortas, donde definen la silueta. En una burbuja alta de varias líneas ese
 * mismo radio es una fracción mínima del contorno y la sombra desaparece a ese tamaño: el
 * mensaje deja de leerse como una burbuja y pasa a ser un bloque de texto con un borde. La
 * salida es que lo que dibuja la silueta **escale con ella** en vez de ser fijo (esquina,
 * borde y sombra; ver [bubbleBorderPx] y [bubbleShadowPx]).
 *
 * Escala con la **altura**, no con el área ni con el ancho: una burbuja de una línea muy
 * larga sigue siendo baja y sus esquinas de 18 dp se leen perfectamente; es al crecer hacia
 * abajo cuando el contorno se pierde. El crecimiento es suave y va topado ([BUBBLE_CORNER_MAX]),
 * porque pasado cierto punto redondear más convierte la burbuja en una cápsula.
 *
 * Es un [Shape] y no un cálculo en la composición a propósito: `createOutline` recibe el
 * tamaño ya medido, así que el radio correcto sale **en el primer fotograma**. Midiendo con
 * `onSizeChanged` habría que recomponer, y la burbuja se pintaría un fotograma con el radio
 * corto — un "salto" visible al entrar en pantalla mientras se desplaza la lista.
 *
 * `data class` para que Compose pueda comparar dos formas iguales y saltarse el trabajo.
 */
private data class BubbleShape(val mine: Boolean, val first: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val big = bubbleCornerPx(size, density)
        val small = with(density) { BUBBLE_TAIL_CORNER.toPx() }
        val corners = if (mine) {
            RoundedCornerShape(big, if (first) big else small, small, big)
        } else {
            RoundedCornerShape(if (first) big else small, big, big, small)
        }
        return corners.createOutline(size, layoutDirection, density)
    }
}

/**
 * Interpola linealmente entre [min] y [max] según cuánto sobrepasa la altura de la burbuja la
 * de un mensaje corto ([BUBBLE_SHORT_HEIGHT]), a razón de [growth] por píxel de alto. Debajo
 * de esa altura devuelve [min] exacto: las burbujas cortas se quedan **como estaban**, que es
 * donde estos valores ya se veían bien.
 */
private fun scaledByHeight(heightPx: Float, density: Density, min: Dp, max: Dp, growth: Float): Float =
    with(density) {
        val from = BUBBLE_SHORT_HEIGHT.toPx()
        (min.toPx() + (heightPx - from) * growth).coerceIn(min.toPx(), max.toPx())
    }

/** Radio de las esquinas redondeadas de la burbuja, topado además a la mitad de su altura. */
private fun bubbleCornerPx(size: Size, density: Density): Float =
    scaledByHeight(size.height, density, BUBBLE_CORNER_MIN, BUBBLE_CORNER_MAX, BUBBLE_CORNER_GROWTH)
        .coerceAtMost(size.height / 2f)

/** Grosor del borde de la burbuja recibida (px). */
private fun bubbleBorderPx(size: Size, density: Density): Float =
    scaledByHeight(size.height, density, BUBBLE_BORDER_MIN, BUBBLE_BORDER_MAX, BUBBLE_BORDER_GROWTH)

/** Elevación de la sombra de la burbuja (px, para `graphicsLayer`). */
private fun bubbleShadowPx(size: Size, density: Density): Float =
    scaledByHeight(size.height, density, BUBBLE_SHADOW_MIN, BUBBLE_SHADOW_MAX, BUBBLE_SHADOW_GROWTH)

/** Altura aproximada de una burbuja de una línea: por debajo, nada escala. */
private val BUBBLE_SHORT_HEIGHT = 48.dp

/** Esquina del lado del emisor: fija, es la "cola" que identifica quién escribe. */
private val BUBBLE_TAIL_CORNER = 4.dp

private val BUBBLE_CORNER_MIN = 18.dp

private val BUBBLE_CORNER_MAX = 28.dp

private const val BUBBLE_CORNER_GROWTH = 0.06f

private val BUBBLE_BORDER_MIN = 1.dp

private val BUBBLE_BORDER_MAX = 1.75.dp

private const val BUBBLE_BORDER_GROWTH = 0.005f

private val BUBBLE_SHADOW_MIN = 1.5.dp

private val BUBBLE_SHADOW_MAX = 3.dp

private const val BUBBLE_SHADOW_GROWTH = 0.01f

private const val BUBBLE_MAX_WIDTH_RATIO = 0.78f

private fun bubbleTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

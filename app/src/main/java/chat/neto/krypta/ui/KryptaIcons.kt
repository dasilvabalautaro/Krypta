package chat.neto.krypta.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Construye un icono 24×24 desde un pathData de Material Symbols (mismo formato que los
 * VectorDrawable XML). Para los iconos nuevos es más legible que transcribir el path a la
 * DSL de PathBuilder; ambos estilos conviven en este archivo.
 */
private fun materialIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = "Krypta.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(addPathNodes(pathData), fill = SolidColor(Color.Black)).build()

/** Flecha curva de responder (`Reply`): citar un mensaje. */
val KryptaReplyIcon: ImageVector by lazy {
    materialIcon(
        "Reply",
        "M10,9V5l-7,7 7,7v-4.1c5,0 8.5,1.6 11,5.1 -1,-5 -4,-10 -11,-11z",
    )
}

/** Aspa de cerrar (`Close`): descartar la cita en la barra de respuesta. */
val KryptaCloseIcon: ImageVector by lazy {
    materialIcon(
        "Close",
        "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59," +
            "19 19,17.59 13.41,12z",
    )
}

/** Flecha atrás (equivalente a `ArrowBack`), para las barras superiores. */
val KryptaBackIcon: ImageVector by lazy {
    materialIcon("Back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
}

/** Engranaje de ajustes (equivalente a `Settings`). */
val KryptaSettingsIcon: ImageVector by lazy {
    materialIcon(
        "Settings",
        "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03," +
            "-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59," +
            "-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24," +
            "-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12," +
            "5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66," +
            "9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03," +
            "1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39," +
            "-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24," +
            "0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22," +
            "0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94z" +
            "M12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98," +
            "15.6 12,15.6z",
    )
}

/** Cruz "añadir" (equivalente a `Add`), para el FAB de nuevo contacto. */
val KryptaAddIcon: ImageVector by lazy {
    materialIcon("Add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
}

/** Flecha hacia una bandeja (equivalente a `FileUpload`), para "Exportar copia". */
val KryptaUploadIcon: ImageVector by lazy {
    materialIcon(
        "Upload",
        "M9,16h6v-6h4L12,3L5,10h4V16z M5,18h14v2H5V18z",
    )
}

/** Flecha desde una bandeja (equivalente a `FileDownload`), para "Importar copia". */
val KryptaDownloadIcon: ImageVector by lazy {
    materialIcon(
        "Download",
        "M19,9h-4V3H9v6H5l7,7L19,9z M5,18h14v2H5V18z",
    )
}

/** Campana (equivalente a `Notifications`), para "Probar aviso". */
val KryptaBellIcon: ImageVector by lazy {
    materialIcon(
        "Bell",
        "M12,22c1.1,0 2,-0.9 2,-2h-4C10,21.1 10.9,22 12,22z M18,16v-5c0,-3.07 -1.64,-5.64 " +
            "-4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6," +
            "7.92 6,11v5l-2,2v1h16v-1L18,16z",
    )
}

/** Check sencillo (enviado). */
val KryptaCheckIcon: ImageVector by lazy {
    materialIcon("Check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
}

/** Interrogante en un círculo (equivalente a `Help`), para el acceso a la Ayuda. */
val KryptaHelpIcon: ImageVector by lazy {
    materialIcon(
        "Help",
        "M11,18h2v-2h-2v2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12," +
            "2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8zM12,6c-2.21," +
            "0 -4,1.79 -4,4h2c0,-1.1 0.9,-2 2,-2s2,0.9 2,2c0,2 -3,1.75 -3,5h2c0,-2.25 3,-2.5 3," +
            "-5 0,-2.21 -1.79,-4 -4,-4z",
    )
}

/** "i" en un círculo (equivalente a `Info`), para las ayudas contextuales. */
val KryptaInfoIcon: ImageVector by lazy {
    materialIcon(
        "Info",
        "M11,7h2v2h-2zM11,11h2v6h-2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10," +
            "-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8," +
            "8z",
    )
}

/** Chevron hacia abajo (equivalente a `ExpandMore`), para las tarjetas desplegables del FAQ. */
val KryptaExpandMoreIcon: ImageVector by lazy {
    materialIcon("ExpandMore", "M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z")
}

/** Tres puntos verticales (equivalente a `MoreVert`), para el menú del chat. */
val KryptaMoreIcon: ImageVector by lazy {
    materialIcon(
        "More",
        "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 " +
            "-2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2," +
            "-0.9 2,-2 -0.9,-2 -2,-2z",
    )
}

/** Círculo tachado (`Block`): contacto bloqueado. */
val KryptaBlockIcon: ImageVector by lazy {
    materialIcon(
        "Block",
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM4,12c0,-4.42 " +
            "3.58,-8 8,-8 1.85,0 3.55,0.63 4.9,1.69L5.69,16.9C4.63,15.55 4,13.85 4,12zM12," +
            "20c-1.85,0 -3.55,-0.63 -4.9,-1.69L18.31,7.1C19.37,8.45 20,10.15 20,12c0,4.42 " +
            "-3.58,8 -8,8z",
    )
}

/** Papelera (equivalente a `Delete`), para vaciar chat / eliminar contacto. */
val KryptaDeleteIcon: ImageVector by lazy {
    materialIcon(
        "Delete",
        "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z",
    )
}

/** Check doble (entregado; teñido de primary = leído). */
val KryptaDoubleCheckIcon: ImageVector by lazy {
    materialIcon(
        "DoubleCheck",
        "M18,7l-1.41,-1.41 -6.34,6.34 1.41,1.41L18,7zM22.24,5.59L11.66,16.17 7.48,12l-1.41," +
            "1.41L11.66,19l12,-12 -1.42,-1.41zM0.41,13.41L6,19l1.41,-1.41L1.83,12 0.41,13.41z",
    )
}

/** Reloj (mensaje pendiente de envío). */
val KryptaClockIcon: ImageVector by lazy {
    materialIcon(
        "Clock",
        "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2z" +
            "M12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6" +
            "l5.25,3.15 0.75,-1.23 -4.5,-2.67z",
    )
}

/** Burbuja de chat (el glifo de marca, sin candado), para estados vacíos. */
val KryptaChatBubbleIcon: ImageVector by lazy {
    materialIcon(
        "ChatBubble",
        "M20,2H4C2.9,2 2,2.9 2,4v18l4,-4h14c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2z" +
            "M20,16H6l-2,2V4h16V16z",
    )
}

/** Micrófono tachado (silenciar), para los controles de llamada. */
val KryptaMicOffIcon: ImageVector by lazy {
    materialIcon(
        "MicOff",
        "M19,11h-1.7c0,0.74 -0.16,1.43 -0.43,2.05l1.23,1.23c0.56,-0.98 0.9,-2.09 0.9,-3.28z" +
            "M14.98,11.17c0,-0.06 0.02,-0.11 0.02,-0.17V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5" +
            "v0.18l5.98,5.99zM4.27,3L3,4.27l6.01,6.01V11c0,1.66 1.33,3 2.99,3c0.22,0 0.44," +
            "-0.03 0.65,-0.08l1.66,1.66c-0.71,0.33 -1.5,0.52 -2.31,0.52c-2.76,0 -5.3,-2.1 " +
            "-5.3,-5.1H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c0.91,-0.13 1.77,-0.45 2.54,-0.9" +
            "L19.73,21L21,19.73L4.27,3z",
    )
}

/** Altavoz (volumen alto), para el toggle de manos libres. */
val KryptaSpeakerIcon: ImageVector by lazy {
    materialIcon(
        "Speaker",
        "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05" +
            "c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71" +
            "s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
    )
}

/** Cámara de vídeo, para el toggle de vídeo en llamada. */
val KryptaVideocamIcon: ImageVector by lazy {
    materialIcon(
        "Videocam",
        "M17,10.5V7c0,-0.55 -0.45,-1 -1,-1H4C3.45,6 3,6.45 3,7v10c0,0.55 0.45,1 1,1h12" +
            "c0.55,0 1,-0.45 1,-1v-3.5l4,4v-11L17,10.5z",
    )
}

/** Flechas circulares (cambiar de cámara frontal/trasera). */
val KryptaFlipCameraIcon: ImageVector by lazy {
    materialIcon(
        "FlipCamera",
        "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6c0,1.01 -0.25,1.97 -0.7,2.8l1.46,1.46" +
            "C19.54,16.03 20,14.57 20,13c0,-4.42 -3.58,-8 -8,-8zM6,13c0,-1.01 0.25,-1.97 " +
            "0.7,-2.8L5.24,8.74C4.46,9.97 4,11.43 4,13c0,4.42 3.58,8 8,8v4l5,-5 -5,-5v4" +
            "c-3.31,0 -6,-2.69 -6,-6z",
    )
}

/** Teléfono colgando (fin de llamada), para el botón rojo de colgar. */
val KryptaCallEndIcon: ImageVector by lazy {
    materialIcon(
        "CallEnd",
        "M12,9c-1.6,0 -3.15,0.25 -4.6,0.72v3.1c0,0.39 -0.23,0.74 -0.56,0.9" +
            "c-0.98,0.49 -1.87,1.12 -2.66,1.85c-0.18,0.18 -0.43,0.28 -0.7,0.28" +
            "c-0.28,0 -0.53,-0.11 -0.71,-0.29L0.29,13.08c-0.18,-0.17 -0.29,-0.42 -0.29,-0.7" +
            "c0,-0.28 0.11,-0.53 0.29,-0.71C3.34,8.78 7.46,7 12,7s8.66,1.78 11.71,4.67" +
            "c0.18,0.18 0.29,0.43 0.29,0.71c0,0.28 -0.11,0.53 -0.29,0.7l-2.48,2.48" +
            "c-0.18,0.18 -0.43,0.29 -0.71,0.29c-0.27,0 -0.52,-0.11 -0.7,-0.28" +
            "c-0.79,-0.74 -1.69,-1.36 -2.67,-1.85c-0.33,-0.16 -0.56,-0.5 -0.56,-0.9v-3.1" +
            "C15.15,9.25 13.6,9 12,9z",
    )
}

/**
 * Icono "copiar" (equivalente a `ContentCopy` de Material), definido localmente con la API de
 * vectores de Compose para **no** depender de `material-icons-extended` (miles de iconos) solo
 * por este. El de compartir sí viene en `material-icons-core` (`Icons.Default.Share`).
 */
val KryptaCopyIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Krypta.Copy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Hoja trasera.
        path(fill = SolidColor(Color.Black)) {
            moveTo(16f, 1f)
            horizontalLineTo(4f)
            curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(2f)
            verticalLineTo(3f)
            horizontalLineToRelative(12f)
            verticalLineTo(1f)
            close()
        }
        // Hoja delantera con hueco (evenOdd para el recorte interior).
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(19f, 5f)
            horizontalLineTo(8f)
            curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
            verticalLineToRelative(14f)
            curveTo(6f, 22.1f, 6.9f, 23f, 8f, 23f)
            horizontalLineToRelative(11f)
            curveTo(20.1f, 23f, 21f, 22.1f, 21f, 21f)
            verticalLineTo(7f)
            curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
            close()
            moveTo(19f, 21f)
            horizontalLineTo(8f)
            verticalLineTo(7f)
            horizontalLineToRelative(11f)
            close()
        }
    }.build()
}

/**
 * Icono "escudo con check" (verificación de identidad anti-MITM), local para no depender
 * de `material-icons-extended` (`VerifiedUser`). Se pinta relleno cuando el contacto está
 * verificado y con menos énfasis cuando no.
 */
val KryptaShieldIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Krypta.Shield",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Escudo con una marca de verificación recortada (evenOdd).
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(12f, 1f)
            lineTo(3f, 5f)
            verticalLineToRelative(6f)
            curveToRelative(0f, 5.55f, 3.84f, 10.74f, 9f, 12f)
            curveToRelative(5.16f, -1.26f, 9f, -6.45f, 9f, -12f)
            verticalLineTo(5f)
            lineTo(12f, 1f)
            close()
            // Check interior.
            moveTo(10.5f, 16.5f)
            lineTo(6.5f, 12.5f)
            lineToRelative(1.41f, -1.41f)
            lineToRelative(2.59f, 2.58f)
            lineToRelative(5.09f, -5.09f)
            lineTo(17f, 10f)
            close()
        }
    }.build()
}

/**
 * Icono "imagen" (equivalente a `Image` de Material), local para no depender de
 * `material-icons-extended`. Un marco con montaña + sol, la silueta clásica de foto.
 */
val KryptaImageIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Krypta.Image",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            // Marco exterior con hueco (evenOdd) + montaña y sol dentro.
            moveTo(21f, 19f)
            verticalLineTo(5f)
            curveTo(21f, 3.9f, 20.1f, 3f, 19f, 3f)
            horizontalLineTo(5f)
            curveTo(3.9f, 3f, 3f, 3.9f, 3f, 5f)
            verticalLineToRelative(14f)
            curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
            horizontalLineToRelative(14f)
            curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
            close()
            moveTo(8.5f, 13.5f)
            lineToRelative(2.5f, 3.01f)
            lineTo(14.5f, 12f)
            lineToRelative(4.5f, 6f)
            horizontalLineTo(5f)
            lineToRelative(3.5f, -4.5f)
            close()
        }
    }.build()
}

/**
 * Icono "clip" (adjuntar archivo), local para no depender de `material-icons-extended`.
 */
val KryptaAttachIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Krypta.Attach",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(16.5f, 6f)
            verticalLineToRelative(11.5f)
            curveToRelative(0f, 2.21f, -1.79f, 4f, -4f, 4f)
            reflectiveCurveToRelative(-4f, -1.79f, -4f, -4f)
            verticalLineTo(5f)
            curveToRelative(0f, -1.38f, 1.12f, -2.5f, 2.5f, -2.5f)
            reflectiveCurveTo(13.5f, 3.62f, 13.5f, 5f)
            verticalLineToRelative(10.5f)
            curveToRelative(0f, 0.55f, -0.45f, 1f, -1f, 1f)
            reflectiveCurveToRelative(-1f, -0.45f, -1f, -1f)
            verticalLineTo(6f)
            horizontalLineTo(10f)
            verticalLineToRelative(9.5f)
            curveToRelative(0f, 1.38f, 1.12f, 2.5f, 2.5f, 2.5f)
            reflectiveCurveToRelative(2.5f, -1.12f, 2.5f, -2.5f)
            verticalLineTo(5f)
            curveToRelative(0f, -2.21f, -1.79f, -4f, -4f, -4f)
            reflectiveCurveTo(7f, 2.79f, 7f, 5f)
            verticalLineToRelative(12.5f)
            curveToRelative(0f, 3.04f, 2.46f, 5.5f, 5.5f, 5.5f)
            reflectiveCurveToRelative(5.5f, -2.46f, 5.5f, -5.5f)
            verticalLineTo(6f)
            horizontalLineToRelative(-1.5f)
            close()
        }
    }.build()
}

/**
 * Icono "micrófono" (equivalente a `Mic` de Material), local para no depender de
 * `material-icons-extended`. Para grabar notas de voz.
 */
val KryptaMicIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Krypta.Mic",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Cápsula del micro.
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 14f)
            curveToRelative(1.66f, 0f, 3f, -1.34f, 3f, -3f)
            verticalLineTo(5f)
            curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f)
            reflectiveCurveTo(9f, 3.34f, 9f, 5f)
            verticalLineToRelative(6f)
            curveToRelative(0f, 1.66f, 1.34f, 3f, 3f, 3f)
            close()
        }
        // Arco + pie.
        path(fill = SolidColor(Color.Black)) {
            moveTo(17f, 11f)
            curveToRelative(0f, 2.76f, -2.24f, 5f, -5f, 5f)
            reflectiveCurveToRelative(-5f, -2.24f, -5f, -5f)
            horizontalLineTo(5f)
            curveToRelative(0f, 3.53f, 2.61f, 6.43f, 6f, 6.92f)
            verticalLineTo(21f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-3.08f)
            curveToRelative(3.39f, -0.49f, 6f, -3.39f, 6f, -6.92f)
            horizontalLineToRelative(-2f)
            close()
        }
    }.build()
}

/**
 * Icono "reproducir" (triángulo `PlayArrow`), local. Para la burbuja de nota de voz.
 */
val KryptaPlayIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Krypta.Play",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(8f, 5f)
            verticalLineToRelative(14f)
            lineToRelative(11f, -7f)
            close()
        }
    }.build()
}

/**
 * Icono "pausa" (dos barras `Pause`), local. Para la burbuja de nota de voz.
 */
val KryptaPauseIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Krypta.Pause",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6f, 19f)
            horizontalLineToRelative(4f)
            verticalLineTo(5f)
            horizontalLineTo(6f)
            close()
            moveTo(14f, 5f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(4f)
            verticalLineTo(5f)
            close()
        }
    }.build()
}

/**
 * Icono "teléfono" (equivalente a `Call` de Material), local. Para iniciar llamadas.
 */
val KryptaPhoneIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Krypta.Phone",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6.62f, 10.79f)
            curveToRelative(1.44f, 2.83f, 3.76f, 5.14f, 6.59f, 6.59f)
            lineToRelative(2.2f, -2.2f)
            curveToRelative(0.27f, -0.27f, 0.67f, -0.36f, 1.02f, -0.24f)
            curveToRelative(1.12f, 0.37f, 2.33f, 0.57f, 3.57f, 0.57f)
            curveToRelative(0.55f, 0f, 1f, 0.45f, 1f, 1f)
            verticalLineTo(20f)
            curveToRelative(0f, 0.55f, -0.45f, 1f, -1f, 1f)
            curveToRelative(-9.39f, 0f, -17f, -7.61f, -17f, -17f)
            curveToRelative(0f, -0.55f, 0.45f, -1f, 1f, -1f)
            horizontalLineToRelative(3.5f)
            curveToRelative(0.55f, 0f, 1f, 0.45f, 1f, 1f)
            curveToRelative(0f, 1.25f, 0.2f, 2.45f, 0.57f, 3.57f)
            curveToRelative(0.11f, 0.35f, 0.03f, 0.74f, -0.25f, 1.02f)
            lineToRelative(-2.2f, 2.2f)
            close()
        }
    }.build()
}

/** Candado cerrado (equivalente a `Lock`), para el bloqueo de acceso a la app. */
val KryptaLockIcon: ImageVector by lazy {
    materialIcon(
        "Lock",
        "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9," +
            "2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s" +
            "0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71," +
            "0 3.1,1.39 3.1,3.1v2z",
    )
}

/**
 * Icono "compartir" (equivalente a `Share` de Material), también local para no añadir
 * `material-icons-core`/`-extended` solo por dos iconos.
 */
val KryptaShareIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Krypta.Share",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(18f, 16.08f)
            curveToRelative(-0.76f, 0f, -1.44f, 0.3f, -1.96f, 0.77f)
            lineTo(8.91f, 12.7f)
            curveToRelative(0.05f, -0.23f, 0.09f, -0.46f, 0.09f, -0.7f)
            reflectiveCurveToRelative(-0.04f, -0.47f, -0.09f, -0.7f)
            lineToRelative(7.05f, -4.11f)
            curveToRelative(0.54f, 0.5f, 1.25f, 0.81f, 2.04f, 0.81f)
            curveToRelative(1.66f, 0f, 3f, -1.34f, 3f, -3f)
            reflectiveCurveToRelative(-1.34f, -3f, -3f, -3f)
            reflectiveCurveToRelative(-3f, 1.34f, -3f, 3f)
            curveToRelative(0f, 0.24f, 0.04f, 0.47f, 0.09f, 0.7f)
            lineTo(8.04f, 9.81f)
            curveTo(7.5f, 9.31f, 6.79f, 9f, 6f, 9f)
            curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
            reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
            curveToRelative(0.79f, 0f, 1.5f, -0.31f, 2.04f, -0.81f)
            lineToRelative(7.12f, 4.16f)
            curveToRelative(-0.05f, 0.21f, -0.08f, 0.43f, -0.08f, 0.65f)
            curveToRelative(0f, 1.61f, 1.31f, 2.92f, 2.92f, 2.92f)
            reflectiveCurveToRelative(2.92f, -1.31f, 2.92f, -2.92f)
            reflectiveCurveToRelative(-1.31f, -2.92f, -2.92f, -2.92f)
            close()
        }
    }.build()
}

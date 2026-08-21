package chat.neto.krypta

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bloqueo de captura y grabación de pantalla **de la pantalla de chat**, y la única vía que
 * queda para capturarla: la propia Krypta.
 *
 * **El bloqueo** es `FLAG_SECURE` en la ventana de la Activity ([setSecure]): mientras está
 * puesto el sistema se niega a hacer capturas, el grabador de pantalla graba negro, la
 * miniatura de "recientes" sale en blanco y la ventana no se puede volcar a una pantalla no
 * segura (ni a `adb screencap`).
 *
 * **Es por pantalla, no de toda la app.** Krypta tiene una sola Activity, así que el flag es
 * de la ventana entera: se pone al entrar en el chat y se quita al salir (ver
 * `SecureScreenEffect` en `ui/ChatScreens.kt`). Lo que hay que proteger es el contenido de las
 * conversaciones; el resto de la app (lista, ajustes, ayuda) se puede capturar con normalidad,
 * que es lo que hace falta para dar soporte, mandar una incidencia o hacer material de la
 * tienda. Los diálogos y las hojas inferiores de Compose viven en ventanas propias pero
 * **heredan** el flag de la ventana padre en el momento de abrirse
 * (`SecureFlagPolicy.Inherit` es el valor por defecto de `DialogProperties`, y
 * `ModalBottomSheet` copia el flag del padre), así que los del chat quedan cubiertos sin
 * marcarlos uno a uno.
 *
 * Nota: `FLAG_SECURE` solo afecta a **esta** ventana. Nunca ha impedido capturar en otras
 * apps; si el sistema se niega fuera de Krypta, es una política del dispositivo (perfil de
 * trabajo, modo restringido del fabricante), no esto.
 *
 * **La excepción** es [captureToGallery]: `FLAG_SECURE` impide que *el sistema* lea la
 * superficie, no que la app dibuje su propio contenido. Pintando la jerarquía de vistas sobre
 * un `Canvas` por software se obtiene la imagen sin pasar por el compositor — por eso hay que
 * dibujar a mano en vez de usar `PixelCopy`, que sí va por la superficie y devolvería negro.
 */
object ScreenSecurity {

    /**
     * Pone o quita `FLAG_SECURE` en la ventana. Se puede cambiar con la ventana ya visible:
     * el sistema aplica el flag en el siguiente fotograma.
     */
    fun setSecure(activity: Activity, secure: Boolean) {
        if (secure) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * Captura la pantalla actual y la guarda en la galería (`Pictures/Krypta`). Devuelve la
     * URI o el error. El dibujo va en el hilo principal (es la jerarquía de vistas viva) y la
     * compresión/escritura en E/S.
     *
     * No se necesita permiso de almacenamiento: se escribe por `MediaStore` con `RELATIVE_PATH`
     * dentro de la colección de la app.
     */
    suspend fun captureToGallery(activity: Activity): Result<Uri> = runCatching {
        val bitmap = withContext(Dispatchers.Main) { draw(activity) }
        try {
            withContext(Dispatchers.IO) { save(activity, bitmap) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun draw(activity: Activity): Bitmap {
        val view = activity.window.decorView
        require(view.width > 0 && view.height > 0) { "la ventana aún no tiene tamaño" }
        // Software (ARGB_8888 + Canvas normal): un bitmap de hardware no admite que se pinte
        // sobre él, y es justo el volcado por software el que esquiva el compositor seguro.
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun save(context: Context, bitmap: Bitmap): Uri {
        val name = "Krypta_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        val resolver = context.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Krypta")
            // IS_PENDING: la galería no enseña el archivo hasta que está entero.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pending)
            ?: error("la galería no aceptó el archivo")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "no se pudo comprimir" }
            } ?: error("no se pudo abrir el destino")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null, null,
            )
            return uri
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) } // no dejes una entrada a medias
            throw e
        }
    }
}

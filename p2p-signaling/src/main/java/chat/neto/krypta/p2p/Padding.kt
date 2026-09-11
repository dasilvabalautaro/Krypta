package chat.neto.krypta.p2p

/**
 * Relleno por tramos del texto en claro, **dentro** del cifrado (fase 2.3 de
 * [docs/PLAN-privacidad-y-confianza.md]).
 *
 * Lo que arregla: el nodo no ve el contenido, pero sí **cuántos bytes** tiene cada depósito, y
 * con eso distingue un acuse de lectura (~60 B) de un «hola» de un anuncio de capacidad. Eso es
 * estructura de la conversación —quién leyó qué y cuándo— legible sin romper nada. Cuantizar el
 * tamaño la borra: todos esos mensajes pasan a medir lo mismo.
 *
 * Lo que **no** arregla, y conviene decirlo: un trozo de archivo de 48 KiB sigue siendo
 * reconocible, y una ráfaga de ellos sigue pareciendo un archivo. Ocultar eso pide tráfico de
 * relleno y batching, que es otra cosa y no está hecha (§4 del modelo de seguridad).
 *
 * **Tramos** (el tamaño final incluye el terminador):
 *
 * | Tamaño | Tramo | Sobrecoste máximo |
 * |---|---|---|
 * | ≤ 4 KiB | 160 B | 160 B |
 * | ≤ 64 KiB | 1 KiB | 1 KiB |
 * | > 64 KiB | ninguno | 1 B |
 *
 * Los 160 B son los de Signal, y valen por lo mismo: son el grano donde vive casi todo el
 * tráfico de control. El tramo de 1 KiB cubre la foto en línea (≤58 KiB) y el trozo de archivo
 * (48 KiB) con menos del 2 % de sobrecoste. **Por encima de 64 KiB no se rellena** y esto no es
 * pereza: nada legítimo pasa de ahí —el buzón rechaza blobs de más de 64 KiB— y lo único que
 * puede llegar tan grande es un texto enorme por envío directo, donde el receptor corta en
 * 1 MiB; rellenar ahí arriesgaría cruzar ese tope para no esconder nada (un mensaje de ese
 * tamaño ya se delata solo).
 *
 * **Formato**: `texto ‖ 0x80 ‖ 0x00 …`. Se recupera buscando el último byte no nulo, que tiene
 * que ser el terminador. Funciona con cualquier contenido binario, incluido uno que acabe en
 * ceros: los suyos quedan **antes** del `0x80`, así que el barrido hacia atrás se para donde
 * debe. Es el esquema de Signal por la misma razón: no necesita una longitud explícita, que
 * sería un campo más que un atacante podría mirar.
 */
internal object Padding {

    /** Terminador: marca dónde acaba el texto real. */
    private const val MARKER: Byte = -0x80 // 0x80

    private const val ZERO: Byte = 0

    /** Tramo fino y hasta dónde llega. */
    private const val SMALL_STEP = 160
    private const val SMALL_MAX = 4 * 1024

    /** Tramo grueso y hasta dónde llega (el límite de blob del buzón). */
    private const val LARGE_STEP = 1024
    private const val LARGE_MAX = 64 * 1024

    /** [plaintext] con terminador y ceros hasta el siguiente tramo. */
    fun pad(plaintext: ByteArray): ByteArray {
        val out = ByteArray(targetSize(plaintext.size + 1))
        plaintext.copyInto(out)
        out[plaintext.size] = MARKER
        return out
    }

    /**
     * Quita el relleno de [padded]. Lanza [RatchetException] si no aparece el terminador: eso
     * solo puede pasar con un mensaje malformado, y en ese caso es mejor no abrirlo que entregar
     * hacia arriba algo que no es el texto que se envió.
     */
    fun strip(padded: ByteArray): ByteArray {
        var i = padded.size - 1
        while (i >= 0 && padded[i] == ZERO) i--
        if (i < 0 || padded[i] != MARKER) {
            throw RatchetException("relleno sin terminador: el mensaje viene malformado")
        }
        return padded.copyOfRange(0, i)
    }

    /** Tamaño final para un contenido de [needed] bytes (texto + terminador). */
    private fun targetSize(needed: Int): Int = when {
        needed <= SMALL_MAX -> roundUp(needed, SMALL_STEP)
        needed <= LARGE_MAX -> roundUp(needed, LARGE_STEP)
        else -> needed
    }

    private fun roundUp(value: Int, step: Int): Int = (value + step - 1) / step * step
}

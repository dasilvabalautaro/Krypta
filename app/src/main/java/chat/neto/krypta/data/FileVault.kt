package chat.neto.krypta.data

import chat.neto.krypta.data.crypto.KeyPrefs
import chat.neto.krypta.data.crypto.KeyVault
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado en reposo de los adjuntos de `krypta_files/` (fotos, notas de voz, GIF, archivos).
 *
 * Era lo último que quedaba en claro en el dispositivo: la base se cifró el 9 sep 2026 y la
 * identidad vive envuelta en el Keystore desde el 8, pero una foto recibida seguía siendo un
 * JPEG legible para quien se llevara el `filesDir`. Con el ratchet la incoherencia se veía más:
 * no tiene mucho sentido proteger el **viaje** de una foto y dejar su **reposo** a la vista.
 *
 * - **AES-256-GCM** con una clave de 32 bytes aleatorios que vive **envuelta** por el Android
 *   Keystore (mismo patrón que la frase-clave de la base). El fichero queda
 *   `"KFV1" ‖ nonce(12) ‖ ciphertext+tag`.
 * - **La lectura tolera ficheros en claro**: los adjuntos que ya estaban en el móvil no llevan
 *   la marca y se devuelven tal cual. No se convierten a la fuerza — reescribir el `krypta_files`
 *   entero de un usuario para ganar sobre lo que ya estuvo en claro no compensa el riesgo; lo
 *   nuevo nace cifrado y lo viejo se puede borrar vaciando el chat.
 * - **Si la clave no se puede abrir, falla a la vista** en vez de generar otra: generar otra
 *   dejaría los adjuntos anteriores ilegibles para siempre sin avisar a nadie.
 *
 * Lo que **no** protege: nada de esto vale contra código ejecutándose dentro del proceso o con
 * root —ahí se le pide la clave al TEE igual que se la pide la app—, ni contra abrir un archivo
 * con otra aplicación, que por definición se lo lleva en claro.
 */
class FileVault(
    private val prefs: KeyPrefs,
    private val vault: KeyVault,
    private val random: SecureRandom = SecureRandom(),
) {

    /** Cifra [plain] para dejarlo en disco. */
    fun seal(plain: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keySpec(), GCMParameterSpec(TAG_BITS, nonce))
        }
        return MAGIC + nonce + cipher.doFinal(plain)
    }

    /**
     * Devuelve el contenido en claro de [stored]. Un fichero **sin la marca** se devuelve tal
     * cual: es un adjunto de antes de esta versión, y tiene que seguir abriéndose.
     */
    fun open(stored: ByteArray): ByteArray {
        if (!hasMagic(stored)) return stored
        val nonce = stored.copyOfRange(MAGIC.size, MAGIC.size + NONCE_BYTES)
        val body = stored.copyOfRange(MAGIC.size + NONCE_BYTES, stored.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, keySpec(), GCMParameterSpec(TAG_BITS, nonce))
        }
        return cipher.doFinal(body)
    }

    /** Lee y descifra un fichero del almacén (o lo devuelve tal cual si es de antes). */
    fun read(file: File): ByteArray = open(file.readBytes())

    /** ¿Este contenido está cifrado por este almacén? */
    fun isSealed(stored: ByteArray): Boolean = hasMagic(stored)

    private fun hasMagic(bytes: ByteArray): Boolean =
        bytes.size > MAGIC.size + NONCE_BYTES + TAG_BITS / 8 &&
            MAGIC.indices.all { bytes[it] == MAGIC[it] }

    private fun keySpec() = SecretKeySpec(key(), "AES")

    private fun key(): ByteArray {
        prefs.get(KEY_WRAPPED)?.let { stored ->
            val wrapped = runCatching { java.util.Base64.getDecoder().decode(stored) }.getOrNull()
                ?: error(CORRUPT_MESSAGE)
            return runCatching { vault.unwrap(wrapped) }
                .getOrElse { throw IllegalStateException(CORRUPT_MESSAGE, it) }
        }
        val fresh = ByteArray(KEY_BYTES).also(random::nextBytes)
        val wrapped = vault.wrap(fresh)
        // Comprobar el viaje de ida y vuelta antes de dar la clave por buena: si el Keystore de
        // este móvil hace algo raro, mejor enterarse ahora que cuando ya haya adjuntos escritos.
        check(vault.unwrap(wrapped).contentEquals(fresh)) { "el Keystore no devuelve la clave que guardó" }
        prefs.put(KEY_WRAPPED, java.util.Base64.getEncoder().encodeToString(wrapped))
        return fresh
    }

    private companion object {
        /** Marca de fichero cifrado. Lo que no la lleve es un adjunto anterior, en claro. */
        val MAGIC = "KFV1".toByteArray(Charsets.US_ASCII)
        const val KEY_WRAPPED = "files_key"
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CORRUPT_MESSAGE =
            "la clave de los adjuntos existe pero no se puede abrir; generar otra dejaría los " +
                "adjuntos guardados ilegibles para siempre"
    }
}

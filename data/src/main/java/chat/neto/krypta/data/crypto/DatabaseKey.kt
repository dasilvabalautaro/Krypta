package chat.neto.krypta.data.crypto

import java.security.SecureRandom
import java.util.Base64

/** Envuelve material sensible con una clave que no sale del dispositivo. */
interface KeyVault {
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/** Acceso mínimo a preferencias, para poder probar [DatabaseKey] sin Android. */
interface KeyPrefs {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/**
 * Frase-clave de la base de datos cifrada (SQLCipher): 32 bytes aleatorios, guardados
 * **envueltos** con una clave del Android Keystore que no se puede exportar.
 *
 * Qué protege: los **metadatos locales**. El contenido de los mensajes ya no se podía descifrar
 * con el fichero solo desde que el secreto compartido salió de la base (9 sep 2026), pero
 * seguían a la vista los nombres de contacto, los PeerID, las marcas de tiempo y el tamaño de
 * cada mensaje — o sea, con quién habla, cuándo y cuánto. Eso es lo que esto tapa.
 *
 * La regla vuelve a ser **no perder nunca la clave**, y aquí el listón es más alto que con la
 * identidad: la identidad se puede restaurar de un `.krbk`, pero **el historial de mensajes no
 * tiene copia de seguridad de ninguna clase**. Por eso, si existe una frase-clave guardada y no
 * se puede abrir, esto **falla a la vista** en lugar de generar otra: generar otra dejaría la
 * base anterior ilegible para siempre y el usuario estrenaría un historial vacío sin que nadie
 * le avisara de que el suyo sigue ahí, cifrado y perdido.
 */
class DatabaseKey(
    private val prefs: KeyPrefs,
    private val vault: KeyVault,
    private val random: SecureRandom = SecureRandom(),
) {

    /**
     * Frase-clave en claro. La crea la primera vez.
     *
     * Son 32 bytes de azar **expresados en hexadecimal** (64 caracteres ASCII), y no los bytes
     * crudos, por una razón práctica: la frase hay que pasarla por dos caminos distintos —el
     * `SupportOpenHelperFactory` de Room y una sentencia `ATTACH … KEY` durante el cifrado
     * inicial— y en el segundo va dentro del SQL. Una cadena binaria ahí es un problema (puede
     * traer comillas o ceros); en hexadecimal es siempre segura y no se pierde entropía: 32
     * bytes de azar siguen siendo 32 bytes de azar.
     */
    fun passphrase(): ByteArray {
        prefs.get(KEY_WRAPPED)?.let { stored ->
            val wrapped = runCatching { decode(stored) }.getOrNull()
                ?: error(CORRUPT_MESSAGE)
            return runCatching { vault.unwrap(wrapped) }.getOrElse { throw IllegalStateException(CORRUPT_MESSAGE, it) }
        }
        val fresh = ByteArray(KEY_BYTES).also(random::nextBytes)
            .joinToString("") { "%02x".format(it) }
            .toByteArray(Charsets.US_ASCII)
        // Verificar antes de dar por buena la clave nueva: si el Keystore de este móvil hace
        // algo raro, más vale enterarse ahora —con la base todavía vacía— que al reabrirla.
        val wrapped = vault.wrap(fresh)
        check(vault.unwrap(wrapped).contentEquals(fresh)) { "el almacén de claves no devuelve lo que guarda" }
        prefs.put(KEY_WRAPPED, encode(wrapped))
        return fresh
    }

    /** ¿Ya hay una frase-clave? (Si no, la base o no existe o está sin cifrar.) */
    fun exists(): Boolean = prefs.get(KEY_WRAPPED) != null

    private fun encode(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    private fun decode(s: String) = Base64.getDecoder().decode(s)

    companion object {
        const val KEY_WRAPPED = "db_passphrase_wrapped"
        /** Bytes de azar; la frase resultante son el doble de caracteres (hexadecimal). */
        const val KEY_BYTES = 32
        const val CORRUPT_MESSAGE =
            "la clave de la base de datos está guardada pero no se puede abrir con el almacén " +
                "de claves del sistema; no se crea otra a propósito, porque eso dejaría el " +
                "historial cifrado e inaccesible sin avisar"
    }
}

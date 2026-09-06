package chat.neto.krypta.p2p

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Formato del **respaldo de identidad** (archivo `.krbk`): la identidad Ed25519 + los
 * contactos (nombre, PeerID, verificado), cifrados con una passphrase del usuario.
 * Perder el móvil sin esto = perder el PeerID y que todos tus contactos tengan que
 * re-añadirte y re-verificarte.
 *
 * Binario: `"KRBK1"(5) ‖ salt(16) ‖ nonce(12) ‖ AES-256-GCM(payload)`, con el magic como
 * AAD (un archivo truncado/ajeno falla la autenticación, no produce basura). La clave sale
 * de PBKDF2-HMAC-SHA256 con 310k iteraciones (coste OWASP 2023; ~1 s en un móvil — bien
 * para un archivo que se descifra una vez). El payload es texto por líneas:
 * `v=1`, `id=<base64 identidad>`, y una `c=<base64 nombre>|<peerId>|<0/1>` por contacto
 * (el secreto compartido NO viaja: se re-deriva por ECDH de la identidad al importar).
 * Los **bloqueados** van en líneas aparte `b=<peerId>` en vez de en un cuarto campo de `c=`:
 * así un respaldo nuevo sigue siendo legible por una versión anterior de Krypta (su parser
 * exige exactamente 3 campos en `c=` e **ignora** las líneas que no conoce), que es justo lo
 * que hace falta en un archivo pensado para migrar de móvil.
 */
object IdentityBackup {

    data class BackupContact(
        val displayName: String,
        val peerId: String,
        val verified: Boolean,
        val blocked: Boolean = false,
    )

    class Data(val identity: ByteArray, val contacts: List<BackupContact>)

    /** El archivo no es un respaldo Krypta, está corrupto o la passphrase no es la suya. */
    class InvalidBackup(message: String) : Exception(message)

    fun encode(passphrase: CharArray, data: Data): ByteArray {
        require(passphrase.isNotEmpty()) { "passphrase vacía" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val nonce = ByteArray(NONCE_LEN).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(deriveKey(passphrase, salt), "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(MAGIC)
        return MAGIC + salt + nonce + cipher.doFinal(payload(data))
    }

    fun decode(passphrase: CharArray, blob: ByteArray): Data {
        if (blob.size < MAGIC.size + SALT_LEN + NONCE_LEN + TAG_BITS / 8 ||
            !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
        ) {
            throw InvalidBackup("no es un respaldo de Krypta")
        }
        val salt = blob.copyOfRange(MAGIC.size, MAGIC.size + SALT_LEN)
        val nonce = blob.copyOfRange(MAGIC.size + SALT_LEN, MAGIC.size + SALT_LEN + NONCE_LEN)
        val ciphertext = blob.copyOfRange(MAGIC.size + SALT_LEN + NONCE_LEN, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(deriveKey(passphrase, salt), "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(MAGIC)
        val plain = runCatching { cipher.doFinal(ciphertext) }
            .getOrElse { throw InvalidBackup("passphrase incorrecta o archivo dañado") }
        return parse(plain)
    }

    private fun payload(data: Data): ByteArray = buildString {
        append("v=1\n")
        append("id=").append(encoder.encodeToString(data.identity)).append('\n')
        for (c in data.contacts) {
            append("c=")
                .append(encoder.encodeToString(c.displayName.toByteArray(Charsets.UTF_8)))
                .append('|').append(c.peerId)
                .append('|').append(if (c.verified) '1' else '0')
                .append('\n')
        }
        for (c in data.contacts.filter { it.blocked }) {
            append("b=").append(c.peerId).append('\n')
        }
    }.toByteArray(Charsets.UTF_8)

    private fun parse(plain: ByteArray): Data {
        var identity: ByteArray? = null
        val contacts = mutableListOf<BackupContact>()
        val blocked = mutableSetOf<String>()
        for (line in String(plain, Charsets.UTF_8).lineSequence()) {
            when {
                line.startsWith("id=") -> identity = decoder.decode(line.removePrefix("id="))
                line.startsWith("b=") -> blocked += line.removePrefix("b=")
                line.startsWith("c=") -> {
                    val parts = line.removePrefix("c=").split('|')
                    if (parts.size != 3) throw InvalidBackup("contacto malformado")
                    contacts += BackupContact(
                        displayName = String(decoder.decode(parts[0]), Charsets.UTF_8),
                        peerId = parts[1],
                        verified = parts[2] == "1",
                    )
                }
            }
        }
        return Data(
            identity ?: throw InvalidBackup("respaldo sin identidad"),
            contacts.map { if (it.peerId in blocked) it.copy(blocked = true) else it },
        )
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS))
            .encoded

    private val MAGIC = "KRBK1".toByteArray(Charsets.US_ASCII)
    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val ITERATIONS = 310_000
}

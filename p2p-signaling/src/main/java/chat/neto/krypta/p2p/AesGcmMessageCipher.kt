package chat.neto.krypta.p2p

import chat.neto.krypta.core.MessageCipher
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AEAD con AES-256-GCM. La clave de sesión (32 bytes) se deriva del secreto compartido con
 * `HKDF(sharedSecret, info="krypta-msg-key-v1")`. Cada mensaje usa un nonce aleatorio de
 * 12 bytes que se antepone al texto cifrado: `salida = nonce(12) || ciphertext+tag(16)`.
 *
 * Nota: v1 deriva la clave directamente del secreto compartido estático (sin ratchet/PFS).
 * Suficiente para el spike; un forward-secrecy real (Noise/double-ratchet) es trabajo futuro.
 */
@Singleton
class AesGcmMessageCipher @Inject constructor() : MessageCipher {

    private val random = SecureRandom()

    override fun encrypt(sharedSecret: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, sessionKey(sharedSecret), GCMParameterSpec(TAG_BITS, nonce))
        }
        return nonce + cipher.doFinal(plaintext)
    }

    override fun decrypt(sharedSecret: ByteArray, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > NONCE_BYTES) { "ciphertext too short" }
        val nonce = ciphertext.copyOfRange(0, NONCE_BYTES)
        val body = ciphertext.copyOfRange(NONCE_BYTES, ciphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, sessionKey(sharedSecret), GCMParameterSpec(TAG_BITS, nonce))
        }
        return cipher.doFinal(body)
    }

    /**
     * Clave de sesión del contacto, **cacheada**. La derivación es determinista (mismo
     * secreto → misma clave), pero se hacía en *cada* cifrado y descifrado: al pintar una
     * conversación eso es una HKDF por mensaje y por repintado. La caché no amplía la
     * exposición —la clave ya es derivable del secreto que está guardado en Room— y va
     * acotada por si algún día hay muchos contactos.
     */
    private fun sessionKey(sharedSecret: ByteArray): SecretKeySpec =
        sessionKeys.getOrPut(SecretRef(sharedSecret)) {
            val key = Hkdf.derive(
                ikm = sharedSecret,
                salt = ByteArray(0),
                info = "krypta-msg-key-v1".toByteArray(Charsets.UTF_8),
                length = KEY_BYTES,
            )
            SecretKeySpec(key, "AES")
        }

    /** Clave de mapa por *contenido* del secreto (un ByteArray compara por identidad). */
    private class SecretRef(private val bytes: ByteArray) {
        private val hash = bytes.contentHashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is SecretRef && bytes.contentEquals(other.bytes)
    }

    private val sessionKeys = object : LinkedHashMap<SecretRef, SecretKeySpec>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SecretRef, SecretKeySpec>?) =
            size > MAX_CACHED_KEYS
    }.let { java.util.Collections.synchronizedMap(it) }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        /** Claves de sesión cacheadas (una por contacto activo). */
        const val MAX_CACHED_KEYS = 64
    }
}

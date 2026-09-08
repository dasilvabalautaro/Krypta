package chat.neto.krypta.nativebridge

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [KeyWrapper] respaldado por el **Android Keystore**: la clave AES-256 con la que se envuelve
 * la identidad se genera dentro del almacén del sistema (TEE) y nunca se puede exportar —
 * copiar el fichero de preferencias ya no basta para llevarse la identidad.
 *
 * Deliberadamente **sin autenticación de usuario** (`setUserAuthenticationRequired(false)`, que
 * es el valor por defecto) y sin `setUnlockedDeviceRequired`: Krypta tiene que poder recibir
 * mensajes y llamadas con el móvil bloqueado, y eso exige poder abrir la identidad sin que
 * nadie desbloquee nada. Lo que esto protege es la **extracción en frío** del dato, no el uso
 * por parte de quien ya controla el dispositivo desbloqueado (para eso está el bloqueo de app).
 *
 * Tampoco se pide StrongBox: no todos los dispositivos lo tienen y hay OEM donde falla en
 * tiempo de uso y no de creación, que es justo el momento en el que un fallo dejaría al
 * usuario sin identidad.
 */
class KeystoreKeyWrapper(private val alias: String = DEFAULT_ALIAS) : KeyWrapper {

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        // El IV lo elige el propio Keystore (setRandomizedEncryptionRequired): se guarda delante.
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        require(wrapped.size > IV_BYTES) { "envoltura demasiado corta" }
        val iv = wrapped.copyOfRange(0, IV_BYTES)
        val body = wrapped.copyOfRange(IV_BYTES, wrapped.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        }
        return cipher.doFinal(body)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "krypta_identity_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

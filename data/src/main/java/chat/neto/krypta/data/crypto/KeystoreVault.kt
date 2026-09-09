package chat.neto.krypta.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [KeyVault] respaldado por el Android Keystore, para la frase-clave de la base cifrada.
 *
 * Es gemelo de `KeystoreKeyWrapper` (:native-bridge, que envuelve la identidad Ed25519) y está
 * duplicado a conciencia: `:data` no depende de `:native-bridge` —ni debe—, y el sitio común
 * sería `:core`, que por convención de este repo no lleva componentes de Android. Cincuenta
 * líneas repetidas cuestan menos que romper el grafo de módulos.
 *
 * Sin autenticación de usuario, igual que allí: la app tiene que poder abrir su base con el
 * móvil bloqueado para recibir mensajes. Lo que protege es la extracción en frío del fichero.
 */
class KeystoreVault(private val alias: String = DEFAULT_ALIAS) : KeyVault {

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        require(wrapped.size > IV_BYTES) { "envoltura demasiado corta" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, wrapped.copyOfRange(0, IV_BYTES)))
        }
        return cipher.doFinal(wrapped.copyOfRange(IV_BYTES, wrapped.size))
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
        const val DEFAULT_ALIAS = "krypta_db_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

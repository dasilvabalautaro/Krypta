package chat.neto.krypta.p2p

import chat.neto.krypta.core.Kem
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KEM

/**
 * [Kem] con el ML-KEM-768 nativo del JDK 25 (`SunJCE`), para probar la parte post-cuántica en la
 * JVM. En el dispositivo la implementación es la del puente Go: **Android no trae ML-KEM a
 * ninguna API** (ver [docs/DISENO-postcuantico.md] §1.1).
 *
 * Tiene una verruga, de la misma familia que la de [JdkCurve25519] con la coordenada `u`: el JDK
 * habla de la pública **envuelta en X.509** (1206 bytes) y el cable —y Go— la quieren **cruda**
 * (1184). La diferencia es un prefijo SPKI fijo de 22 bytes, que aquí se quita y se pone. No se
 * da por supuesto: [PREFIX] se **comprueba** contra lo que emite el JDK en cada conversión, para
 * que si un JDK futuro cambiara la codificación el test falle en vez de producir claves
 * silenciosamente inválidas.
 *
 * La privada se queda tal como la da el JDK (PKCS#8 de 2428 bytes, la clave expandida) porque la
 * interfaz la trata como **opaca**: nunca viaja, solo la lee la misma implementación que la
 * escribió. Go, en su lugar, guarda una semilla de 64 bytes.
 */
class JdkKem : Kem {

    override fun generateKeyPair(): Kem.KeyPair {
        val kp = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()
        return Kem.KeyPair(
            privateKey = kp.private.encoded,
            publicKey = stripPrefix(kp.public.encoded),
        )
    }

    override fun encapsulate(publicKey: ByteArray): Kem.Encapsulated {
        require(publicKey.size == Kem.PUBLIC_KEY_BYTES) {
            "pública ML-KEM-768 de ${publicKey.size} bytes, se esperaban ${Kem.PUBLIC_KEY_BYTES}"
        }
        val pub = KeyFactory.getInstance(ALGORITHM)
            .generatePublic(X509EncodedKeySpec(PREFIX + publicKey))
        val encapsulated = KEM.getInstance(KEM_ALGORITHM).newEncapsulator(pub).encapsulate()
        return Kem.Encapsulated(
            sharedSecret = encapsulated.key().encoded,
            ciphertext = encapsulated.encapsulation(),
        )
    }

    override fun decapsulate(privateKey: ByteArray, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size == Kem.CIPHERTEXT_BYTES) {
            "ciphertext ML-KEM-768 de ${ciphertext.size} bytes, se esperaban ${Kem.CIPHERTEXT_BYTES}"
        }
        val priv = KeyFactory.getInstance(ALGORITHM)
            .generatePrivate(PKCS8EncodedKeySpec(privateKey))
        return KEM.getInstance(KEM_ALGORITHM).newDecapsulator(priv).decapsulate(ciphertext).encoded
    }

    /** Quita el prefijo SPKI, comprobando que es **el** prefijo y no otra codificación. */
    private fun stripPrefix(spki: ByteArray): ByteArray {
        require(spki.size == PREFIX.size + Kem.PUBLIC_KEY_BYTES) {
            "SPKI de ${spki.size} bytes: el JDK ha cambiado la codificación de ML-KEM-768"
        }
        val prefix = spki.copyOfRange(0, PREFIX.size)
        require(prefix.contentEquals(PREFIX)) {
            "prefijo SPKI inesperado: ${prefix.toHex()} (se esperaba ${PREFIX.toHex()})"
        }
        return spki.copyOfRange(PREFIX.size, spki.size)
    }

    companion object {
        private const val ALGORITHM = "ML-KEM-768"
        private const val KEM_ALGORITHM = "ML-KEM"

        /**
         * Prefijo `SubjectPublicKeyInfo` de ML-KEM-768: 22 bytes con el OID
         * `2.16.840.1.101.3.4.4.2`. Medido el 11 sep 2026 contra el JDK 25.0.1, y verificado
         * pegándoselo a la clave cruda que produce Go (el resultado es una clave válida y
         * equivalente a la original).
         */
        val PREFIX: ByteArray = byteArrayOf(
            0x30, 0x82.toByte(), 0x04, 0xb2.toByte(), 0x30, 0x0b, 0x06, 0x09,
            0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x04,
            0x02, 0x03, 0x82.toByte(), 0x04, 0xa1.toByte(), 0x00,
        )

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

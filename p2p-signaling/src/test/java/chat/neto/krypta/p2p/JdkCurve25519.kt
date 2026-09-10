package chat.neto.krypta.p2p

import chat.neto.krypta.core.Curve25519
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.XECPrivateKey
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * [Curve25519] con el `XDH` del JDK, para probar el ratchet en la JVM. En el dispositivo la
 * implementación es la del puente Go: Android no trae `XDH` hasta la API 33 y el `minSdk` es 30.
 *
 * El JDK habla de la pública como el entero `u` en vez de como 32 bytes, así que hay que
 * convertir en los dos sentidos (little-endian, con el bit alto enmascarado según RFC 7748).
 */
class JdkCurve25519 : Curve25519 {

    override fun generateKeyPair(): Curve25519.KeyPair {
        val kp = KeyPairGenerator.getInstance("XDH")
            .apply { initialize(NamedParameterSpec.X25519) }
            .generateKeyPair()
        val scalar = (kp.private as XECPrivateKey).scalar.orElseThrow()
        return Curve25519.KeyPair(scalar, encodeU((kp.public as XECPublicKey).u))
    }

    override fun agree(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val factory = KeyFactory.getInstance("XDH")
        val priv = factory.generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, privateKey))
        val pub = factory.generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, decodeU(publicKey)))
        return KeyAgreement.getInstance("XDH").apply { init(priv); doPhase(pub, true) }.generateSecret()
    }

    private fun encodeU(u: BigInteger): ByteArray {
        val bigEndian = u.toByteArray()
        val out = ByteArray(32)
        var i = 0
        var j = bigEndian.size - 1
        while (j >= 0 && i < out.size) out[i++] = bigEndian[j--]
        return out
    }

    private fun decodeU(publicKey: ByteArray): BigInteger {
        require(publicKey.size == 32) { "pública X25519 de ${publicKey.size} bytes" }
        val le = publicKey.copyOf()
        le[31] = (le[31].toInt() and 0x7F).toByte()
        return BigInteger(1, le.reversedArray())
    }
}

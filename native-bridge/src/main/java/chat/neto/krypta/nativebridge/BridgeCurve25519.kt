package chat.neto.krypta.nativebridge

import chat.neto.krypta.bridge.Bridge
import chat.neto.krypta.core.Curve25519
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [Curve25519] sobre el puente Go, que es donde X25519 está disponible en cualquier Android:
 * el `XDH` de la plataforma no llega hasta la API 33 y el `minSdk` de Krypta es 30. En los
 * tests de JVM el ratchet usa la implementación del JDK, así que su lógica se prueba sin
 * dispositivo y esto se queda como una traducción de tipos.
 *
 * `Bridge.ratchetKeyPair()` devuelve `privada(32) ‖ pública(32)` en un solo `[]byte` porque
 * gomobile no sabe cruzar un struct con dos slices.
 */
@Singleton
class BridgeCurve25519 @Inject constructor() : Curve25519 {

    override fun generateKeyPair(): Curve25519.KeyPair {
        val both = Bridge.ratchetKeyPair()
        require(both.size == 2 * KEY_BYTES) { "par X25519 de ${both.size} bytes" }
        return Curve25519.KeyPair(
            privateKey = both.copyOfRange(0, KEY_BYTES),
            publicKey = both.copyOfRange(KEY_BYTES, both.size),
        )
    }

    override fun agree(privateKey: ByteArray, publicKey: ByteArray): ByteArray =
        Bridge.ratchetAgree(privateKey, publicKey)

    private companion object {
        const val KEY_BYTES = 32
    }
}

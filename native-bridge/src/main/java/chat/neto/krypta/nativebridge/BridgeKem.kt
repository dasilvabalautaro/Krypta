package chat.neto.krypta.nativebridge

import chat.neto.krypta.bridge.Bridge
import chat.neto.krypta.core.Kem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [Kem] (ML-KEM-768) sobre el puente Go, que es la **única** forma de tenerlo en Android: la
 * plataforma no lo trae a ninguna API, ni siquiera en la 36 — a diferencia de X25519, que al
 * menos llega en la 33. En los tests de JVM se usa el del JDK 25, así que la lógica del
 * protocolo se prueba sin dispositivo y esto queda como una traducción de tipos.
 *
 * Los valores van **concatenados** en un solo `[]byte` porque gomobile no sabe cruzar un struct
 * con dos slices, igual que ya pasaba con `ratchetKeyPair`:
 *
 * - `kemKeyPair()` → semilla(64) ‖ pública(1184)
 * - `kemEncapsulate()` → secreto(32) ‖ ciphertext(1088)
 *
 * La «privada» de este lado es la **semilla** de 64 bytes, no la clave expandida de 2400: la
 * reconstruye `crypto/mlkem` cuando hace falta. Que el JDK use otro formato para lo mismo da
 * igual, porque la interfaz trata la privada como opaca y el estado del ratchet nunca sale del
 * dispositivo que lo escribió (ver [docs/DISENO-postcuantico.md] §1.2).
 */
@Singleton
class BridgeKem @Inject constructor() : Kem {

    override fun generateKeyPair(): Kem.KeyPair {
        val both = Bridge.kemKeyPair()
        require(both.size == SEED_BYTES + Kem.PUBLIC_KEY_BYTES) {
            "par ML-KEM de ${both.size} bytes, se esperaban ${SEED_BYTES + Kem.PUBLIC_KEY_BYTES}"
        }
        return Kem.KeyPair(
            privateKey = both.copyOfRange(0, SEED_BYTES),
            publicKey = both.copyOfRange(SEED_BYTES, both.size),
        )
    }

    override fun encapsulate(publicKey: ByteArray): Kem.Encapsulated {
        val both = Bridge.kemEncapsulate(publicKey)
        require(both.size == Kem.SECRET_BYTES + Kem.CIPHERTEXT_BYTES) {
            "encapsulado de ${both.size} bytes, " +
                "se esperaban ${Kem.SECRET_BYTES + Kem.CIPHERTEXT_BYTES}"
        }
        return Kem.Encapsulated(
            sharedSecret = both.copyOfRange(0, Kem.SECRET_BYTES),
            ciphertext = both.copyOfRange(Kem.SECRET_BYTES, both.size),
        )
    }

    override fun decapsulate(privateKey: ByteArray, ciphertext: ByteArray): ByteArray =
        Bridge.kemDecapsulate(privateKey, ciphertext)

    private companion object {
        /** Semilla `d ‖ z` de FIPS 203: lo que este lado guarda como privada. */
        const val SEED_BYTES = 64
    }
}

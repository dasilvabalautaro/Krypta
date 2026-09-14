package chat.neto.krypta.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `Hkdf` es la **única primitiva criptográfica implementada a mano** del protocolo (lo demás es
 * JCA o Go), y de ella sale casi todo: el ratchet, la clave v1, las etiquetas del buzón, el
 * rendezvous y las claves de llamada. Un error aquí **no rompería nada visible**: los dos extremos
 * derivarían lo mismo, mal, y se seguirían entendiendo.
 *
 * Por eso se fija contra los vectores del apéndice A de la RFC 5869 (casos con SHA-256). El 14 sep
 * 2026, antes de copiarlos aquí, se recalcularon con una implementación independiente (`hmac` y
 * `hashlib` de la biblioteca estándar de Python) y coincidieron. Ver
 * `docs/REVISION-protocolo-2026-09-14.md` §3.
 */
class HkdfTest {

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun `caso 1 de la RFC 5869`() {
        val okm = Hkdf.derive(
            ikm = ByteArray(22) { 0x0b },
            salt = ByteArray(13) { it.toByte() },
            info = ByteArray(10) { (0xf0 + it).toByte() },
            length = 42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.hex(),
        )
    }

    /** Entradas de 80 bytes y salida de más de dos bloques: ejercita la expansión de verdad. */
    @Test
    fun `caso 2 de la RFC 5869, entradas largas`() {
        val okm = Hkdf.derive(
            ikm = ByteArray(80) { it.toByte() },
            salt = ByteArray(80) { (0x60 + it).toByte() },
            info = ByteArray(80) { (0xb0 + it).toByte() },
            length = 82,
        )
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
                "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
                "cc30c58179ec3e87c14c01d5c1f3434f1d87",
            okm.hex(),
        )
    }

    /**
     * Sal e info vacías, que es **el caso que usa Krypta** en casi todas sus derivaciones: la RFC
     * manda sustituir la sal vacía por 32 bytes a cero, y si no se hiciera, este vector fallaría.
     */
    @Test
    fun `caso 3 de la RFC 5869, sal e info vacias`() {
        val okm = Hkdf.derive(ikm = ByteArray(22) { 0x0b }, salt = ByteArray(0), info = ByteArray(0), length = 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            okm.hex(),
        )
    }
}

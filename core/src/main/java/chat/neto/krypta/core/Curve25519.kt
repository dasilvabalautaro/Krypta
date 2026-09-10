package chat.neto.krypta.core

/**
 * Operaciones X25519 sueltas (generar un par efímero y acordar), que el ratchet necesita para
 * su parte DH. Es una interfaz y no una llamada directa por dos razones:
 *
 * - **Android no trae `XDH` hasta la API 33** y el `minSdk` de Krypta es 30, así que en el
 *   dispositivo la implementación es la del puente Go (que ya hace X25519 en `SharedSecretFor`).
 * - Así el ratchet se puede probar en la JVM, donde el JDK sí trae `KeyAgreement("XDH")`.
 *
 * Ojo con la diferencia respecto a [KeyExchange]: aquel deriva el secreto **estático** de la
 * pareja a partir de las identidades y es una función pura de ellas; este genera claves
 * **efímeras** que nunca se derivan de nada y que se borran, que es de donde sale todo el
 * secreto hacia adelante (ver [docs/DISENO-ratchet.md] §0).
 */
interface Curve25519 {

    /** Par efímero recién sorteado. Ambas mitades son de 32 bytes. */
    fun generateKeyPair(): KeyPair

    /** Punto compartido X25519(privada, pública) — 32 bytes. Lanza si alguna no es válida. */
    fun agree(privateKey: ByteArray, publicKey: ByteArray): ByteArray

    /** Par de claves X25519 en bruto (32 bytes cada mitad). */
    class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)
}

package chat.neto.krypta.core

/**
 * Mecanismo de encapsulado de clave **post-cuántico** (ML-KEM-768, FIPS 203), que el ratchet
 * necesita para mezclar material resistente a un ordenador cuántico en su raíz. El diseño
 * completo —y por qué el material **no** puede viajar en cada mensaje— está en
 * [docs/DISENO-postcuantico.md].
 *
 * Es una interfaz por la misma razón que [Curve25519], solo más marcada: **Android no trae
 * ML-KEM a ninguna API**, así que en el dispositivo la implementación es la del puente Go
 * (`crypto/mlkem`, en la estándar desde Go 1.24), mientras que en la JVM de los tests el JDK 25
 * sí lo trae nativo (`SunJCE`), así que la lógica se prueba sin dispositivo.
 *
 * **Qué formato promete y qué no.** Promete que [KeyPair.publicKey] y
 * [Encapsulated.ciphertext] son los bytes **crudos** de FIPS 203 ([PUBLIC_KEY_BYTES] y
 * [CIPHERTEXT_BYTES]), porque esos dos **viajan por el cable** y los dos extremos pueden estar
 * en implementaciones distintas. No promete nada sobre [KeyPair.privateKey], que es **opaca**:
 * Go entrega una semilla de 64 bytes y el JDK la clave expandida de 2400, y da igual porque el
 * estado del ratchet nunca sale del dispositivo que lo escribió.
 *
 * Ojo con la diferencia respecto a [Curve25519]: aquel es simétrico (los dos lados acuerdan
 * desde extremos opuestos), este **no**. Aquí uno ofrece una clave y el otro *elige* el secreto
 * y lo encapsula; en Krypta quién hace cada papel lo fija el orden canónico de los dos PeerID.
 */
interface Kem {

    /** Par ML-KEM recién sorteado. */
    fun generateKeyPair(): KeyPair

    /**
     * Sortea un secreto y lo encapsula contra [publicKey] (cruda, [PUBLIC_KEY_BYTES] bytes).
     * Lanza si la clave no es válida.
     */
    fun encapsulate(publicKey: ByteArray): Encapsulated

    /**
     * Recupera el secreto de [ciphertext] con [privateKey] (la de este par, opaca).
     *
     * **No lanza con un ciphertext falso**: ML-KEM es de rechazo implícito, así que un
     * ciphertext manipulado devuelve un secreto *distinto* en vez de un error. Quien detecta el
     * engaño es el AEAD que usa ese secreto, no esta función.
     */
    fun decapsulate(privateKey: ByteArray, ciphertext: ByteArray): ByteArray

    /**
     * Par de claves. [publicKey] tiene el formato del cable; [privateKey] es **opaca** y solo la
     * entiende la implementación que la creó (ver la nota de formato de [Kem]).
     */
    class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    /** Resultado de encapsular: el secreto compartido y el ciphertext que hay que enviar. */
    class Encapsulated(val sharedSecret: ByteArray, val ciphertext: ByteArray)

    companion object {
        /** Clave de encapsulado cruda de ML-KEM-768. Es lo que va en la cabecera. */
        const val PUBLIC_KEY_BYTES = 1184

        /** Ciphertext de encapsulado. También va en la cabecera. */
        const val CIPHERTEXT_BYTES = 1088

        /** Secreto compartido resultante. */
        const val SECRET_BYTES = 32
    }
}

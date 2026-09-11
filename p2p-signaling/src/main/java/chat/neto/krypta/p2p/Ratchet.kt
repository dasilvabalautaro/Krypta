package chat.neto.krypta.p2p

import chat.neto.krypta.core.Curve25519
import javax.inject.Inject
import javax.inject.Singleton
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** El ratchet no pudo abrir un mensaje (cabecera ilegible, época perdida, clave gastada). */
class RatchetException(message: String) : Exception(message)

/**
 * Secreto hacia adelante para el contenido: doble ratchet **por épocas**, donde una época es el
 * *par* de claves públicas efímeras vigentes y la transición es simétrica y determinista. El
 * diseño completo, y por qué no es el doble ratchet de Signal tal cual, está en
 * [docs/DISENO-ratchet.md] (§1 el mecanismo, §2 la desviación).
 *
 * En corto:
 *
 * - **Época 0** derivable del secreto compartido de la pareja: cualquiera de los dos puede
 *   escribir primero sin ronda previa y sin carrera. No tiene PFS, igual que el primer mensaje
 *   de una sesión de Signal; lo que importa es salir de ella, y se sale sola (un acuse de
 *   lectura ya es un mensaje).
 * - **Época e ≥ 1**: `RK(e) = HKDF(X25519(mi_priv(e), su_pub(e)), salt = RK(e-1))`. Cada mensaje
 *   lleva mi propuesta para `e+1`; se pasa a `e+1` **en cuanto se tienen las dos públicas**, que
 *   es una condición que ambos evalúan sobre los mismos datos.
 * - **Cadena simétrica** por mensaje (HMAC), clave de mensaje de un solo uso, y nonce derivado
 *   de ella en vez de sorteado.
 * - La privada de una época se **borra en cuanto se ha derivado su raíz**: ahí está el secreto
 *   hacia adelante. Lo que queda en disco no abre lo que ya pasó.
 *
 * No guarda estado propio: ver [RatchetState] sobre por qué es una función pura.
 */
@Singleton
class Ratchet @Inject constructor(private val curve: Curve25519) {

    /** Ciphertext listo para la red y el estado que hay que guardar **con** él. */
    class Sealed(val state: RatchetState, val ciphertext: ByteArray)

    /** Texto en claro y el estado que hay que guardar **con** el mensaje ya persistido. */
    class Opened(val state: RatchetState, val plaintext: ByteArray)

    /**
     * Sesión recién nacida: época 0 del linaje [lineage], derivable a los dos lados del
     * [sharedSecret]. Se usa al añadir un contacto y también como recuperación cuando el estado
     * se pierde o no se puede leer — por eso una conversación nunca queda rota del todo.
     */
    fun initial(
        sharedSecret: ByteArray,
        myPeerId: String,
        theirPeerId: String,
        lineage: Long = System.currentTimeMillis(),
    ): RatchetState {
        val sendDir = directionOf(sender = myPeerId, recipient = theirPeerId)
        return epochZero(sharedSecret, lineage, sendDir, past = emptyList(), skipped = emptyList())
    }

    /**
     * Cifra [plaintext]; devuelve el ciphertext (cabecera ‖ AEAD) y el estado avanzado.
     *
     * Con [pad] el texto en claro va **relleno por tramos** (ver [Padding]) y la cabecera lo
     * marca. Es un parámetro y no una decisión de aquí porque depende del contacto: solo se le
     * rellena a quien haya anunciado que sabe quitarlo (ver `ChatService.PADDING_MIN_PROTOCOL`).
     */
    fun encrypt(state: RatchetState, plaintext: ByteArray, pad: Boolean = false): Sealed {
        val st = maybeAdvance(state)
        val header = Header(
            lineage = st.lineage,
            epoch = st.epoch,
            n = st.sendN,
            pn = st.sendPN,
            curPub = st.myCurPub,
            nextPub = st.nextPub,
            flags = if (pad) FLAG_PADDED else 0,
        ).encode()
        val mk = messageKey(st.sendChain)
        val body = seal(mk, header, if (pad) Padding.pad(plaintext) else plaintext)
        return Sealed(
            st.copy(sendChain = nextChainKey(st.sendChain), sendN = st.sendN + 1),
            header + body,
        )
    }

    /**
     * Descifra [wire]. Lanza [RatchetException] si no se puede abrir — y en ese caso **el estado
     * no cambia**, porque el que devuelve es el único que el llamante guarda. Eso es lo que hace
     * que una cabecera forjada (linaje altísimo, época futura) no pueda mover nada: sin el
     * secreto compartido no hay forma de que el AEAD valide.
     */
    fun decrypt(state: RatchetState, sharedSecret: ByteArray, wire: ByteArray): Opened {
        val header = Header.decode(wire) ?: throw RatchetException("cabecera de ratchet ilegible")
        val body = wire.copyOfRange(HEADER_BYTES, wire.size)
        val aad = wire.copyOfRange(0, HEADER_BYTES)

        var st = state
        // Linaje mayor = el otro perdió su estado y ha vuelto a empezar. Se adopta.
        if (header.lineage > st.lineage) st = reset(st, sharedSecret, header.lineage)
        // Va una época por delante: su pública de esa época viene en la cabecera, y mi privada
        // para ella es la propuesta que aún no he consumido. Como mucho puede ir una, porque
        // para avanzar necesita una propuesta mía que solo hago al entrar en la época anterior.
        if (header.lineage == st.lineage && header.epoch == st.epoch + 1) {
            st = advance(st, header.curPub)
        }

        if (header.lineage != st.lineage || header.epoch != st.epoch) {
            return openOld(st, sharedSecret, header, aad, body)
        }

        val (advanced, mk) = takeReceiveKey(st, header)
        val plaintext = unpad(header, open(mk, aad, body))
        // Solo después de que el AEAD haya validado se apunta su propuesta y se avanza.
        return Opened(maybeAdvance(advanced.copy(peerNextPub = header.nextPub)), plaintext)
    }

    // --- épocas -------------------------------------------------------------------------

    /** Con las dos públicas de la época siguiente, se pasa a ella. Si no, el estado tal cual. */
    private fun maybeAdvance(st: RatchetState): RatchetState =
        st.peerNextPub?.let { advance(st, it) } ?: st

    /**
     * Entra en la época `epoch + 1` con la pública [peerPub] del otro. La privada propia se
     * consume aquí y **no se guarda**: es lo único que impide reconstruir esta época mañana.
     */
    private fun advance(st: RatchetState, peerPub: ByteArray): RatchetState {
        val dh = runCatching { curve.agree(st.nextPriv, peerPub) }
            .getOrElse { throw RatchetException("clave pública de época inválida: ${it.message}") }
        // Punto de orden bajo: el acuerdo sale todo ceros y la época entera sería pública.
        if (dh.all { it == ZERO_BYTE }) throw RatchetException("clave pública de época degenerada")
        val epoch = st.epoch + 1
        val root = Hkdf.derive(ikm = dh, salt = st.rootKey, info = info("root:$epoch"), length = KEY_BYTES)
        val fresh = curve.generateKeyPair()
        return st.copy(
            epoch = epoch,
            rootKey = root,
            sendChain = chainKey(root, epoch, st.sendDir),
            sendN = 0,
            sendPN = st.sendN,
            recvChain = chainKey(root, epoch, 1 - st.sendDir),
            recvN = 0,
            myCurPub = st.nextPub,
            nextPriv = fresh.privateKey,
            nextPub = fresh.publicKey,
            peerNextPub = null,
            past = retire(st.past, st.lineage, st.epoch, st.recvChain, st.recvN),
        )
    }

    /** Vuelve a la época 0 de un linaje nuevo, conservando lo que pueda seguir en vuelo. */
    private fun reset(st: RatchetState, sharedSecret: ByteArray, lineage: Long): RatchetState =
        epochZero(
            sharedSecret,
            lineage,
            st.sendDir,
            past = retire(st.past, st.lineage, st.epoch, st.recvChain, st.recvN),
            skipped = st.skipped,
        )

    private fun epochZero(
        sharedSecret: ByteArray,
        lineage: Long,
        sendDir: Int,
        past: List<RatchetState.PastChain>,
        skipped: List<RatchetState.SkippedKey>,
    ): RatchetState {
        // El linaje entra en la derivación: si no, dos linajes distintos compartirían las claves
        // de la época 0 y sus contadores N chocarían — la misma clave dos veces.
        val root = Hkdf.derive(
            ikm = sharedSecret, salt = ByteArray(0),
            info = info("root:0:$lineage"), length = KEY_BYTES,
        )
        val fresh = curve.generateKeyPair()
        return RatchetState(
            lineage = lineage,
            epoch = 0,
            sendDir = sendDir,
            rootKey = root,
            sendChain = chainKey(root, 0, sendDir),
            sendN = 0,
            sendPN = 0,
            recvChain = chainKey(root, 0, 1 - sendDir),
            recvN = 0,
            myCurPub = ByteArray(KEY_BYTES),
            nextPriv = fresh.privateKey,
            nextPub = fresh.publicKey,
            peerNextPub = null,
            past = past,
            skipped = skipped,
        )
    }

    private fun retire(
        past: List<RatchetState.PastChain>,
        lineage: Long,
        epoch: Int,
        chainKey: ByteArray,
        n: Int,
    ): List<RatchetState.PastChain> =
        (past + RatchetState.PastChain(lineage, epoch, chainKey, n)).takeLast(MAX_PAST_CHAINS)

    // --- claves de mensaje --------------------------------------------------------------

    /** Clave del mensaje `header.n` de la cadena actual, guardando las que queden por medio. */
    private fun takeReceiveKey(st: RatchetState, header: Header): Pair<RatchetState, ByteArray> {
        if (header.n < st.recvN) return takeSkipped(st, st.lineage, st.epoch, header.n)
        val gap = header.n - st.recvN
        if (gap > MAX_SKIP) throw RatchetException("hueco de $gap mensajes: por encima del tope")
        var chain = st.recvChain
        val skipped = st.skipped.toMutableList()
        for (n in st.recvN until header.n) {
            skipped += RatchetState.SkippedKey(st.lineage, st.epoch, n, messageKey(chain))
            chain = nextChainKey(chain)
        }
        val mk = messageKey(chain)
        return st.copy(
            recvChain = nextChainKey(chain),
            recvN = header.n + 1,
            skipped = skipped.takeLast(MAX_SKIPPED_KEYS),
        ) to mk
    }

    /** Mensaje que llega tarde: de una época retirada, o de un linaje que ya no es el nuestro. */
    private fun openOld(
        st: RatchetState,
        sharedSecret: ByteArray,
        header: Header,
        aad: ByteArray,
        body: ByteArray,
    ): Opened {
        val skipped = st.skipped.firstOrNull {
            it.lineage == header.lineage && it.epoch == header.epoch && it.n == header.n
        }
        if (skipped != null) {
            val (dropped, mk) = takeSkipped(st, header.lineage, header.epoch, header.n)
            return Opened(dropped, unpad(header, open(mk, aad, body)))
        }

        val chain = st.past.firstOrNull { it.lineage == header.lineage && it.epoch == header.epoch }
        // La época 0 de cualquier linaje es derivable del secreto compartido, así que un mensaje
        // de un linaje anterior (el otro aún no ha adoptado el nuestro) siempre se puede abrir.
        // Más allá de la 0 no: para llegar ahí el otro necesitó una propuesta nuestra, o sea que
        // el linaje sería el mismo.
        val resolved = chain ?: if (header.epoch == 0) {
            val root = Hkdf.derive(
                ikm = sharedSecret, salt = ByteArray(0),
                info = info("root:0:${header.lineage}"), length = KEY_BYTES,
            )
            RatchetState.PastChain(header.lineage, 0, chainKey(root, 0, 1 - st.sendDir), 0)
        } else {
            throw RatchetException("época ${header.epoch} del linaje ${header.lineage} ya no está")
        }

        if (header.n < resolved.n) throw RatchetException("clave del mensaje ${header.n} ya gastada")
        val gap = header.n - resolved.n
        if (gap > MAX_SKIP) throw RatchetException("hueco de $gap mensajes: por encima del tope")
        var ck = resolved.chainKey
        val skippedKeys = st.skipped.toMutableList()
        for (n in resolved.n until header.n) {
            skippedKeys += RatchetState.SkippedKey(header.lineage, header.epoch, n, messageKey(ck))
            ck = nextChainKey(ck)
        }
        val mk = messageKey(ck)
        val plaintext = unpad(header, open(mk, aad, body))
        val updated = RatchetState.PastChain(header.lineage, header.epoch, nextChainKey(ck), header.n + 1)
        return Opened(
            st.copy(
                past = (st.past.filterNot { it.lineage == header.lineage && it.epoch == header.epoch } + updated)
                    .takeLast(MAX_PAST_CHAINS),
                skipped = skippedKeys.takeLast(MAX_SKIPPED_KEYS),
            ),
            plaintext,
        )
    }

    private fun takeSkipped(st: RatchetState, lineage: Long, epoch: Int, n: Int): Pair<RatchetState, ByteArray> {
        val hit = st.skipped.firstOrNull { it.lineage == lineage && it.epoch == epoch && it.n == n }
            ?: throw RatchetException("clave del mensaje $n (época $epoch) ya gastada")
        return st.copy(skipped = st.skipped - hit) to hit.messageKey
    }

    // --- primitivas ---------------------------------------------------------------------

    /**
     * Quita el relleno si la cabecera dice que lo lleva.
     *
     * El bit vive **dentro del AAD**, así que para cuando se llega aquí el propio AEAD ya ha
     * garantizado que lo puso quien cifró: nadie puede encenderlo ni apagarlo por el camino
     * (encenderlo haría que se comiera el final del mensaje; apagarlo, que se entregara el
     * relleno como si fuera contenido). Los bits que esta versión no conoce se ignoran a
     * propósito, para que una futura pueda usarlos sin romper a esta.
     */
    private fun unpad(header: Header, plaintext: ByteArray): ByteArray =
        if (header.padded) Padding.strip(plaintext) else plaintext

    private fun seal(messageKey: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val (key, nonce) = keyAndNonce(messageKey)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
        return cipher.doFinal(plaintext)
    }

    private fun open(messageKey: ByteArray, aad: ByteArray, body: ByteArray): ByteArray {
        val (key, nonce) = keyAndNonce(messageKey)
        return runCatching {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(aad)
            }.doFinal(body)
        }.getOrElse { throw RatchetException("el mensaje no autentica: ${it.message}") }
    }

    private fun keyAndNonce(messageKey: ByteArray): Pair<ByteArray, ByteArray> {
        val out = Hkdf.derive(
            ikm = messageKey, salt = ByteArray(0),
            info = info("msg"), length = KEY_BYTES + NONCE_BYTES,
        )
        return out.copyOfRange(0, KEY_BYTES) to out.copyOfRange(KEY_BYTES, out.size)
    }

    private fun chainKey(root: ByteArray, epoch: Int, dir: Int): ByteArray =
        Hkdf.derive(ikm = root, salt = ByteArray(0), info = info("chain:$epoch:$dir"), length = KEY_BYTES)

    private fun messageKey(chainKey: ByteArray): ByteArray = hmac(chainKey, MESSAGE_KEY_SEED)

    private fun nextChainKey(chainKey: ByteArray): ByteArray = hmac(chainKey, CHAIN_KEY_SEED)

    private fun hmac(key: ByteArray, seed: Byte): ByteArray =
        Mac.getInstance(HMAC_SHA256).apply { init(SecretKeySpec(key, HMAC_SHA256)) }.doFinal(byteArrayOf(seed))

    private fun info(suffix: String): ByteArray = "$INFO_PREFIX$suffix".toByteArray(Charsets.UTF_8)

    /**
     * Cabecera en claro que precede al ciphertext, y que va **autenticada como AAD**: tocarla
     * rompe el AEAD. Lleva la pública de la época actual *además* de la propuesta para la
     * siguiente, y esos 32 bytes de más son deliberados: sin ellos, un lado que no hubiera
     * recibido ningún mensaje de la época anterior no podría alcanzar la época del otro.
     */
    internal class Header(
        val lineage: Long,
        val epoch: Int,
        val n: Int,
        val pn: Int,
        val curPub: ByteArray,
        val nextPub: ByteArray,
        val flags: Int = 0,
    ) {
        /** ¿El texto en claro va relleno por tramos? (bit 0 de los flags, ver [Padding]). */
        val padded: Boolean get() = flags and FLAG_PADDED != 0

        fun encode(): ByteArray {
            val out = ByteArray(HEADER_BYTES)
            out[0] = WIRE_VERSION
            out[1] = flags.toByte()
            putLong(out, 2, lineage)
            putInt(out, 10, epoch)
            putInt(out, 14, n)
            putInt(out, 18, pn)
            curPub.copyInto(out, 22)
            nextPub.copyInto(out, 54)
            return out
        }

        companion object {
            fun decode(wire: ByteArray): Header? {
                if (wire.size <= HEADER_BYTES || wire[0] != WIRE_VERSION) return null
                val epoch = getInt(wire, 10)
                val n = getInt(wire, 14)
                val pn = getInt(wire, 18)
                // Negativos = cabecera corrupta o maliciosa; los contadores no dan la vuelta.
                if (epoch < 0 || n < 0 || pn < 0) return null
                return Header(
                    lineage = getLong(wire, 2),
                    epoch = epoch,
                    n = n,
                    pn = pn,
                    curPub = wire.copyOfRange(22, 54),
                    nextPub = wire.copyOfRange(54, 86),
                    flags = wire[1].toInt() and 0xFF,
                )
            }

            private fun putInt(b: ByteArray, at: Int, v: Int) {
                b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte()
                b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
            }

            private fun putLong(b: ByteArray, at: Int, v: Long) {
                putInt(b, at, (v ushr 32).toInt()); putInt(b, at + 4, v.toInt())
            }

            private fun getInt(b: ByteArray, at: Int): Int =
                ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
                    ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

            private fun getLong(b: ByteArray, at: Int): Long =
                (getInt(b, at).toLong() and 0xFFFFFFFFL shl 32) or (getInt(b, at + 4).toLong() and 0xFFFFFFFFL)
        }
    }

    companion object {
        /** Sentido canónico de un envío, que ambos extremos calculan igual (como `MailboxLabel`). */
        fun directionOf(sender: String, recipient: String): Int = if (sender < recipient) 0 else 1

        /**
         * ¿Estos bytes **parecen** un sobre de ratchet? Es solo una pista para decidir en qué
         * orden intentar v2 y v1: un ciphertext v1 son bytes arbitrarios y podría empezar igual.
         * Quien decide de verdad es el AEAD.
         */
        fun looksLikeRatchet(bytes: ByteArray): Boolean =
            bytes.size > HEADER_BYTES && bytes[0] == WIRE_VERSION

        /** Versión del sobre de transporte: 1 era `nonce(12) ‖ ct+tag` sin cabecera. */
        const val WIRE_VERSION: Byte = 0x02

        /** `versión(1) ‖ flags(1) ‖ linaje(8) ‖ época(4) ‖ N(4) ‖ PN(4) ‖ pub(32) ‖ siguiente(32)`. */
        const val HEADER_BYTES = 86

        /**
         * Bit 0 de los flags: el texto en claro va **relleno por tramos** (ver [Padding]).
         *
         * Va en la cabecera y no en el texto cifrado porque hay que saberlo **antes** de
         * interpretar lo que salga del AEAD; y como la cabecera entera es el AAD, el bit está
         * autenticado sin costar un byte más.
         */
        const val FLAG_PADDED = 0x01

        /** Tope de claves derivadas de golpe para cubrir un hueco (anti-abuso, §1.5). */
        const val MAX_SKIP = 1000

        /** Tope de claves saltadas guardadas, en total. */
        const val MAX_SKIPPED_KEYS = 2000

        /** Cadenas de recepción de épocas/linajes ya retirados que se conservan. */
        const val MAX_PAST_CHAINS = 3

        private const val INFO_PREFIX = "krypta-rtc-"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val HMAC_SHA256 = "HmacSHA256"
        private const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val ZERO_BYTE: Byte = 0
        private const val MESSAGE_KEY_SEED: Byte = 0x01
        private const val CHAIN_KEY_SEED: Byte = 0x02
    }
}

package chat.neto.krypta.p2p

import chat.neto.krypta.core.KeyExchange
import chat.neto.krypta.nativebridge.Libp2pNode
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [KeyExchange] respaldado por la identidad del nodo libp2p ([Libp2pNode]).
 *
 * El secreto compartido **ya no se guarda en la base de datos** (se quitó porque el fichero
 * bastaba para descifrar el historial), así que ahora se deriva cada vez que se lee un
 * contacto. Derivar es un salto a Go y un X25519, y la lista de conversaciones se remapea a
 * menudo, de modo que se cachea en memoria: es determinista mientras no cambie la identidad,
 * y un cambio de identidad exige reiniciar el proceso, con lo que la caché muere con él.
 *
 * La caché vive solo en memoria a propósito: es justo el valor que se ha sacado del disco.
 */
@Singleton
class Libp2pKeyExchange @Inject constructor(
    private val node: Libp2pNode,
) : KeyExchange {

    private val secrets = ConcurrentHashMap<String, ByteArray>()

    override fun localPeerId(): String = node.localPeerId()

    override fun sharedSecretWith(peerId: String): ByteArray =
        secrets.getOrPut(peerId) { node.sharedSecretWith(peerId) }
}

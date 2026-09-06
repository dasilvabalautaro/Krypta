package chat.neto.krypta.p2p

import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.core.repository.ContactRepository
import chat.neto.krypta.nativebridge.Libp2pNode
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orquesta el respaldo de identidad: exporta identidad + contactos al formato cifrado
 * [IdentityBackup], y al importar persiste la identidad (efectiva **al reiniciar el
 * proceso**) y re-crea los contactos con su secreto ECDH derivado de la identidad
 * importada — el secreto nunca viaja en el archivo.
 */
@Singleton
class BackupManager @Inject constructor(
    private val node: Libp2pNode,
    private val contacts: ContactRepository,
) {

    class ImportResult(val peerId: String, val contactCount: Int)

    suspend fun export(passphrase: CharArray): ByteArray {
        val all = contacts.observeAll().first()
        return IdentityBackup.encode(
            passphrase,
            IdentityBackup.Data(
                identity = node.exportIdentityBytes(),
                contacts = all.map {
                    IdentityBackup.BackupContact(it.displayName, it.peerId, it.verified, it.blocked)
                },
            ),
        )
    }

    /**
     * Descifra [blob] (lanza [IdentityBackup.InvalidBackup] si la passphrase no es la suya),
     * persiste la identidad y upserta los contactos. OJO: el secreto compartido se deriva
     * de la identidad **importada** (no de la aún en uso), para que ya quede correcto
     * cuando el proceso reinicie con ella.
     */
    suspend fun import(passphrase: CharArray, blob: ByteArray): ImportResult {
        val data = IdentityBackup.decode(passphrase, blob)
        val peerId = node.importIdentityBytes(data.identity)
        for (c in data.contacts) {
            val secret = runCatching { node.sharedSecretFor(data.identity, c.peerId) }.getOrNull()
                ?: continue // PeerID raro en el respaldo: mejor saltarlo que abortar el import
            contacts.upsert(
                Contact(
                    id = c.peerId,
                    displayName = c.displayName,
                    peerId = c.peerId,
                    publicKey = ByteArray(0),
                    sharedSecret = secret,
                    verified = c.verified,
                    blocked = c.blocked,
                ),
            )
        }
        return ImportResult(peerId, data.contacts.size)
    }
}

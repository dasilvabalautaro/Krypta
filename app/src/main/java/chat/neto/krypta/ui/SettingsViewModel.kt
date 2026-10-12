package chat.neto.krypta.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import chat.neto.krypta.p2p.BootstrapResult
import chat.neto.krypta.p2p.ChatService

/**
 * **Ajustes**: identidad, nodos, red local, copia de seguridad y diagnóstico. Se separó de
 * `ChatViewModel` el 12 oct 2026 (§5 de docs/AUDITORIA-2026-10-12.md): aquel servía a todas las
 * pantallas a la vez.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val chat: ChatService,
    private val backup: chat.neto.krypta.p2p.BackupManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _myPeerId = MutableStateFlow("")

    /** PeerID propio, para compartir con quien te quiera añadir. */
    val myPeerId: StateFlow<String> = _myPeerId.asStateFlow()

    private val _bootstrap = MutableStateFlow("")

    /** Multiaddr del nodo bootstrap WAN (vacío = solo LAN/mDNS). */
    val bootstrap: StateFlow<String> = _bootstrap.asStateFlow()

    private val _lanDiscovery = MutableStateFlow(false)

    /** ¿Descubrimiento en la red local (mDNS)? Apagado de serie; ver `security-model.md` §5.1. */
    val lanDiscovery: StateFlow<Boolean> = _lanDiscovery.asStateFlow()

    private val _bootstrapError = MutableStateFlow<String?>(null)

    /** Mensaje de error del campo bootstrap (null = sin error). */
    val bootstrapError: StateFlow<String?> = _bootstrapError.asStateFlow()

    val diagnostics: StateFlow<List<String>> = chat.log

    /** Activa o desactiva el mDNS. Surte efecto en el momento, en los dos sentidos. */
    fun setLanDiscovery(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { chat.setLanDiscovery(enabled) }
            _lanDiscovery.value = chat.lanDiscovery()
        }
    }

    /** Guarda el nodo bootstrap WAN y arranca el descubrimiento por rendezvous. */
    fun setBootstrap(addr: String) {
        viewModelScope.launch {
            when (runCatching { chat.setBootstrap(addr) }.getOrNull()) {
                BootstrapResult.INVALID ->
                    _bootstrapError.value =
                        "Multiaddr inválido (uno por línea). " +
                            "Ej: /ip4/216.128.169.83/tcp/4001/p2p/<PeerID>"
                else -> {
                    _bootstrapError.value = null
                    _bootstrap.value = addr.trim()
                }
            }
        }
    }

    /**
     * Sonda del gate de llamadas (Fase 7a): apunta los códecs de audio del móvil y mide el
     * RTT al nodo; ambos salen por el panel de diagnóstico.
     */
    fun probeCallLatency() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { chat.diagnose("🎙 ${AudioCodecs.summary()}") }
            runCatching { chat.latencyProbe() }
        }
    }

    // --- Respaldo de identidad ---------------------------------------------------------

    private val _backupMessage = MutableStateFlow<String?>(null)

    /** Resultado del último export/import fallido o export OK (para un toast). */
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    fun clearBackupMessage() { _backupMessage.value = null }

    private val _restoredPeerId = MutableStateFlow<String?>(null)

    /** PeerID recién importado, pendiente de reiniciar Krypta (dispara el diálogo). */
    val restoredPeerId: StateFlow<String?> = _restoredPeerId.asStateFlow()

    /** Cifra identidad + contactos con [passphrase] y lo escribe en [uri] (SAF). */
    fun exportBackup(passphrase: String, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val blob = backup.export(passphrase.toCharArray())
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(blob) }
                    ?: error("no se pudo abrir el destino")
            }.onSuccess {
                _backupMessage.value = "Copia de identidad exportada ✓"
            }.onFailure {
                _backupMessage.value = "No se pudo exportar la copia"
            }
        }
    }

    /** Lee [uri], descifra con [passphrase] y restaura identidad + contactos. */
    fun importBackup(passphrase: String, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val blob = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("no se pudo leer el archivo")
                require(blob.size <= 1_000_000) { "demasiado grande para ser un respaldo" }
                backup.import(passphrase.toCharArray(), blob)
            }.onSuccess { result ->
                _restoredPeerId.value = result.peerId
            }.onFailure {
                _backupMessage.value =
                    if (it is chat.neto.krypta.p2p.IdentityBackup.InvalidBackup) {
                        "Passphrase incorrecta o archivo no válido"
                    } else {
                        "No se pudo importar la copia"
                    }
            }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _myPeerId.value = chat.myPeerId()
            _bootstrap.value = chat.bootstrap().orEmpty()
            _lanDiscovery.value = chat.lanDiscovery()
        }
    }
}

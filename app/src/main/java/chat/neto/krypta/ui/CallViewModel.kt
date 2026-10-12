package chat.neto.krypta.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat.neto.krypta.core.model.Contact
import chat.neto.krypta.p2p.CallPhase
import chat.neto.krypta.p2p.CallService
import chat.neto.krypta.p2p.CallState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * La **llamada en curso** (voz y vídeo) para `CallScreen`. Se separó de `ChatViewModel` el 12 oct
 * 2026 (§5 de docs/AUDITORIA-2026-10-12.md): aquel servía a todas las pantallas a la vez.
 */
@HiltViewModel
class CallViewModel @Inject constructor(
    private val calls: CallService,
    private val video: chat.neto.krypta.video.MediaCodecVideoEngine,
) : ViewModel() {

    private val _error = MutableStateFlow<String?>(null)
    /** Error de la llamada para enseñar una vez (p. ej. la cámara no arranca). */
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() { _error.value = null }

    /** Estado de la llamada en curso (IDLE = sin llamada; la UI superpone CallScreen si no). */
    val callState: StateFlow<CallState> = calls.state

    private val _callMuted = MutableStateFlow(false)

    val callMuted: StateFlow<Boolean> = _callMuted.asStateFlow()

    private val _callSpeaker = MutableStateFlow(false)

    val callSpeaker: StateFlow<Boolean> = _callSpeaker.asStateFlow()

    fun startCall(contact: Contact) {
        viewModelScope.launch { runCatching { calls.startCall(contact) } }
    }

    fun acceptCall() {
        viewModelScope.launch { runCatching { calls.accept() } }
    }

    fun rejectCall() {
        viewModelScope.launch { runCatching { calls.reject() } }
    }

    fun hangupCall() {
        viewModelScope.launch { runCatching { calls.hangup() } }
    }

    fun toggleCallMute() {
        _callMuted.value = !_callMuted.value
        calls.setMuted(_callMuted.value)
    }

    fun toggleCallSpeaker() {
        _callSpeaker.value = !_callSpeaker.value
        calls.setSpeakerphone(_callSpeaker.value)
    }

    // --- Vídeo en llamada (Fase 7c) --------------------------------------------------------

    /** El usuario quiere enviar cámara: el UI muestra el PiP y, con su Surface lista, arranca. */
    private val _videoWanted = MutableStateFlow(false)

    val videoWanted: StateFlow<Boolean> = _videoWanted.asStateFlow()

    /** Rotaciones para que el UI gire el render remoto y el PiP propio. */
    val remoteVideoRotation: StateFlow<Int> = video.remoteRotation

    val localVideoRotation: StateFlow<Int> = video.localRotation

    /** Botón 🎥: pide encender (el arranque real espera a la Surface del PiP) o apaga. */
    fun toggleVideo() {
        if (_videoWanted.value) {
            _videoWanted.value = false
            viewModelScope.launch {
                runCatching { calls.stopVideo() }
                runCatching { video.stopCapture() }
            }
        } else {
            _videoWanted.value = true
        }
    }

    /** El PiP propio ya tiene Surface: abre el canal de vídeo y enciende la cámara. */
    fun onPreviewSurfaceReady(surface: android.view.Surface) {
        if (!_videoWanted.value || callState.value.videoSending) return
        viewModelScope.launch {
            val ok = runCatching { calls.startVideo() }.getOrDefault(false)
            if (!ok) {
                _videoWanted.value = false
                return@launch
            }
            runCatching {
                video.startCapture(surface) { frame -> calls.sendVideoFrame(frame) }
            }.onFailure {
                _videoWanted.value = false
                calls.stopVideo()
                _error.value = "No se pudo encender la cámara"
            }
        }
    }

    /** El UI entrega/retira la Surface donde pintar el vídeo remoto. */
    fun setRemoteVideoSurface(surface: android.view.Surface?) {
        video.setRemoteSurface(surface)
    }

    /** 7d: alterna cámara frontal/trasera durante el envío de vídeo. */
    fun switchCamera() {
        // Default: reiniciar cámara+encoder hace un join breve del hilo de drenado.
        viewModelScope.launch(Dispatchers.Default) { runCatching { video.switchCamera() } }
    }

    init {
        // Al terminar cada llamada, vuelve el micro/altavoz a su estado por defecto y apaga
        // cámara/decoder de vídeo.
        viewModelScope.launch {
            calls.state.collect {
                if (it.phase == CallPhase.IDLE || it.phase == CallPhase.ENDED) {
                    _callMuted.value = false
                    _callSpeaker.value = false
                    if (_videoWanted.value || it.phase == CallPhase.ENDED) {
                        _videoWanted.value = false
                        runCatching { video.stopAll() }
                    }
                }
            }
        }
        // Frames de vídeo remotos → decoder (pinta en la Surface que entregó el UI).
        viewModelScope.launch(Dispatchers.Default) {
            calls.remoteVideoFrames.collect { frame ->
                runCatching { video.onRemoteFrame(frame) }
            }
        }
    }
}

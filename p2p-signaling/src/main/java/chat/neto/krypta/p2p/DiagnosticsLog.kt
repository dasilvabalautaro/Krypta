package chat.neto.krypta.p2p

import chat.neto.krypta.p2p.ChatService.Companion.LOG_LINES
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime

/**
 * El registro del panel de **Diagnóstico** (y de logcat). Lo comparten [ChatService] y las piezas
 * en las que se partió (§5 de docs/AUDITORIA-2026-10-12.md), para que todo siga saliendo en el
 * mismo sitio y en orden.
 */
internal class DiagnosticsLog {
    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** Últimas [LOG_LINES] líneas, la más reciente al final. */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun line(msg: String) {
        val line = "${LocalTime.now().withNano(0)}  $msg"
        runCatching { android.util.Log.i("KryptaDiag", msg) } // a logcat; no-op en tests JVM
        _lines.update { (it + line).takeLast(LOG_LINES) }
    }
}

/** Un PeerID abreviado para el diagnóstico: `…` y sus últimos 8 caracteres. */
internal fun short(peerId: String) = if (peerId.length > 10) "…${peerId.takeLast(8)}" else peerId

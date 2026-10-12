package chat.neto.krypta.p2p

import kotlin.coroutines.CoroutineContext

/**
 * Dónde corre el trabajo de CPU del dominio (cifrar, avanzar el ratchet). En la app es
 * `Dispatchers.Default` (lo da `CoroutineModule`): quien llama suele ser la UI, desde el hilo
 * principal (O-8 de docs/AUDITORIA-2026-10-12.md). Los tests usan un contexto vacío, que deja el
 * trabajo en el hilo y el reloj virtual del test.
 *
 * Es un tipo propio y no un `CoroutineContext` a secas para que Hilt no lo confunda con ningún
 * otro contexto que se inyecte.
 */
class CpuContext(val context: CoroutineContext)

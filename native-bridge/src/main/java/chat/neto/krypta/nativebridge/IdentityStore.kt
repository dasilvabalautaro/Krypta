package chat.neto.krypta.nativebridge

import java.util.Base64

/**
 * Envoltura de material sensible con una clave que **no sale del dispositivo**. La
 * implementación real usa el Android Keystore ([KeystoreKeyWrapper]); en tests se sustituye
 * por una falsa.
 */
interface KeyWrapper {
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/** Acceso mínimo a preferencias, para poder probar [IdentityStore] sin Android. */
interface IdentityPrefs {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/**
 * Guarda la **identidad Ed25519** del dispositivo — la raíz de todo: de ella salen el PeerID
 * y, por ECDH, el secreto compartido (y por tanto la clave de cifrado) de cada contacto.
 *
 * Hasta ahora se guardaba en Base64 **en claro** dentro de `SharedPreferences`, junto a la
 * base de mensajes. Quien pudiera leer el directorio de datos de la app (móvil rooteado,
 * extracción forense) se llevaba la identidad, y con ella el historial entero y la capacidad
 * de suplantar al usuario para siempre — el PeerID *es* la identidad y no hay revocación.
 * Ahora se guarda **envuelta** con una clave del Android Keystore, que vive en el TEE y no
 * puede extraerse aunque se copie el fichero (auditoría A-4).
 *
 * Regla de oro de esta clase: **nunca perder la identidad**. Perderla es peor que cualquier
 * ataque que evite, porque todos los contactos se quedan apuntando a un PeerID muerto. Por
 * eso:
 *  - la copia en claro solo se borra tras **verificar** que lo envuelto se recupera igual;
 *  - si el Keystore no está o falla (OEM raro, clave invalidada), se sigue usando el
 *    almacenamiento en claro en vez de dejar al usuario sin identidad;
 *  - descifrar mal nunca genera una identidad nueva: se cae a la copia en claro si existe.
 */
class IdentityStore(
    private val prefs: IdentityPrefs,
    private val wrapper: KeyWrapper?,
    private val generate: () -> ByteArray,
    private val log: (String) -> Unit = {},
) {

    /** Identidad del dispositivo: la existente (migrándola si hace falta) o una nueva. */
    fun load(): ByteArray {
        wrapped()?.let { return it }
        legacy()?.let { plain ->
            migrate(plain)
            return plain
        }
        // Había una identidad envuelta y no se ha podido abrir, y ya no queda copia en claro.
        // Aquí NO se genera otra: rotar el PeerID en silencio es el peor desenlace posible —
        // todos los contactos se quedarían escribiendo a un móvil que ya no existe y nadie se
        // enteraría. Fallar a la vista es recuperable (restaurar el respaldo .krbk); perder la
        // identidad sin avisar, no.
        check(prefs.get(KEY_WRAPPED) == null) {
            "la identidad está guardada pero no se puede abrir con el almacén de claves del " +
                "sistema; restaura tu copia de seguridad (.krbk) en vez de crear una nueva"
        }
        val fresh = generate()
        persist(fresh)
        return fresh
    }

    /** Sustituye la identidad guardada (importación de un respaldo `.krbk`). */
    fun save(identity: ByteArray) = persist(identity)

    private fun wrapped(): ByteArray? {
        val w = wrapper ?: return null
        val stored = prefs.get(KEY_WRAPPED) ?: return null
        return runCatching { w.unwrap(decode(stored)) }
            .onFailure { log("identidad envuelta ilegible (${it.javaClass.simpleName}); se usa la copia en claro si la hay") }
            .getOrNull()
    }

    private fun legacy(): ByteArray? =
        prefs.get(KEY_PLAIN)?.let { runCatching { decode(it) }.getOrNull() }

    /** Envuelve la identidad ya existente y **solo entonces** retira la copia en claro. */
    private fun migrate(plain: ByteArray) {
        val w = wrapper ?: return
        val ok = runCatching {
            val blob = w.wrap(plain)
            // Verificar antes de borrar nada: si el Keystore de este móvil se comporta de
            // forma rara, más vale seguir en claro que quedarse sin identidad.
            w.unwrap(blob).contentEquals(plain).also { if (it) prefs.put(KEY_WRAPPED, encode(blob)) }
        }.getOrDefault(false)
        if (ok) {
            prefs.remove(KEY_PLAIN)
            log("identidad migrada al almacén de claves del sistema")
        } else {
            log("no se pudo envolver la identidad; se mantiene como estaba")
        }
    }

    private fun persist(identity: ByteArray) {
        val w = wrapper
        if (w != null) {
            val ok = runCatching {
                val blob = w.wrap(identity)
                w.unwrap(blob).contentEquals(identity).also { if (it) prefs.put(KEY_WRAPPED, encode(blob)) }
            }.getOrDefault(false)
            if (ok) {
                prefs.remove(KEY_PLAIN)
                return
            }
            log("almacén de claves no disponible: la identidad queda en almacenamiento privado")
        }
        prefs.put(KEY_PLAIN, encode(identity))
        prefs.remove(KEY_WRAPPED)
    }

    // java.util.Base64 (no android.util) para que la clase corra en tests JVM. El alfabeto y
    // el relleno son los mismos que usaba android.util.Base64.NO_WRAP, así que lee sin
    // problema lo que guardaron versiones anteriores.
    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    companion object {
        /** Identidad envuelta con la clave del Keystore. */
        const val KEY_WRAPPED = "ed25519_wrapped"
        /** Formato anterior: identidad en claro (se migra y se borra). */
        const val KEY_PLAIN = "ed25519"
    }
}

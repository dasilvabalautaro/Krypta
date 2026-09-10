package chat.neto.krypta.data.crypto

import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Carga de la biblioteca nativa de SQLCipher, en un solo sitio y **por construcción**.
 *
 * `net.zetetic:sqlcipher-android` no se autocarga: hay que llamar a
 * `System.loadLibrary("sqlcipher")` antes de tocar nada suyo, o el primer acceso muere con
 * `UnsatisfiedLinkError: No implementation found for … nativeOpen`.
 *
 * Esto existe porque esa llamada estaba **dentro de `DatabaseEncryption.encryptInPlace`, después
 * de su salida temprana**: en el arranque en el que se convertía la base en claro se cargaba, y
 * en todos los siguientes —cuando ya estaba cifrada— no. Resultado: la app arrancaba una vez y a
 * partir de ahí **se caía nada más abrir la base**, es decir, no arrancaba nunca más (visto en el
 * TECNO el 9 sep 2026). Por eso la fábrica solo se puede obtener por [openHelperFactory], que
 * carga antes de construirla: así no hay ninguna forma de pedir la base sin haber cargado.
 */
object SqlCipher {

    @Volatile
    private var loaded = false

    /** Idempotente. `System.loadLibrary` ya lo es; la bandera evita el sincronizado repetido. */
    @Synchronized
    fun load() {
        if (loaded) return
        System.loadLibrary("sqlcipher")
        loaded = true
    }

    /** La fábrica que Room necesita para abrir la base cifrada, con la nativa ya cargada. */
    fun openHelperFactory(passphrase: ByteArray): SupportOpenHelperFactory {
        load()
        return SupportOpenHelperFactory(passphrase)
    }
}

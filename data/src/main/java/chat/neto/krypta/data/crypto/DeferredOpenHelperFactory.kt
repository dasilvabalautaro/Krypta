package chat.neto.krypta.data.crypto

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Fábrica que **aplaza** lo caro de abrir la base hasta el primer acceso real.
 *
 * Abrir `krypta.db` exige desenvolver la frase-clave con el Android Keystore y, la primera vez,
 * convertir la base en claro de versiones anteriores ([DatabaseEncryption.encryptInPlace]).
 * Antes eso ocurría al **construir** Room, y Room se construye en cuanto Hilt inyecta cualquier
 * repositorio: desde `KryptaApplication.onCreate` (vía `IncomingNotifier` → `ChatService`), es
 * decir, **en el hilo principal y en cada arranque del proceso**, también cuando lo arranca un
 * `BroadcastReceiver` (O-8 de docs/AUDITORIA-2026-10-12.md). La conversión de una base grande en
 * el hilo principal es un ANR seguro.
 *
 * Room no abre la base al construirse, solo al ejecutar la primera consulta, y no permite
 * consultas en el hilo principal. Así que basta con que [prepare] se ejecute en ese momento: la
 * frase-clave, la conversión y la carga de SQLCipher pasan al hilo de consultas de Room.
 */
class DeferredOpenHelperFactory(
    private val prepare: () -> SupportSQLiteOpenHelper.Factory,
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        DeferredOpenHelper(configuration, prepare)
}

/** Delega en el helper real, que se crea la primera vez que alguien pide la base. */
private class DeferredOpenHelper(
    private val configuration: SupportSQLiteOpenHelper.Configuration,
    private val prepare: () -> SupportSQLiteOpenHelper.Factory,
) : SupportSQLiteOpenHelper {

    private val lock = Any()
    private var delegate: SupportSQLiteOpenHelper? = null

    /** Lo que Room pida antes de que exista el helper real, para aplicárselo al crearlo. */
    private var writeAheadLogging: Boolean? = null

    private fun real(): SupportSQLiteOpenHelper = synchronized(lock) {
        delegate ?: prepare().create(configuration).also { helper ->
            writeAheadLogging?.let(helper::setWriteAheadLoggingEnabled)
            delegate = helper
        }
    }

    override val databaseName: String?
        get() = configuration.name

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) = synchronized(lock) {
        val helper = delegate
        if (helper != null) helper.setWriteAheadLoggingEnabled(enabled) else writeAheadLogging = enabled
    }

    override val writableDatabase: SupportSQLiteDatabase
        get() = real().writableDatabase

    override val readableDatabase: SupportSQLiteDatabase
        get() = real().readableDatabase

    /** Cerrar lo que nunca se abrió no debe obligar a desenvolver la clave. */
    override fun close() {
        synchronized(lock) { delegate }?.close()
    }
}

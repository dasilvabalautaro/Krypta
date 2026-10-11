package chat.neto.krypta.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import chat.neto.krypta.core.RatchetStore
import chat.neto.krypta.core.TransactionRunner
import chat.neto.krypta.core.repository.ContactRepository
import chat.neto.krypta.core.repository.MessageRepository
import chat.neto.krypta.data.KryptaDatabase
import chat.neto.krypta.data.MIGRATION_2_3
import chat.neto.krypta.data.MIGRATION_3_4
import chat.neto.krypta.data.MIGRATION_4_5
import chat.neto.krypta.data.MIGRATION_5_6
import chat.neto.krypta.data.MIGRATION_6_7
import chat.neto.krypta.data.MIGRATION_7_8
import chat.neto.krypta.data.MIGRATION_8_9
import chat.neto.krypta.data.MIGRATION_9_10
import chat.neto.krypta.data.crypto.DatabaseEncryption
import chat.neto.krypta.data.crypto.DatabaseKey
import chat.neto.krypta.data.crypto.KeyPrefs
import chat.neto.krypta.data.crypto.KeystoreVault
import chat.neto.krypta.data.crypto.SqlCipher
import chat.neto.krypta.data.dao.ContactDao
import chat.neto.krypta.data.dao.MessageDao
import chat.neto.krypta.data.dao.RatchetDao
import chat.neto.krypta.data.repository.RoomContactRepository
import chat.neto.krypta.data.repository.RoomMessageRepository
import chat.neto.krypta.data.repository.RoomRatchetStore
import chat.neto.krypta.data.repository.RoomTransactionRunner
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KryptaDatabase {
        // Base **cifrada** (SQLCipher) con una frase-clave que vive envuelta en el Android
        // Keystore. Lo que protege son los metadatos locales -nombres, PeerID, marcas de
        // tiempo, quien habla con quien-; el contenido ya dependia de la identidad desde que
        // el secreto compartido salio de la base.
        val key = DatabaseKey(
            prefs = SharedKeyPrefs(context.getSharedPreferences("krypta_db", Context.MODE_PRIVATE)),
            vault = KeystoreVault(),
        )
        val passphrase = key.passphrase()
        // Conversion de la base en claro que dejaron las versiones anteriores. Va aqui, antes
        // de que Room la abra, y es idempotente: si ya esta cifrada no hace nada.
        DatabaseEncryption.encryptInPlace(context.getDatabasePath("krypta.db"), passphrase)

        return Room.databaseBuilder(context, KryptaDatabase::class.java, "krypta.db")
            .openHelperFactory(SqlCipher.openHelperFactory(passphrase))
            // Migraciones reales: preservan contactos + mensajes al subir de versión.
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
            // Red de seguridad solo para la v1 antigua (sin migración definida); v2+ migra.
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
            .addCallback(SecureDeleteCallback)
            .build()
    }

    /**
     * `secure_delete` en **cada** conexión (11 oct 2026). Es un pragma por conexión, y hasta ahora
     * solo se activaba dentro de `MIGRATION_5_6`: el estado del ratchet, que se reescribe con cada
     * mensaje, dejaba sus versiones viejas en páginas libres de la base (cifradas por SQLCipher, pero
     * con la clave en el mismo móvil). Lo encontró responder a la auditoría interna del 11 oct 2026.
     * Coste medido en el TECNO: 300 transacciones de 8 KB, 195 ms sin él y 236 ms con él.
     *
     * Límite: sobrescribe lo borrado en el fichero de la base. Las tramas viejas del WAL siguen ahí
     * hasta que el WAL se reutiliza, así que es «no queda en páginas libres», no borrado garantizado.
     * El pragma devuelve una fila, así que va por `query` (con `execSQL` falla, lección de la v6).
     */
    private object SecureDeleteCallback : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            runCatching { db.query("PRAGMA secure_delete = ON").use { it.moveToFirst() } }
        }
    }

    /** Adaptador de `SharedPreferences` al puerto que usa [DatabaseKey]. */
    private class SharedKeyPrefs(
        private val prefs: android.content.SharedPreferences,
    ) : KeyPrefs {
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
    }

    @Provides
    fun provideMessageDao(database: KryptaDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideContactDao(database: KryptaDatabase): ContactDao = database.contactDao()

    @Provides
    fun provideRatchetDao(database: KryptaDatabase): RatchetDao = database.ratchetDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindMessageRepository(impl: RoomMessageRepository): MessageRepository

    @Binds
    abstract fun bindContactRepository(impl: RoomContactRepository): ContactRepository

    @Binds
    abstract fun bindRatchetStore(impl: RoomRatchetStore): RatchetStore

    @Binds
    abstract fun bindTransactionRunner(impl: RoomTransactionRunner): TransactionRunner
}

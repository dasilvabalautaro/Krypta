package chat.neto.krypta.data.di

import android.content.Context
import androidx.room.Room
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
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            // Red de seguridad solo para la v1 antigua (sin migración definida); v2+ migra.
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
            .build()
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

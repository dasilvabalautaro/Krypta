package chat.neto.krypta.data.di

import android.content.Context
import androidx.room.Room
import chat.neto.krypta.core.repository.ContactRepository
import chat.neto.krypta.core.repository.MessageRepository
import chat.neto.krypta.data.KryptaDatabase
import chat.neto.krypta.data.MIGRATION_2_3
import chat.neto.krypta.data.MIGRATION_3_4
import chat.neto.krypta.data.MIGRATION_4_5
import chat.neto.krypta.data.dao.ContactDao
import chat.neto.krypta.data.dao.MessageDao
import chat.neto.krypta.data.repository.RoomContactRepository
import chat.neto.krypta.data.repository.RoomMessageRepository
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
    fun provideDatabase(@ApplicationContext context: Context): KryptaDatabase =
        Room.databaseBuilder(context, KryptaDatabase::class.java, "krypta.db")
            // Migraciones reales: preservan contactos + mensajes al subir de versión.
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            // Red de seguridad solo para la v1 antigua (sin migración definida); v2+ migra.
            .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
            .build()

    @Provides
    fun provideMessageDao(database: KryptaDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideContactDao(database: KryptaDatabase): ContactDao = database.contactDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindMessageRepository(impl: RoomMessageRepository): MessageRepository

    @Binds
    abstract fun bindContactRepository(impl: RoomContactRepository): ContactRepository
}

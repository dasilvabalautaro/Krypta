package chat.neto.krypta.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import chat.neto.krypta.data.dao.ContactDao
import chat.neto.krypta.data.dao.MessageDao
import chat.neto.krypta.data.dao.RatchetDao
import chat.neto.krypta.data.entity.ContactEntity
import chat.neto.krypta.data.entity.MessageEntity
import chat.neto.krypta.data.entity.RatchetSeenEntity
import chat.neto.krypta.data.entity.RatchetSessionEntity

@Database(
    entities = [
        MessageEntity::class,
        ContactEntity::class,
        RatchetSessionEntity::class,
        RatchetSeenEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class KryptaDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun ratchetDao(): RatchetDao
}

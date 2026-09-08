package chat.neto.krypta.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import chat.neto.krypta.data.dao.ContactDao
import chat.neto.krypta.data.dao.MessageDao
import chat.neto.krypta.data.entity.ContactEntity
import chat.neto.krypta.data.entity.MessageEntity

@Database(
    entities = [MessageEntity::class, ContactEntity::class],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class KryptaDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
}

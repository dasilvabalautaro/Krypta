package chat.neto.krypta.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migraciones Room explícitas: preservan los datos (contactos + mensajes) al subir de versión,
 * en vez de recrear la BD (lo que hacía `fallbackToDestructiveMigration` en dev y borraba todo).
 * A partir de aquí, **cada cambio de esquema añade una `Migration` nueva + sube la versión**.
 */

/** v2→v3: verificación de identidad anti-MITM (número de seguridad / QR). */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contacts ADD COLUMN verified INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v3→v4: índice compuesto `(conversationId, timestamp)` en `messages`. La consulta de la
 * conversación filtra por `conversationId` y ordena por `timestamp`; con el compuesto SQLite
 * resuelve WHERE + ORDER BY desde el índice (el antiguo era solo sobre `conversationId`).
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS index_messages_conversationId")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_messages_conversationId_timestamp " +
                "ON messages (conversationId, timestamp)",
        )
    }
}

/**
 * v4→v5: bloqueo de contacto. `blocked = 1` = no se anuncia su rendezvous, lo que llegue de
 * él se descarta sin persistir ni avisar, y la UI impide escribirle o llamarle. Es local: el
 * bloqueado no recibe ninguna señal de que lo está (sus envíos le quedan como enviados, igual
 * que si estuvieras desconectado).
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contacts ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v5→v6: **quita `contacts.sharedSecret`**. Se guardaba en claro y, como la clave de cada
 * mensaje sale de él por HKDF, quien se llevara el fichero `krypta.db` descifraba todas las
 * conversaciones sin necesitar la identidad ni ejecutar nada dentro de la app. No hay nada
 * que migrar del valor: es una función pura de la identidad y del PeerID del contacto, así
 * que a partir de ahora se deriva al leer (`RoomContactRepository`).
 *
 * SQLite no sabía borrar columnas hasta 3.35, y para no depender de la versión del
 * dispositivo se hace el baile clásico: tabla nueva, copia, borrado y renombrado, recreando
 * después el índice de `peerId` (que se va con la tabla vieja).
 *
 * `secure_delete` va delante a propósito: sin él, SQLite se limita a marcar como libres las
 * páginas de la tabla vieja y **los secretos seguirían legibles dentro del fichero**, que es
 * justo lo que esta migración viene a evitar. Con él, esas páginas se sobrescriben con ceros.
 *
 * Dos detalles que costaron un arranque fallido en el TECNO: ese PRAGMA **devuelve una fila**
 * con su nuevo valor, y `execSQL` de Android rechaza cualquier sentencia que devuelva algo
 * ("Queries can be performed using query or rawQuery methods only"), así que hay que
 * ejecutarlo como consulta y consumir el cursor. Y va en `runCatching` porque es un extra de
 * higiene: si un dispositivo se negara, lo que no puede fallar es quitar la columna.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        runCatching { db.query("PRAGMA secure_delete = ON").use { it.moveToFirst() } }
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `contacts_new` (" +
                "`id` TEXT NOT NULL, `displayName` TEXT NOT NULL, `peerId` TEXT NOT NULL, " +
                "`publicKey` BLOB NOT NULL, `verified` INTEGER NOT NULL, " +
                "`blocked` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "INSERT INTO `contacts_new` (id, displayName, peerId, publicKey, verified, blocked) " +
                "SELECT id, displayName, peerId, publicKey, verified, blocked FROM `contacts`",
        )
        db.execSQL("DROP TABLE `contacts`")
        db.execSQL("ALTER TABLE `contacts_new` RENAME TO `contacts`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_contacts_peerId` ON `contacts` (`peerId`)")
    }
}

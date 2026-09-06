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

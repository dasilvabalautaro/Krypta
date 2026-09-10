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

/**
 * v6→v7: **estado del ratchet** (secreto hacia adelante). Dos tablas nuevas, ninguna columna
 * tocada: `ratchet_sessions` guarda el estado de cada conversación como blob opaco, y
 * `ratchet_seen` las huellas de los ciphertext ya procesados.
 *
 * La segunda no es un detalle de rendimiento: con ratchet la clave de un mensaje **se borra al
 * usarla**, así que una reentrega del buzón —que es lo normal cuando un acuse se pierde— ya no
 * se puede descifrar. Sin reconocerla antes de intentarlo parecería un mensaje corrupto.
 *
 * Aditiva y sin riesgo: una base v6 sube sin tocar contactos ni mensajes, y una sesión que no
 * exista se crea sola en la época 0, que es derivable del secreto compartido.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ratchet_sessions` (" +
                "`conversationId` TEXT NOT NULL, `state` BLOB NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`conversationId`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ratchet_seen` (" +
                "`conversationId` TEXT NOT NULL, `digest` TEXT NOT NULL, " +
                "`seenAt` INTEGER NOT NULL, PRIMARY KEY(`conversationId`, `digest`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ratchet_seen_conversationId_seenAt` " +
                "ON `ratchet_seen` (`conversationId`, `seenAt`)",
        )
    }
}

/**
 * v7→v8: **el historial deja de guardarse cifrado con la clave estática**. `messages.ciphertext`
 * pasa a llamarse `payload` y aparece `encrypted`, que marca las filas que todavía son de antes.
 *
 * Es el cambio que exige el secreto hacia adelante (`docs/DISENO-ratchet.md` §4): con ratchet la
 * clave de cada mensaje se borra al usarla, así que guardar el ciphertext de la red significaría
 * guardar algo que dentro de un minuto ya no se puede abrir —tampoco los mensajes propios—. Lo
 * que protege el historial pasa a ser el cifrado de la base entera, que existe desde el 9 sep.
 *
 * **No se descifra nada aquí**: dentro de una `Migration` no hay identidad ni claves. Las filas
 * viejas se quedan con `encrypted = 1` y se convierten después, en segundo plano y de una en
 * una, con el historial siempre legible mientras tanto (`ChatService.unsealHistory`). Renombrar
 * en vez de copiar la tabla es a propósito: mueve el mismo dato sin reescribir el fichero, así
 * que no hay ventana en la que el historial —que no tiene copia de seguridad de ninguna clase—
 * exista solo a medias.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `messages` RENAME COLUMN `ciphertext` TO `payload`")
        db.execSQL("ALTER TABLE `messages` ADD COLUMN `encrypted` INTEGER NOT NULL DEFAULT 1")
    }
}

/**
 * v8→v9: **anuncio de capacidad por contacto**. `peerProtocol` es la versión de protocolo que el
 * contacto ha anunciado y `announcedProtocol` la que ya le anunciamos nosotros.
 *
 * Es lo que permite encender el ratchet **contacto a contacto** en vez de esperar a que todo el
 * mundo actualice y cambiar una constante en una publicación posterior (la lección del depósito
 * ciego, `docs/DISENO-ratchet.md` §5). Ambas arrancan en 0 = «no se sabe / nunca», que es
 * exactamente lo que corresponde a un contacto de antes.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `contacts` ADD COLUMN `peerProtocol` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `contacts` ADD COLUMN `announcedProtocol` INTEGER NOT NULL DEFAULT 0")
    }
}

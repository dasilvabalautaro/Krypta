package chat.neto.krypta.data.crypto

import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Convierte la base de datos en claro que dejaron las versiones anteriores en una base
 * **cifrada** con SQLCipher, una sola vez y sin perder nada.
 *
 * El contenido de los mensajes ya no era legible con el fichero solo —el secreto compartido
 * salió de la base el 9 sep 2026— pero seguían a la vista los metadatos locales: nombres,
 * PeerID, marcas de tiempo, quién habla con quién y cuánto. Esto los tapa.
 *
 * **Por qué copia a mano en vez de usar `sqlcipher_export`.** La receta habitual —abrir la base
 * en claro con SQLCipher usando clave vacía, adjuntarle la cifrada y volcar— se quedó **colgada
 * indefinidamente** en el dispositivo de pruebas (TECNO, Android 15): el proceso seguía vivo,
 * sin error y sin avanzar, con y sin diario WAL. En vez de seguir adivinando por qué, la
 * conversión usa dos caminos que sí son de fiar: se **lee** con el SQLite del sistema y se
 * **escribe** con SQLCipher. Es más código, pero es código que se sigue de arriba abajo.
 */
object DatabaseEncryption {

    private const val TAG = "KryptaDbCipher"

    /**
     * Cabecera de un fichero SQLite sin cifrar. En una base cifrada los primeros bytes son
     * indistinguibles del azar, así que basta mirar aquí para saber qué hay delante.
     */
    private val PLAINTEXT_HEADER = "SQLite format 3".toByteArray(Charsets.US_ASCII)

    /** ¿Existe y está **sin cifrar**? (Si no existe, o ya está cifrada, no hay nada que hacer.) */
    fun needsEncrypting(db: File): Boolean {
        if (!db.isFile || db.length() < PLAINTEXT_HEADER.size) return false
        val head = ByteArray(PLAINTEXT_HEADER.size)
        db.inputStream().use { if (it.read(head) != head.size) return false }
        return head.contentEquals(PLAINTEXT_HEADER)
    }

    /**
     * Cifra [db] en el sitio con [passphrase]. No hace nada si ya estaba cifrada.
     *
     * Trabaja sobre un fichero aparte y solo sustituye el original **después de comprobar** que
     * la copia tiene las mismas tablas y las mismas filas. Un corte a mitad, o una copia
     * incompleta, dejan la base vieja intacta y el intento se repite en el siguiente arranque:
     * el historial de mensajes no tiene copia de seguridad de ninguna clase, así que aquí no
     * vale el "casi seguro que ha ido bien".
     */
    fun encryptInPlace(db: File, passphrase: ByteArray) {
        if (!needsEncrypting(db)) return
        SqlCipher.load()

        val encrypted = File(db.parentFile, db.name + ".cipher.tmp")
        listOf(encrypted, File(encrypted.path + "-journal"), File(encrypted.path + "-wal")).forEach { it.delete() }

        val origen = android.database.sqlite.SQLiteDatabase.openDatabase(
            db.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        )
        val esperado: Map<String, Int>
        try {
            val destino = SQLiteDatabase.openOrCreateDatabase(encrypted, passphrase, null, null)
            try {
                copiarEsquema(origen, destino)
                esperado = copiarFilas(origen, destino)
                destino.version = origen.version
            } finally {
                destino.close()
            }
        } finally {
            origen.close()
        }

        verificar(encrypted, passphrase, esperado)

        check(encrypted.renameTo(db) || (db.delete() && encrypted.renameTo(db))) {
            "no se pudo sustituir la base por su versión cifrada"
        }
        // Los -wal/-shm de la base vieja no valen para la nueva: hay que quitarlos, o SQLite
        // intentaria aplicar un diario que no le corresponde.
        listOf("-wal", "-shm", "-journal").forEach { File(db.path + it).delete() }
        Log.i(TAG, "base de datos cifrada (" + esperado.size + " tablas, " + esperado.values.sum() + " filas)")
    }

    /** Copia tablas e índices tal como los definió Room, sin interpretarlos. */
    private fun copiarEsquema(origen: android.database.sqlite.SQLiteDatabase, destino: SQLiteDatabase) {
        origen.rawQuery(
            "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'",
            null,
        ).use { c ->
            while (c.moveToNext()) destino.execSQL(c.getString(0))
        }
    }

    /** Copia las filas de cada tabla. Devuelve cuántas por tabla, para poder verificarlo luego. */
    private fun copiarFilas(
        origen: android.database.sqlite.SQLiteDatabase,
        destino: SQLiteDatabase,
    ): Map<String, Int> {
        val tablas = mutableListOf<String>()
        origen.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
            null,
        ).use { c -> while (c.moveToNext()) tablas.add(c.getString(0)) }

        val cuenta = mutableMapOf<String, Int>()
        for (tabla in tablas) {
            var filas = 0
            origen.rawQuery("SELECT * FROM `" + tabla + "`", null).use { c ->
                val columnas = c.columnNames
                val huecos = columnas.joinToString(",") { "?" }
                val nombres = columnas.joinToString(",") { "`" + it + "`" }
                val sql = "INSERT INTO `" + tabla + "` (" + nombres + ") VALUES (" + huecos + ")"
                destino.beginTransaction()
                try {
                    while (c.moveToNext()) {
                        val valores = arrayOfNulls<Any>(columnas.size)
                        for (i in columnas.indices) {
                            valores[i] = when (c.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> null
                                android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                                android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                                else -> c.getString(i)
                            }
                        }
                        destino.execSQL(sql, valores)
                        filas++
                    }
                    destino.setTransactionSuccessful()
                } finally {
                    destino.endTransaction()
                }
            }
            cuenta[tabla] = filas
        }
        return cuenta
    }

    /** Reabre la copia cifrada y comprueba que está todo antes de dejar que sustituya nada. */
    private fun verificar(encrypted: File, passphrase: ByteArray, esperado: Map<String, Int>) {
        val comprobacion = SQLiteDatabase.openOrCreateDatabase(encrypted, passphrase, null, null)
        val real = mutableMapOf<String, Int>()
        try {
            for (tabla in esperado.keys) {
                comprobacion.rawQuery("SELECT count(*) FROM `" + tabla + "`", null).use { c ->
                    real[tabla] = if (c.moveToFirst()) c.getInt(0) else -1
                }
            }
        } finally {
            comprobacion.close()
        }
        if (real != esperado) {
            encrypted.delete()
            error("la copia cifrada no coincide con el original (" + real + " vs " + esperado + "): no se sustituye")
        }
    }
}

package com.meshchats.app.data.local

import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.IOException

/**
 * The real [EncryptedExporter], backed by the SQLCipher engine. Converts a
 * plaintext SQLite database into a SQLCipher-encrypted one using the officially
 * documented `sqlcipher_export()` convenience function.
 *
 * ### Approach (documented SQLCipher encryption recipe)
 * The plaintext source is opened with an **empty** key (SQLCipher skips keying
 * when the key byte array is empty, so a plaintext database opens normally). An
 * empty, freshly created destination is `ATTACH`ed with the real raw key, then
 * `SELECT sqlcipher_export('encrypted')` streams every table and row through the
 * engine into the encrypted attachment. `user_version` is copied so Room sees the
 * same schema version it wrote.
 *
 * ### Key never appears in SQL text
 * The raw key is supplied to `ATTACH ... KEY ?` as a **bound statement
 * parameter**, never string-concatenated into SQL, so it is never interpolated,
 * never logged, and not vulnerable to SQL injection. It is passed in the
 * SQLCipher raw-key form (`x'<hex>'`) produced by [SqlCipherRawKey], which makes
 * SQLCipher use the literal 32-byte key instead of deriving one via PBKDF2 —
 * matching exactly how the Room open path keys the same database.
 *
 * ### Never mutates the source
 * Only `ATTACH`/`DETACH`/`SELECT`/`PRAGMA` run against the source connection; no
 * statement writes to it. On any failure the caller deletes the partial [dest]
 * and the plaintext source is left byte-for-byte intact.
 */
class SqlCipherExporter : EncryptedExporter {

    init {
        SqlCipherNative.ensureLoaded()
    }

    override fun export(source: File, dest: File, rawKeyAscii: ByteArray): DatabaseContentReport {
        if (dest.exists()) {
            throw IOException("encrypted destination already exists: ${dest.name}")
        }
        // Bound parameter value for the KEY expression. Constructing a String here
        // is unavoidable for the ATTACH parameter, but it is passed only as a bound
        // arg — never concatenated into SQL — and this method's frame is short-lived.
        val keyParam = String(rawKeyAscii, Charsets.US_ASCII)

        // Open the plaintext source with an empty key (no decryption applied).
        val src = openPlaintext(source)
        try {
            val userVersion = src.version
            val tableCounts = readTableCounts(src)

            // ATTACH the encrypted destination with the raw key as a BOUND parameter.
            src.rawExecSQL("ATTACH DATABASE ? AS encrypted KEY ?", dest.absolutePath, keyParam)
            try {
                // Stream schema + data into the encrypted attachment.
                consume(src.rawQuery("SELECT sqlcipher_export('encrypted')", arrayOf<String>()))
                // Preserve the schema version Room relies on. PRAGMA cannot bind an
                // integer parameter, but userVersion is a locally-read Int (not
                // attacker-influenced text), so formatting it is safe from injection.
                src.rawExecSQL("PRAGMA encrypted.user_version = $userVersion")
            } finally {
                src.rawExecSQL("DETACH DATABASE encrypted")
            }

            return DatabaseContentReport(rowCounts = tableCounts, integrityOk = true)
        } catch (e: RuntimeException) {
            // SQLCipher throws android.database.SQLException (a RuntimeException).
            throw IOException("sqlcipher_export failed", e)
        } finally {
            src.close()
        }
    }

    override fun readEncrypted(file: File, rawKeyAscii: ByteArray): DatabaseContentReport? {
        if (!file.isFile) return null
        SqlCipherNative.ensureLoaded()
        val db = try {
            SQLiteDatabase.openOrCreateDatabase(file, rawKeyAscii, null, null)
        } catch (_: RuntimeException) {
            return null
        }
        return try {
            // A wrong key or non-encrypted file fails the first real read.
            val integrityOk = runIntegrityCheck(db) ?: return null
            val counts = readTableCounts(db)
            DatabaseContentReport(rowCounts = counts, integrityOk = integrityOk)
        } catch (_: RuntimeException) {
            null
        } finally {
            db.close()
        }
    }

    private fun openPlaintext(source: File): SQLiteDatabase {
        SqlCipherNative.ensureLoaded()
        return try {
            // Empty key → SQLCipher applies no cipher key, opening the plaintext file.
            SQLiteDatabase.openOrCreateDatabase(source, ByteArray(0), null, null)
        } catch (e: RuntimeException) {
            throw IOException("failed to open plaintext source", e)
        }
    }

    /** Returns true/false for `PRAGMA integrity_check`, or null if it will not run. */
    private fun runIntegrityCheck(db: SQLiteDatabase): Boolean? {
        db.rawQuery("PRAGMA integrity_check", arrayOf<String>()).use { c ->
            if (!c.moveToFirst()) return null
            return c.getString(0).equals("ok", ignoreCase = true)
        }
    }

    /**
     * Reads a `table name -> row count` snapshot for every user table (excluding
     * SQLite/Android internal tables), used to prove the export copied every row.
     */
    private fun readTableCounts(db: SQLiteDatabase): Map<String, Long> {
        val tables = mutableListOf<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_metadata'",
            arrayOf<String>(),
        ).use { c ->
            while (c.moveToNext()) tables.add(c.getString(0))
        }
        val counts = linkedMapOf<String, Long>()
        for (t in tables.sorted()) {
            // Table names come from sqlite_master (the DB's own catalog), not user
            // input; quoting them defensively still guards against odd identifiers.
            db.rawQuery("SELECT COUNT(*) FROM \"${t.replace("\"", "\"\"")}\"", arrayOf<String>()).use { c ->
                counts[t] = if (c.moveToFirst()) c.getLong(0) else 0L
            }
        }
        return counts
    }

    private fun consume(cursor: Cursor) {
        cursor.use { while (it.moveToNext()) { /* drain sqlcipher_export result */ } }
    }
}

package com.meshchats.app.data.local

import java.io.File
import java.io.IOException

/**
 * Structural inspection of an on-disk database file, used to decide whether an
 * existing `mesh-chats.db` predates encryption and must be migrated.
 *
 * The decision is made purely from the file's leading bytes, never by trying to
 * open it: a plaintext SQLite database begins with the fixed 16-byte magic
 * `"SQLite format 3\u0000"`. A SQLCipher-encrypted database begins with a random
 * salt, so its first 16 bytes will not match. This gives three unambiguous
 * outcomes — plaintext (migrate), encrypted-or-not-sqlite (leave alone), and
 * absent (nothing to migrate) — with no false "encrypted" reading of a genuine
 * plaintext database.
 */
object SqliteDatabaseFile {

    /**
     * The exact 16-byte header every plaintext SQLite 3 database starts with:
     * the ASCII text `SQLite format 3` followed by a single NUL terminator.
     */
    val PLAINTEXT_HEADER: ByteArray =
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * True only if [file] exists and its first 16 bytes are exactly the plaintext
     * SQLite header. An encrypted database (random salt prefix), a truncated file
     * shorter than the header, or a missing file all return false — encryption
     * must never be triggered against anything but a genuine plaintext database.
     */
    fun isPlaintextSqlite(file: File): Boolean {
        if (!file.isFile) return false
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(PLAINTEXT_HEADER.size)
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n < 0) return false // shorter than a full header → not plaintext
                    read += n
                }
                header.contentEquals(PLAINTEXT_HEADER)
            }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

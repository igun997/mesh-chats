package com.meshchats.app.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SqliteDatabaseFileTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray): File =
        File(temp.root, name).apply { writeBytes(bytes) }

    @Test
    fun `exact plaintext header is detected as plaintext`() {
        val f = file("db", SqliteDatabaseFile.PLAINTEXT_HEADER + ByteArray(100))
        assertTrue(SqliteDatabaseFile.isPlaintextSqlite(f))
    }

    @Test
    fun `header must match all sixteen bytes exactly`() {
        val corrupted = SqliteDatabaseFile.PLAINTEXT_HEADER.copyOf()
        corrupted[15] = 0x01 // NUL terminator flipped
        val f = file("db", corrupted + ByteArray(100))
        assertFalse(SqliteDatabaseFile.isPlaintextSqlite(f))
    }

    @Test
    fun `encrypted database with random salt prefix is not plaintext`() {
        // A SQLCipher database begins with a random 16-byte salt; overwhelmingly
        // this will not equal the fixed plaintext magic.
        val salt = ByteArray(16) { (it * 7 + 3).toByte() }
        val f = file("db", salt + ByteArray(100))
        assertFalse(SqliteDatabaseFile.isPlaintextSqlite(f))
    }

    @Test
    fun `file shorter than header is not plaintext`() {
        val f = file("db", SqliteDatabaseFile.PLAINTEXT_HEADER.copyOf(10))
        assertFalse(SqliteDatabaseFile.isPlaintextSqlite(f))
    }

    @Test
    fun `absent file is not plaintext`() {
        assertFalse(SqliteDatabaseFile.isPlaintextSqlite(File(temp.root, "missing")))
    }

    @Test
    fun `plaintext header is the documented sixteen-byte SQLite magic`() {
        assertTrue(SqliteDatabaseFile.PLAINTEXT_HEADER.size == 16)
        assertTrue(
            SqliteDatabaseFile.PLAINTEXT_HEADER.contentEquals(
                byteArrayOf(
                    0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66,
                    0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00,
                ),
            ),
        )
    }
}

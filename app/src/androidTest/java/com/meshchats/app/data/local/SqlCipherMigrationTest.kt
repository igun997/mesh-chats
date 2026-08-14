package com.meshchats.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Exercises the real SQLCipher exporter and the crash-safe migration on-device,
 * against isolated temp database files (never the production `mesh-chats.db` and
 * never any real Keystore alias). Each test uses a unique working directory that
 * is deleted afterward.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherMigrationTest {

    private lateinit var workDir: File
    private lateinit var dbFile: File

    // A fixed, test-only 32-byte key. This never touches the Keystore or the real
    // database-key file; it is pure test material.
    private val key = ByteArray(32) { (it * 3 + 1).toByte() }
    private val keyAscii = SqlCipherRawKey.encode(key)
    private val wrongKeyAscii = SqlCipherRawKey.encode(ByteArray(32) { (it + 99).toByte() })

    @Before
    fun setUp() {
        SqlCipherNative.ensureLoaded()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        workDir = File(ctx.cacheDir, "sqlc-mig-${UUID.randomUUID()}").apply { mkdirs() }
        dbFile = File(workDir, "mesh-chats.db")
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    /** Creates a plaintext SQLite database with a `messages` table and [rows] rows. */
    private fun createPlaintextDb(rows: Int) {
        // Empty key → plaintext database, so it begins with the SQLite magic header.
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, ByteArray(0), null, null)
        try {
            db.execSQL(
                "CREATE TABLE messages (id TEXT PRIMARY KEY, conversation_id TEXT, body TEXT, sent_at INTEGER)",
            )
            db.version = 1
            for (i in 0 until rows) {
                db.execSQL(
                    "INSERT INTO messages (id, conversation_id, body, sent_at) VALUES (?, ?, ?, ?)",
                    arrayOf<Any>("m$i", "c1", "hello $i", i.toLong()),
                )
            }
        } finally {
            db.close()
        }
        assertTrue("fixture must be plaintext", SqliteDatabaseFile.isPlaintextSqlite(dbFile))
    }

    private fun openEncryptedCount(file: File, keyBytes: ByteArray): Long {
        val db = SQLiteDatabase.openOrCreateDatabase(file, keyBytes, null, null)
        return try {
            db.rawQuery("SELECT COUNT(*) FROM messages", arrayOf<String>()).use { c ->
                assertTrue(c.moveToFirst()); c.getLong(0)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun plaintextMessagesSurviveEncryptedConversion() {
        createPlaintextDb(rows = 5)

        val result = PlaintextDatabaseMigration(dbFile, SqlCipherExporter())
            .migrateIfNeeded(keyAscii)

        assertEquals(DatabaseMigrationResult.Migrated, result)
        // Header changed: no longer plaintext.
        assertFalse("db header must change to encrypted", SqliteDatabaseFile.isPlaintextSqlite(dbFile))
        // Correct key opens and every row survived.
        assertEquals(5L, openEncryptedCount(dbFile, keyAscii))
    }

    @Test
    fun encryptedDatabaseHeaderIsNotPlaintext() {
        createPlaintextDb(rows = 1)
        PlaintextDatabaseMigration(dbFile, SqlCipherExporter()).migrateIfNeeded(keyAscii)

        // Read the first 16 bytes directly and assert they are not the SQLite magic.
        val header = dbFile.inputStream().use { it.readNBytes(16) }
        assertFalse(header.contentEquals(SqliteDatabaseFile.PLAINTEXT_HEADER))
    }

    @Test
    fun correctKeyOpensAndWrongKeyFails() {
        createPlaintextDb(rows = 3)
        PlaintextDatabaseMigration(dbFile, SqlCipherExporter()).migrateIfNeeded(keyAscii)

        // Correct key: opens and reads.
        assertEquals(3L, openEncryptedCount(dbFile, keyAscii))

        // Wrong key: the first real read must fail.
        var failed = false
        try {
            openEncryptedCount(dbFile, wrongKeyAscii)
        } catch (_: RuntimeException) {
            failed = true
        }
        assertTrue("wrong key must not open the encrypted database", failed)
    }

    @Test
    fun exporterReadEncryptedRejectsWrongKey() {
        createPlaintextDb(rows = 2)
        val exporter = SqlCipherExporter()
        PlaintextDatabaseMigration(dbFile, exporter).migrateIfNeeded(keyAscii)

        assertNotNull("right key verifies", exporter.readEncrypted(dbFile, keyAscii))
        assertNull("wrong key fails verification", exporter.readEncrypted(dbFile, wrongKeyAscii))
    }

    @Test
    fun injectedExportFailureBeforeExportPreservesSource() {
        createPlaintextDb(rows = 4)
        val originalBytes = dbFile.readBytes()

        val failing = object : EncryptedExporter {
            override fun export(source: File, dest: File, rawKeyAscii: ByteArray): DatabaseContentReport =
                throw IOException("injected export failure")
            override fun readEncrypted(file: File, rawKeyAscii: ByteArray): DatabaseContentReport? =
                SqlCipherExporter().readEncrypted(file, rawKeyAscii)
        }

        val result = PlaintextDatabaseMigration(dbFile, failing).migrateIfNeeded(keyAscii)

        assertEquals(
            DatabaseMigrationResult.Failed(DatabaseMigrationError.EXPORT_FAILED),
            result,
        )
        assertTrue("plaintext source preserved", SqliteDatabaseFile.isPlaintextSqlite(dbFile))
        org.junit.Assert.assertArrayEquals("byte-for-byte source preserved", originalBytes, dbFile.readBytes())
    }

    @Test
    fun injectedFailureAfterExportPreservesRecoverableSource() {
        createPlaintextDb(rows = 6)
        val originalBytes = dbFile.readBytes()

        // Real export succeeds, but verification is forced to fail (wrong row count),
        // modelling a corruption detected after export. Source must be preserved.
        val real = SqlCipherExporter()
        val badVerify = object : EncryptedExporter {
            override fun export(source: File, dest: File, rawKeyAscii: ByteArray): DatabaseContentReport {
                real.export(source, dest, rawKeyAscii)
                // Report a wrong row count so verification mismatches the real readback.
                return DatabaseContentReport(mapOf("messages" to 999L), true)
            }
            override fun readEncrypted(file: File, rawKeyAscii: ByteArray): DatabaseContentReport? =
                real.readEncrypted(file, rawKeyAscii)
        }

        val result = PlaintextDatabaseMigration(dbFile, badVerify).migrateIfNeeded(keyAscii)

        assertEquals(
            DatabaseMigrationResult.Failed(DatabaseMigrationError.VERIFICATION_FAILED),
            result,
        )
        assertTrue("plaintext source preserved", SqliteDatabaseFile.isPlaintextSqlite(dbFile))
        org.junit.Assert.assertArrayEquals(originalBytes, dbFile.readBytes())
        // Temp discarded.
        assertFalse(File(workDir, "mesh-chats.db.enc-tmp").exists())
    }

    @Test
    fun reopenAfterMigrationIsIdempotent() {
        createPlaintextDb(rows = 2)
        val exporter = SqlCipherExporter()
        assertEquals(
            DatabaseMigrationResult.Migrated,
            PlaintextDatabaseMigration(dbFile, exporter).migrateIfNeeded(keyAscii),
        )

        // Second run: already encrypted → no work, data intact.
        assertEquals(
            DatabaseMigrationResult.NotNeeded,
            PlaintextDatabaseMigration(dbFile, SqlCipherExporter()).migrateIfNeeded(keyAscii),
        )
        assertEquals(2L, openEncryptedCount(dbFile, keyAscii))
    }

    @Test
    fun staleWalAndShmAreRemovedOnMigration() {
        createPlaintextDb(rows = 1)
        // Simulate leftover plaintext side-files.
        File(workDir, "mesh-chats.db-wal").writeBytes("stale-wal".toByteArray())
        File(workDir, "mesh-chats.db-shm").writeBytes("stale-shm".toByteArray())

        assertEquals(
            DatabaseMigrationResult.Migrated,
            PlaintextDatabaseMigration(dbFile, SqlCipherExporter()).migrateIfNeeded(keyAscii),
        )
        assertFalse(File(workDir, "mesh-chats.db-wal").exists())
        assertFalse(File(workDir, "mesh-chats.db-shm").exists())
        assertEquals(1L, openEncryptedCount(dbFile, keyAscii))
    }

    @Test
    fun userVersionIsPreservedThroughEncryption() {
        // Fixture sets user_version = 1 (see createPlaintextDb).
        createPlaintextDb(rows = 1)
        PlaintextDatabaseMigration(dbFile, SqlCipherExporter()).migrateIfNeeded(keyAscii)

        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, keyAscii, null, null)
        try {
            assertEquals("Room schema version must survive encryption", 1, db.version)
        } finally {
            db.close()
        }
    }
}

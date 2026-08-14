package com.meshchats.app.data.local

import com.meshchats.app.crypto.AtomicMover
import com.meshchats.app.crypto.DirectorySync
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Exercises the crash-safe swap state machine of [PlaintextDatabaseMigration] on
 * the host JVM with a fake exporter. The invariant every test guards: after any
 * failure or interruption, the original plaintext database is still present and
 * still reads as plaintext, and no data is silently destroyed.
 */
class PlaintextDatabaseMigrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val key = ByteArray(32) { it.toByte() }
    private val keyAscii = SqlCipherRawKey.encode(key)

    private val noSync = DirectorySync { /* no-op on host JVM */ }

    /** Writes a file whose first 16 bytes are the plaintext SQLite magic. */
    private fun writePlaintextDb(file: File, marker: ByteArray = "PLAINTEXTDATA".toByteArray()) {
        file.writeBytes(SqliteDatabaseFile.PLAINTEXT_HEADER + marker)
    }

    /**
     * A fake exporter that copies a synthetic encrypted file and reports a fixed
     * row-count snapshot. It models the real cipher engine's contract without a
     * device: the "encrypted" file simply does not start with the plaintext magic.
     */
    private inner class FakeExporter(
        private val report: DatabaseContentReport = DatabaseContentReport(mapOf("messages" to 3L), true),
        private val failExport: Boolean = false,
        private val failReadEncrypted: Boolean = false,
        private val reportOverrideForVerify: DatabaseContentReport? = null,
    ) : EncryptedExporter {
        var exportCount = 0
        override fun export(source: File, dest: File, rawKeyAscii: ByteArray): DatabaseContentReport {
            exportCount++
            if (failExport) throw IOException("export failed")
            assertTrue("source must be plaintext", SqliteDatabaseFile.isPlaintextSqlite(source))
            assertArrayEquals("raw key must reach exporter", keyAscii, rawKeyAscii)
            // Encrypted output: random-ish prefix that is NOT the plaintext magic.
            dest.writeBytes(byteArrayOf(0x2a, 0x11, 0x77, 0x03) + "ENC".toByteArray() + ByteArray(20))
            return report
        }

        override fun readEncrypted(file: File, rawKeyAscii: ByteArray): DatabaseContentReport? {
            if (failReadEncrypted) return null
            if (SqliteDatabaseFile.isPlaintextSqlite(file)) return null
            return reportOverrideForVerify ?: report
        }
    }

    /** A marker writer that records phases and can be toggled to fail durably. */
    private inner class RecordingMarkerWriter(
        private val fail: Boolean = false,
        private val failOnPhase: String? = null,
    ) : MigrationMarkerWriter {
        val written: MutableList<String> = mutableListOf()
        override fun write(marker: File, content: String): Boolean {
            if (fail || content == failOnPhase) return false
            written.add(content)
            // Persist non-durably (plain write) so recovery/idempotency still work.
            marker.writeText(content)
            return true
        }
    }

    private fun migration(
        dbFile: File,
        exporter: EncryptedExporter,
        mover: AtomicMover = AtomicMover.Default,
        markerWriter: MigrationMarkerWriter? = null,
    ) = PlaintextDatabaseMigration(
        dbFile,
        exporter,
        noSync,
        mover,
        markerWriter = markerWriter,
        // Host-JVM temp dirs support OS locks, but keep tests independent of the
        // shared lock file unless a test explicitly exercises the process lock.
        useProcessLock = false,
    )

    @Test
    fun `plaintext database is converted and swapped in`() {
        val db = File(temp.root, "mesh-chats.db")
        writePlaintextDb(db)

        val result = migration(db, FakeExporter()).migrateIfNeeded(keyAscii)

        assertEquals(DatabaseMigrationResult.Migrated, result)
        assertTrue(db.isFile)
        assertFalse("db must no longer be plaintext", SqliteDatabaseFile.isPlaintextSqlite(db))
        // No residue.
        assertFalse(File(temp.root, "mesh-chats.db.enc-tmp").exists())
        assertFalse(File(temp.root, "mesh-chats.db.pt-bak").exists())
        assertFalse(File(temp.root, "mesh-chats.db.migration").exists())
    }

    @Test
    fun `absent database needs no migration`() {
        val db = File(temp.root, "mesh-chats.db")
        val result = migration(db, FakeExporter()).migrateIfNeeded(keyAscii)
        assertEquals(DatabaseMigrationResult.NotNeeded, result)
    }

    @Test
    fun `already-encrypted database needs no migration`() {
        val db = File(temp.root, "mesh-chats.db")
        db.writeBytes(byteArrayOf(0x01, 0x02, 0x03) + ByteArray(60)) // not plaintext magic
        val exporter = FakeExporter()
        val result = migration(db, exporter).migrateIfNeeded(keyAscii)
        assertEquals(DatabaseMigrationResult.NotNeeded, result)
        assertEquals("must not export an already-encrypted db", 0, exporter.exportCount)
    }

    @Test
    fun `reopen after successful migration is idempotent`() {
        val db = File(temp.root, "mesh-chats.db")
        writePlaintextDb(db)
        migration(db, FakeExporter()).migrateIfNeeded(keyAscii)

        val exporter = FakeExporter()
        val second = migration(db, exporter).migrateIfNeeded(keyAscii)
        assertEquals(DatabaseMigrationResult.NotNeeded, second)
        assertEquals(0, exporter.exportCount)
    }

    @Test
    fun `export failure preserves the plaintext original`() {
        val db = File(temp.root, "mesh-chats.db")
        writePlaintextDb(db)

        val result = migration(db, FakeExporter(failExport = true)).migrateIfNeeded(keyAscii)

        assertEquals(
            DatabaseMigrationResult.Failed(DatabaseMigrationError.EXPORT_FAILED),
            result,
        )
        assertTrue("plaintext original must survive", SqliteDatabaseFile.isPlaintextSqlite(db))
        assertFalse(File(temp.root, "mesh-chats.db.enc-tmp").exists())
        assertFalse(File(temp.root, "mesh-chats.db.migration").exists())
    }

    @Test
    fun `verification failure on row-count mismatch preserves plaintext and discards temp`() {
        val db = File(temp.root, "mesh-chats.db")
        writePlaintextDb(db)

        // Source snapshot says 3 rows; verify reads back 2 → mismatch.
        val exporter = FakeExporter(
            report = DatabaseContentReport(mapOf("messages" to 3L), true),
            reportOverrideForVerify = DatabaseContentReport(mapOf("messages" to 2L), true),
        )
        val result = migration(db, exporter).migrateIfNeeded(keyAscii)

        assertEquals(
            DatabaseMigrationResult.Failed(DatabaseMigrationError.VERIFICATION_FAILED),
            result,
        )
        assertTrue(SqliteDatabaseFile.isPlaintextSqlite(db))
        assertFalse(File(temp.root, "mesh-chats.db.enc-tmp").exists())
    }

    @Test
    fun `verification failure on integrity check preserves plaintext`() {
        val db = File(temp.root, "mesh-chats.db")
        writePlaintextDb(db)

        val exporter = FakeExporter(
            report = DatabaseContentReport(mapOf("messages" to 3L), true),
            reportOverrideForVerify = DatabaseContentReport(mapOf("messages" to 3L), false),
        )
        val result = migration(db, exporter).migrateIfNeeded(keyAscii)

        assertEquals(
            DatabaseMigrationResult.Failed(DatabaseMigrationError.VERIFICATION_FAILED),
            result,
        )
        assertTrue(SqliteDatabaseFile.isPlaintextSqlite(db))
    }

    @Test
    fun `verification failure when temp cannot be reopened preserves plaintext`() {
        val db = File(temp.root, "mesh-chats.db")
        writePlaintextDb(db)

        val result = migration(db, FakeExporter(failReadEncrypted = true)).migrateIfNeeded(keyAscii)

        assertEquals(
            DatabaseMigrationResult.Failed(DatabaseMigrationError.VERIFICATION_FAILED),
            result,
        )
        assertTrue(SqliteDatabaseFile.isPlaintextSqlite(db))
    }

    @Test
    fun `swap failure moving the original restores the plaintext database`() {
        val db = File(temp.root, "mesh-chats.db")
        writePlaintextDb(db)

        // Mover that fails the FIRST move (the original → backup) but the default
        // otherwise. Because the backup move is first, the original is untouched.
        var moves = 0
        val failFirstMove = AtomicMover { source, dest ->
            moves++
            if (moves == 1) throw IOException("swap interrupted")
            AtomicMover.Default.move(source, dest)
        }

        val result = migration(db, FakeExporter(), failFirstMove).migrateIfNeeded(keyAscii)

        assertEquals(
            DatabaseMigrationResult.Failed(DatabaseMigrationError.SWAP_FAILED),
            result,
        )
        assertTrue("plaintext must still be usable", SqliteDatabaseFile.isPlaintextSqlite(db))
    }

    @Test
    fun `swap failure after moving original into backup rolls back from backup`() {
        val db = File(temp.root, "mesh-chats.db")
        writePlaintextDb(db, marker = "UNIQUEMARK".toByteArray())
        val originalBytes = db.readBytes()

        // Fail the move that puts the encrypted temp onto the live path. By then
        // the original has been moved to backup; rollback must restore it.
        val failTempMove = AtomicMover { source, dest ->
            if (source.name.endsWith(".enc-tmp")) throw IOException("temp move failed")
            AtomicMover.Default.move(source, dest)
        }

        val result = migration(db, FakeExporter(), failTempMove).migrateIfNeeded(keyAscii)

        assertEquals(
            DatabaseMigrationResult.Failed(DatabaseMigrationError.SWAP_FAILED),
            result,
        )
        assertTrue("plaintext restored", SqliteDatabaseFile.isPlaintextSqlite(db))
        assertArrayEquals("exact original bytes restored", originalBytes, db.readBytes())
        assertFalse(File(temp.root, "mesh-chats.db.pt-bak").exists())
    }

    @Test
    fun `final open failure after swap rolls back to plaintext`() {
        val db = File(temp.root, "mesh-chats.db")
        writePlaintextDb(db)
        val originalBytes = db.readBytes()

        // readEncrypted passes during verify (temp) but the exporter is toggled to
        // fail the FINAL live-open check. Model with a counting exporter.
        val exporter = object : EncryptedExporter {
            var reads = 0
            override fun export(source: File, dest: File, rawKeyAscii: ByteArray): DatabaseContentReport {
                dest.writeBytes(byteArrayOf(0x2a, 0x11) + ByteArray(30))
                return DatabaseContentReport(mapOf("messages" to 1L), true)
            }
            override fun readEncrypted(file: File, rawKeyAscii: ByteArray): DatabaseContentReport? {
                reads++
                // First read = temp verification (ok); second = final live open (fail).
                return if (reads == 1) DatabaseContentReport(mapOf("messages" to 1L), true) else null
            }
        }

        val result = migration(db, exporter).migrateIfNeeded(keyAscii)

        assertEquals(
            DatabaseMigrationResult.Failed(DatabaseMigrationError.ENCRYPTED_OPEN_FAILED),
            result,
        )
        assertTrue("plaintext restored after failed final open", SqliteDatabaseFile.isPlaintextSqlite(db))
        assertArrayEquals(originalBytes, db.readBytes())
    }

    @Test
    fun `WAL and SHM side files are moved with the database on success`() {
        val db = File(temp.root, "mesh-chats.db")
        writePlaintextDb(db)
        val wal = File(temp.root, "mesh-chats.db-wal").apply { writeBytes("WAL".toByteArray()) }
        val shm = File(temp.root, "mesh-chats.db-shm").apply { writeBytes("SHM".toByteArray()) }

        val result = migration(db, FakeExporter()).migrateIfNeeded(keyAscii)

        assertEquals(DatabaseMigrationResult.Migrated, result)
        // Stale plaintext WAL/SHM must not remain to be applied over the encrypted db.
        assertFalse("stale plaintext WAL removed", wal.exists())
        assertFalse("stale plaintext SHM removed", shm.exists())
    }

    @Test
    fun `interrupted export leaves recoverable state and the next run migrates`() {
        val db = File(temp.root, "mesh-chats.db")
        writePlaintextDb(db)

        // Simulate a crash during export by leaving a marker + partial temp behind.
        File(temp.root, "mesh-chats.db.migration").writeText("EXPORT")
        File(temp.root, "mesh-chats.db.enc-tmp").writeBytes(byteArrayOf(0x00, 0x01))

        val result = migration(db, FakeExporter()).migrateIfNeeded(keyAscii)

        assertEquals(DatabaseMigrationResult.Migrated, result)
        assertFalse(SqliteDatabaseFile.isPlaintextSqlite(db))
        assertFalse(File(temp.root, "mesh-chats.db.enc-tmp").exists())
        assertFalse(File(temp.root, "mesh-chats.db.migration").exists())
    }

    @Test
    fun `interrupted swap with valid encrypted live db and leftover backup finalizes`() {
        val db = File(temp.root, "mesh-chats.db")
        // Live db is already the encrypted result.
        db.writeBytes(byteArrayOf(0x2a, 0x11) + ByteArray(30))
        // A backup of the plaintext original remains from the interrupted swap.
        File(temp.root, "mesh-chats.db.pt-bak").apply { writePlaintextDb(this) }
        File(temp.root, "mesh-chats.db.migration").writeText("SWAP")

        val result = migration(db, FakeExporter()).migrateIfNeeded(keyAscii)

        // Live db was valid encrypted → finalize: NotNeeded (no new export) and backup gone.
        assertEquals(DatabaseMigrationResult.NotNeeded, result)
        assertFalse("backup discarded once encrypted db confirmed", File(temp.root, "mesh-chats.db.pt-bak").exists())
        assertFalse(File(temp.root, "mesh-chats.db.migration").exists())
    }

    @Test
    fun `interrupted swap with invalid live db rolls back to plaintext backup`() {
        val db = File(temp.root, "mesh-chats.db")
        // Live db is a corrupt/partial encrypted file that will not open.
        db.writeBytes(byteArrayOf(0x2a, 0x11) + ByteArray(30))
        File(temp.root, "mesh-chats.db.pt-bak").apply { writePlaintextDb(this, "RECOVERME".toByteArray()) }
        File(temp.root, "mesh-chats.db.migration").writeText("SWAP")

        // Exporter whose readEncrypted always fails → live db "won't open".
        val result = migration(db, FakeExporter(failReadEncrypted = true)).migrateIfNeeded(keyAscii)

        // Rolled back to plaintext, which is then eligible to migrate again this run,
        // but the same exporter fails read → verification fails, plaintext preserved.
        assertTrue("plaintext recovered from backup", SqliteDatabaseFile.isPlaintextSqlite(db))
    }

    @Test
    fun `marker write failure aborts export with plaintext untouched`() {
        val db = File(temp.root, "mesh-chats.db")
        writePlaintextDb(db)
        val originalBytes = db.readBytes()

        val exporter = FakeExporter()
        val result = migration(db, exporter, markerWriter = RecordingMarkerWriter(fail = true))
            .migrateIfNeeded(keyAscii)

        assertEquals(
            DatabaseMigrationResult.Failed(DatabaseMigrationError.MARKER_WRITE_FAILED),
            result,
        )
        assertTrue("plaintext untouched when marker cannot persist", SqliteDatabaseFile.isPlaintextSqlite(db))
        assertArrayEquals(originalBytes, db.readBytes())
        assertEquals("export must not run without a durable marker", 0, exporter.exportCount)
        assertFalse(File(temp.root, "mesh-chats.db.enc-tmp").exists())
        assertFalse(File(temp.root, "mesh-chats.db.migration").exists())
    }

    @Test
    fun `swap marker write failure aborts before any destructive move`() {
        val db = File(temp.root, "mesh-chats.db")
        writePlaintextDb(db)
        val originalBytes = db.readBytes()

        // EXPORT marker persists, SWAP marker fails → abort before moving anything.
        val result = migration(
            db,
            FakeExporter(),
            markerWriter = RecordingMarkerWriter(failOnPhase = "SWAP"),
        ).migrateIfNeeded(keyAscii)

        assertEquals(
            DatabaseMigrationResult.Failed(DatabaseMigrationError.MARKER_WRITE_FAILED),
            result,
        )
        assertTrue("plaintext original preserved", SqliteDatabaseFile.isPlaintextSqlite(db))
        assertArrayEquals(originalBytes, db.readBytes())
        assertFalse("no backup taken", File(temp.root, "mesh-chats.db.pt-bak").exists())
        assertFalse(File(temp.root, "mesh-chats.db.enc-tmp").exists())
        assertFalse(File(temp.root, "mesh-chats.db.migration").exists())
    }

    @Test
    fun `torn marker with backup and corrupt live db restores plaintext`() {
        val db = File(temp.root, "mesh-chats.db")
        // Live is a corrupt encrypted partial that will not open.
        db.writeBytes(byteArrayOf(0x2a, 0x11) + ByteArray(30))
        File(temp.root, "mesh-chats.db.pt-bak").apply { writePlaintextDb(this, "RESTORED".toByteArray()) }
        // Torn/garbage marker content that does not name a known phase.
        File(temp.root, "mesh-chats.db.migration").writeText("\u0000TORN\u0000")

        // Recovery must be independent of the marker: backup present + unopenable
        // live → restore plaintext, then attempt migration.
        val result = migration(db, FakeExporter(failReadEncrypted = true)).migrateIfNeeded(keyAscii)

        assertTrue("plaintext restored despite torn marker", SqliteDatabaseFile.isPlaintextSqlite(db))
        // The restored plaintext is eligible to migrate but the exporter fails read →
        // verification fails, plaintext preserved (never an empty db).
        assertEquals(
            DatabaseMigrationResult.Failed(DatabaseMigrationError.VERIFICATION_FAILED),
            result,
        )
    }

    @Test
    fun `missing marker with backup and missing live db restores plaintext`() {
        val db = File(temp.root, "mesh-chats.db")
        // Live db is absent (crashed after moving original to backup, before temp move).
        File(temp.root, "mesh-chats.db.pt-bak").apply { writePlaintextDb(this, "ONLYBACKUP".toByteArray()) }
        // No marker at all.

        val result = migration(db, FakeExporter()).migrateIfNeeded(keyAscii)

        // Restored plaintext then migrated forward to encrypted.
        assertEquals(DatabaseMigrationResult.Migrated, result)
        assertTrue(db.isFile)
        assertFalse("migrated to encrypted", SqliteDatabaseFile.isPlaintextSqlite(db))
        assertFalse("backup consumed", File(temp.root, "mesh-chats.db.pt-bak").exists())
    }

    @Test
    fun `missing live and unrestorable backup fails closed preserving backup`() {
        val db = File(temp.root, "mesh-chats.db")
        // Live absent, backup present, but the move (restore) fails.
        File(temp.root, "mesh-chats.db.pt-bak").apply { writePlaintextDb(this, "PRESERVE".toByteArray()) }

        val failRestore = AtomicMover { _, _ -> throw IOException("cannot restore backup") }
        val result = migration(db, FakeExporter(), failRestore).migrateIfNeeded(keyAscii)

        assertEquals(
            DatabaseMigrationResult.Failed(DatabaseMigrationError.RECOVERY_FAILED),
            result,
        )
        assertFalse("Room must not see a live db", db.exists())
        assertTrue("backup preserved for next-launch retry", File(temp.root, "mesh-chats.db.pt-bak").exists())
    }

    @Test
    fun `backup with intact live plaintext drops redundant backup and migrates`() {
        val db = File(temp.root, "mesh-chats.db")
        writePlaintextDb(db, marker = "LIVEINTACT".toByteArray())
        // A stale backup from an aborted attempt where the original was never moved.
        File(temp.root, "mesh-chats.db.pt-bak").apply { writePlaintextDb(this, "STALEBAK".toByteArray()) }
        File(temp.root, "mesh-chats.db.migration").writeText("SWAP")

        val result = migration(db, FakeExporter()).migrateIfNeeded(keyAscii)

        assertEquals(DatabaseMigrationResult.Migrated, result)
        assertFalse("migrated to encrypted", SqliteDatabaseFile.isPlaintextSqlite(db))
        assertFalse("redundant backup dropped", File(temp.root, "mesh-chats.db.pt-bak").exists())
    }

    @Test
    fun `orphan plaintext backup and WAL SHM are swept on idempotent encrypted startup`() {
        val db = File(temp.root, "mesh-chats.db")
        // Live db already encrypted.
        db.writeBytes(byteArrayOf(0x2a, 0x11) + ByteArray(30))
        // Stray plaintext orphans from an old, fully-completed attempt.
        val bak = File(temp.root, "mesh-chats.db.pt-bak").apply { writePlaintextDb(this) }
        val bakWal = File(temp.root, "mesh-chats.db-wal.pt-bak").apply { writeBytes("WAL".toByteArray()) }
        val bakShm = File(temp.root, "mesh-chats.db-shm.pt-bak").apply { writeBytes("SHM".toByteArray()) }
        val tmp = File(temp.root, "mesh-chats.db.enc-tmp").apply { writeBytes(byteArrayOf(0x2a, 0x11)) }

        val exporter = FakeExporter()
        val result = migration(db, exporter).migrateIfNeeded(keyAscii)

        assertEquals(DatabaseMigrationResult.NotNeeded, result)
        assertEquals("no export on an encrypted live db", 0, exporter.exportCount)
        assertFalse("orphan backup swept", bak.exists())
        assertFalse("orphan WAL backup swept", bakWal.exists())
        assertFalse("orphan SHM backup swept", bakShm.exists())
        assertFalse("orphan temp swept", tmp.exists())
    }

    @Test
    fun `default marker writer persists durably and survives a torn write attempt`() {
        // DurableMigrationMarkerWriter writes temp + atomic replace; a fresh write
        // fully replaces any prior marker, never leaving a torn value.
        val marker = File(temp.root, "mesh-chats.db.migration")
        val writer = DurableMigrationMarkerWriter(noSync, AtomicMover.Default)

        assertTrue(writer.write(marker, "EXPORT"))
        assertEquals("EXPORT", marker.readText())
        assertTrue(writer.write(marker, "SWAP"))
        assertEquals("SWAP", marker.readText())
        // No temp residue left behind.
        val stray = temp.root.listFiles()?.filter { it.name.contains(".tmp-") } ?: emptyList()
        assertTrue("marker temp cleaned up, found $stray", stray.isEmpty())
    }

    @Test
    fun `default marker writer reports failure when atomic move fails`() {
        val marker = File(temp.root, "mesh-chats.db.migration")
        val failMove = AtomicMover { _, _ -> throw IOException("move failed") }
        val writer = DurableMigrationMarkerWriter(noSync, failMove)

        assertFalse("marker write must report failure", writer.write(marker, "SWAP"))
        assertFalse("no marker published on move failure", marker.exists())
        val stray = temp.root.listFiles()?.filter { it.name.contains(".tmp-") } ?: emptyList()
        assertTrue("marker temp cleaned up, found $stray", stray.isEmpty())
    }

    @Test
    fun `process lock serializes two migration instances on the same db`() {
        val db = File(temp.root, "mesh-chats.db")
        writePlaintextDb(db)

        // Two instances using the real process lock (default true) on the same path.
        val a = PlaintextDatabaseMigration(db, FakeExporter(), noSync)
        val b = PlaintextDatabaseMigration(db, FakeExporter(), noSync)

        val results = java.util.Collections.synchronizedList(mutableListOf<DatabaseMigrationResult>())
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(a, b).map { m ->
                pool.submit {
                    start.await()
                    results.add(m.migrateIfNeeded(keyAscii))
                }
            }
            start.countDown()
            futures.forEach { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        // Exactly one instance performs the migration; the other sees the already-
        // encrypted db and reports NotNeeded. Never two Migrated (no double export).
        assertEquals(1, results.count { it == DatabaseMigrationResult.Migrated })
        assertEquals(1, results.count { it == DatabaseMigrationResult.NotNeeded })
        assertFalse(SqliteDatabaseFile.isPlaintextSqlite(db))
    }
}

package com.meshchats.app.data.local

import com.meshchats.app.crypto.AndroidDirectorySync
import com.meshchats.app.crypto.AtomicMover
import com.meshchats.app.crypto.DirectorySync
import com.meshchats.app.crypto.ProcessFileLock
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** A bounded reason a plaintext→encrypted migration could not complete. */
enum class DatabaseMigrationError {
    /** The SQLCipher export step failed; the original plaintext database is untouched. */
    EXPORT_FAILED,

    /**
     * The exported database failed verification (would not open with the key,
     * failed `integrity_check`, or had mismatched tables/row counts). The
     * encrypted candidate is discarded and the original plaintext is untouched.
     */
    VERIFICATION_FAILED,

    /**
     * The crash-recovery phase marker could not be written durably. Because the
     * marker guards the destructive swap, we refuse to move any file: the
     * original live database is left byte-for-byte unchanged.
     */
    MARKER_WRITE_FAILED,

    /**
     * The atomic file swap failed partway. The original plaintext database has
     * been restored from its backup and remains usable.
     */
    SWAP_FAILED,

    /**
     * After the swap the encrypted database did not open cleanly. The original
     * plaintext database has been restored from its backup and remains usable.
     */
    ENCRYPTED_OPEN_FAILED,

    /**
     * A prior interrupted swap left the live database missing or unopenable and
     * the preserved plaintext backup could not be restored over it. We fail
     * closed with the backup intact rather than let Room create an empty
     * database and silently lose the user's data.
     */
    RECOVERY_FAILED,

    /** A filesystem precondition (missing parent directory) prevented migration. */
    IO_FAILED,
}

/** Outcome of [PlaintextDatabaseMigration.migrateIfNeeded]. */
sealed interface DatabaseMigrationResult {
    /** No plaintext database was present, or the database was already encrypted. */
    data object NotNeeded : DatabaseMigrationResult

    /** The plaintext database was exported to an encrypted one and swapped in. */
    data object Migrated : DatabaseMigrationResult

    /**
     * Migration did not complete. The original plaintext database is guaranteed
     * to still be present and usable; nothing was destructively lost.
     */
    data class Failed(val error: DatabaseMigrationError) : DatabaseMigrationResult
}

/** A snapshot of `table name -> row count` used to prove an export copied every row. */
data class DatabaseContentReport(val rowCounts: Map<String, Long>, val integrityOk: Boolean)

/**
 * Performs the actual SQLCipher-level work of a migration, isolated behind an
 * interface so the crash-safe orchestration in [PlaintextDatabaseMigration] can
 * be exercised on the host JVM with a fake, and the real cipher operations can be
 * exercised on-device.
 *
 * Implementations must never mutate the plaintext [source]; they only read it.
 */
interface EncryptedExporter {
    /**
     * Exports the full schema and data of plaintext [source] into a freshly
     * created encrypted database at [dest], keyed by [rawKeyAscii] (the SQLCipher
     * raw-key `x'<hex>'` ASCII bytes). Copies `user_version`. [dest] must not
     * already exist. Returns the source's `table -> row count` snapshot for
     * verification. Throws [IOException] on any failure, leaving [source]
     * untouched (callers delete a partial [dest]).
     */
    @Throws(IOException::class)
    fun export(source: File, dest: File, rawKeyAscii: ByteArray): DatabaseContentReport

    /**
     * Opens the encrypted database at [file] with [rawKeyAscii], runs
     * `PRAGMA integrity_check`, and reads its `table -> row count` snapshot.
     * Returns null if the file will not open with the key (wrong key, not an
     * encrypted database, corrupt).
     */
    fun readEncrypted(file: File, rawKeyAscii: ByteArray): DatabaseContentReport?
}

/**
 * Writes the crash-recovery phase marker durably. Isolated as a seam so a test
 * can inject a writer that fails and assert the destructive swap never runs.
 *
 * A durable implementation writes to a fresh temp file (`CREATE_NEW`), fsyncs the
 * bytes, atomically replaces the marker path, then fsyncs the parent directory so
 * the marker's own directory entry survives power loss.
 */
fun interface MigrationMarkerWriter {
    /** Durably writes [content] to [marker]. Returns false on any write/fsync/rename failure. */
    fun write(marker: File, content: String): Boolean
}

/**
 * Production [MigrationMarkerWriter]: temp-file + fsync + atomic replace + parent
 * fsync. Any failure at any step returns false with the previous marker (if any)
 * left intact — never a torn marker.
 */
class DurableMigrationMarkerWriter(
    private val directorySync: DirectorySync = AndroidDirectorySync(),
    private val mover: AtomicMover = AtomicMover.Default,
) : MigrationMarkerWriter {
    override fun write(marker: File, content: String): Boolean {
        val dir = marker.parentFile ?: return false
        if (!dir.exists() && !dir.mkdirs()) return false
        val temp = File(dir, "${marker.name}.tmp-${System.nanoTime()}")
        // A stale same-named temp would make CREATE_NEW fail; clear it defensively.
        if (temp.exists()) temp.delete()
        try {
            FileChannel.open(
                temp.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { ch ->
                val buf = ByteBuffer.wrap(content.toByteArray(Charsets.US_ASCII))
                while (buf.hasRemaining()) ch.write(buf)
                // Bytes + metadata durable before the marker name is published.
                ch.force(true)
            }
            try {
                // Atomic replace: never leaves a torn marker on the live path.
                mover.move(temp, marker)
            } catch (_: IOException) {
                return false
            }
            // Make the marker's directory entry durable across power loss.
            directorySync.sync(dir)
            return true
        } catch (_: IOException) {
            return false
        } catch (_: SecurityException) {
            return false
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}

/**
 * Migrates a pre-existing **plaintext** Room database to a SQLCipher-encrypted
 * one exactly once, before Room ever opens the file, with crash safety as the
 * paramount property: **every** failure path leaves the original plaintext
 * database present and openable, and no data is ever destroyed on a recovery
 * path.
 *
 * ### Why this runs before Room opens
 * Room, once pointed at the file through the SQLCipher [SupportOpenHelperFactory],
 * can only open an encrypted database. A user upgrading from a build that wrote a
 * plaintext `mesh-chats.db` would otherwise have Room fail to open (or, worse,
 * treat the plaintext file as corrupt). We convert the file first so Room always
 * sees an encrypted database.
 *
 * ### Single-process assumption
 * The whole recover-then-migrate sequence runs under an exclusive OS + in-process
 * lock ([ProcessFileLock]) on a sibling `.migration.lock` file, so two app
 * instances (e.g. a `:remote` process) can never race on the same database files.
 * Within one process the lock also serializes concurrent Room initializations.
 *
 * ### Detection
 * Migration runs only if the file exists **and** begins with the exact 16-byte
 * plaintext SQLite header ([SqliteDatabaseFile]). An already-encrypted database
 * (random salt prefix) or an absent file yields [DatabaseMigrationResult.NotNeeded]
 * with no work done — the operation is idempotent across reopens.
 *
 * ### Crash-safe swap
 * The conversion uses a temp file, a recoverable backup of the original, and a
 * durably written phase marker so an interrupted run can be recovered on next
 * launch:
 *
 * 1. Export plaintext → `*.enc-tmp` (encrypted). On failure: delete temp, done.
 * 2. Verify the temp: header is not plaintext, it opens with the key,
 *    `integrity_check` passes, and its row counts match the source exactly.
 * 3. fsync the directory so the temp is durable.
 * 4. Durably write marker `SWAP`. If the marker cannot be persisted the swap is
 *    abandoned and the original is left untouched.
 * 5. Move the original db/WAL/SHM aside to `*.pt-bak*` and move the temp onto the
 *    live path, fsync-ing the directory between steps. The original is **never**
 *    deleted before its backup exists.
 * 6. Re-open the live encrypted database to confirm success, then delete the
 *    backup and marker. Any failure before this point restores the plaintext
 *    backup via an atomic replace that never pre-deletes the live file.
 *
 * On next launch [migrateIfNeeded] first runs [recover], which is **independent
 * of the marker**: it inspects the live/temp/backup files directly so that even a
 * missing or torn marker with a backup present can never let Room create an empty
 * database. WAL and SHM side-files are moved with the database so a stale
 * plaintext WAL can never be applied against the new encrypted database.
 */
class PlaintextDatabaseMigration(
    private val databaseFile: File,
    private val exporter: EncryptedExporter,
    private val directorySync: DirectorySync = AndroidDirectorySync(),
    private val mover: AtomicMover = AtomicMover.Default,
    markerWriter: MigrationMarkerWriter? = null,
    private val useProcessLock: Boolean = true,
) {

    private val dir: File? = databaseFile.parentFile
    private val name: String = databaseFile.name

    private val markerWriter: MigrationMarkerWriter =
        // The marker writer uses its own reliable atomic mover, independent of the
        // swap [mover], so a test injecting a swap-move failure does not also break
        // marker persistence (in production both are AtomicMover.Default anyway).
        markerWriter ?: DurableMigrationMarkerWriter(directorySync)

    private val walFile = sibling("$name-wal")
    private val shmFile = sibling("$name-shm")
    private val tempFile = sibling("$name.enc-tmp")
    private val backupFile = sibling("$name.pt-bak")
    private val backupWal = sibling("$name-wal.pt-bak")
    private val backupShm = sibling("$name-shm.pt-bak")
    private val marker = sibling("$name.migration")
    private val lockFile = sibling("$name.migration.lock")

    private enum class Phase { EXPORT, SWAP }

    private fun sibling(n: String): File = File(dir, n)

    /**
     * Recovers any interrupted prior attempt, then migrates the plaintext
     * database if one is present. Never throws; a caller can safely invoke this
     * on every startup and only [DatabaseMigrationResult.Migrated] indicates work
     * was done this run.
     */
    fun migrateIfNeeded(rawKeyAscii: ByteArray): DatabaseMigrationResult {
        if (dir == null) {
            // No parent directory to anchor temp/backup/marker files. If a plaintext
            // db somehow exists here we cannot safely migrate it; report closed.
            return if (databaseFile.isFile && SqliteDatabaseFile.isPlaintextSqlite(databaseFile)) {
                DatabaseMigrationResult.Failed(DatabaseMigrationError.IO_FAILED)
            } else {
                DatabaseMigrationResult.NotNeeded
            }
        }

        // Serialize the entire recover+migrate sequence across processes/threads.
        return if (useProcessLock) {
            ProcessFileLock.withExclusiveLock(lockFile) { runLocked(rawKeyAscii) }
        } else {
            runLocked(rawKeyAscii)
        }
    }

    private fun runLocked(rawKeyAscii: ByteArray): DatabaseMigrationResult {
        // Defensive, marker-independent reconciliation. A terminal outcome here is
        // returned as-is; null means "state is now safe, continue to detection".
        recover(rawKeyAscii)?.let { return it }

        if (!databaseFile.isFile) return DatabaseMigrationResult.NotNeeded
        if (!SqliteDatabaseFile.isPlaintextSqlite(databaseFile)) {
            // Live database is already encrypted (idempotent startup). Sweep any
            // stray plaintext backup/WAL/SHM/temp orphans left by an old attempt.
            sweepOrphans()
            return DatabaseMigrationResult.NotNeeded
        }

        return runMigration(rawKeyAscii)
    }

    private fun runMigration(rawKeyAscii: ByteArray): DatabaseMigrationResult {
        // Clear any residue from a fully rolled-back prior attempt.
        cleanTemp()
        cleanBackup()

        if (!writeMarkerDurably(Phase.EXPORT)) {
            return DatabaseMigrationResult.Failed(DatabaseMigrationError.MARKER_WRITE_FAILED)
        }

        val sourceReport = try {
            exporter.export(databaseFile, tempFile, rawKeyAscii)
        } catch (_: IOException) {
            cleanTemp()
            clearMarker()
            return DatabaseMigrationResult.Failed(DatabaseMigrationError.EXPORT_FAILED)
        } catch (_: RuntimeException) {
            cleanTemp()
            clearMarker()
            return DatabaseMigrationResult.Failed(DatabaseMigrationError.EXPORT_FAILED)
        }

        // The exported file must not read as a plaintext SQLite database.
        if (!tempFile.isFile || SqliteDatabaseFile.isPlaintextSqlite(tempFile)) {
            cleanTemp()
            clearMarker()
            return DatabaseMigrationResult.Failed(DatabaseMigrationError.VERIFICATION_FAILED)
        }

        val verify = readEncryptedSafely(tempFile, rawKeyAscii)
        if (verify == null || !verify.integrityOk || verify.rowCounts != sourceReport.rowCounts) {
            cleanTemp()
            clearMarker()
            return DatabaseMigrationResult.Failed(DatabaseMigrationError.VERIFICATION_FAILED)
        }

        // Make the verified temp durable before we start moving files around.
        fsyncDir()

        // ---- Swap phase: originals are backed up, never deleted outright. ----
        // The SWAP marker must be durably on disk BEFORE any destructive move so a
        // crash mid-swap is recoverable. If it cannot be persisted, abort with the
        // original live database untouched.
        if (!writeMarkerDurably(Phase.SWAP)) {
            cleanTemp()
            clearMarker()
            return DatabaseMigrationResult.Failed(DatabaseMigrationError.MARKER_WRITE_FAILED)
        }
        try {
            if (databaseFile.exists()) mover.move(databaseFile, backupFile)
            if (walFile.exists()) mover.move(walFile, backupWal)
            if (shmFile.exists()) mover.move(shmFile, backupShm)
            fsyncDir()
            mover.move(tempFile, databaseFile)
            fsyncDir()
        } catch (_: IOException) {
            rollbackSwap()
            clearMarker()
            return DatabaseMigrationResult.Failed(DatabaseMigrationError.SWAP_FAILED)
        }

        // Confirm the live encrypted database opens before discarding the backup.
        val finalOk = readEncryptedSafely(databaseFile, rawKeyAscii)?.integrityOk == true
        if (!finalOk) {
            rollbackSwap()
            clearMarker()
            return DatabaseMigrationResult.Failed(DatabaseMigrationError.ENCRYPTED_OPEN_FAILED)
        }

        cleanBackup()
        clearMarker()
        fsyncDir()
        return DatabaseMigrationResult.Migrated
    }

    /**
     * Reconciles the on-disk state after an interrupted attempt **without relying
     * on the marker**, whose content may be missing or torn. The presence of the
     * plaintext backup is the authoritative signal that a destructive swap was in
     * flight; whenever it is present we guarantee the live path ends up as a
     * usable database (encrypted-and-verified, or the restored plaintext) so Room
     * can never open onto an empty file.
     *
     * @return a terminal [DatabaseMigrationResult] the caller must return, or null
     * to continue to normal detection/migration.
     */
    private fun recover(rawKeyAscii: ByteArray): DatabaseMigrationResult? {
        if (backupFile.exists()) {
            // A destructive swap was underway. Decide from the live file's state.
            val liveEncryptedOk = databaseFile.isFile &&
                !SqliteDatabaseFile.isPlaintextSqlite(databaseFile) &&
                readEncryptedSafely(databaseFile, rawKeyAscii)?.integrityOk == true
            if (liveEncryptedOk) {
                // The encrypted database is already in place and verifies; finalize.
                cleanBackup()
                cleanTemp()
                clearMarker()
                fsyncDir()
                return DatabaseMigrationResult.NotNeeded
            }

            val livePlaintextOk = databaseFile.isFile &&
                SqliteDatabaseFile.isPlaintextSqlite(databaseFile)
            if (livePlaintextOk) {
                // The live plaintext original is intact; the backup is redundant.
                // Drop backup + stale temp, then migrate the live plaintext.
                cleanBackup()
                cleanTemp()
                clearMarker()
                fsyncDir()
                return null
            }

            // Live is missing or an unopenable/corrupt encrypted partial. Restore
            // the preserved plaintext backup over it (atomic replace, never a
            // pre-delete). On success we fall through to migrate the plaintext.
            return if (restoreBackup()) {
                clearMarker()
                fsyncDir()
                null
            } else {
                // Fail closed with the backup preserved rather than let Room create
                // an empty database and lose the user's data.
                DatabaseMigrationResult.Failed(DatabaseMigrationError.RECOVERY_FAILED)
            }
        }

        // No backup: nothing destructive is half-done, so the live file (plaintext,
        // encrypted, or absent) is authoritative. Drop any partial temp/marker
        // residue from an interrupted export.
        if (marker.exists() || tempFile.exists()) {
            cleanTemp()
            clearMarker()
        }
        return null
    }

    /**
     * Restores the plaintext backup over the live path with an atomic replace that
     * never pre-deletes the live file, then moves any WAL/SHM backups back. On the
     * main-db move failing the backup is preserved for the next launch.
     *
     * @return true if the primary database was restored (or was already restored),
     * false if the backup could not be moved into place.
     */
    private fun restoreBackup(): Boolean {
        if (!backupFile.exists()) return databaseFile.isFile
        return try {
            // Atomic REPLACE_EXISTING: safely overwrites a corrupt encrypted live
            // file or creates it if absent, in one filesystem operation.
            mover.move(backupFile, databaseFile)
            try {
                if (backupWal.exists()) mover.move(backupWal, walFile)
                if (backupShm.exists()) mover.move(backupShm, shmFile)
            } catch (_: IOException) {
                // Best effort on side-files; the primary data is already restored.
            }
            cleanTemp()
            true
        } catch (_: IOException) {
            // Preserve the backup untouched so a later launch can retry recovery.
            false
        }
    }

    /**
     * Restores the plaintext backup over the live path and clears the temp. Uses
     * an atomic replace only — the live file is never deleted first — and the
     * backup is preserved if the move fails.
     */
    private fun rollbackSwap() {
        if (!backupFile.exists()) {
            cleanTemp()
            return
        }
        try {
            mover.move(backupFile, databaseFile)
            if (backupWal.exists()) mover.move(backupWal, walFile)
            if (backupShm.exists()) mover.move(backupShm, shmFile)
            fsyncDir()
        } catch (_: IOException) {
            // Preserve the backup for next-launch recovery; never delete it here.
        } finally {
            cleanTemp()
        }
    }

    private fun readEncryptedSafely(file: File, rawKeyAscii: ByteArray): DatabaseContentReport? =
        try {
            exporter.readEncrypted(file, rawKeyAscii)
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    /** Removes stray plaintext backup/WAL/SHM/temp orphans and fsyncs if any went away. */
    private fun sweepOrphans() {
        val hadOrphan = backupFile.exists() || backupWal.exists() ||
            backupShm.exists() || tempFile.exists() || marker.exists()
        cleanBackup()
        cleanTemp()
        clearMarker()
        if (hadOrphan) fsyncDir()
    }

    private fun cleanTemp() {
        if (tempFile.exists()) tempFile.delete()
    }

    private fun cleanBackup() {
        if (backupFile.exists()) backupFile.delete()
        if (backupWal.exists()) backupWal.delete()
        if (backupShm.exists()) backupShm.delete()
    }

    private fun fsyncDir() {
        dir?.let { directorySync.sync(it) }
    }

    private fun writeMarkerDurably(phase: Phase): Boolean = markerWriter.write(marker, phase.name)

    private fun clearMarker() {
        if (marker.exists()) marker.delete()
    }
}

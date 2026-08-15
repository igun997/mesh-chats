package com.meshchats.app.crypto

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Outcome of closing the encrypted database and disposing of its in-memory key
 * during a wipe.
 */
enum class DatabaseCloseOutcome {
    /**
     * The database was closed and its in-memory SQLCipher raw key was safely
     * zeroed (or was never held). No process restart is needed to guarantee no
     * key bytes linger in RAM.
     */
    CLOSED_AND_KEY_CLEARED,

    /**
     * The database was closed (or was never open) but the retained raw SQLCipher
     * key could not be safely zeroed in place, so key bytes may still sit in
     * process memory. Data at rest is still unrecoverable once the wrapping key
     * domains are destroyed; a process restart is required only to clear the
     * in-RAM residue. Forces the coordinator to report at most
     * [PanicWipeOutcome.KEYS_DESTROYED_DATA_PARTIAL].
     */
    RESTART_REQUIRED,
}

/**
 * Confirms whether a sensitive file is absent after a best-effort delete. Isolated
 * as a seam so tests can inject deletion failures.
 */
fun interface SensitiveFileDeleter {
    /** Deletes [file] if present. Returns true iff the path is absent afterward. */
    fun deleteConfirmingAbsent(file: File): Boolean

    companion object {
        /**
         * Real filesystem deleter, symlink-safe. It **never follows** a symbolic
         * link: it deletes the link itself (including a dangling link whose target
         * no longer exists) and confirms absence with `NOFOLLOW_LINKS`, so it can
         * neither be tricked into deleting a link's target elsewhere nor fooled
         * into reporting success because a link's (missing) target "doesn't exist".
         *
         * Presence and post-delete absence are both judged by whether the path
         * itself exists as a link-or-file (`Files.exists(..., NOFOLLOW_LINKS)`),
         * not by whether its target resolves. An already-absent path is success.
         */
        val Default: SensitiveFileDeleter = SensitiveFileDeleter { f ->
            try {
                val path = f.toPath()
                // NOFOLLOW_LINKS: a dangling symlink still "exists" as a link and
                // must be removed; a resolved-away target must not count as gone.
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    // Nothing at the path (not even a dangling link): already absent.
                    return@SensitiveFileDeleter true
                }
                // deleteIfExists removes the link entry itself, never its target.
                Files.deleteIfExists(path)
                !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }
}

/**
 * Bundles the two ways to destroy one persistent key domain: the dedicated
 * Keystore-alias [destroyer] and the sole wrapped-blob file [wrappedBlob].
 * Destroying **either** already renders the domain's secret unrecoverable; for
 * defense in depth the coordinator attempts **both** and only treats the domain
 * as destroyed when both are confirmed gone.
 */
class KeyDomain(
    val destroyer: KeyMaterialDestroyer,
    private val wrappedBlob: File,
    private val deleter: SensitiveFileDeleter = SensitiveFileDeleter.Default,
) {
    /**
     * Destroys the domain key-first: deletes the Keystore alias, then deletes the
     * wrapped blob. Returns true only when BOTH the alias is confirmed absent and
     * the blob is confirmed absent — conservative so the coordinator never
     * over-claims. Never throws.
     */
    fun destroy(): Boolean {
        val aliasGone = try {
            destroyer.destroy() is WrappingKeyDeleteResult.Deleted
        } catch (_: Throwable) {
            false
        }
        // Attempt the blob delete regardless of the alias result (defense in depth):
        // either alone renders the domain unrecoverable, but we want both confirmed.
        val blobGone = try {
            deleter.deleteConfirmingAbsent(wrappedBlob)
        } catch (_: Throwable) {
            false
        }
        return aliasGone && blobGone
    }
}

/**
 * Authoritative app-level [PanicWipeCoordinator]. Makes persistent decryption
 * impossible **first** by destroying both key domains (Keystore alias + wrapped
 * blob each), then closes the database and best-effort deletes the now-inert data
 * files.
 *
 * ### Strict ordering
 * 1. Destroy the database-key domain.
 * 2. Destroy the identity-key domain.
 * 3. Close the encrypted database and dispose of its in-memory raw key.
 * 4. Best-effort delete every sensitive data file.
 *
 * Steps 1–2 (the irreversible cryptographic steps) always run before any data
 * file is touched, so a crash after the first key destruction still leaves data
 * unrecoverable and a retry converges. Step 3 (the database close) runs **after**
 * both key-domain attempts and **before** the first data delete: the SQLCipher
 * open-helper must release the file before the files are unlinked, and closing it
 * only after the keys are gone means a crash mid-close still leaves data
 * unrecoverable.
 *
 * ### Honesty
 * - [PanicWipeOutcome.COMPLETE] requires BOTH domains destroyed, every file
 *   removed, and no process-restart residue.
 * - [PanicWipeOutcome.KEYS_DESTROYED_DATA_PARTIAL] when both domains are destroyed
 *   (data cryptographically unrecoverable) but file residue remains OR a restart
 *   is required to clear the in-RAM key.
 * - [PanicWipeOutcome.FAILED] whenever either key domain cannot be confirmed
 *   destroyed.
 *
 * ### Caller contract for [PanicWipeReport.processRestartRequired]
 * When the returned report has `processRestartRequired == true`, the caller MUST
 * terminate the process immediately (e.g. `Runtime.getRuntime().halt(0)` after
 * surfacing the outcome). The coordinator deliberately does **not** kill the
 * process itself: it must return the [PanicWipeReport] so the caller/UI can act,
 * and process termination is a UI/trigger concern that is out of this class's
 * scope. Until that termination happens the raw SQLCipher key may linger in RAM.
 *
 * ### Serialization + idempotency
 * All work runs under a single monitor so concurrent duress signals serialize. A
 * repeated wipe converges: an already-absent alias/blob counts as destroyed, so a
 * second call after success still reports success. No key is ever regenerated
 * during a wipe.
 */
class DefaultPanicWipeCoordinator(
    private val databaseKeyDomain: KeyDomain,
    private val identityKeyDomain: KeyDomain,
    private val closeDatabase: () -> DatabaseCloseOutcome,
    private val sensitiveFiles: () -> List<File>,
    private val deleter: SensitiveFileDeleter = SensitiveFileDeleter.Default,
) : PanicWipeCoordinator {

    private val monitor = Any()

    override fun wipe(): PanicWipeReport = synchronized(monitor) {
        // 1 & 2: KEY-FIRST. Destroy both persistent wrapping-key domains before
        // any data file is touched. Wrap each in a bounded guard so one throwing
        // never aborts the other or the data cleanup.
        val dbKeyDestroyed = safeDestroy(databaseKeyDomain)
        val identityKeyDestroyed = safeDestroy(identityKeyDomain)

        // 3: Close the database and dispose of its in-memory raw key. Runs AFTER
        // both key-domain attempts and BEFORE any data delete: the open-helper must
        // release the file before we unlink it, and closing only after the keys are
        // gone means a crash mid-close still leaves data unrecoverable. Even if this
        // fails, the wrapping keys are already gone, so data at rest is unrecoverable.
        val closeOutcome = try {
            closeDatabase()
        } catch (_: Throwable) {
            DatabaseCloseOutcome.RESTART_REQUIRED
        }
        val restartRequired = closeOutcome == DatabaseCloseOutcome.RESTART_REQUIRED

        // 4: Best-effort delete every sensitive data file. Each deletion is bounded;
        // a single failure only downgrades the outcome, never throws.
        val files = try {
            sensitiveFiles()
        } catch (_: Throwable) {
            emptyList()
        }
        var allRemoved = true
        for (f in files) {
            val removed = try {
                deleter.deleteConfirmingAbsent(f)
            } catch (_: Throwable) {
                false
            }
            if (!removed) allRemoved = false
        }

        val keysDestroyed = dbKeyDestroyed && identityKeyDestroyed
        val outcome = when {
            !keysDestroyed -> PanicWipeOutcome.FAILED
            allRemoved && !restartRequired -> PanicWipeOutcome.COMPLETE
            else -> PanicWipeOutcome.KEYS_DESTROYED_DATA_PARTIAL
        }

        PanicWipeReport(
            outcome = outcome,
            databaseKeyDestroyed = dbKeyDestroyed,
            identityKeyDestroyed = identityKeyDestroyed,
            filesRemoved = allRemoved,
            processRestartRequired = restartRequired,
        )
    }

    private fun safeDestroy(domain: KeyDomain): Boolean = try {
        domain.destroy()
    } catch (_: Throwable) {
        false
    }
}

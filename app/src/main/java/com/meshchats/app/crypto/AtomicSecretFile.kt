package com.meshchats.app.crypto

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * A bounded reason reading a wrapped-secret file failed. All I/O and structural
 * problems collapse to these closed values; callers branch exhaustively and no
 * caller sees a raw [IOException].
 */
enum class SecretFileReadError {
    /** No file exists at the target path (a first run, or the secret was wiped). */
    NOT_FOUND,

    /**
     * The target path is a symbolic link or resolves outside its parent
     * directory. Refused defensively: a wrapped secret must live at a real file
     * inside its owned directory, never behind a link an attacker could redirect.
     */
    UNSAFE_PATH,

    /** The file could not be read (permission, device error). */
    IO_FAILED,

    /** The file contents are not a structurally valid wrapped-secret record. */
    CORRUPT,
}

/** Result of reading a wrapped-secret file. */
sealed interface SecretFileReadResult {
    data class Success(val nonce: ByteArray, val ciphertext: ByteArray) : SecretFileReadResult
    data class Failure(val error: SecretFileReadError) : SecretFileReadResult
}

/** A bounded reason writing a wrapped-secret file failed. */
enum class SecretFileWriteError {
    /** The target path is a symlink or escapes its parent directory (see [SecretFileReadError.UNSAFE_PATH]). */
    UNSAFE_PATH,

    /** The parent directory could not be created. */
    DIRECTORY_UNAVAILABLE,

    /**
     * The caller supplied a nonce/ciphertext the record format cannot represent
     * (out-of-range nonce, empty or oversized ciphertext). This is a precondition
     * violation, not a storage fault — the disk was never touched.
     */
    ENCODE_INVALID,

    /** The bytes could not be durably written (write, fsync, or atomic rename failed). */
    IO_FAILED,
}

/** Result of writing a wrapped-secret file. */
sealed interface SecretFileWriteResult {
    data object Success : SecretFileWriteResult
    data class Failure(val error: SecretFileWriteError) : SecretFileWriteResult
}

/**
 * Atomically replaces one file with another, requiring true atomicity. The move
 * either fully succeeds or leaves the destination untouched; it never deletes the
 * destination first. Isolated as a seam so a test can inject a move failure and
 * assert the previous contents survive.
 */
fun interface AtomicMover {
    /**
     * Atomically moves [source] onto [dest], replacing any existing [dest] in a
     * single filesystem operation. Throws [IOException] (including
     * [AtomicMoveNotSupportedException]) if the move cannot be performed
     * atomically; on any throw the caller must treat [dest] as unchanged.
     */
    @Throws(IOException::class)
    fun move(source: File, dest: File)

    companion object {
        /** Backed by [Files.move] with `ATOMIC_MOVE` + `REPLACE_EXISTING`. */
        val Default: AtomicMover = AtomicMover { source, dest ->
            Files.move(
                source.toPath(),
                dest.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}

/**
 * Durably and atomically stores a single wrapped-secret record at a fixed path.
 *
 * A write goes to a freshly created sibling temp file (opened `CREATE_NEW`, so an
 * attacker-planted temp or symlink is refused), is flushed and fsync'd, then
 * **atomically moved** over the target via [AtomicMover]. The live target is
 * never deleted before the replacement lands: an observer — and a crash at any
 * instant — sees either the old contents or the fully written new contents,
 * never nothing and never a torn file. If the platform cannot perform an atomic
 * move, the write fails with [SecretFileWriteError.IO_FAILED] and the old file is
 * preserved intact rather than being replaced non-atomically.
 *
 * After the move the containing directory's metadata is fsync'd via
 * [DirectorySync] so the rename itself is durable across power loss.
 *
 * First-creation across concurrent callers — even in separate OS processes — is
 * serialized by [withCreationLock], which combines a JVM-wide per-path monitor
 * (so two instances in one process cannot collide on the same file lock) with an
 * exclusive [FileLock] on a persistent sibling `.lock` file.
 *
 * The containing directory is expected to be app-private and excluded from backup
 * (e.g. `noBackupFilesDir`); this class enforces path safety but not backup
 * policy.
 *
 * Path safety: before any read or write, the target and its parent are checked to
 * reject symlinks and path traversal. A wrapped secret must resolve to a real
 * regular file directly inside its declared parent directory. This blocks an
 * attacker who can plant a symlink from redirecting reads/writes elsewhere.
 *
 * The record body is validated with [WrappedSecretCodec] on read, so a truncated
 * or garbage file surfaces as [SecretFileReadError.CORRUPT] rather than reaching
 * the AEAD layer as malformed input.
 */
class AtomicSecretFile(
    private val target: File,
    private val directorySync: DirectorySync = AndroidDirectorySync(),
    private val mover: AtomicMover = AtomicMover.Default,
) {

    private val parent: File? = target.parentFile

    private companion object {
        /**
         * Per-canonical-path monitors so two [AtomicSecretFile] instances in the
         * same process that point at the same file serialize before either tries
         * to take the OS [FileLock] — a second overlapping lock in the same JVM
         * would otherwise throw [OverlappingFileLockException]. Entries are held
         * for process life (never removed) so the mapping is stable.
         */
        private val processLocks = ConcurrentHashMap<String, Any>()

        private fun monitorFor(lockFile: File): Any {
            val key = try {
                lockFile.canonicalPath
            } catch (_: IOException) {
                lockFile.absolutePath
            }
            return processLocks.computeIfAbsent(key) { Any() }
        }
    }

    /** True if a file currently exists at the target path (symlink status not considered). */
    fun exists(): Boolean = target.exists()

    /**
     * Runs [block] while holding an exclusive creation lock for this file's path,
     * serializing first-creation across both threads and OS processes.
     *
     * The lock is an exclusive [FileLock] on a persistent sibling `.lock` file,
     * guarded first by a JVM-wide per-path monitor so same-process instances do
     * not collide. The `.lock` file is created if absent and is **never deleted**
     * while the app runs. If OS-level file locking is unavailable (e.g. a
     * filesystem or host JVM that does not support it), the JVM monitor still
     * serializes callers within this process; [block] always runs exactly once.
     */
    fun <T> withCreationLock(block: () -> T): T {
        val dir = parent
        if (dir == null) {
            // No parent directory to anchor a lock file; fall back to a global
            // monitor keyed by the target's own path. Correct within-process.
            synchronized(monitorFor(target)) { return block() }
        }
        if (!dir.exists()) dir.mkdirs()
        val lockFile = File(dir, "${target.name}.lock")

        synchronized(monitorFor(lockFile)) {
            var channel: FileChannel? = null
            var fileLock: FileLock? = null
            try {
                // A symlinked lock file could redirect the lock elsewhere; refuse
                // OS locking in that case and rely on the JVM monitor.
                if (!isSymlink(lockFile)) {
                    channel = FileChannel.open(
                        lockFile.toPath(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                    )
                    fileLock = channel.lock()
                }
            } catch (_: IOException) {
                // OS file locking unavailable; JVM monitor still serializes this process.
            } catch (_: OverlappingFileLockException) {
                // Should not happen under the monitor, but never fail creation over it.
            }
            try {
                return block()
            } finally {
                try {
                    fileLock?.release()
                } catch (_: Exception) {
                    // best effort
                }
                try {
                    channel?.close()
                } catch (_: Exception) {
                    // best effort
                }
            }
        }
    }

    /**
     * Reads and structurally validates the wrapped-secret record. Never throws;
     * returns a bounded [SecretFileReadResult.Failure] on any problem.
     */
    fun read(): SecretFileReadResult {
        if (!isPathSafe()) return SecretFileReadResult.Failure(SecretFileReadError.UNSAFE_PATH)
        if (!target.exists()) return SecretFileReadResult.Failure(SecretFileReadError.NOT_FOUND)
        if (!target.isFile) return SecretFileReadResult.Failure(SecretFileReadError.UNSAFE_PATH)

        val bytes = try {
            target.readBytes()
        } catch (_: IOException) {
            return SecretFileReadResult.Failure(SecretFileReadError.IO_FAILED)
        } catch (_: SecurityException) {
            return SecretFileReadResult.Failure(SecretFileReadError.IO_FAILED)
        }

        return when (val decoded = WrappedSecretCodec.decode(bytes)) {
            is WrappedSecretDecodeResult.Success ->
                SecretFileReadResult.Success(nonce = decoded.nonce, ciphertext = decoded.ciphertext)
            is WrappedSecretDecodeResult.Failure ->
                SecretFileReadResult.Failure(SecretFileReadError.CORRUPT)
        }
    }

    /**
     * Encodes and durably writes [nonce] + [ciphertext] via a `CREATE_NEW` temp
     * file, fsync, and an atomic move over the target. Never throws; returns a
     * bounded [SecretFileWriteResult.Failure]. On any I/O failure the prior target
     * contents are left intact.
     */
    fun write(nonce: ByteArray, ciphertext: ByteArray): SecretFileWriteResult {
        val encoded = when (val e = WrappedSecretCodec.encode(nonce, ciphertext)) {
            is WrappedSecretEncodeResult.Success -> e.bytes
            // A bad nonce/ciphertext is a precondition violation, not a disk fault:
            // report it distinctly and touch nothing on disk.
            is WrappedSecretEncodeResult.Failure -> return SecretFileWriteResult.Failure(SecretFileWriteError.ENCODE_INVALID)
        }

        val dir = parent ?: return SecretFileWriteResult.Failure(SecretFileWriteError.DIRECTORY_UNAVAILABLE)
        if (!dir.exists() && !dir.mkdirs()) {
            return SecretFileWriteResult.Failure(SecretFileWriteError.DIRECTORY_UNAVAILABLE)
        }
        if (!isPathSafe()) return SecretFileWriteResult.Failure(SecretFileWriteError.UNSAFE_PATH)

        val temp = File(dir, "${target.name}.tmp-${System.nanoTime()}")
        try {
            // CREATE_NEW gives O_EXCL semantics: creation fails if anything (a stale
            // temp, or a planted symlink) already occupies the path.
            FileChannel.open(
                temp.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { ch ->
                val buf = ByteBuffer.wrap(encoded)
                while (buf.hasRemaining()) ch.write(buf)
                // Force bytes and metadata to stable storage before the move so a
                // crash cannot expose an empty or partially written temp file.
                ch.force(true)
            }

            // Atomic replace over the live target. This NEVER deletes the target
            // first; if it cannot be done atomically it throws and the old file is
            // preserved untouched.
            try {
                mover.move(temp, target)
            } catch (_: AtomicMoveNotSupportedException) {
                // Refuse to fall back to a delete+rename that would expose a window
                // where the target is absent. Old contents remain intact.
                return SecretFileWriteResult.Failure(SecretFileWriteError.IO_FAILED)
            }

            // Make the directory entry (the new name) durable.
            directorySync.sync(dir)
            return SecretFileWriteResult.Success
        } catch (_: IOException) {
            return SecretFileWriteResult.Failure(SecretFileWriteError.IO_FAILED)
        } catch (_: SecurityException) {
            return SecretFileWriteResult.Failure(SecretFileWriteError.IO_FAILED)
        } finally {
            // On success the temp was moved away; on any failure remove the residue.
            if (temp.exists()) temp.delete()
        }
    }

    /** Deletes the target file if present. Returns true if the path is now absent. */
    fun delete(): Boolean {
        if (!target.exists()) return true
        return target.delete()
    }

    /**
     * Rejects symlinks and path traversal. The canonical path of the target must
     * live directly inside the canonical path of its parent; a symlink (whose
     * canonical path differs from its absolute path) or a parent mismatch is
     * refused.
     */
    private fun isPathSafe(): Boolean {
        val dir = parent ?: return false
        return try {
            val canonicalParent = dir.canonicalFile
            val canonicalTarget = target.canonicalFile
            // The resolved target must sit immediately within the resolved parent.
            if (canonicalTarget.parentFile != canonicalParent) return false
            !isSymlink(target) && !isSymlink(dir)
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun isSymlink(file: File): Boolean {
        if (!file.exists()) return false
        return try {
            val canon = if (file.parent == null) file else File(file.parentFile!!.canonicalFile, file.name)
            canon.canonicalFile != canon.absoluteFile
        } catch (_: IOException) {
            true
        }
    }
}

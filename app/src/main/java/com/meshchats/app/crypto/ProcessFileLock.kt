package com.meshchats.app.crypto

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * A reusable exclusive lock scoped to a single filesystem path, serializing a
 * critical section across both threads of one process and across separate OS
 * processes.
 *
 * Two layers cooperate:
 *  - A JVM-wide per-canonical-path monitor. Two instances in the *same* process
 *    that target the same lock file must serialize before either tries to take
 *    the OS lock, because a second overlapping [FileLock] in one JVM throws
 *    [OverlappingFileLockException] rather than blocking.
 *  - An exclusive [FileLock] on a persistent `.lock` file, which blocks a second
 *    OS process.
 *
 * If OS-level file locking is unavailable (a filesystem or host JVM that does not
 * support it), the JVM monitor still serializes callers within this process and
 * [block] always runs exactly once. The lock file is created if absent and is
 * never deleted while the app runs, so the path→monitor mapping stays stable.
 */
object ProcessFileLock {

    private val monitors = ConcurrentHashMap<String, Any>()

    private fun monitorFor(lockFile: File): Any {
        val key = try {
            lockFile.canonicalPath
        } catch (_: IOException) {
            lockFile.absolutePath
        }
        return monitors.computeIfAbsent(key) { Any() }
    }

    /**
     * Runs [block] while holding the exclusive lock for [lockFile]. When
     * [useOsLock] is false only the in-process monitor is used (e.g. the lock
     * file path is unsafe), which still serializes within this process.
     */
    fun <T> withExclusiveLock(lockFile: File, useOsLock: Boolean = true, block: () -> T): T {
        synchronized(monitorFor(lockFile)) {
            var channel: FileChannel? = null
            var fileLock: FileLock? = null
            try {
                if (useOsLock) {
                    channel = FileChannel.open(
                        lockFile.toPath(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                    )
                    fileLock = channel.lock()
                }
            } catch (_: IOException) {
                // OS file locking unavailable; the JVM monitor still serializes this process.
            } catch (_: OverlappingFileLockException) {
                // Should not happen under the monitor, but never fail the caller over it.
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
}

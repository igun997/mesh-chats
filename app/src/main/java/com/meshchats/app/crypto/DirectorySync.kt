package com.meshchats.app.crypto

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor

/**
 * Forces a directory's own metadata (the entries created or replaced by a rename)
 * to stable storage.
 *
 * A file's bytes being fsync'd guarantees the bytes survive a crash, but the
 * *directory entry* that names the file is a separate object: after an atomic
 * rename, the new name is only durable once the containing directory is itself
 * synced. This interface isolates that platform-specific operation so it can be
 * exercised with a fake on the host JVM (where directory fsync is unavailable)
 * and implemented for real on-device.
 *
 * Implementations must be best-effort and must never throw: a failed directory
 * sync only risks the *rename* being lost on power failure (falling back to the
 * previous durable contents), never a torn or partially written file.
 */
fun interface DirectorySync {
    /** Best-effort durable sync of [dir]'s directory metadata. Must not throw. */
    fun sync(dir: File)
}

/**
 * Production [DirectorySync] that opens the directory read-only and fsyncs it via
 * [android.system.Os], the only supported way to fsync a directory on Android.
 *
 * `FileOutputStream(dir)` — the naive approach — cannot work: a directory cannot
 * be opened for writing, so it throws immediately and never actually syncs
 * anything. This implementation opens with `O_RDONLY | O_DIRECTORY` and calls
 * `fsync(2)` on the resulting descriptor, then always closes it. Every failure
 * (unsupported filesystem, `ErrnoException`, or the stubbed `Os` on a host JVM)
 * is swallowed as documented best-effort behavior.
 */
class AndroidDirectorySync : DirectorySync {
    override fun sync(dir: File) {
        if (!dir.isDirectory) return
        var fd: FileDescriptor? = null
        try {
            fd = Os.open(dir.absolutePath, OsConstants.O_RDONLY, 0)
            Os.fsync(fd)
        } catch (_: Throwable) {
            // ErrnoException on unsupported filesystems, or a "Stub!" RuntimeException
            // when android.system.Os runs against the host JVM's android.jar. The file
            // bytes are already fsync'd and the rename is atomic, so this is non-fatal.
        } finally {
            val open = fd
            if (open != null) {
                try {
                    Os.close(open)
                } catch (_: Throwable) {
                    // best effort
                }
            }
        }
    }
}

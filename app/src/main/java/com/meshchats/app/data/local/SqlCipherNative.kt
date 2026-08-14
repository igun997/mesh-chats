package com.meshchats.app.data.local

/**
 * Loads the SQLCipher native library exactly once for the process.
 *
 * `net.zetetic:sqlcipher-android` does **not** self-load its `.so` from any
 * static initializer (verified against the 4.17.0 artifact: no class calls
 * `System.loadLibrary`). The consuming app must load `libsqlcipher.so` itself
 * before touching any `net.zetetic.database.sqlcipher` class, or the first JNI
 * call crashes with `UnsatisfiedLinkError`. Doing it here — idempotently and
 * synchronized — means both the migration exporter and the Room open path share
 * one guaranteed-loaded library.
 */
object SqlCipherNative {

    @Volatile
    private var loaded = false

    /** Loads `libsqlcipher.so` once. Safe to call repeatedly and concurrently. */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary("sqlcipher")
            loaded = true
        }
    }
}

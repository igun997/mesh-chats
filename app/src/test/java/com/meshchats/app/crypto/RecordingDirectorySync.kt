package com.meshchats.app.crypto

import java.io.File

/**
 * A host-JVM [DirectorySync] that records the directories it was asked to sync,
 * standing in for the Android `Os.fsync` implementation (which is unavailable
 * off-device). Lets tests assert that a durable write fsyncs the containing
 * directory after the atomic move.
 */
class RecordingDirectorySync : DirectorySync {
    val synced: MutableList<File> = mutableListOf()

    override fun sync(dir: File) {
        synced.add(dir)
    }
}

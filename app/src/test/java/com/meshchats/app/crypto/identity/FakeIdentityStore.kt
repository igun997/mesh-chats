package com.meshchats.app.crypto.identity

/**
 * An in-memory [IdentityStore] modeling the atomic device+Signal insert on the
 * host JVM. [insertBoth] is all-or-nothing; [failWrite] / [failRead] inject
 * database faults, and [rowsInserted] counts commits so a test can assert that a
 * failed second attempt did not write again.
 */
class FakeIdentityStore : IdentityStore {

    private var device: StoredDeviceIdentity? = null
    private var signal: StoredSignalIdentity? = null

    var failWrite: Boolean = false
    var failRead: Boolean = false
    var rowsInserted: Int = 0
        private set

    /** Directly seed rows (e.g. to simulate DB-present/file-absent partial state). */
    fun seed(device: StoredDeviceIdentity?, signal: StoredSignalIdentity?) {
        this.device = device
        this.signal = signal
    }

    fun deviceRow(): StoredDeviceIdentity? = device
    fun signalRow(): StoredSignalIdentity? = signal

    override fun read(): IdentityReadResult {
        if (failRead) return IdentityReadResult.Failure(IdentityStoreError.STORAGE_FAILED)
        return IdentityReadResult.Success(StoredIdentity(device, signal))
    }

    override fun insertBoth(
        device: StoredDeviceIdentity,
        signal: StoredSignalIdentity,
    ): IdentityWriteResult {
        if (failWrite) return IdentityWriteResult.Failure(IdentityStoreError.STORAGE_FAILED)
        // Atomic: both land together.
        this.device = device
        this.signal = signal
        rowsInserted++
        return IdentityWriteResult.Success
    }
}

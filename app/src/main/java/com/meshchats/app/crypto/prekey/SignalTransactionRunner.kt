package com.meshchats.app.crypto.prekey

/**
 * Runs a block inside a single Room database transaction, returning its result.
 *
 * A thin port over `MeshDatabase.runInTransaction(Callable)` so [SignalPreKeyManager]
 * can provision an entire inventory batch (or snapshot a bundle) atomically without
 * importing Room, and so device tests can supply the real database while the block
 * runs on the crypto dispatcher. A block that throws rolls the whole transaction
 * back — the manager relies on this for its all-or-nothing provisioning contract.
 */
interface SignalTransactionRunner {
    fun <T> runInTransaction(block: () -> T): T
}

package com.meshchats.app.crypto.prekey

import com.meshchats.protocol.wire.PublishedPreKeyBundle

/**
 * A bounded reason a prekey-manager operation failed. Never carries key bytes,
 * SQL detail, or a raw libsignal exception.
 */
enum class PreKeyManagerError {
    /**
     * The device's own identity could not be obtained or the Signal local identity
     * row was missing/unreadable. Inventory work requires a provisioned identity.
     */
    IDENTITY_UNAVAILABLE,

    /** libsignal could not generate or sign fresh key material. */
    KEY_GENERATION_FAILED,

    /**
     * A stored record failed to parse, or a stored/generated signature failed to
     * verify — the local store is corrupt. Fails closed; nothing is published.
     */
    CORRUPT_STORE,

    /**
     * A collision-free positive id could not be drawn within the bounded attempt
     * budget, or repeated insertion conflicts could not be resolved within the
     * bounded retry budget. Nothing is persisted.
     */
    ID_EXHAUSTED,

    /** The database was unavailable or a transaction failed. Nothing is persisted. */
    STORAGE_FAILED,

    /**
     * A bundle was requested but the inventory has no publishable signed EC prekey
     * or no publishable Kyber prekey (neither one-time nor last-resort). This
     * indicates provisioning did not complete; the caller should retry ensure.
     */
    NO_PUBLISHABLE_KEY,
}

/** Result of ensuring / replenishing the local prekey inventory. */
sealed interface PreKeyEnsureResult {
    /**
     * The inventory now meets its targets. [generatedEcOneTime] /
     * [generatedKyberOneTime] / [generatedLastResort] / [generatedSigned] report
     * what this call minted (all zero / false when it was already healthy), so the
     * caller and tests can assert idempotence.
     */
    data class Success(
        val generatedEcOneTime: Int,
        val generatedKyberOneTime: Int,
        val generatedLastResort: Boolean,
        val generatedSigned: Boolean,
    ) : PreKeyEnsureResult

    data class Failure(val error: PreKeyManagerError) : PreKeyEnsureResult
}

/** Result of building a publishable PQXDH bundle. */
sealed interface PublishedBundleResult {
    data class Success(val bundle: PublishedPreKeyBundle) : PublishedBundleResult
    data class Failure(val error: PreKeyManagerError) : PublishedBundleResult
}

/**
 * App-owned owner of the device's PQXDH prekey inventory.
 *
 * Exposes only bounded suspend APIs and the `:mesh-protocol` [PublishedPreKeyBundle]
 * DTO; no UI or transport code imports libsignal through it. Every identity,
 * native-libsignal, and Room operation runs on the injected single-parallelism
 * crypto dispatcher, and the manager holds a [kotlinx.coroutines.sync.Mutex] so
 * two concurrent callers serialize rather than double-provision. All database
 * mutations happen inside one Room transaction via a [SignalTransactionRunner],
 * so a failed batch leaves no partial inventory.
 *
 * ## Idempotence scope (intra-process only)
 * [ensureInventory] tops the inventory back up to target only when a pool falls
 * below its threshold (see [PreKeyReplenishmentPlanner]); a second call on a
 * healthy inventory generates nothing. Existing used keys never count toward the
 * live pool, and an existing active signed key is reused (rotation is a later
 * task).
 *
 * This idempotence is guaranteed only **within a single process**: the manager
 * mutex plus the single-parallelism crypto dispatcher serialize all callers here.
 * Inserts use IGNORE-on-conflict and roll back on a same-id clash, so a concurrent
 * external writer can never *clobber* an existing key or its metadata. It can,
 * however, **overfill** a pool: two processes that each independently count "below
 * target" and draw disjoint random ids will both insert, because their ids do not
 * conflict and so neither insert is rejected. This app is currently single-process,
 * so that path does not arise. Any future multi-process design (e.g. a separate
 * key-service process) MUST coordinate provisioning through a shared lock — an
 * advisory DB lock or a dedicated single-writer — rather than relying on this
 * class; the conflict-retry here bounds clobbering, not double-provisioning.
 *
 * ## Publication
 * [createPublishedBundle] first ensures the inventory, then snapshots — in one
 * transaction — one one-time EC prekey (if any), the active signed prekey, an
 * unused one-time Kyber prekey (falling back to the reusable last-resort), and the
 * local identity/registration, building a [PublishedPreKeyBundle]. Publication does
 * NOT mark any key consumed. A used one-time Kyber key is never published; the
 * last-resort key may be used and reused and remains publishable.
 *
 * ### One-time EC keys may be published more than once (known limitation)
 * Because publication does not reserve or consume a key, repeated calls select the
 * **same** oldest one-time EC prekey (and the same unused one-time Kyber prekey)
 * until a peer actually establishes a session and libsignal removes it. That means
 * the same one-time key can be handed to several peers if the bundle is published
 * or distributed repeatedly before any first decrypt. Publication is deliberately
 * NOT treated as consumption at this layer — the store has no per-peer reservation
 * schema yet, and adding one is future work.
 *
 * The distribution layer above this manager therefore MUST:
 *  - request a **fresh** bundle per peer / per advertisement rather than caching
 *    and re-broadcasting one snapshot, so a new one-time key is picked each time
 *    the pool has surplus; and
 *  - treat a "stale / already-consumed one-time prekey" failure at session
 *    establishment as retryable — drop the one-time key and fall back to the
 *    signed + Kyber material (PQXDH still succeeds without the optional one-time
 *    EC key), or request a fresh bundle and retry.
 *
 * A per-peer one-time-key reservation schema that would make publication a true
 * consumption is out of scope for this task; the behavior above is the approved
 * interim contract.
 */
interface SignalPreKeyManager {

    /** Ensures the local prekey inventory meets its targets, replenishing if below threshold. */
    suspend fun ensureInventory(): PreKeyEnsureResult

    /** Ensures inventory, then builds a publishable PQXDH bundle from a consistent snapshot. */
    suspend fun createPublishedBundle(): PublishedBundleResult
}

package com.meshchats.app.startup

import com.meshchats.app.data.local.EncryptedDatabaseError
import com.meshchats.app.data.local.EncryptedDatabaseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A single seam that force-opens the encrypted database. Implementations must
 * actually open the underlying SQLCipher connection (not merely construct Room),
 * so a key-unavailable or migration failure surfaces here rather than lazily on
 * the first DAO call from the UI thread.
 */
fun interface DatabaseForceOpen {
    /**
     * Resolves and force-opens the database, throwing on failure.
     *
     * @throws EncryptedDatabaseException (possibly wrapped by Hilt/Provision) when
     * the key is unavailable or a plaintext migration failed.
     */
    suspend fun open()
}

/**
 * App-owned coordinator that brings encrypted storage online off the main thread
 * and publishes a bounded [DatabaseStartupState] the UI gate observes.
 */
interface DatabaseStartupCoordinator {
    /** The current startup state; starts at [DatabaseStartupState.Idle]. */
    val state: StateFlow<DatabaseStartupState>

    /**
     * Starts (or retries) the open attempt. Single-flight: while an attempt is
     * [DatabaseStartupState.Initializing], additional callers are no-ops that join
     * by observing [state] rather than each driving a fresh open, so a burst of
     * callers can never fan out into repeated opens. A no-op once
     * [DatabaseStartupState.Ready]. After a settled [DatabaseStartupState.Failed]
     * (or a cancellation-reset [DatabaseStartupState.Idle]), a fresh call drives a
     * genuine retry. Cancellation is honoured and never published as an UNEXPECTED
     * failure.
     */
    suspend fun initialize()
}

/**
 * Default coordinator. Force-opens on the injected IO [CoroutineDispatcher] and
 * uses a [Mutex] only to *claim* an attempt (the Idle/Failed -> Initializing
 * transition); the open itself runs with the lock released so concurrent callers
 * see [DatabaseStartupState.Initializing] and short-circuit instead of blocking or
 * re-driving. [DatabaseStartupState.Ready] is idempotent, and retry is allowed
 * once the state has settled to [DatabaseStartupState.Failed].
 *
 * Failure classification walks the cause chain with a bounded, cycle-safe loop and
 * maps the first [EncryptedDatabaseException] found to its [StorageStartupReason];
 * anything else becomes [StorageStartupReason.UNEXPECTED]. No exception text or key
 * material is ever read into the published state or logged.
 *
 * Cancellation semantics: if an attempt is cancelled mid-open, the state is reset
 * to [DatabaseStartupState.Idle] *before* the [CancellationException] is rethrown,
 * so the state is never left stuck in [DatabaseStartupState.Initializing]. A fresh
 * caller (e.g. a re-created [StorageStartupViewModel]) can then re-drive
 * [initialize] from a clean slate.
 */
class DefaultDatabaseStartupCoordinator(
    private val forceOpen: DatabaseForceOpen,
    private val ioDispatcher: CoroutineDispatcher,
) : DatabaseStartupCoordinator {

    private val _state = MutableStateFlow<DatabaseStartupState>(DatabaseStartupState.Idle)
    override val state: StateFlow<DatabaseStartupState> = _state.asStateFlow()

    // Guards only the attempt-claiming transition, so two callers can never both
    // start an open; it is not held across the (long) open itself.
    private val claimLock = Mutex()

    override suspend fun initialize() {
        // Fast path: an attempt is already running or has succeeded, so this caller
        // has nothing to drive and simply observes [state].
        if (isRunningOrReady(_state.value)) return

        val claimed = claimLock.withLock {
            // Re-check under the lock: a concurrent caller may have just claimed the
            // attempt or reached Ready while we waited on the lock.
            if (isRunningOrReady(_state.value)) {
                false
            } else {
                _state.value = DatabaseStartupState.Initializing
                true
            }
        }
        if (!claimed) return

        try {
            withContext(ioDispatcher) { forceOpen.open() }
            _state.value = DatabaseStartupState.Ready
        } catch (cancellation: CancellationException) {
            // Structured cancellation is not a storage failure. Reset to Idle BEFORE
            // rethrowing so the state is never stuck in Initializing and a fresh
            // caller can re-drive a clean attempt. Never publish UNEXPECTED here.
            _state.value = DatabaseStartupState.Idle
            throw cancellation
        } catch (t: Throwable) {
            _state.value = DatabaseStartupState.Failed(classify(t))
        }
    }

    // Ready is terminal-success; Initializing means an attempt is already in flight.
    // Both mean a new caller has nothing to claim.
    private fun isRunningOrReady(state: DatabaseStartupState): Boolean =
        state == DatabaseStartupState.Ready || state == DatabaseStartupState.Initializing

    private fun classify(error: Throwable): StorageStartupReason {
        var cur: Throwable? = error
        var depth = 0
        val seen = HashSet<Throwable>()
        while (cur != null && depth < MAX_CAUSE_DEPTH && seen.add(cur)) {
            if (cur is EncryptedDatabaseException) {
                return when (cur.reason) {
                    EncryptedDatabaseError.KEY_UNAVAILABLE -> StorageStartupReason.KEY_UNAVAILABLE
                    EncryptedDatabaseError.MIGRATION_FAILED -> StorageStartupReason.MIGRATION_FAILED
                }
            }
            cur = cur.cause
            depth++
        }
        return StorageStartupReason.UNEXPECTED
    }

    private companion object {
        // Bounded so a pathological or cyclic cause chain can never spin forever;
        // `seen` also guards cycles, this caps depth even for long acyclic chains.
        const val MAX_CAUSE_DEPTH = 32
    }
}

package com.meshchats.app.crypto

/**
 * Outcome of an app-level panic wipe. The three states are ordered by how much
 * the caller may honestly claim; the coordinator is deliberately conservative and
 * never reports a stronger state than it can prove.
 */
enum class PanicWipeOutcome {
    /**
     * BOTH persistent key domains (database key and identity key) are confirmed
     * destroyed AND every sensitive file was removed. Nothing recoverable and no
     * sensitive residue remains.
     */
    COMPLETE,

    /**
     * BOTH persistent key domains are confirmed destroyed — so all data at rest is
     * cryptographically **unrecoverable** — but some sensitive file could not be
     * removed (residue remains). The important security property (unrecoverability)
     * holds; only inert, undecryptable residue is left.
     */
    KEYS_DESTROYED_DATA_PARTIAL,

    /**
     * One or more persistent key domains could NOT be confirmed destroyed, so
     * wrapped data may still be recoverable. This is the fail-closed result and
     * must never be presented as a successful wipe.
     */
    FAILED,
}

/**
 * The result of a panic wipe, carrying the [outcome] plus enough per-domain detail
 * for a caller (or a later UI) to decide whether a process restart / retry is
 * needed. It never overstates success: [PanicWipeOutcome.COMPLETE] requires both
 * key domains destroyed and no file residue.
 */
data class PanicWipeReport(
    val outcome: PanicWipeOutcome,
    /** The database-key wrapping domain was confirmed destroyed. */
    val databaseKeyDestroyed: Boolean,
    /** The identity-key wrapping domain was confirmed destroyed. */
    val identityKeyDestroyed: Boolean,
    /** Every sensitive file was confirmed removed. */
    val filesRemoved: Boolean,
    /**
     * True if the coordinator could not fully close/zero the in-memory SQLCipher
     * raw key and a process restart is required to guarantee no key bytes linger
     * in RAM. Data at rest is still unrecoverable when the key domains are
     * destroyed; this flags only the in-memory residue.
     */
    val processRestartRequired: Boolean,
)

/**
 * App-level, key-first panic wipe. On a duress signal it makes persistent
 * decryption **impossible first** by destroying both wrapping-key domains (each
 * dedicated Keystore alias and its sole wrapped blob), and only then best-effort
 * deletes the now-undecryptable data files.
 *
 * Contract:
 * - **Key-first ordering** is mandatory: both key domains are attacked before any
 *   data file is touched. A crash after the first key destruction still leaves the
 *   data cryptographically unrecoverable, and a retry converges.
 * - **Never over-claims**: [PanicWipeOutcome.COMPLETE] is returned only when both
 *   key domains are confirmed destroyed and no sensitive file residue remains. If
 *   only the keys are gone, [PanicWipeOutcome.KEYS_DESTROYED_DATA_PARTIAL]. If any
 *   key domain may remain, [PanicWipeOutcome.FAILED].
 * - **Never regenerates** keys while wiping and never throws; all failures collapse
 *   into the returned [PanicWipeReport].
 * - **Serialized and idempotent**: concurrent or repeated calls are safe and a
 *   second call after a successful wipe still reports success (no-op convergence),
 *   while a no-op on an untouched install never reports [PanicWipeOutcome.COMPLETE]
 *   falsely — it reports success only because destruction is genuinely confirmed.
 */
fun interface PanicWipeCoordinator {
    /** Performs the key-first wipe. Never throws; returns a bounded [PanicWipeReport]. */
    fun wipe(): PanicWipeReport
}

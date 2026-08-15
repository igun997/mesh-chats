package com.meshchats.app.crypto

/**
 * The production database-close step for the panic wipe, extracted from the DI
 * wiring so it can be unit-tested on the host JVM (Dagger/Room/SQLCipher cannot
 * run there).
 *
 * ### Why this always returns [DatabaseCloseOutcome.RESTART_REQUIRED]
 * SQLCipher's `SupportOpenHelperFactory` retains the raw-key byte array by
 * reference for the database's lifetime and re-keys every connection from it (see
 * [com.meshchats.app.data.local.EncryptedDatabaseOpener]); that array is never
 * exposed for in-place zeroing. Closing the database releases the file handle —
 * which is what lets the subsequent file deletes remove the database — but it
 * does **not** guarantee the raw key bytes are gone from RAM. We therefore report
 * [DatabaseCloseOutcome.RESTART_REQUIRED] even on a clean close: data at rest is
 * unrecoverable once the wrapping keys are destroyed, but the caller MUST
 * terminate the process to clear the in-memory key. Reporting anything stronger
 * would over-claim.
 *
 * ### Contract
 * - [close] is invoked exactly once and its result is ignored; any exception it
 *   throws is swallowed. Closing must happen (best effort) so the file is
 *   released before the wipe deletes it, but a close failure never aborts the
 *   wipe and never changes the outcome (still [DatabaseCloseOutcome.RESTART_REQUIRED]).
 * - Never throws.
 */
object ProductionDatabaseClose {
    /**
     * Runs [close] (best effort, bounded) and returns
     * [DatabaseCloseOutcome.RESTART_REQUIRED] regardless of whether the close
     * succeeded or threw — the retained raw key can never be confirmed cleared
     * without a process restart.
     */
    fun run(close: () -> Unit): DatabaseCloseOutcome {
        try {
            close()
        } catch (_: Throwable) {
            // A failed close still leaves data unrecoverable (keys already gone) and
            // still requires a restart; swallow so the wipe proceeds to file deletes.
        }
        return DatabaseCloseOutcome.RESTART_REQUIRED
    }
}

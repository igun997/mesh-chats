package com.meshchats.app.crypto.identity

import com.meshchats.app.crypto.AtomicSecretFile

/** Outcome of a panic wipe. */
enum class PanicWipeResult {
    /**
     * The identity key file was removed AND the injected derived-state cleanup ran
     * successfully. Note this still does not mean every device secret is gone: see
     * the class KDoc — the Signal private key remains inside the SQLCipher database
     * until a later task wipes the database key and state.
     */
    WIPED,

    /**
     * The identity key file was removed but derived state was not fully cleared —
     * either no cleanup was injected, or the injected cleanup reported failure or
     * threw. The irreversible key deletion still happened; only the best-effort
     * cleanup is incomplete.
     */
    PARTIAL,
}

/**
 * Destroys the device's Ed25519 identity key material on a duress / panic signal.
 *
 * ## Key-first ordering
 *
 * The wrapped identity-secret file is deleted **first**, before anything else. It
 * holds the Ed25519 private key (and a wrapped copy of the Signal serialized key
 * pair). Deleting it destroys the **Ed25519 private key** copy that lives in this
 * file; once it is gone that copy is unrecoverable even if the process is killed
 * mid-wipe.
 *
 * ## What this does NOT yet destroy (honesty)
 *
 * Deleting this file alone does **not** make the whole identity cryptographically
 * unrecoverable. The Signal private key still lives inside the SQLCipher-encrypted
 * database, protected by the separate database Keystore alias. Until a later task
 * (Task 6) wipes the database key and the database state, that Signal private key
 * remains recoverable by anyone who can unlock the database key. This hook
 * therefore makes no claim of total, unrecoverable destruction — it performs the
 * one irreversible Ed25519-key step and defers full teardown to the injected
 * [clearDerivedState] and the later database wipe.
 *
 * This is the minimal key-first hook. Full duress UI, database-key destruction,
 * and Keystore alias deletion are wired in a later task; this interface exists so
 * those can call one authoritative, correctly ordered entry point.
 */
interface IdentityPanicWipe {
    /** Performs the key-first wipe. Never throws; returns a bounded [PanicWipeResult]. */
    fun wipe(): PanicWipeResult
}

/**
 * Default [IdentityPanicWipe] that deletes the wrapped identity-secret file first,
 * then runs a required best-effort [clearDerivedState] hook (DB rows, caches).
 *
 * There is deliberately **no successful default** for [clearDerivedState]. A hook
 * that trivially returned `true` would let [wipe] report [PanicWipeResult.WIPED]
 * while nothing derived was actually cleared — a false success on a security
 * operation. When no cleanup is injected, the derived state is by definition
 * uncleared, so [wipe] reports [PanicWipeResult.PARTIAL]: the irreversible key
 * deletion happened, but derived teardown did not. Callers that have real
 * derived-state teardown inject it and can then observe [PanicWipeResult.WIPED].
 */
class DefaultIdentityPanicWipe(
    private val secretFile: AtomicSecretFile,
    private val clearDerivedState: (() -> Boolean)? = null,
) : IdentityPanicWipe {

    override fun wipe(): PanicWipeResult {
        // 1) Irreversible: remove the wrapped Ed25519 private key. delete() returns
        //    true if the path is absent afterward (including "was never there").
        val keyGone = try {
            secretFile.delete()
        } catch (_: Throwable) {
            false
        }

        // 2) Best-effort cleanup of derived state. Absent an injected cleanup, we
        //    cannot honestly claim derived state was cleared -> PARTIAL.
        val cleanup = clearDerivedState
        val derivedCleared = if (cleanup == null) {
            false
        } else {
            try {
                cleanup()
            } catch (_: Throwable) {
                false
            }
        }

        return if (keyGone && derivedCleared) PanicWipeResult.WIPED else PanicWipeResult.PARTIAL
    }
}

package com.meshchats.app.crypto

/**
 * A bounded reason destroying a wrapping key (a Keystore alias) failed. Both are
 * "not confirmed absent" outcomes: after either, the caller must assume the
 * wrapping key *may still exist* and must never report the key domain destroyed.
 */
enum class WrappingKeyDeleteError {
    /** The backing key store could not be loaded or queried at all. */
    STORE_UNAVAILABLE,

    /**
     * The delete was attempted but the alias could not be confirmed absent
     * afterward (delete threw, or a post-delete existence check still found it).
     */
    DELETE_FAILED,
}

/**
 * Result of destroying a wrapping key. [Deleted] is the only outcome that lets a
 * caller treat the key as gone; crucially it also covers the idempotent case
 * where the alias was already absent, so a repeated wipe converges. Every failure
 * is a bounded [Failure] the caller must treat conservatively (key may remain).
 */
sealed interface WrappingKeyDeleteResult {
    /** The wrapping key is confirmed absent (deleted now, or never existed). */
    data object Deleted : WrappingKeyDeleteResult

    /** The wrapping key could not be confirmed absent. Carries a bounded reason. */
    data class Failure(val error: WrappingKeyDeleteError) : WrappingKeyDeleteResult
}

/**
 * Destroys the persistent, non-exportable wrapping key that protects one secret
 * domain (e.g. the database key alias, or the identity key alias). Destroying the
 * wrapping key makes every secret it ever wrapped **cryptographically
 * unrecoverable**, even if the wrapped blob still sits on disk: without the
 * Keystore key there is no way to unwrap it.
 *
 * This is the strongest single step in a panic wipe and must run **before** any
 * best-effort data deletion. Implementations must:
 * - be idempotent — destroying an already-absent key is [WrappingKeyDeleteResult.Deleted];
 * - never throw — every failure collapses to [WrappingKeyDeleteResult.Failure];
 * - never regenerate or recreate the key;
 * - report [WrappingKeyDeleteResult.Deleted] only when the key is *confirmed*
 *   absent afterward, so a coordinator never over-claims a completed wipe.
 */
fun interface KeyMaterialDestroyer {
    /** Destroys the wrapping key. Never throws; returns a bounded result. */
    fun destroy(): WrappingKeyDeleteResult
}

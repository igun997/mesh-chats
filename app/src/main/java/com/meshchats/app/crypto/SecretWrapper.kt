package com.meshchats.app.crypto

/**
 * A bounded reason wrapping a plaintext secret failed. Wrapping errors are
 * operational (the backing key store is unavailable or refused the operation),
 * never a property of attacker-controlled bytes.
 */
enum class SecretWrapError {
    /** The wrapping key could not be created or retrieved from the backing store. */
    KEY_UNAVAILABLE,

    /** The backing key store refused or failed the encrypt operation. */
    WRAP_FAILED,
}

/**
 * A bounded reason unwrapping a wrapped secret failed. These are the failure
 * modes callers must fail closed on: the record was tampered with (AEAD tag
 * mismatch), the non-exportable wrapping key is gone (device credential reset,
 * app data partially cleared), or the store is otherwise unavailable. None of
 * them is recoverable by regenerating the secret when a wrapped file already
 * exists.
 */
enum class SecretUnwrapError {
    /**
     * The wrapping key that produced this record no longer exists in the backing
     * store (e.g. the user cleared the lock screen credential, which permanently
     * invalidates Keystore keys). The wrapped secret is unrecoverable.
     */
    KEY_LOST,

    /**
     * AEAD authentication failed: the nonce, ciphertext, or bound associated data
     * was altered. The record must be treated as hostile, never as recoverable.
     */
    TAMPERED,

    /** The backing key store was unavailable or the decrypt operation failed for another reason. */
    UNWRAP_FAILED,
}

/**
 * Output of [SecretWrapper.wrap]. Carries the AEAD nonce and the AEAD ciphertext
 * (which embeds its own authentication tag). Both fields are opaque to callers
 * and are framed for storage by [WrappedSecretCodec]; nothing here is a plaintext
 * secret.
 */
class WrappedSecret(val nonce: ByteArray, val ciphertext: ByteArray)

/** Result of a wrap operation. [Failure] carries a bounded [SecretWrapError]. */
sealed interface WrapResult {
    data class Success(val wrapped: WrappedSecret) : WrapResult
    data class Failure(val error: SecretWrapError) : WrapResult
}

/**
 * Result of an unwrap operation. [Success] carries the recovered plaintext bytes,
 * which the caller owns and must zero after use. [Failure] carries a bounded
 * [SecretUnwrapError]; callers must fail closed and never regenerate a secret
 * whose wrapped file still exists.
 */
sealed interface UnwrapResult {
    data class Success(val plaintext: ByteArray) : UnwrapResult
    data class Failure(val error: SecretUnwrapError) : UnwrapResult
}

/**
 * Wraps and unwraps small secrets (a database key, a private key) using a
 * non-exportable device-backed key. Implementations must:
 *
 * - never expose or return the wrapping key material itself;
 * - bind [associatedData] into the AEAD so a wrapped secret cannot be replayed
 *   under a different logical purpose (domain separation);
 * - report every failure as a bounded [WrapResult.Failure] / [UnwrapResult.Failure]
 *   rather than leaking backing-store exception types to callers.
 *
 * Implementations must never log or stringify plaintext or key material.
 */
interface SecretWrapper {
    /**
     * Encrypts [plaintext] under the non-exportable wrapping key, binding
     * [associatedData] as AEAD associated data for domain separation. The caller
     * still owns [plaintext] and is responsible for zeroing it.
     */
    fun wrap(plaintext: ByteArray, associatedData: ByteArray): WrapResult

    /**
     * Decrypts a previously wrapped secret, requiring the same [associatedData]
     * used at wrap time. Returns [UnwrapResult.Failure] with [SecretUnwrapError.TAMPERED]
     * if authentication fails and [SecretUnwrapError.KEY_LOST] if the wrapping key
     * is gone.
     */
    fun unwrap(nonce: ByteArray, ciphertext: ByteArray, associatedData: ByteArray): UnwrapResult
}

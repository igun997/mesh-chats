package com.meshchats.app.crypto.session

import com.meshchats.protocol.wire.PublishedPreKeyBundle

/**
 * A bounded reason a [SignalCryptoEngine] operation failed. Each is a fixed enum
 * constant carrying no key bytes, plaintext, ciphertext, SQL detail, or raw
 * libsignal message — a failure can never leak secret material through a message,
 * log, or stack trace.
 */
enum class SignalCryptoError {
    /**
     * A caller-supplied input was rejected before any crypto or storage work:
     * empty/oversized plaintext or ciphertext, a bundle whose device id or
     * identity key does not match the verified peer, or a structurally invalid
     * argument. Nothing was read or written.
     */
    INVALID_INPUT,

    /** The device's own Signal identity could not be obtained or provisioned. */
    IDENTITY_UNAVAILABLE,

    /**
     * A remote bundle's or incoming PREKEY message's embedded identity key did not
     * match the verified peer's expected identity binding. Fails closed before any
     * session write, so the engine never trusts a substituted identity.
     */
    REMOTE_IDENTITY_MISMATCH,

    /** libsignal reported the remote identity as untrusted (a changed known key). */
    UNTRUSTED_IDENTITY,

    /** An operation required an established session and none existed. */
    NO_SESSION,

    /** The message was a duplicate the ratchet had already processed. */
    DUPLICATE_MESSAGE,

    /** The ciphertext was malformed, truncated, the wrong version, or legacy. */
    MALFORMED_MESSAGE,

    /** A referenced one-time / signed / Kyber prekey was absent (already consumed). */
    MISSING_PREKEY,

    /** A Kyber base key was reused — a replay of a prekey message. */
    REUSED_BASE_KEY,

    /** A stored record failed to parse; the local store is corrupt. */
    CORRUPT_STORE,

    /** The database was unavailable or a transaction failed. */
    STORAGE_UNAVAILABLE,

    /** libsignal or the native layer was unavailable, or an unexpected fault occurred. */
    CRYPTO_UNAVAILABLE,
}

/** Result of ensuring the local prekey inventory. */
sealed interface SignalEnsureResult {
    data object Success : SignalEnsureResult
    data class Failure(val error: SignalCryptoError) : SignalEnsureResult
}

/** Result of building a publishable PQXDH bundle. */
sealed interface SignalBundleResult {
    data class Success(val bundle: PublishedPreKeyBundle) : SignalBundleResult
    data class Failure(val error: SignalCryptoError) : SignalBundleResult
}

/** Result of establishing an outbound session from a verified remote bundle. */
sealed interface SignalSessionResult {
    data object Success : SignalSessionResult
    data class Failure(val error: SignalCryptoError) : SignalSessionResult
}

/** Result of encrypting plaintext to a typed serialized Signal ciphertext. */
sealed interface SignalEncryptResult {
    data class Success(val ciphertext: SignalCiphertext) : SignalEncryptResult
    data class Failure(val error: SignalCryptoError) : SignalEncryptResult
}

/** Result of decrypting a typed Signal ciphertext to recovered plaintext. */
sealed interface SignalDecryptResult {
    /**
     * Recovered plaintext. The constructor copies the supplied bytes and every
     * [plaintext] read yields a fresh copy, so neither the producer nor a prior
     * reader can mutate the payload another holder observes. [toString] reports
     * the size only and never the recovered bytes.
     */
    class Success(plaintext: ByteArray) : SignalDecryptResult {
        private val plaintextBytes: ByteArray = plaintext.copyOf()

        /** Fresh copy of the recovered plaintext, owned by the caller. */
        val plaintext: ByteArray get() = plaintextBytes.copyOf()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return plaintextBytes.contentEquals(other.plaintextBytes)
        }

        override fun hashCode(): Int = plaintextBytes.contentHashCode()

        /** Redacted summary: size only — never the recovered plaintext bytes. */
        override fun toString(): String = "SignalDecryptResult.Success(plaintext=${plaintextBytes.size}B)"
    }

    data class Failure(val error: SignalCryptoError) : SignalDecryptResult
}

/** Result of querying whether a trusted, established session exists for a peer. */
sealed interface SignalHasSessionResult {
    data class Success(val hasSession: Boolean) : SignalHasSessionResult
    data class Failure(val error: SignalCryptoError) : SignalHasSessionResult
}

/**
 * App-owned owner of libsignal 1:1 session state and Double Ratchet
 * encrypt/decrypt. Exposes only bounded suspend APIs and app-owned DTOs
 * ([VerifiedSignalPeer], [SignalCiphertext], [PublishedPreKeyBundle]); no UI or
 * transport code imports libsignal through it.
 *
 * ## Threading and atomicity
 * Every public API copies its mutable inputs before dispatch, runs on the
 * injected single-parallelism crypto dispatcher, and holds an engine-wide mutex so
 * two callers serialize. Every native `SessionBuilder` / `SessionCipher` operation
 * — together with all the synchronous store callbacks it triggers — runs inside a
 * single Room transaction, so a multi-callback operation (decrypt → load session →
 * consume prekey → store session) is atomic and rolls back on failure.
 *
 * ## Identity binding (no blind TOFU)
 * Session establishment and every incoming PREKEY message are checked against the
 * [VerifiedSignalPeer]'s expected identity key BEFORE any transactional session
 * write. WHISPER operations require a stored trusted identity for the address that
 * already matches the expected binding. The engine therefore never relies on
 * libsignal's trust-on-first-use for an unverified key.
 *
 * ## Secret handling
 * The engine logs nothing and stringifies no plaintext, key, or ciphertext.
 * Recovered plaintext is defensively copied out of libsignal.
 */
interface SignalCryptoEngine {

    /** Ensures the local prekey inventory meets its targets, replenishing if below threshold. */
    suspend fun ensureInventory(): SignalEnsureResult

    /** Ensures inventory, then builds a publishable PQXDH bundle from a consistent snapshot. */
    suspend fun createPublishedBundle(): SignalBundleResult

    /**
     * Establishes an outbound session to [peer] from a verified remote [bundle].
     * The bundle's device id and identity key must match [peer] exactly; otherwise
     * fails closed with [SignalCryptoError.REMOTE_IDENTITY_MISMATCH] before any
     * session write.
     */
    suspend fun establishSession(peer: VerifiedSignalPeer, bundle: PublishedPreKeyBundle): SignalSessionResult

    /** Encrypts [plaintext] to [peer], producing a typed [SignalCiphertext]. */
    suspend fun encrypt(peer: VerifiedSignalPeer, plaintext: ByteArray): SignalEncryptResult

    /** Decrypts [ciphertext] from [peer], recovering the plaintext. */
    suspend fun decrypt(peer: VerifiedSignalPeer, ciphertext: SignalCiphertext): SignalDecryptResult

    /** Reports whether a trusted, established session exists for [peer]. */
    suspend fun hasSession(peer: VerifiedSignalPeer): SignalHasSessionResult
}

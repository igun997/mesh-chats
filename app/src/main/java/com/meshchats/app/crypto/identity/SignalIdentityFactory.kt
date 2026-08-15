package com.meshchats.app.crypto.identity

/**
 * A freshly created or re-parsed Signal local identity. [serializedKeyPair] is the
 * opaque libsignal `IdentityKeyPair.serialize()` blob (it contains the Signal
 * **private** key and lives only inside the SQLCipher-encrypted database).
 * [publicIdentityBytes] is the opaque serialized public `IdentityKey`, which the
 * device binds its Ed25519 key to and publishes in the QR payload.
 */
class SignalLocalIdentity(
    val registrationId: Int,
    val serializedKeyPair: ByteArray,
    val publicIdentityBytes: ByteArray,
)

/** A bounded reason a Signal identity operation failed. */
enum class SignalIdentityError {
    /** libsignal could not generate a new identity or registration id. */
    GENERATE_FAILED,

    /** A stored serialized key pair could not be parsed back into a Signal identity. */
    PARSE_FAILED,
}

/** Result of creating or re-parsing a Signal local identity. */
sealed interface SignalIdentityResult {
    data class Success(val identity: SignalLocalIdentity) : SignalIdentityResult
    data class Failure(val error: SignalIdentityError) : SignalIdentityResult
}

/**
 * Creates and re-parses the device's Signal local identity via libsignal.
 *
 * This is a port so the platform-free identity core can be unit-tested on the
 * host JVM (where libsignal's native library is unavailable) with a fake, while
 * production uses the real libsignal 0.100.0 `IdentityKeyPair.generate()` and a
 * cryptographically random, in-range `registrationId` from
 * `KeyHelper.generateRegistrationId`.
 *
 * Implementations must never log or stringify the serialized key pair (it holds
 * the Signal private key).
 */
interface SignalIdentityFactory {

    /** Generates a new Signal local identity with a valid random registration id. */
    fun create(): SignalIdentityResult

    /**
     * Re-parses a previously stored [serializedKeyPair], returning the same public
     * identity bytes and registration id binding. Used on reopen to confirm the
     * stored Signal row is intact and matches the recorded binding.
     */
    fun parse(serializedKeyPair: ByteArray, registrationId: Int): SignalIdentityResult
}

package com.meshchats.app.crypto.identity

/**
 * A freshly generated Ed25519 key pair, in the standard encoded forms.
 *
 * [privatePkcs8] is the PKCS#8 DER encoding of the private key and is a **secret**:
 * it is wrapped by a device-backed key before it ever touches disk and is never
 * stored in the database, logged, or stringified. Callers must zero it after use.
 * [publicX509] is the X.509 SubjectPublicKeyInfo DER encoding of the public key
 * and is safe to store and share.
 */
class Ed25519KeyPair(val privatePkcs8: ByteArray, val publicX509: ByteArray)

/**
 * A bounded reason an Ed25519 operation failed. These are operational or
 * input-validation outcomes; none leaks a provider exception type to callers, and
 * a failure never causes the repository to regenerate identity over existing
 * state.
 */
enum class Ed25519Error {
    /** The isolated crypto provider could not be initialized or a key could not be generated. */
    PROVIDER_UNAVAILABLE,

    /** A supplied key encoding (PKCS#8 private or X.509 public) could not be parsed. */
    INVALID_KEY,

    /** A message or signature failed a bound (size/shape) check before the operation. */
    INVALID_INPUT,

    /** The signing or verification operation itself failed inside the provider. */
    OPERATION_FAILED,
}

/** Result of generating a key pair. */
sealed interface Ed25519GenerateResult {
    data class Success(val keyPair: Ed25519KeyPair) : Ed25519GenerateResult
    data class Failure(val error: Ed25519Error) : Ed25519GenerateResult
}

/** Result of deriving the X.509 public encoding from a PKCS#8 private encoding. */
sealed interface Ed25519DeriveResult {
    data class Success(val publicX509: ByteArray) : Ed25519DeriveResult {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Success && publicX509.contentEquals(other.publicX509))

        override fun hashCode(): Int = publicX509.contentHashCode()
    }

    data class Failure(val error: Ed25519Error) : Ed25519DeriveResult
}

/** Result of a signing operation. [Success] carries the 64-byte signature. */
sealed interface Ed25519SignResult {
    data class Success(val signature: ByteArray) : Ed25519SignResult {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Success && signature.contentEquals(other.signature))

        override fun hashCode(): Int = signature.contentHashCode()
    }

    data class Failure(val error: Ed25519Error) : Ed25519SignResult
}

/** Result of a verification. [Success] carries whether the signature verified. */
sealed interface Ed25519VerifyResult {
    data class Success(val valid: Boolean) : Ed25519VerifyResult
    data class Failure(val error: Ed25519Error) : Ed25519VerifyResult
}

/**
 * Ed25519 key generation, public-key derivation, and detached sign/verify over an
 * **isolated** crypto provider.
 *
 * The production implementation ([BouncyCastleEd25519Crypto]) constructs its own
 * `BouncyCastleProvider` instance and passes it explicitly to every JCA factory,
 * so it never calls `Security.addProvider` / `Security.insertProviderAt` and thus
 * never mutates the process-global provider list (which on Android is the
 * platform's AndroidKeyStore/Conscrypt set). This keeps the app's Ed25519 usage
 * from perturbing platform TLS or the Keystore.
 *
 * All methods are total and fail closed with a bounded [Ed25519Error]; none
 * throws, logs, or stringifies key material. Callers own and must zero any
 * private bytes they pass or receive.
 */
interface Ed25519Crypto {

    /** Generates a new key pair using the isolated provider and a secure RNG. */
    fun generate(): Ed25519GenerateResult

    /**
     * Derives the X.509 public encoding that corresponds to a PKCS#8 [privatePkcs8]
     * private key. Used on reopen to prove the wrapped private key still matches
     * the stored public key.
     */
    fun derivePublic(privatePkcs8: ByteArray): Ed25519DeriveResult

    /**
     * Produces a detached Ed25519 signature over [message] using [privatePkcs8].
     * [message] must be at most [MAX_MESSAGE_BYTES]; a larger message is refused
     * with [Ed25519Error.INVALID_INPUT]. The implementation copies [message]
     * defensively and does not retain it.
     */
    fun sign(privatePkcs8: ByteArray, message: ByteArray): Ed25519SignResult

    /**
     * Verifies a detached Ed25519 [signature] over [message] against the X.509
     * [publicX509] key. Returns [Ed25519VerifyResult.Success] with `false` for a
     * well-formed but non-matching signature; [Failure] only for malformed keys,
     * oversized input, or a provider fault.
     */
    fun verify(publicX509: ByteArray, message: ByteArray, signature: ByteArray): Ed25519VerifyResult

    companion object {
        /**
         * Hard upper bound on a message handed to [sign]/[verify]. Identity
         * payloads (bindings, QR payloads, challenge nonces) are small; a generous
         * ceiling keeps a caller bug or hostile input from feeding an unbounded
         * buffer to the signer.
         */
        const val MAX_MESSAGE_BYTES: Int = 64 * 1024

        /** A detached Ed25519 signature is always 64 bytes. */
        const val SIGNATURE_BYTES: Int = 64
    }
}

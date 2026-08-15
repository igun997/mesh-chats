package com.meshchats.app.crypto.identity

/**
 * The device's verified long-term identity, all public material.
 *
 * [fingerprintSha256] is the authoritative fingerprint: SHA-256 over
 * [edPublicX509]. [fourWords] is the short, non-authoritative convenience display
 * derived from the first 44 bits of the fingerprint (see [FourWordFingerprint]);
 * it must never be used as the sole basis for trusting an identity.
 */
class DeviceIdentity(
    val edPublicX509: ByteArray,
    val fingerprintSha256: ByteArray,
    val signalPublicBinding: ByteArray,
    val bindingSignature: ByteArray,
    val bindingVersion: Int,
    val createdAt: Long,
    val fourWords: List<String>,
) {
    /** The four-word display joined by [separator]; convenience only, not authoritative. */
    fun fourWordDisplay(separator: String = "-"): String = fourWords.joinToString(separator)
}

/**
 * A bounded reason an identity operation failed. Several of these are
 * **fail-closed, unrecoverable** states: the repository must surface them and
 * must never regenerate identity over any existing partial state.
 */
enum class DeviceIdentityError {
    /**
     * The wrapped identity-secret file exists but its wrapping key is gone: the
     * Keystore alias was deleted or the app's Keystore material was lost with its
     * app data. (The alias is not auth-bound, so a lock-screen credential change
     * does not cause this.) The private key is unrecoverable; the identity is
     * lost. NEVER regenerate over this.
     */
    KEY_LOST,

    /**
     * Stored identity state was tampered with or is internally inconsistent: the
     * wrapped file failed AEAD authentication, the recovered private key does not
     * derive the stored public key, the fingerprint does not match, the Signal row
     * does not parse, the binding public does not match the Signal public, or the
     * binding signature does not verify. Treated as hostile; NEVER regenerated.
     */
    TAMPERED,

    /** The Keystore / wrapping backend was unavailable. */
    WRAPPER_UNAVAILABLE,

    /** The identity-secret file could not be read or durably written. */
    STORAGE_FAILED,

    /** The isolated Ed25519 provider or libsignal was unavailable during creation. */
    CRYPTO_UNAVAILABLE,

    /** The database (device/Signal rows) was unavailable. */
    DATABASE_UNAVAILABLE,

    /**
     * A message handed to [DeviceIdentityRepository.sign] exceeded
     * [IdentityBinding.MAX_MESSAGE_BYTES] once framed. Refused before signing;
     * the message is never truncated to fit.
     */
    MESSAGE_TOO_LARGE,
}

/** Result of obtaining the device identity. */
sealed interface DeviceIdentityResult {
    data class Success(val identity: DeviceIdentity) : DeviceIdentityResult
    data class Failure(val error: DeviceIdentityError) : DeviceIdentityResult
}

/** Result of an identity signing operation. [Success] carries a detached signature. */
sealed interface IdentitySignResult {
    data class Success(val signature: ByteArray) : IdentitySignResult
    data class Failure(val error: DeviceIdentityError) : IdentitySignResult
}

/** Result of verifying a scanned QR payload. */
sealed interface ScannedIdentityResult {
    /** The payload was well-formed AND its fingerprint and binding signature verified. */
    data class Verified(val payload: IdentityQrPayload) : ScannedIdentityResult

    /** The payload could not be decoded or failed fingerprint/signature verification. */
    data class Rejected(val reason: ScannedIdentityRejection) : ScannedIdentityResult
}

/** Why a scanned payload was rejected. */
enum class ScannedIdentityRejection {
    /** The QR text was not a structurally valid payload. */
    MALFORMED,

    /** The carried fingerprint did not equal SHA-256 of the carried public key. */
    FINGERPRINT_MISMATCH,

    /** The binding signature did not verify against the carried keys. */
    SIGNATURE_INVALID,

    /** A crypto fault occurred while verifying (provider/key error). */
    VERIFY_FAILED,
}

/**
 * Owns the device's verified cryptographic identity: an Ed25519 key pair bound to
 * a Signal local identity, created exactly once and thereafter re-verified on
 * every open.
 *
 * ## Guarantees
 *
 * - **Create once.** The first [getOrCreateIdentity] generates the Ed25519 key
 *   (isolated Bouncy Castle provider), initializes the Signal local identity
 *   (libsignal), signs the canonical binding of the two public keys, wraps the
 *   Ed private key (with public recovery metadata) into the identity-secret file,
 *   and inserts the device + Signal rows in one DB transaction. All of this is
 *   serialized by a process/file lock so two racing callers produce exactly one
 *   identity.
 * - **Reopen verifies everything.** A subsequent open unwraps the private key,
 *   re-derives the public key and checks it matches the stored public, recomputes
 *   and checks the fingerprint, re-parses the Signal row, checks the stored
 *   binding public equals the Signal public, and verifies the binding signature.
 *   Any mismatch fails closed as [DeviceIdentityError.TAMPERED] or
 *   [DeviceIdentityError.KEY_LOST].
 * - **Never regenerate over partial state.** If the wrapped file exists, identity
 *   is only ever recovered from it. Key loss / tamper are reported, never healed
 *   by minting a new identity (which would silently orphan the old one).
 * - **Crash consistency.** The wrapped file embeds enough public metadata to
 *   finalize the DB rows if a crash landed the file before the DB commit. If the
 *   DB rows exist but the file is missing or unwrappable, that is key loss /
 *   tamper, never a reason to regenerate.
 *
 * The Ed25519 private key never touches the database, logs, or `toString`.
 */
interface DeviceIdentityRepository {

    /**
     * Returns the device identity, creating and persisting it once if absent, or
     * recovering and fully re-verifying it otherwise. Never throws; every failure
     * is a bounded [DeviceIdentityResult.Failure].
     */
    fun getOrCreateIdentity(): DeviceIdentityResult

    /**
     * Signs [message] with the device's Ed25519 key under the app-message domain
     * ([IdentityBinding.messagePayload]), so app signatures cannot be replayed as
     * binding signatures. [message] is bounded by [IdentityBinding.MAX_MESSAGE_BYTES]
     * (the signer's ceiling minus the message framing overhead); a larger message
     * fails closed with [DeviceIdentityError.MESSAGE_TOO_LARGE] and is never
     * truncated. Requires the identity to already exist / be recoverable.
     */
    fun sign(message: ByteArray): IdentitySignResult

    /**
     * Verifies a detached [signature] over [message] against a given X.509 public
     * key, under the same app-message domain used by [sign]. A message larger than
     * [IdentityBinding.MAX_MESSAGE_BYTES] cannot have been produced by [sign] and
     * returns `false` rather than throwing.
     */
    fun verify(publicKeyX509: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    /** The canonical QR / share payload for this device's identity. */
    fun qrPayload(): DeviceIdentityResult

    /**
     * Structurally decodes and cryptographically verifies a scanned QR payload:
     * fingerprint must match the carried public key and the binding signature must
     * verify. Returns [ScannedIdentityResult.Verified] only when both hold.
     */
    fun verifyScannedPayload(text: String): ScannedIdentityResult
}

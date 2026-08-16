package com.meshchats.app.crypto.session

/**
 * An app-owned description of a remote party a Signal session may be established
 * with, carrying only the material the engine needs to bind libsignal operations
 * to a verified identity. It exposes **no** libsignal type, so UI/transport code
 * can reference a peer without importing the crypto library.
 *
 * ## Fields
 * - [fingerprintSha256]: the peer's authoritative Ed25519 identity fingerprint
 *   (SHA-256, exactly [SignalProtocolName.FINGERPRINT_BYTES] bytes). The stable
 *   protocol name is derived canonically from this FULL fingerprint via
 *   [SignalProtocolName]; the four-word display, BLE id, and display name are
 *   never used.
 * - [deviceId]: the peer's libsignal device id, strictly positive.
 * - [expectedSignalIdentityKey]: the serialized libsignal `IdentityKey` public
 *   bytes the app has bound to this peer out of band. The engine checks incoming
 *   PREKEY messages and remote bundles against these exact bytes BEFORE any
 *   session write, so the engine never blindly trusts on first use.
 *
 * All byte inputs are copied on construction and every read, so a holder cannot
 * mutate stored bytes and a later mutation of the caller's array cannot change
 * this peer. [toString] reports sizes/ids and the derived name only — never key
 * bytes.
 */
class VerifiedSignalPeer(
    fingerprintSha256: ByteArray,
    val deviceId: Int,
    expectedSignalIdentityKey: ByteArray,
) {
    private val fingerprintBytes: ByteArray = fingerprintSha256.copyOf()
    private val expectedIdentityBytes: ByteArray = expectedSignalIdentityKey.copyOf()

    /** The stable libsignal protocol-address name, derived from the full fingerprint. */
    val protocolName: String

    init {
        require(fingerprintBytes.size == SignalProtocolName.FINGERPRINT_BYTES) {
            "fingerprint must be ${SignalProtocolName.FINGERPRINT_BYTES} bytes, was ${fingerprintBytes.size}"
        }
        require(deviceId > 0) { "device id must be positive, was $deviceId" }
        require(expectedIdentityBytes.isNotEmpty()) { "expected identity key must not be empty" }
        require(expectedIdentityBytes.size <= MAX_IDENTITY_KEY_BYTES) {
            "expected identity key exceeds $MAX_IDENTITY_KEY_BYTES bytes, was ${expectedIdentityBytes.size}"
        }
        protocolName = SignalProtocolName.fromFingerprint(fingerprintBytes)
    }

    /** Fresh copy of the authoritative fingerprint bytes. */
    val fingerprintSha256: ByteArray get() = fingerprintBytes.copyOf()

    /** Fresh copy of the expected serialized Signal identity public key bytes. */
    val expectedSignalIdentityKey: ByteArray get() = expectedIdentityBytes.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VerifiedSignalPeer) return false
        return deviceId == other.deviceId &&
            fingerprintBytes.contentEquals(other.fingerprintBytes) &&
            expectedIdentityBytes.contentEquals(other.expectedIdentityBytes)
    }

    override fun hashCode(): Int {
        var result = deviceId
        result = 31 * result + fingerprintBytes.contentHashCode()
        result = 31 * result + expectedIdentityBytes.contentHashCode()
        return result
    }

    /** Redacted summary: derived name, device id, and key size only — no key bytes. */
    override fun toString(): String =
        "VerifiedSignalPeer(" +
            "protocolName=$protocolName, " +
            "deviceId=$deviceId, " +
            "expectedSignalIdentityKey=${expectedIdentityBytes.size}B)"

    companion object {
        /**
         * Upper bound on the serialized identity key. A libsignal `IdentityKey` is
         * a 33-byte compressed EC public key; the ceiling refuses an unbounded blob
         * without constraining any legitimate key.
         */
        const val MAX_IDENTITY_KEY_BYTES: Int = 128
    }
}

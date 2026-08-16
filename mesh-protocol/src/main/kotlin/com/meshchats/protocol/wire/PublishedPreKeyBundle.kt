package com.meshchats.protocol.wire

/**
 * An app-independent, publishable PQXDH prekey bundle.
 *
 * This is the public material a peer needs to open an outbound Signal session:
 * registration/device identity, an optional one-time EC prekey, the signed EC
 * prekey and its signature, the long-term Signal identity key, the Kyber-1024
 * KEM prekey and its signature, and the time the bundle was issued.
 *
 * It deliberately carries **no** private key material, no contact/transport/BLE
 * identifiers, and nothing app-specific. It exists in `:mesh-protocol` so both
 * the Android app and a future relay can encode/decode it with the same
 * [PublishedPreKeyBundleCodec] and identical byte semantics.
 *
 * Every byte array is defensively copied on construction and on every read so a
 * holder cannot mutate the stored bytes. Equality is structural over all fields
 * (with array content compared by value); [toString] never reveals key or
 * signature bytes.
 *
 * The optional one-time EC prekey is represented canonically: either both
 * [oneTimePreKeyId] and [oneTimePreKeyPublic] are present, or neither is. Any
 * half-present combination is rejected at construction.
 */
class PublishedPreKeyBundle(
    val registrationId: Int,
    val deviceId: Int,
    oneTimePreKeyId: Int?,
    oneTimePreKeyPublic: ByteArray?,
    val signedPreKeyId: Int,
    signedPreKeyPublic: ByteArray,
    signedPreKeySignature: ByteArray,
    identityKey: ByteArray,
    val kyberPreKeyId: Int,
    kyberPreKeyPublic: ByteArray,
    kyberPreKeySignature: ByteArray,
    val issuedAtEpochMillis: Long,
) {
    /** The one-time EC prekey id, or null when no one-time prekey is published. */
    val oneTimePreKeyId: Int? = oneTimePreKeyId

    private val oneTimePreKeyPublicBytes: ByteArray? = oneTimePreKeyPublic?.copyOf()
    private val signedPreKeyPublicBytes: ByteArray = signedPreKeyPublic.copyOf()
    private val signedPreKeySignatureBytes: ByteArray = signedPreKeySignature.copyOf()
    private val identityKeyBytes: ByteArray = identityKey.copyOf()
    private val kyberPreKeyPublicBytes: ByteArray = kyberPreKeyPublic.copyOf()
    private val kyberPreKeySignatureBytes: ByteArray = kyberPreKeySignature.copyOf()

    init {
        val idPresent = oneTimePreKeyId != null
        val keyPresent = oneTimePreKeyPublic != null
        require(idPresent == keyPresent) {
            "one-time prekey id and public must both be present or both absent"
        }
    }

    /** True when this bundle publishes a one-time EC prekey. */
    val hasOneTimePreKey: Boolean get() = oneTimePreKeyId != null

    /** Fresh copy of the one-time EC prekey public bytes, or null when absent. */
    val oneTimePreKeyPublic: ByteArray? get() = oneTimePreKeyPublicBytes?.copyOf()

    /** Fresh copy of the signed EC prekey public bytes. */
    val signedPreKeyPublic: ByteArray get() = signedPreKeyPublicBytes.copyOf()

    /** Fresh copy of the signed EC prekey signature bytes. */
    val signedPreKeySignature: ByteArray get() = signedPreKeySignatureBytes.copyOf()

    /** Fresh copy of the Signal identity key bytes. */
    val identityKey: ByteArray get() = identityKeyBytes.copyOf()

    /** Fresh copy of the Kyber-1024 prekey public bytes. */
    val kyberPreKeyPublic: ByteArray get() = kyberPreKeyPublicBytes.copyOf()

    /** Fresh copy of the Kyber-1024 prekey signature bytes. */
    val kyberPreKeySignature: ByteArray get() = kyberPreKeySignatureBytes.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PublishedPreKeyBundle) return false
        return registrationId == other.registrationId &&
            deviceId == other.deviceId &&
            oneTimePreKeyId == other.oneTimePreKeyId &&
            arrayEq(oneTimePreKeyPublicBytes, other.oneTimePreKeyPublicBytes) &&
            signedPreKeyId == other.signedPreKeyId &&
            signedPreKeyPublicBytes.contentEquals(other.signedPreKeyPublicBytes) &&
            signedPreKeySignatureBytes.contentEquals(other.signedPreKeySignatureBytes) &&
            identityKeyBytes.contentEquals(other.identityKeyBytes) &&
            kyberPreKeyId == other.kyberPreKeyId &&
            kyberPreKeyPublicBytes.contentEquals(other.kyberPreKeyPublicBytes) &&
            kyberPreKeySignatureBytes.contentEquals(other.kyberPreKeySignatureBytes) &&
            issuedAtEpochMillis == other.issuedAtEpochMillis
    }

    override fun hashCode(): Int {
        var result = registrationId
        result = 31 * result + deviceId
        result = 31 * result + (oneTimePreKeyId ?: 0)
        result = 31 * result + (oneTimePreKeyPublicBytes?.contentHashCode() ?: 0)
        result = 31 * result + signedPreKeyId
        result = 31 * result + signedPreKeyPublicBytes.contentHashCode()
        result = 31 * result + signedPreKeySignatureBytes.contentHashCode()
        result = 31 * result + identityKeyBytes.contentHashCode()
        result = 31 * result + kyberPreKeyId
        result = 31 * result + kyberPreKeyPublicBytes.contentHashCode()
        result = 31 * result + kyberPreKeySignatureBytes.contentHashCode()
        result = 31 * result + issuedAtEpochMillis.hashCode()
        return result
    }

    /**
     * A redacted summary. Reports ids, sizes, and the one-time flag but never
     * emits key or signature bytes, so bundle logging cannot leak public
     * material into logs or crash reports.
     */
    override fun toString(): String =
        "PublishedPreKeyBundle(" +
            "registrationId=$registrationId, " +
            "deviceId=$deviceId, " +
            "hasOneTimePreKey=$hasOneTimePreKey, " +
            "oneTimePreKeyId=$oneTimePreKeyId, " +
            "oneTimePreKeyPublic=${oneTimePreKeyPublicBytes?.size ?: 0}B, " +
            "signedPreKeyId=$signedPreKeyId, " +
            "signedPreKeyPublic=${signedPreKeyPublicBytes.size}B, " +
            "signedPreKeySignature=${signedPreKeySignatureBytes.size}B, " +
            "identityKey=${identityKeyBytes.size}B, " +
            "kyberPreKeyId=$kyberPreKeyId, " +
            "kyberPreKeyPublic=${kyberPreKeyPublicBytes.size}B, " +
            "kyberPreKeySignature=${kyberPreKeySignatureBytes.size}B, " +
            "issuedAtEpochMillis=$issuedAtEpochMillis)"

    private fun arrayEq(a: ByteArray?, b: ByteArray?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> a.contentEquals(b)
    }
}

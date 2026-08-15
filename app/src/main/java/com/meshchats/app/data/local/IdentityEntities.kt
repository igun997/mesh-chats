package com.meshchats.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The device's own long-term identity metadata. Singleton: exactly one row,
 * pinned to [SINGLETON_ID].
 *
 * This row holds only PUBLIC material and self-signed binding metadata. The raw
 * Ed25519 private key is never stored here (nor anywhere in the database); it is
 * wrapped independently in a Keystore-protected file (Task 5). Keeping the private
 * key out of the encrypted database keeps the two secrets domain-separated: a
 * compromise of the database key alone never yields the signing key.
 */
@Entity(tableName = "device_identity")
data class DeviceIdentityEntity(
    /** Always [SINGLETON_ID]; enforces a single row via the primary key. */
    @PrimaryKey val id: Int = SINGLETON_ID,

    /** Ed25519 public key, X.509 SubjectPublicKeyInfo DER bytes. */
    @ColumnInfo(name = "public_key_x509", typeAffinity = ColumnInfo.BLOB)
    val publicKeyX509: ByteArray,

    /** SHA-256 over [publicKeyX509]; the stable identity fingerprint. */
    @ColumnInfo(name = "fingerprint_sha256", typeAffinity = ColumnInfo.BLOB)
    val fingerprintSha256: ByteArray,

    @ColumnInfo(name = "created_at") val createdAt: Long,

    /**
     * The Signal public identity key this device binds to, if a Signal identity
     * has been provisioned. Opaque public bytes; null until bound.
     */
    @ColumnInfo(name = "signal_public_binding", typeAffinity = ColumnInfo.BLOB)
    val signalPublicBinding: ByteArray? = null,

    /**
     * Ed25519 signature over the Signal binding (the device attests the Signal
     * identity belongs to it). Opaque; null until bound.
     */
    @ColumnInfo(name = "signal_binding_signature", typeAffinity = ColumnInfo.BLOB)
    val signalBindingSignature: ByteArray? = null,

    /** Version of the binding scheme, so the format can evolve without a migration. */
    @ColumnInfo(name = "binding_version") val bindingVersion: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceIdentityEntity) return false
        return id == other.id &&
            publicKeyX509.contentEquals(other.publicKeyX509) &&
            fingerprintSha256.contentEquals(other.fingerprintSha256) &&
            createdAt == other.createdAt &&
            (signalPublicBinding?.contentEquals(other.signalPublicBinding) ?: (other.signalPublicBinding == null)) &&
            (signalBindingSignature?.contentEquals(other.signalBindingSignature)
                ?: (other.signalBindingSignature == null)) &&
            bindingVersion == other.bindingVersion
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + publicKeyX509.contentHashCode()
        result = 31 * result + fingerprintSha256.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (signalPublicBinding?.contentHashCode() ?: 0)
        result = 31 * result + (signalBindingSignature?.contentHashCode() ?: 0)
        result = 31 * result + bindingVersion
        return result
    }

    companion object {
        const val SINGLETON_ID: Int = 1
    }
}

/**
 * A remote contact device's identity and current trust state. Keyed by the stable
 * [address] (contact/device address string), which is what the outbox and Signal
 * stores reference.
 */
@Entity(
    tableName = "contact_identities",
    indices = [Index(value = ["trust_state"])],
)
data class ContactIdentityEntity(
    /** Stable contact/device address. Never a rotating BLE ephemeral id. */
    @PrimaryKey val address: String,

    /** The contact's public identity key. Opaque public bytes. */
    @ColumnInfo(name = "public_key", typeAffinity = ColumnInfo.BLOB)
    val publicKey: ByteArray,

    /** SHA-256 over [publicKey]. */
    @ColumnInfo(name = "fingerprint_sha256", typeAffinity = ColumnInfo.BLOB)
    val fingerprintSha256: ByteArray,

    /** One of [TrustState] names: UNVERIFIED / VERIFIED / REVOKED. */
    @ColumnInfo(name = "trust_state") val trustState: String,

    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,

    /** When the contact was verified out-of-band, or null if never. */
    @ColumnInfo(name = "verified_at") val verifiedAt: Long? = null,

    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactIdentityEntity) return false
        return address == other.address &&
            publicKey.contentEquals(other.publicKey) &&
            fingerprintSha256.contentEquals(other.fingerprintSha256) &&
            trustState == other.trustState &&
            firstSeenAt == other.firstSeenAt &&
            verifiedAt == other.verifiedAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + fingerprintSha256.contentHashCode()
        result = 31 * result + trustState.hashCode()
        result = 31 * result + firstSeenAt.hashCode()
        result = 31 * result + (verifiedAt?.hashCode() ?: 0)
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

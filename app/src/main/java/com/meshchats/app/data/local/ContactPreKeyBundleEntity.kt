package com.meshchats.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The most-recent verified pre-key bundle received from a contact, keyed by the
 * contact's stable [contactAddress]. One bundle per contact: a fresher bundle
 * replaces the previous one (the address is the primary key).
 *
 * [encodedBundle] is the opaque, byte-for-byte encoded pre-key bundle (public
 * material only — one-time/signed/kyber public prekeys and signatures). It carries
 * no private material, but it is still stored inside the SQLCipher-encrypted
 * database so a device compromise does not leak who a user is talking to or which
 * key material they exchanged. The app never interprets these bytes here;
 * [schemaVersion] lets the encoding evolve without a Room migration.
 *
 * The contact address carries a foreign key to `contact_identities` with cascade
 * delete: a bundle never outlives the contact it belongs to.
 */
@Entity(
    tableName = "contact_prekey_bundles",
    foreignKeys = [
        ForeignKey(
            entity = ContactIdentityEntity::class,
            parentColumns = ["address"],
            childColumns = ["contact_address"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // Supports deleteExpired() sweeps and expiry-ordered reads.
        Index(value = ["expires_at"]),
        // Supports per-device lookups/audits.
        Index(value = ["device_id"]),
    ],
)
data class ContactPreKeyBundleEntity(
    /** Stable contact address; the FK/PK back to `contact_identities.address`. */
    @PrimaryKey @ColumnInfo(name = "contact_address") val contactAddress: String,

    /** The contact device this bundle was issued for. */
    @ColumnInfo(name = "device_id") val deviceId: Int,

    /** Opaque, byte-for-byte encoded public pre-key bundle. */
    @ColumnInfo(name = "encoded_bundle", typeAffinity = ColumnInfo.BLOB)
    val encodedBundle: ByteArray,

    /** When the contact issued the bundle (their clock), if carried in the bundle. */
    @ColumnInfo(name = "issued_at") val issuedAt: Long,

    /** When this device received and stored the bundle (our clock). */
    @ColumnInfo(name = "received_at") val receivedAt: Long,

    /** When the bundle expires and should no longer be used to start a session. */
    @ColumnInfo(name = "expires_at") val expiresAt: Long,

    /** Version of the [encodedBundle] encoding, so the format can evolve. */
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactPreKeyBundleEntity) return false
        return contactAddress == other.contactAddress &&
            deviceId == other.deviceId &&
            encodedBundle.contentEquals(other.encodedBundle) &&
            issuedAt == other.issuedAt &&
            receivedAt == other.receivedAt &&
            expiresAt == other.expiresAt &&
            schemaVersion == other.schemaVersion
    }

    override fun hashCode(): Int {
        var result = contactAddress.hashCode()
        result = 31 * result + deviceId
        result = 31 * result + encodedBundle.contentHashCode()
        result = 31 * result + issuedAt.hashCode()
        result = 31 * result + receivedAt.hashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + schemaVersion
        return result
    }

    /**
     * Redacted: never dumps the encoded bundle bytes (only its length). Even though
     * the bundle is public material, leaking it into logs would reveal contact
     * relationships and key material, so [toString] reports metadata only.
     */
    override fun toString(): String =
        "ContactPreKeyBundleEntity(contactAddress=$contactAddress, deviceId=$deviceId, " +
            "encodedBundle=<${encodedBundle.size} bytes>, issuedAt=$issuedAt, " +
            "receivedAt=$receivedAt, expiresAt=$expiresAt, schemaVersion=$schemaVersion)"
}

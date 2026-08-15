package com.meshchats.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The device's Signal local identity. Singleton: exactly one row, pinned to
 * [SINGLETON_ID].
 *
 * The [identityKeyPair] blob is the opaque serialized libsignal `IdentityKeyPair`
 * (it includes the Signal private key). It lives inside the SQLCipher-encrypted
 * database; the app never interprets its bytes. [schemaVersion] lets the
 * serialization format evolve without a Room migration.
 */
@Entity(tableName = "signal_identity")
data class SignalIdentityEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,

    /** libsignal registration id. */
    @ColumnInfo(name = "registration_id") val registrationId: Int,

    /** Opaque serialized libsignal IdentityKeyPair. */
    @ColumnInfo(name = "identity_key_pair", typeAffinity = ColumnInfo.BLOB)
    val identityKeyPair: ByteArray,

    @ColumnInfo(name = "schema_version") val schemaVersion: Int,

    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignalIdentityEntity) return false
        return id == other.id &&
            registrationId == other.registrationId &&
            identityKeyPair.contentEquals(other.identityKeyPair) &&
            schemaVersion == other.schemaVersion &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + registrationId
        result = 31 * result + identityKeyPair.contentHashCode()
        result = 31 * result + schemaVersion
        result = 31 * result + createdAt.hashCode()
        return result
    }

    companion object {
        const val SINGLETON_ID: Int = 1
    }
}

/**
 * A trusted remote Signal identity key, as stored by libsignal's
 * `IdentityKeyStore`. Keyed by the protocol address `name` plus `device_id`.
 * The [identityKey] blob is the opaque serialized libsignal `IdentityKey`.
 *
 * This is distinct from [ContactIdentityEntity]: contacts carry app-level trust
 * state and Ed25519 identity, while this table is the byte-for-byte backing store
 * for the Signal protocol adapter (next task). Keeping them separate avoids the
 * app reinterpreting Signal's opaque bytes as its own trust model.
 */
@Entity(
    tableName = "signal_trusted_identities",
    primaryKeys = ["name", "device_id"],
)
data class SignalTrustedIdentityEntity(
    val name: String,

    @ColumnInfo(name = "device_id") val deviceId: Int,

    /** Opaque serialized libsignal IdentityKey (public). */
    @ColumnInfo(name = "identity_key", typeAffinity = ColumnInfo.BLOB)
    val identityKey: ByteArray,

    @ColumnInfo(name = "schema_version") val schemaVersion: Int,

    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignalTrustedIdentityEntity) return false
        return name == other.name &&
            deviceId == other.deviceId &&
            identityKey.contentEquals(other.identityKey) &&
            schemaVersion == other.schemaVersion &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + deviceId
        result = 31 * result + identityKey.contentHashCode()
        result = 31 * result + schemaVersion
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

/**
 * A libsignal session record, keyed by protocol address `name` + `device_id`.
 * [record] is the opaque serialized libsignal `SessionRecord`.
 */
@Entity(
    tableName = "signal_sessions",
    primaryKeys = ["name", "device_id"],
)
data class SignalSessionEntity(
    val name: String,

    @ColumnInfo(name = "device_id") val deviceId: Int,

    @ColumnInfo(name = "record", typeAffinity = ColumnInfo.BLOB)
    val record: ByteArray,

    @ColumnInfo(name = "schema_version") val schemaVersion: Int,

    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignalSessionEntity) return false
        return name == other.name &&
            deviceId == other.deviceId &&
            record.contentEquals(other.record) &&
            schemaVersion == other.schemaVersion &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + deviceId
        result = 31 * result + record.contentHashCode()
        result = 31 * result + schemaVersion
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

/**
 * A one-time prekey, keyed by libsignal prekey id. [record] is the opaque
 * serialized `PreKeyRecord`.
 */
@Entity(tableName = "signal_prekeys")
data class SignalPreKeyEntity(
    @PrimaryKey @ColumnInfo(name = "prekey_id") val preKeyId: Int,

    @ColumnInfo(name = "record", typeAffinity = ColumnInfo.BLOB)
    val record: ByteArray,

    @ColumnInfo(name = "schema_version") val schemaVersion: Int,

    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignalPreKeyEntity) return false
        return preKeyId == other.preKeyId &&
            record.contentEquals(other.record) &&
            schemaVersion == other.schemaVersion &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = preKeyId
        result = 31 * result + record.contentHashCode()
        result = 31 * result + schemaVersion
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

/**
 * A signed prekey, keyed by libsignal signed-prekey id. [record] is the opaque
 * serialized `SignedPreKeyRecord`.
 */
@Entity(tableName = "signal_signed_prekeys")
data class SignalSignedPreKeyEntity(
    @PrimaryKey @ColumnInfo(name = "signed_prekey_id") val signedPreKeyId: Int,

    @ColumnInfo(name = "record", typeAffinity = ColumnInfo.BLOB)
    val record: ByteArray,

    @ColumnInfo(name = "schema_version") val schemaVersion: Int,

    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignalSignedPreKeyEntity) return false
        return signedPreKeyId == other.signedPreKeyId &&
            record.contentEquals(other.record) &&
            schemaVersion == other.schemaVersion &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = signedPreKeyId
        result = 31 * result + record.contentHashCode()
        result = 31 * result + schemaVersion
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

/**
 * A Kyber prekey (post-quantum), keyed by libsignal kyber-prekey id. [record] is
 * the opaque serialized `KyberPreKeyRecord`. [used] and [lastResort] carry the
 * lifecycle metadata libsignal's `KyberPreKeyStore` needs.
 */
@Entity(tableName = "signal_kyber_prekeys")
data class SignalKyberPreKeyEntity(
    @PrimaryKey @ColumnInfo(name = "kyber_prekey_id") val kyberPreKeyId: Int,

    @ColumnInfo(name = "record", typeAffinity = ColumnInfo.BLOB)
    val record: ByteArray,

    /** True once consumed; libsignal marks one-time kyber prekeys used. */
    @ColumnInfo(name = "used") val used: Boolean,

    /** Last-resort kyber prekeys are reusable and never deleted on use. */
    @ColumnInfo(name = "last_resort") val lastResort: Boolean,

    @ColumnInfo(name = "schema_version") val schemaVersion: Int,

    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignalKyberPreKeyEntity) return false
        return kyberPreKeyId == other.kyberPreKeyId &&
            record.contentEquals(other.record) &&
            used == other.used &&
            lastResort == other.lastResort &&
            schemaVersion == other.schemaVersion &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = kyberPreKeyId
        result = 31 * result + record.contentHashCode()
        result = 31 * result + used.hashCode()
        result = 31 * result + lastResort.hashCode()
        result = 31 * result + schemaVersion
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

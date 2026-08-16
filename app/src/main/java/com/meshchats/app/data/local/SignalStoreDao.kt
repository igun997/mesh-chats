package com.meshchats.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Backing store for libsignal's `IdentityKeyStore` — both the local identity
 * (singleton) and the trusted remote identities. Operations mirror the store
 * adapter's needs: load/store/contains.
 */
@Dao
interface SignalIdentityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLocal(identity: SignalIdentityEntity)

    @Query("SELECT * FROM signal_identity WHERE id = :id LIMIT 1")
    suspend fun getLocal(id: Int = SignalIdentityEntity.SINGLETON_ID): SignalIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTrusted(identity: SignalTrustedIdentityEntity)

    @Query("SELECT * FROM signal_trusted_identities WHERE name = :name AND device_id = :deviceId LIMIT 1")
    suspend fun getTrusted(name: String, deviceId: Int): SignalTrustedIdentityEntity?

    @Query("SELECT COUNT(*) FROM signal_trusted_identities WHERE name = :name AND device_id = :deviceId")
    suspend fun countTrusted(name: String, deviceId: Int): Int

    suspend fun containsTrusted(name: String, deviceId: Int): Boolean =
        countTrusted(name, deviceId) > 0

    @Query("DELETE FROM signal_trusted_identities WHERE name = :name AND device_id = :deviceId")
    suspend fun deleteTrusted(name: String, deviceId: Int)
}

/**
 * Backing store for libsignal's `SessionStore`. Address = name + device id.
 */
@Dao
interface SignalSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun store(session: SignalSessionEntity)

    @Query("SELECT * FROM signal_sessions WHERE name = :name AND device_id = :deviceId LIMIT 1")
    suspend fun load(name: String, deviceId: Int): SignalSessionEntity?

    @Query("SELECT device_id FROM signal_sessions WHERE name = :name ORDER BY device_id ASC")
    suspend fun deviceIdsFor(name: String): List<Int>

    @Query("SELECT COUNT(*) FROM signal_sessions WHERE name = :name AND device_id = :deviceId")
    suspend fun count(name: String, deviceId: Int): Int

    suspend fun contains(name: String, deviceId: Int): Boolean = count(name, deviceId) > 0

    @Query("DELETE FROM signal_sessions WHERE name = :name AND device_id = :deviceId")
    suspend fun delete(name: String, deviceId: Int)

    @Query("DELETE FROM signal_sessions WHERE name = :name")
    suspend fun deleteAllFor(name: String)
}

/**
 * Backing store for libsignal's `PreKeyStore`.
 */
@Dao
interface SignalPreKeyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun store(preKey: SignalPreKeyEntity)

    @Query("SELECT * FROM signal_prekeys WHERE prekey_id = :id LIMIT 1")
    suspend fun load(id: Int): SignalPreKeyEntity?

    @Query("SELECT prekey_id FROM signal_prekeys ORDER BY prekey_id ASC")
    suspend fun allIds(): List<Int>

    @Query("SELECT COUNT(*) FROM signal_prekeys WHERE prekey_id = :id")
    suspend fun count(id: Int): Int

    suspend fun contains(id: Int): Boolean = count(id) > 0

    @Query("DELETE FROM signal_prekeys WHERE prekey_id = :id")
    suspend fun delete(id: Int)
}

/**
 * Backing store for libsignal's `SignedPreKeyStore`.
 */
@Dao
interface SignalSignedPreKeyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun store(signedPreKey: SignalSignedPreKeyEntity)

    @Query("SELECT * FROM signal_signed_prekeys WHERE signed_prekey_id = :id LIMIT 1")
    suspend fun load(id: Int): SignalSignedPreKeyEntity?

    @Query("SELECT * FROM signal_signed_prekeys ORDER BY signed_prekey_id ASC")
    suspend fun loadAll(): List<SignalSignedPreKeyEntity>

    @Query("SELECT signed_prekey_id FROM signal_signed_prekeys ORDER BY signed_prekey_id ASC")
    suspend fun allIds(): List<Int>

    @Query("SELECT COUNT(*) FROM signal_signed_prekeys WHERE signed_prekey_id = :id")
    suspend fun count(id: Int): Int

    suspend fun contains(id: Int): Boolean = count(id) > 0

    @Query("DELETE FROM signal_signed_prekeys WHERE signed_prekey_id = :id")
    suspend fun delete(id: Int)
}

/**
 * Backing store for libsignal's `KyberPreKeyStore`.
 */
@Dao
interface SignalKyberPreKeyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun store(kyberPreKey: SignalKyberPreKeyEntity)

    @Query("SELECT * FROM signal_kyber_prekeys WHERE kyber_prekey_id = :id LIMIT 1")
    suspend fun load(id: Int): SignalKyberPreKeyEntity?

    @Query("SELECT * FROM signal_kyber_prekeys ORDER BY kyber_prekey_id ASC")
    suspend fun loadAll(): List<SignalKyberPreKeyEntity>

    @Query("SELECT kyber_prekey_id FROM signal_kyber_prekeys ORDER BY kyber_prekey_id ASC")
    suspend fun allIds(): List<Int>

    @Query("SELECT COUNT(*) FROM signal_kyber_prekeys WHERE kyber_prekey_id = :id")
    suspend fun count(id: Int): Int

    suspend fun contains(id: Int): Boolean = count(id) > 0

    // Consuming a one-time Kyber prekey also clears any recipient reservation (v4),
    // freeing its slot. Last-resort keys are never per-recipient reserved, so this
    // is a no-op for them and preserves their reusable last-resort semantics.
    @Query(
        "UPDATE signal_kyber_prekeys SET used = 1, " +
            "reserved_for_address = NULL, reserved_for_device_id = NULL, reserved_at = NULL " +
            "WHERE kyber_prekey_id = :id",
    )
    suspend fun markUsed(id: Int)

    @Query("DELETE FROM signal_kyber_prekeys WHERE kyber_prekey_id = :id")
    suspend fun delete(id: Int)
}

package com.meshchats.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Blocking (synchronous) DAO dedicated to libsignal store callbacks.
 *
 * libsignal's `IdentityKeyStore` / `SessionStore` / `PreKeyStore` /
 * `SignedPreKeyStore` / `KyberPreKeyStore` callbacks are **synchronous**: the
 * native protocol code invokes them and expects a value back on the same thread,
 * with no suspension point available. The app's ordinary DAOs are `suspend` and
 * serve the coroutine-based app layer; converting them would force a `runBlocking`
 * bridge inside every native callback. Instead this DAO exposes plain blocking
 * methods over the same Signal tables, so [RoomSignalProtocolStore] can serve a
 * synchronous callback with a direct call and no coroutine bridge.
 *
 * ## Threading contract
 * Every method **must** be called:
 *  1. off the main thread, **and**
 *  2. on the dedicated single-parallelism crypto dispatcher, **and**
 *  3. inside an outer Room transaction opened by that dispatcher via
 *     [runInTransaction], so a multi-call libsignal operation (e.g. a decrypt that
 *     loads a session, consumes a prekey, and stores the ratcheted session) is
 *     atomic and sees a consistent snapshot.
 *
 * Only obligation (1) is enforced at this layer: Room's `allowMainThreadQueries`
 * is *not* set, so a main-thread call throws. Obligations (2) and (3) are
 * **caller contracts that this DAO cannot verify from inside a blocking query** —
 * a query has no view of which dispatcher invoked it or whether a transaction is
 * open. They are owned and enforced/tested by the engine layer (Task 5), which is
 * the sole caller and opens the single dispatcher + outer transaction. Do not read
 * this KDoc as a guarantee that this class guards single-dispatcher or transaction
 * discipline.
 *
 * This DAO never converts the existing suspend app DAOs; it is an additive,
 * app-internal surface. It logs nothing and never stringifies a record blob.
 */
@Dao
abstract class BlockingSignalStoreDao {

    // --- Local identity (singleton) ----------------------------------------

    @Query("SELECT * FROM signal_identity WHERE id = :id LIMIT 1")
    abstract fun localIdentity(id: Int = SignalIdentityEntity.SINGLETON_ID): SignalIdentityEntity?

    // --- Trusted remote identities -----------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsertTrustedIdentity(identity: SignalTrustedIdentityEntity)

    @Query("SELECT * FROM signal_trusted_identities WHERE name = :name AND device_id = :deviceId LIMIT 1")
    abstract fun trustedIdentity(name: String, deviceId: Int): SignalTrustedIdentityEntity?

    // --- Sessions ----------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsertSession(session: SignalSessionEntity)

    @Query("SELECT * FROM signal_sessions WHERE name = :name AND device_id = :deviceId LIMIT 1")
    abstract fun session(name: String, deviceId: Int): SignalSessionEntity?

    @Query("SELECT COUNT(*) FROM signal_sessions WHERE name = :name AND device_id = :deviceId")
    abstract fun sessionCount(name: String, deviceId: Int): Int

    /**
     * Device ids of every stored session for [name] **except** device 1 (the
     * primary device), sorted ascending — exactly libsignal's
     * `getSubDeviceSessions` contract, which excludes the primary and returns the
     * linked sub-device ids.
     */
    @Query("SELECT device_id FROM signal_sessions WHERE name = :name AND device_id != 1 ORDER BY device_id ASC")
    abstract fun subDeviceIds(name: String): List<Int>

    @Query("DELETE FROM signal_sessions WHERE name = :name AND device_id = :deviceId")
    abstract fun deleteSession(name: String, deviceId: Int)

    @Query("DELETE FROM signal_sessions WHERE name = :name")
    abstract fun deleteAllSessions(name: String)

    // --- One-time EC prekeys -----------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsertPreKey(preKey: SignalPreKeyEntity)

    @Query("SELECT * FROM signal_prekeys WHERE prekey_id = :id LIMIT 1")
    abstract fun preKey(id: Int): SignalPreKeyEntity?

    @Query("SELECT COUNT(*) FROM signal_prekeys WHERE prekey_id = :id")
    abstract fun preKeyCount(id: Int): Int

    @Query("DELETE FROM signal_prekeys WHERE prekey_id = :id")
    abstract fun deletePreKey(id: Int)

    // --- Signed prekeys ----------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsertSignedPreKey(signedPreKey: SignalSignedPreKeyEntity)

    @Query("SELECT * FROM signal_signed_prekeys WHERE signed_prekey_id = :id LIMIT 1")
    abstract fun signedPreKey(id: Int): SignalSignedPreKeyEntity?

    @Query("SELECT * FROM signal_signed_prekeys ORDER BY signed_prekey_id ASC")
    abstract fun allSignedPreKeys(): List<SignalSignedPreKeyEntity>

    @Query("SELECT COUNT(*) FROM signal_signed_prekeys WHERE signed_prekey_id = :id")
    abstract fun signedPreKeyCount(id: Int): Int

    @Query("DELETE FROM signal_signed_prekeys WHERE signed_prekey_id = :id")
    abstract fun deleteSignedPreKey(id: Int)

    // --- Kyber prekeys -----------------------------------------------------

    @Query("SELECT * FROM signal_kyber_prekeys WHERE kyber_prekey_id = :id LIMIT 1")
    abstract fun kyberPreKey(id: Int): SignalKyberPreKeyEntity?

    @Query("SELECT * FROM signal_kyber_prekeys ORDER BY kyber_prekey_id ASC")
    abstract fun allKyberPreKeys(): List<SignalKyberPreKeyEntity>

    @Query("SELECT COUNT(*) FROM signal_kyber_prekeys WHERE kyber_prekey_id = :id")
    abstract fun kyberPreKeyCount(id: Int): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertKyberPreKeyIfAbsent(kyberPreKey: SignalKyberPreKeyEntity): Long

    @Query("UPDATE signal_kyber_prekeys SET record = :record, schema_version = :schemaVersion WHERE kyber_prekey_id = :id")
    protected abstract fun updateKyberRecord(id: Int, record: ByteArray, schemaVersion: Int)

    /**
     * Store a Kyber prekey record, **preserving** the existing `used` and
     * `last_resort` metadata when a record with the same id already exists.
     *
     * This mirrors libsignal's `InMemoryKyberPreKeyStore`, whose `used` set and
     * base-key map are independent of the record map: re-storing a record never
     * clears its used/last-resort lifecycle state. A plain REPLACE upsert would
     * reset those columns to the incoming row's defaults and silently un-use a
     * consumed key, so replacement updates only the opaque [record] (and its
     * [schemaVersion]) and leaves `used` / `last_resort` untouched. A brand-new id
     * is inserted with the caller-supplied defaults (`used=false`,
     * `last_resort=…`).
     */
    @Transaction
    open fun storeKyberPreKeyPreservingMetadata(kyberPreKey: SignalKyberPreKeyEntity) {
        val rowId = insertKyberPreKeyIfAbsent(kyberPreKey)
        if (rowId <= 0L) {
            // Row already exists: update only the opaque record + schema version,
            // preserving used / last_resort.
            updateKyberRecord(kyberPreKey.kyberPreKeyId, kyberPreKey.record, kyberPreKey.schemaVersion)
        }
    }

    // --- Kyber base-key replay (blocking mirror of the suspend v3 op) ------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertKyberBaseKey(row: SignalKyberBaseKeyEntity): Long

    @Query("UPDATE signal_kyber_prekeys SET used = 1 WHERE kyber_prekey_id = :id")
    protected abstract fun markKyberUsed(id: Int)

    /**
     * Blocking mirror of [SignalKyberBaseKeyDao.markKyberUsedWithBaseKey].
     *
     * This method and the suspend original are **two intentional views of the same
     * operation over the same `signal_kyber_base_keys` schema**: identical
     * exact-triple UNIQUE-constraint replay detection (the IGNORE insert's
     * non-positive rowid means replay), identical up-front [SignalKyberBaseKeyBounds]
     * bounding of [baseKey], and the identical bounded [MarkKyberUsedResult]
     * (`MISSING` / `REUSED` / `MARKED`) contract. They differ only in call shape:
     * this one is synchronous for the native `markKyberPreKeyUsed` callback, the
     * other is `suspend` for the coroutine app layer. Neither is a superset of the
     * other; both are covered by their own tests, and any change to the replay
     * semantics or schema must be mirrored in both. No shared helper is extracted
     * because Room generates each DAO's SQL independently and a blocking method
     * cannot call a suspend one.
     */
    @Transaction
    open fun markKyberUsedWithBaseKeyBlocking(
        kyberId: Int,
        signedPreKeyId: Int,
        baseKey: ByteArray,
        now: Long,
    ): MarkKyberUsedResult {
        SignalKyberBaseKeyBounds.requireValid(baseKey)

        if (kyberPreKeyCount(kyberId) == 0) return MarkKyberUsedResult.MISSING

        val rowId = insertKyberBaseKey(
            SignalKyberBaseKeyEntity(
                kyberPreKeyId = kyberId,
                signedPreKeyId = signedPreKeyId,
                baseKey = baseKey,
                firstSeenAt = now,
            ),
        )
        if (rowId <= 0L) return MarkKyberUsedResult.REUSED

        markKyberUsed(kyberId)
        return MarkKyberUsedResult.MARKED
    }

    // --- Inventory provisioning (Task 4) -----------------------------------
    //
    // These read the LIVE prekey pools the app-owned prekey manager provisions
    // against, and insert freshly generated keys. Like every method here they are
    // synchronous and MUST run off the main thread on the crypto dispatcher inside
    // an outer transaction opened by the manager, so a count → generate → insert
    // ensure is a consistent, atomic snapshot. No schema change: these are
    // additional queries over the existing v3 Signal tables.

    /** Count of one-time EC prekeys currently stored (all are unused; consumption deletes). */
    @Query("SELECT COUNT(*) FROM signal_prekeys")
    abstract fun oneTimePreKeyCount(): Int

    /** Every stored one-time EC prekey id, ascending. */
    @Query("SELECT prekey_id FROM signal_prekeys ORDER BY prekey_id ASC")
    abstract fun oneTimePreKeyIds(): List<Int>

    /**
     * The oldest stored one-time EC prekey (ascending id), or null when none is
     * available. Publishing a one-time EC prekey does NOT delete it — consumption
     * happens later when libsignal removes it during session establishment.
     */
    @Query("SELECT * FROM signal_prekeys ORDER BY prekey_id ASC LIMIT 1")
    abstract fun oldestOneTimePreKey(): SignalPreKeyEntity?

    /** True iff a one-time EC prekey with [id] exists. */
    @Query("SELECT COUNT(*) FROM signal_prekeys WHERE prekey_id = :id")
    abstract fun preKeyIdCount(id: Int): Int

    /** Count of active (non-consumed) signed EC prekeys. Signed keys are never marked used here. */
    @Query("SELECT COUNT(*) FROM signal_signed_prekeys")
    abstract fun signedPreKeyTotal(): Int

    /** The most-recently created signed EC prekey, or null when none exists. */
    @Query("SELECT * FROM signal_signed_prekeys ORDER BY created_at DESC, signed_prekey_id DESC LIMIT 1")
    abstract fun latestSignedPreKey(): SignalSignedPreKeyEntity?

    /** True iff a signed EC prekey with [id] exists. */
    @Query("SELECT COUNT(*) FROM signal_signed_prekeys WHERE signed_prekey_id = :id")
    abstract fun signedPreKeyIdCount(id: Int): Int

    /**
     * Insert a freshly generated signed EC prekey. IGNORE-on-conflict so a
     * concurrent same-id insert is a no-op (non-positive rowid) rather than
     * replacing a live signed key; the manager recounts/retries on conflict.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertSignedPreKeyIfAbsent(signedPreKey: SignalSignedPreKeyEntity): Long

    /** Count of unused, NON-last-resort one-time Kyber prekeys. */
    @Query("SELECT COUNT(*) FROM signal_kyber_prekeys WHERE used = 0 AND last_resort = 0")
    abstract fun unusedOneTimeKyberCount(): Int

    /** Count of last-resort Kyber prekeys (used or not; last-resort is reusable). */
    @Query("SELECT COUNT(*) FROM signal_kyber_prekeys WHERE last_resort = 1")
    abstract fun lastResortKyberCount(): Int

    /**
     * The single reusable last-resort Kyber prekey, most-recently created first, or
     * null when none exists. Published only as a fallback when no unused one-time
     * Kyber prekey is available.
     */
    @Query("SELECT * FROM signal_kyber_prekeys WHERE last_resort = 1 ORDER BY created_at DESC, kyber_prekey_id DESC LIMIT 1")
    abstract fun latestLastResortKyber(): SignalKyberPreKeyEntity?

    /**
     * The oldest unused, non-last-resort one-time Kyber prekey (ascending id), or
     * null when none is available. Publishing a one-time Kyber key does NOT mark it
     * used — consumption happens later during session establishment.
     */
    @Query("SELECT * FROM signal_kyber_prekeys WHERE used = 0 AND last_resort = 0 ORDER BY kyber_prekey_id ASC LIMIT 1")
    abstract fun oldestUnusedOneTimeKyber(): SignalKyberPreKeyEntity?

    /** True iff a Kyber prekey with [id] exists (any lifecycle/last-resort state). */
    @Query("SELECT COUNT(*) FROM signal_kyber_prekeys WHERE kyber_prekey_id = :id")
    abstract fun kyberIdCount(id: Int): Int

    /** Every stored Kyber prekey id (any state), ascending. */
    @Query("SELECT kyber_prekey_id FROM signal_kyber_prekeys ORDER BY kyber_prekey_id ASC")
    abstract fun kyberPreKeyIds(): List<Int>

    /** Every stored signed EC prekey id, ascending. */
    @Query("SELECT signed_prekey_id FROM signal_signed_prekeys ORDER BY signed_prekey_id ASC")
    abstract fun signedPreKeyIds(): List<Int>

    /**
     * Insert a freshly generated one-time EC prekey. IGNORE-on-conflict so a
     * concurrent insert of the same id is a no-op (non-positive rowid) rather than
     * clobbering an existing key; the manager recounts/retries on a non-positive
     * result. Returns the inserted rowid, or a non-positive value on conflict.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertPreKeyIfAbsent(preKey: SignalPreKeyEntity): Long

    /**
     * Insert a freshly generated Kyber prekey (one-time or last-resort), carrying
     * its `used` / `last_resort` metadata. IGNORE-on-conflict, same contract as
     * [insertPreKeyIfAbsent]: a same-id conflict is a no-op the manager detects via
     * a non-positive rowid, never a metadata-clobbering replace.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertKyberPreKeyMetadataIfAbsent(kyberPreKey: SignalKyberPreKeyEntity): Long
}

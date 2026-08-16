package com.meshchats.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Store for the most-recent verified pre-key bundle received per contact (v4).
 * One bundle per contact address; a fresher bundle replaces the previous one.
 *
 * The bundle blob is public material but is SQLCipher-protected at rest, so this
 * DAO never stringifies it. Cascade delete on the contact FK removes a bundle
 * automatically when its contact is deleted; [deleteExpired] is the explicit sweep
 * for time-based expiry.
 */
@Dao
interface ContactPreKeyBundleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bundle: ContactPreKeyBundleEntity)

    @Query("SELECT * FROM contact_prekey_bundles WHERE contact_address = :address LIMIT 1")
    suspend fun get(address: String): ContactPreKeyBundleEntity?

    /** All stored bundles, soonest-to-expire first (deterministic tie-break by address). */
    @Query("SELECT * FROM contact_prekey_bundles ORDER BY expires_at ASC, contact_address ASC")
    suspend fun all(): List<ContactPreKeyBundleEntity>

    @Query("DELETE FROM contact_prekey_bundles WHERE contact_address = :address")
    suspend fun delete(address: String)

    /**
     * Purge every bundle that has expired strictly before [now]. Returns the number
     * of rows removed so callers can log/audit the sweep.
     */
    @Query("DELETE FROM contact_prekey_bundles WHERE expires_at < :now")
    suspend fun deleteExpired(now: Long): Int
}

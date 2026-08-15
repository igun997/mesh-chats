package com.meshchats.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Atomically provisions the device's own identity: the [DeviceIdentityEntity] and
 * the local [SignalIdentityEntity] are inserted **together** in one transaction,
 * so the database never holds one without the other. The identity repository
 * relies on this all-or-nothing insert for its crash-consistency guarantees.
 *
 * `ABORT` on conflict (rather than `REPLACE`) means a second attempt to provision
 * over an already-present identity throws and rolls back, instead of silently
 * overwriting a live identity — provisioning must happen exactly once, and any
 * re-provision attempt is a bug the caller should see.
 */
@Dao
abstract class IdentityProvisioningDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertDevice(device: DeviceIdentityEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSignal(signal: SignalIdentityEntity)

    @Query("SELECT * FROM device_identity WHERE id = :id LIMIT 1")
    abstract suspend fun getDevice(id: Int = DeviceIdentityEntity.SINGLETON_ID): DeviceIdentityEntity?

    @Query("SELECT * FROM signal_identity WHERE id = :id LIMIT 1")
    abstract suspend fun getSignal(id: Int = SignalIdentityEntity.SINGLETON_ID): SignalIdentityEntity?

    /**
     * Inserts both singleton rows in one transaction. If either insert fails (e.g.
     * a row already exists), Room rolls the whole transaction back and neither row
     * survives.
     */
    @Transaction
    open suspend fun provisionIdentity(device: DeviceIdentityEntity, signal: SignalIdentityEntity) {
        insertDevice(device)
        insertSignal(signal)
    }
}

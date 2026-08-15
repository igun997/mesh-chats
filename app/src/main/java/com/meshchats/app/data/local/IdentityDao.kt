package com.meshchats.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceIdentityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(identity: DeviceIdentityEntity)

    @Query("SELECT * FROM device_identity WHERE id = :id LIMIT 1")
    suspend fun get(id: Int = DeviceIdentityEntity.SINGLETON_ID): DeviceIdentityEntity?

    @Query("SELECT * FROM device_identity WHERE id = :id LIMIT 1")
    fun observe(id: Int = DeviceIdentityEntity.SINGLETON_ID): Flow<DeviceIdentityEntity?>

    @Query("DELETE FROM device_identity")
    suspend fun clear()
}

@Dao
interface ContactIdentityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactIdentityEntity)

    @Query("SELECT * FROM contact_identities WHERE address = :address LIMIT 1")
    suspend fun get(address: String): ContactIdentityEntity?

    @Query("SELECT * FROM contact_identities ORDER BY address ASC")
    suspend fun all(): List<ContactIdentityEntity>

    /** Verified destinations only, deterministically ordered by address. */
    @Query(
        "SELECT * FROM contact_identities WHERE trust_state = :state ORDER BY address ASC",
    )
    suspend fun byTrustState(state: String = TrustState.VERIFIED.name): List<ContactIdentityEntity>

    @Query("DELETE FROM contact_identities WHERE address = :address")
    suspend fun delete(address: String)
}

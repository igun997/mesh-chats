package com.meshchats.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MessageEntity::class,
        DeviceIdentityEntity::class,
        ContactIdentityEntity::class,
        SignalIdentityEntity::class,
        SignalTrustedIdentityEntity::class,
        SignalSessionEntity::class,
        SignalPreKeyEntity::class,
        SignalSignedPreKeyEntity::class,
        SignalKyberPreKeyEntity::class,
        SignalKyberBaseKeyEntity::class,
        CiphertextOutboxEntity::class,
        DeliveryAttemptEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun deviceIdentityDao(): DeviceIdentityDao
    abstract fun contactIdentityDao(): ContactIdentityDao
    abstract fun signalIdentityDao(): SignalIdentityDao
    abstract fun signalSessionDao(): SignalSessionDao
    abstract fun signalPreKeyDao(): SignalPreKeyDao
    abstract fun signalSignedPreKeyDao(): SignalSignedPreKeyDao
    abstract fun signalKyberPreKeyDao(): SignalKyberPreKeyDao
    abstract fun signalKyberBaseKeyDao(): SignalKyberBaseKeyDao

    /**
     * Blocking (synchronous) DAO dedicated to libsignal's synchronous store
     * callbacks. Backs [RoomSignalProtocolStore]; must only be called off the main
     * thread on the dedicated crypto dispatcher, inside an outer transaction. No
     * schema change: it is an additional DAO over the existing v3 Signal tables.
     */
    abstract fun blockingSignalStoreDao(): BlockingSignalStoreDao

    abstract fun outboxDao(): OutboxDao
    abstract fun identityProvisioningDao(): IdentityProvisioningDao

    companion object {
        const val NAME = "mesh-chats.db"
    }
}

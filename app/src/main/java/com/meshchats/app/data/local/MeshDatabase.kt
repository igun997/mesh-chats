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
        CiphertextOutboxEntity::class,
        DeliveryAttemptEntity::class,
    ],
    version = 2,
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
    abstract fun outboxDao(): OutboxDao

    companion object {
        const val NAME = "mesh-chats.db"
    }
}

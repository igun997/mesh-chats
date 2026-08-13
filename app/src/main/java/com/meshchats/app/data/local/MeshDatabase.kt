package com.meshchats.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MessageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        const val NAME = "mesh-chats.db"
    }
}

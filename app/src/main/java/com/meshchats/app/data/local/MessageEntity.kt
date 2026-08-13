package com.meshchats.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversation_id", "sent_at"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @androidx.room.ColumnInfo(name = "conversation_id") val conversationId: String,
    @androidx.room.ColumnInfo(name = "author_id") val authorId: String,
    val body: String,
    @androidx.room.ColumnInfo(name = "sent_at") val sentAt: Long,
    @androidx.room.ColumnInfo(name = "is_outgoing") val isOutgoing: Boolean,
)

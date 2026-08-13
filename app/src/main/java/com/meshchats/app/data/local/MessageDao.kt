package com.meshchats.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY sent_at ASC")
    fun observeConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages AS message
        WHERE message.id = (
            SELECT candidate.id
            FROM messages AS candidate
            WHERE candidate.conversation_id = message.conversation_id
            ORDER BY candidate.sent_at DESC, candidate.id DESC
            LIMIT 1
        )
        ORDER BY message.sent_at DESC
        """,
    )
    fun observeLatestPerConversation(): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun clearConversation(conversationId: String)
}

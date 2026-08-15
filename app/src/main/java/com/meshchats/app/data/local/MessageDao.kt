package com.meshchats.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
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

    /**
     * Insert a new message, or update the existing row with the same id in place.
     *
     * Uses [Upsert] (INSERT ... ON CONFLICT DO UPDATE) rather than
     * `@Insert(onConflict = REPLACE)`. REPLACE deletes the conflicting row before
     * re-inserting, which would cascade-delete any child `ciphertext_outbox` rows
     * (and their `delivery_attempts`) that reference this message via its foreign
     * key. Upsert mutates the row in place, so updating a queued message's
     * delivery fields never destroys its pending outbox packet.
     */
    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun clearConversation(conversationId: String)
}

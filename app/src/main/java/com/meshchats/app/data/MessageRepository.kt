package com.meshchats.app.data

import com.meshchats.app.data.local.MessageDao
import com.meshchats.app.data.local.MessageEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
) {
    fun observeConversation(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.observeConversation(conversationId)

    fun observeConversationHeads(): Flow<List<MessageEntity>> =
        messageDao.observeLatestPerConversation()

    suspend fun send(conversationId: String, body: String, authorId: String = LOCAL_AUTHOR) {
        messageDao.upsert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                authorId = authorId,
                body = body,
                sentAt = System.currentTimeMillis(),
                isOutgoing = authorId == LOCAL_AUTHOR,
            ),
        )
    }

    companion object {
        const val LOCAL_AUTHOR = "me"
    }
}

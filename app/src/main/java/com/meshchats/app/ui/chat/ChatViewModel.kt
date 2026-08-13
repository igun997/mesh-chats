package com.meshchats.app.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.meshchats.app.data.MessageRepository
import com.meshchats.app.ui.navigation.ChatRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: String,
    val body: String,
    val sentAt: Long,
    val isOutgoing: Boolean,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MessageRepository,
) : ViewModel() {

    private val conversationId: String = savedStateHandle.toRoute<ChatRoute>().conversationId

    val messages: StateFlow<List<ChatMessage>> = repository.observeConversation(conversationId)
        .map { rows ->
            rows.map { row ->
                ChatMessage(
                    id = row.id,
                    body = row.body,
                    sentAt = row.sentAt,
                    isOutgoing = row.isOutgoing,
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun send(body: String) {
        if (body.isBlank()) return
        viewModelScope.launch { repository.send(conversationId, body.trim()) }
    }
}

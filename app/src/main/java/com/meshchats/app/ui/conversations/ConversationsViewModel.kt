package com.meshchats.app.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchats.app.data.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConversationSummary(
    val id: String,
    val title: String,
    val preview: String,
    val updatedAt: Long,
)

data class ConversationsUiState(
    val conversations: List<ConversationSummary> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val repository: MessageRepository,
) : ViewModel() {

    val uiState: StateFlow<ConversationsUiState> = repository.observeConversationHeads()
        .map { heads ->
            ConversationsUiState(
                conversations = heads.map { head ->
                    ConversationSummary(
                        id = head.conversationId,
                        title = head.conversationId.replaceFirstChar { it.uppercase() },
                        preview = head.body,
                        updatedAt = head.sentAt,
                    )
                },
                isLoading = false,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ConversationsUiState(),
        )

    fun startConversation() {
        viewModelScope.launch {
            val id = "mesh-${System.currentTimeMillis() % 100_000}"
            repository.send(conversationId = id, body = "Say hi over the mesh")
        }
    }
}

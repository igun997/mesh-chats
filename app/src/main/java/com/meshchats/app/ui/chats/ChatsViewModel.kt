package com.meshchats.app.ui.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchats.app.core.mesh.MeshState
import com.meshchats.app.core.mesh.MeshStateRepository
import com.meshchats.app.core.mesh.Peer
import com.meshchats.app.core.mesh.TransportId
import com.meshchats.app.data.MessageRepository
import com.meshchats.app.data.local.MessageEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Conversations are peers: in a mesh there is no account directory, so the peer
 * list is the chat list. Reachability drives ordering, not recency alone.
 */
data class ChatSummary(
    val conversationId: String,
    val name: String,
    val monogram: String,
    val fingerprint: String,
    val verified: Boolean,
    val preview: String,
    val transport: TransportId?,
    val reachable: Boolean,
    val lastSeenMinutes: Int,
)

data class ChatsUiState(
    val reachable: List<ChatSummary> = emptyList(),
    val outOfRange: List<ChatSummary> = emptyList(),
    val meshState: MeshState = MeshState.Empty,
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = reachable.isEmpty() && outOfRange.isEmpty()
}

@HiltViewModel
class ChatsViewModel @Inject constructor(
    meshStateRepository: MeshStateRepository,
    messageRepository: MessageRepository,
) : ViewModel() {

    val uiState: StateFlow<ChatsUiState> = combine(
        meshStateRepository.state,
        messageRepository.observeConversationHeads(),
    ) { mesh, heads ->
        val summaries = mesh.peers.map { peer -> peer.toSummary(heads) }
        ChatsUiState(
            reachable = summaries.filter { it.reachable },
            outOfRange = summaries.filterNot { it.reachable }
                .sortedBy { it.lastSeenMinutes },
            meshState = mesh,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatsUiState(),
    )
}

private fun Peer.toSummary(heads: List<MessageEntity>): ChatSummary {
    val head = heads.firstOrNull { it.conversationId == id }
    return ChatSummary(
        conversationId = id,
        name = displayName,
        monogram = monogram,
        fingerprint = fingerprintShort,
        verified = verified,
        preview = head?.body ?: "no messages yet",
        transport = reachableVia.minByOrNull { it.ordinal },
        reachable = isReachable,
        lastSeenMinutes = lastSeenMinutes,
    )
}

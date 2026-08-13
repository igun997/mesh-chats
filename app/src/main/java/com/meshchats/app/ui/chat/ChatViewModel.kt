package com.meshchats.app.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.meshchats.app.core.mesh.Constraints
import com.meshchats.app.core.mesh.MeshStateRepository
import com.meshchats.app.core.mesh.Route
import com.meshchats.app.core.mesh.TransportId
import com.meshchats.app.data.MessageRepository
import com.meshchats.app.ui.components.DeliveryState
import com.meshchats.app.ui.navigation.ChatRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: String,
    val body: String,
    val sentAt: Long,
    val isOutgoing: Boolean,
    val transport: TransportId,
    val hops: Int,
    val delivery: DeliveryState,
)

data class ChatUiState(
    val conversationId: String,
    val peerName: String = conversationId,
    val fingerprint: String = "unverified identity",
    val verified: Boolean = false,
    val route: Route? = null,
    val constraints: Constraints = Constraints(maxPayloadBytes = 1_048_576, typicalLatencyMs = 0),
    val messages: List<ChatMessage> = emptyList(),
) {
    val routeLabel: String
        get() = route?.let { route ->
            buildString {
                append(if (route.isDirect) "direct" else "mesh")
                append(" · ${route.transport.shortLabel}")
                append(" · ${route.hops} hop${if (route.hops == 1) "" else "s"}")
                if (route.viaRelay) append(" · global · E2E")
            }
        } ?: "out of range · messages queue locally"
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    meshStateRepository: MeshStateRepository,
) : ViewModel() {

    private val conversationId: String = savedStateHandle.toRoute<ChatRoute>().conversationId

    val uiState: StateFlow<ChatUiState> = combine(
        messageRepository.observeConversation(conversationId),
        meshStateRepository.state,
    ) { rows, mesh ->
        val peer = mesh.peers.firstOrNull { it.id == conversationId }
        val transport = peer?.reachableVia?.minByOrNull { it.ordinal }
        val status = transport?.let(mesh::transport)
        val route = transport?.let {
            Route(
                transport = it,
                hops = peer.hops ?: 1,
                latencyMs = status?.constraints?.typicalLatencyMs ?: 0,
                viaRelay = it == TransportId.RELAY,
            )
        }
        ChatUiState(
            conversationId = conversationId,
            peerName = peer?.displayName ?: conversationId,
            fingerprint = peer?.fingerprintFull ?: "unverified identity",
            verified = peer?.verified == true,
            route = route,
            constraints = status?.constraints ?: Constraints(1_048_576, 0),
            messages = rows.map { row ->
                ChatMessage(
                    id = row.id,
                    body = row.body,
                    sentAt = row.sentAt,
                    isOutgoing = row.isOutgoing,
                    transport = transport ?: TransportId.RELAY,
                    hops = peer?.hops ?: 0,
                    delivery = if (transport == null) {
                        DeliveryState.QUEUED
                    } else {
                        DeliveryState.DELIVERED
                    },
                )
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatUiState(conversationId),
    )

    fun send(body: String) {
        if (body.isBlank()) return
        viewModelScope.launch { messageRepository.send(conversationId, body.trim()) }
    }
}

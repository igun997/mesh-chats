package com.meshchats.app.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshchats.app.core.mesh.MeshState
import com.meshchats.app.core.mesh.TransportId
import com.meshchats.app.ui.components.HairlineDivider
import com.meshchats.app.ui.components.PeerMonogram
import com.meshchats.app.ui.shell.MeshScreenScaffold
import com.meshchats.app.ui.shell.withShellClearance
import com.meshchats.app.ui.theme.FingerprintTextStyle
import com.meshchats.app.ui.theme.LocalMeshTokens
import com.meshchats.app.ui.theme.MeshChatsTheme
import com.meshchats.app.ui.theme.MeshSpec
import com.meshchats.app.ui.theme.MetaTextStyle

@Composable
fun ChatsScreen(
    onOpenChat: (String) -> Unit,
    onOpenMesh: () -> Unit,
    viewModel: ChatsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ChatsContent(uiState = uiState, onOpenChat = onOpenChat, onOpenMesh = onOpenMesh)
}

@Composable
private fun ChatsContent(
    uiState: ChatsUiState,
    onOpenChat: (String) -> Unit,
    onOpenMesh: () -> Unit,
) {
    MeshScreenScaffold(
        title = "Chats",
        meshState = uiState.meshState,
        onOpenMesh = onOpenMesh,
    ) { innerPadding ->
        if (uiState.isEmpty && !uiState.isLoading) {
            EmptyChats(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding.withShellClearance())
                    .padding(MeshSpec.screenPadding),
                onOpenMesh = onOpenMesh,
            )
            return@MeshScreenScaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding.withShellClearance(),
        ) {
            if (uiState.reachable.isNotEmpty()) {
                item { SectionHeader("NEARBY (${uiState.reachable.size})") }
                items(uiState.reachable, key = { it.conversationId }) { chat ->
                    ChatRow(chat = chat, onClick = { onOpenChat(chat.conversationId) })
                    HairlineDivider()
                }
            }
            if (uiState.outOfRange.isNotEmpty()) {
                item { SectionHeader("OUT OF RANGE (${uiState.outOfRange.size})") }
                items(uiState.outOfRange, key = { it.conversationId }) { chat ->
                    ChatRow(chat = chat, onClick = { onOpenChat(chat.conversationId) })
                    HairlineDivider()
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MetaTextStyle,
        color = LocalMeshTokens.current.meta,
        modifier = Modifier.padding(
            start = MeshSpec.screenPadding,
            end = MeshSpec.screenPadding,
            top = 16.dp,
            bottom = 8.dp,
        ),
    )
}

@Composable
private fun ChatRow(chat: ChatSummary, onClick: () -> Unit) {
    val tokens = LocalMeshTokens.current
    val alpha = if (chat.reachable) 1f else 0.48f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MeshSpec.screenPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerMonogram(monogram = chat.monogram, verified = chat.verified)

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = chat.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = chat.preview,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.secondary.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = chat.fingerprint,
                style = FingerprintTextStyle,
                color = tokens.meta.copy(alpha = alpha),
                maxLines = 1,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = chat.transport?.shortLabel ?: "—",
                style = MetaTextStyle,
                color = tokens.meta,
            )
            Text(
                text = if (chat.reachable) "now" else "${chat.lastSeenMinutes}m",
                style = MetaTextStyle,
                color = tokens.meta,
            )
        }
    }
}

@Composable
private fun EmptyChats(modifier: Modifier, onOpenMesh: () -> Unit) {
    val tokens = LocalMeshTokens.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No peers yet", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Peers appear here when Wi-Fi or Bluetooth is on.",
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.secondary,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = "OPEN MESH",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .padding(top = 20.dp)
                .clickable(onClick = onOpenMesh)
                .padding(12.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 360, heightDp = 720)
@Composable
private fun ChatsPreview() {
    MeshChatsTheme(darkTheme = true) {
        ChatsContent(
            uiState = ChatsUiState(
                reachable = listOf(
                    ChatSummary(
                        "peer-1", "Ari", "ADLN", "anchor · drift", true,
                        "handshake ok", TransportId.WIFI, true, 0,
                    ),
                    ChatSummary(
                        "peer-3", "unknown", "CHMF", "cinder · harbor", false,
                        "no messages yet", TransportId.BT, true, 0,
                    ),
                ),
                outOfRange = listOf(
                    ChatSummary(
                        "peer-4", "Rae", "DOST", "delta · orchid", false,
                        "see you at basecamp", null, false, 46,
                    ),
                ),
                meshState = MeshState.Empty,
                isLoading = false,
            ),
            onOpenChat = {},
            onOpenMesh = {},
        )
    }
}

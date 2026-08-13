package com.meshchats.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshchats.app.core.mesh.Constraints
import com.meshchats.app.core.mesh.Route
import com.meshchats.app.core.mesh.TransportId
import com.meshchats.app.ui.components.DeliveryState
import com.meshchats.app.ui.components.HairlineDivider
import com.meshchats.app.ui.components.MessageBubble
import com.meshchats.app.ui.theme.FingerprintTextStyle
import com.meshchats.app.ui.theme.LocalMeshTokens
import com.meshchats.app.ui.theme.MeshChatsTheme
import com.meshchats.app.ui.theme.MetaTextStyle
import com.meshchats.app.ui.theme.TabularTextStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ChatContent(uiState = uiState, onBack = onBack, onSend = viewModel::send)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatContent(
    uiState: ChatUiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val tokens = LocalMeshTokens.current
    val byteCount = draft.toByteArray(Charsets.UTF_8).size
    val fragmentCount = if (uiState.constraints.maxPayloadBytes > 0) {
        (byteCount + uiState.constraints.maxPayloadBytes - 1) / uiState.constraints.maxPayloadBytes
    } else {
        1
    }
    val isConstrained = uiState.constraints.maxPayloadBytes <= 1_024

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) listState.animateScrollToItem(uiState.messages.lastIndex)
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing.only(
            androidx.compose.foundation.layout.WindowInsetsSides.Top,
        ),
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                uiState.peerName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                uiState.routeLabel,
                                style = MetaTextStyle,
                                color = tokens.meta,
                                maxLines = 1,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                Text(
                    text = uiState.fingerprint,
                    style = FingerprintTextStyle,
                    color = tokens.meta,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                HairlineDivider()
            }
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (isConstrained) {
                    val limit = uiState.constraints.maxPayloadBytes
                    Text(
                        text = if (fragmentCount <= 1) {
                            "$byteCount/$limit bytes"
                        } else {
                            "$byteCount bytes · $fragmentCount fragments · " +
                                "~${fragmentCount * uiState.constraints.typicalLatencyMs / 1_000}s"
                        },
                        style = TabularTextStyle.copy(
                            fontWeight = if (byteCount >= limit * 0.9f) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                        ),
                        color = tokens.meta,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                        maxLines = 4,
                    )
                    FilledIconButton(
                        onClick = {
                            if (draft.isNotBlank()) {
                                onSend(draft)
                                draft = ""
                            }
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = if (uiState.route == null) "Queue" else "Send",
                        )
                    }
                }

                if (uiState.route == null) {
                    Text(
                        "QUEUED · sends when peer returns in range",
                        style = MetaTextStyle,
                        color = tokens.meta,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(uiState.messages, key = { it.id }) { message ->
                MessageBubble(
                    text = message.body,
                    isOutgoing = message.isOutgoing,
                    time = message.sentAt.asClockTime(),
                    transport = message.transport,
                    hops = message.hops,
                    deliveryState = message.delivery,
                )
            }
        }
    }
}

private fun Long.asClockTime(): String = DateTimeFormatter.ofPattern("HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(this))

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 360, heightDp = 720)
@Composable
private fun ChatPreview() {
    MeshChatsTheme(darkTheme = true) {
        ChatContent(
            uiState = ChatUiState(
                conversationId = "peer-1",
                peerName = "Ari",
                fingerprint = "anchor · drift · lantern · nine",
                verified = true,
                route = Route(TransportId.BT, 2, 180, false),
                constraints = Constraints(20_480, 180),
                messages = listOf(
                    ChatMessage(
                        "1", "peer discovered", 0L, false,
                        TransportId.BT, 2, DeliveryState.DELIVERED,
                    ),
                    ChatMessage(
                        "2", "handshake ok", 0L, true,
                        TransportId.BT, 2, DeliveryState.SENT,
                    ),
                ),
            ),
            onBack = {},
            onSend = {},
        )
    }
}

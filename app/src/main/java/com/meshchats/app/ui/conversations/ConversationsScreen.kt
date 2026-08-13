package com.meshchats.app.ui.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshchats.app.R
import com.meshchats.app.ui.theme.MeshChatsTheme

@Composable
fun ConversationsScreen(
    onOpenConversation: (String) -> Unit,
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ConversationsContent(
        uiState = uiState,
        onOpenConversation = onOpenConversation,
        onStartConversation = viewModel::startConversation,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationsContent(
    uiState: ConversationsUiState,
    onOpenConversation: (String) -> Unit,
    onStartConversation: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.conversations_title)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onStartConversation) {
                Icon(Icons.Filled.Add, contentDescription = "New conversation")
            }
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            uiState.conversations.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No conversations yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tap + to open a mesh channel.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(uiState.conversations, key = { it.id }) { conversation ->
                    ListItem(
                        headlineContent = { Text(conversation.title) },
                        supportingContent = { Text(conversation.preview, maxLines = 1) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenConversation(conversation.id) },
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun ConversationsPreview() {
    MeshChatsTheme {
        ConversationsContent(
            uiState = ConversationsUiState(
                conversations = listOf(
                    ConversationSummary("mesh-1", "Mesh 1", "peer joined", 0L),
                    ConversationSummary("mesh-2", "Mesh 2", "ack received", 0L),
                ),
                isLoading = false,
            ),
            onOpenConversation = {},
            onStartConversation = {},
        )
    }
}

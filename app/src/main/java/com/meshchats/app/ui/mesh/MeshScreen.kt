package com.meshchats.app.ui.mesh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshchats.app.core.mesh.MeshState
import com.meshchats.app.core.mesh.Peer
import com.meshchats.app.core.mesh.TransportId
import com.meshchats.app.ui.components.MeshRadioCard
import com.meshchats.app.ui.components.PeerMonogram
import com.meshchats.app.ui.components.hairlineBorder
import com.meshchats.app.ui.components.previewMeshState
import com.meshchats.app.ui.shell.MeshScreenScaffold
import com.meshchats.app.ui.shell.withShellClearance
import com.meshchats.app.ui.theme.FingerprintTextStyle
import com.meshchats.app.ui.theme.LocalMeshTokens
import com.meshchats.app.ui.theme.MeshChatsTheme
import com.meshchats.app.ui.theme.MeshSpec
import com.meshchats.app.ui.theme.MetaTextStyle
import com.meshchats.app.ui.theme.TabularTextStyle

@Composable
fun MeshScreen(viewModel: MeshViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MeshContent(
        state = state,
        onToggleTransport = viewModel::toggleTransport,
        onLocalMeshOnly = viewModel::setLocalMeshOnly,
    )
}

@Composable
private fun MeshContent(
    state: MeshState,
    onToggleTransport: (TransportId, Boolean) -> Unit,
    onLocalMeshOnly: (Boolean) -> Unit,
) {
    val tokens = LocalMeshTokens.current
    val unverified = state.peers.filterNot { it.verified }
    val verified = state.peers.filter { it.verified }

    MeshScreenScaffold(title = "Mesh", meshState = state, onOpenMesh = {}) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding.withShellClearance(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                KillSwitchRow(
                    enabled = state.localMeshOnly,
                    onToggle = onLocalMeshOnly,
                    modifier = Modifier.padding(horizontal = MeshSpec.screenPadding),
                )
            }

            items(state.transports, key = { it.id }) { status ->
                MeshRadioCard(
                    status = status,
                    onToggle = { enabled -> onToggleTransport(status.id, enabled) },
                    onAttach = {},
                    modifier = Modifier.padding(horizontal = MeshSpec.screenPadding),
                )
            }

            if (unverified.isNotEmpty()) {
                item { Header("UNVERIFIED (${unverified.size})") }
                items(unverified, key = { it.id }) { peer -> PeerRow(peer) }
            }
            if (verified.isNotEmpty()) {
                item { Header("VERIFIED (${verified.size})") }
                items(verified, key = { it.id }) { peer -> PeerRow(peer) }
            }

            item {
                Text(
                    text = "Battery: Wi-Fi scan ~4%/h · BLE ~2%/h · LoRa listen ~1%/h",
                    style = MetaTextStyle,
                    color = tokens.meta,
                    modifier = Modifier.padding(
                        horizontal = MeshSpec.screenPadding,
                        vertical = 12.dp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun KillSwitchRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalMeshTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .hairlineBorder(RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("LOCAL MESH ONLY", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Disables the relay and every internet path.",
                style = MetaTextStyle,
                color = tokens.meta,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onSurface,
                checkedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                checkedBorderColor = tokens.glyphActive,
                uncheckedThumbColor = tokens.glyphOff,
                uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                uncheckedBorderColor = tokens.hairline,
            ),
        )
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text = text,
        style = MetaTextStyle,
        color = LocalMeshTokens.current.meta,
        modifier = Modifier.padding(
            start = MeshSpec.screenPadding,
            end = MeshSpec.screenPadding,
            top = 12.dp,
        ),
    )
}

@Composable
private fun PeerRow(peer: Peer) {
    val tokens = LocalMeshTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MeshSpec.screenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerMonogram(monogram = peer.monogram, verified = peer.verified, size = 40.dp)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(peer.displayName, style = MaterialTheme.typography.titleMedium)
            Text(peer.fingerprintFull, style = FingerprintTextStyle, color = tokens.meta)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = peer.rssiDbm?.let { "$it dBm" } ?: "no link",
                style = TabularTextStyle,
                color = tokens.secondary,
            )
            Text(
                text = peer.hops?.let { "$it hops" } ?: "${peer.lastSeenMinutes}m ago",
                style = MetaTextStyle,
                color = tokens.meta,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 360, heightDp = 900)
@Composable
private fun MeshPreview() {
    MeshChatsTheme(darkTheme = true) {
        MeshContent(
            state = previewMeshState(loraAttached = true),
            onToggleTransport = { _, _ -> },
            onLocalMeshOnly = {},
        )
    }
}

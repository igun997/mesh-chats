package com.meshchats.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshchats.app.ui.components.HairlineDivider
import com.meshchats.app.ui.components.PeerMonogram
import com.meshchats.app.ui.components.previewMeshState
import com.meshchats.app.ui.mesh.MeshViewModel
import com.meshchats.app.ui.shell.MeshScreenScaffold
import com.meshchats.app.ui.shell.withShellClearance
import com.meshchats.app.ui.theme.FingerprintTextStyle
import com.meshchats.app.ui.theme.LocalMeshTokens
import com.meshchats.app.ui.theme.MeshChatsTheme
import com.meshchats.app.ui.theme.MeshSpec
import com.meshchats.app.ui.theme.MetaTextStyle

@Composable
fun SettingsScreen(
    onOpenMesh: () -> Unit,
    viewModel: MeshViewModel = hiltViewModel(),
) {
    val meshState by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(meshState = meshState, onOpenMesh = onOpenMesh)
}

@Composable
private fun SettingsContent(
    meshState: com.meshchats.app.core.mesh.MeshState,
    onOpenMesh: () -> Unit,
) {
    var blockScreenshots by remember { mutableStateOf(true) }
    var openBeacon by remember { mutableStateOf(true) }
    var readReceipts by remember { mutableStateOf(false) }
    var typingIndicators by remember { mutableStateOf(false) }
    val tokens = LocalMeshTokens.current

    MeshScreenScaffold(
        title = "Settings",
        meshState = meshState,
        onOpenMesh = onOpenMesh,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding.withShellClearance(),
        ) {
            item { Section("IDENTITY") }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MeshSpec.screenPadding, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PeerMonogram("ADLN", verified = true, size = 52.dp)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("My device", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "anchor · drift · lantern · nine",
                            style = FingerprintTextStyle,
                            color = tokens.secondary,
                        )
                        Text("VERIFIED IDENTITY", style = MetaTextStyle, color = tokens.meta)
                    }
                }
            }
            item { ActionRow("Show my QR", "Let a nearby peer verify this key") }
            item { ActionRow("Scan to verify", "Verify a peer face-to-face") }
            item { ActionRow("Back up identity", "Passphrase-encrypted file") }

            item { Section("PRIVACY") }
            item {
                ToggleRow(
                    "Block screenshots",
                    "Coming next · hide chat content from screenshots and recents",
                    blockScreenshots,
                    enabled = false,
                ) { blockScreenshots = it }
            }
            item {
                ToggleRow(
                    "Open distress beacon",
                    "Coming next · nearby strangers see distress + coarse location",
                    openBeacon,
                    enabled = false,
                ) { openBeacon = it }
            }
            item {
                ToggleRow(
                    "Read receipts", "Coming next · delivery acknowledgement stays on",
                    readReceipts, enabled = false,
                ) { readReceipts = it }
            }
            item {
                ToggleRow(
                    "Typing indicators", "Coming next · leaks when you are composing",
                    typingIndicators, enabled = false,
                ) { typingIndicators = it }
            }
            item { ActionRow("App lock", "Biometric or PIN · locks after 1 minute") }
            item { ActionRow("Panic wipe", "Duress PIN deletes identity keys and local history") }

            item { Section("NETWORK") }
            item { ActionRow("Relay", "relay.mesh.example:443 · stores nothing", onOpenMesh) }
            item { ActionRow("LoRa region", "Not set · required before transmit") }
            item { ActionRow("Battery saver", "Longer scan intervals · LoRa listen windows") }

            item { Section("STORAGE") }
            item { ActionRow("Local messages", "SQLCipher · 1.8 MB") }
            item { ActionRow("Offline maps", "No regions downloaded") }
            item { ActionRow("Clear cache", "Keeps identity and messages") }

            item { Section("ABOUT") }
            item { ActionRow("What this cannot protect", "Traffic analysis · seized unlocked phone") }
            item { ActionRow("Open-source licenses", "Mesh Chats 0.1.0") }
            item {
                Text(
                    text = "ROTATE IDENTITY",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = tokens.secondary,
                    modifier = Modifier
                        .clickable {}
                        .padding(MeshSpec.screenPadding),
                )
            }
        }
    }
}

@Composable
private fun Section(label: String) {
    Text(
        text = label,
        style = MetaTextStyle,
        color = LocalMeshTokens.current.meta,
        modifier = Modifier.padding(
            start = MeshSpec.screenPadding,
            end = MeshSpec.screenPadding,
            top = 20.dp,
            bottom = 8.dp,
        ),
    )
}

@Composable
private fun ActionRow(title: String, detail: String, onClick: () -> Unit = {}) {
    val tokens = LocalMeshTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MeshSpec.screenPadding, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MetaTextStyle, color = tokens.meta)
        }
        Text("›", color = tokens.meta, style = MaterialTheme.typography.titleMedium)
    }
    HairlineDivider(Modifier.padding(horizontal = MeshSpec.screenPadding))
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val tokens = LocalMeshTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MeshSpec.screenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MetaTextStyle, color = tokens.meta)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
    HairlineDivider(Modifier.padding(horizontal = MeshSpec.screenPadding))
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 360, heightDp = 900)
@Composable
private fun SettingsPreview() {
    MeshChatsTheme(darkTheme = true) {
        SettingsContent(meshState = previewMeshState(), onOpenMesh = {})
    }
}

package com.meshchats.app.ui.mesh

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshchats.app.core.mesh.MeshState
import com.meshchats.app.core.mesh.Peer
import com.meshchats.app.core.mesh.TransportId
import com.meshchats.app.core.transport.ble.BleDiscoveryState
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
    val discovery by viewModel.discovery.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Discovery runs only while this screen is at least STARTED. Tied to the
    // lifecycle (not composition) so it also stops when the app goes to the
    // Home screen or the device locks, and resumes when we return to the
    // foreground — the app never advertises for the whole process lifetime.
    LifecycleStartEffect(Unit) {
        viewModel.onScreenStarted()
        onStopOrDispose { viewModel.onScreenStopped() }
    }

    // Once any permission is denied, Android stops showing the request dialog,
    // so the action must switch from "Grant" to "Open app settings". Saved
    // across config changes so a rotation doesn't reset us back to a dialog
    // that will never appear.
    var permissionDenied by rememberSaveable { mutableStateOf(false) }

    // Runtime permissions are requested only when the user taps Grant, never
    // automatically. The launcher returns a per-permission grant map: if every
    // permission is granted we clear the denial and retry; any denial flips us
    // to the app-settings path. Either way we retry so the controller re-checks
    // its preconditions.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = RequestMultiplePermissions(),
    ) { result ->
        permissionDenied = PermissionResultDecision.from(result).denied
        viewModel.retryDiscovery()
    }

    val openAppSettings = {
        // Guarded: a missing settings activity must not crash the app.
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        Unit
    }

    MeshContent(
        state = state,
        discovery = discovery,
        permissionDenied = permissionDenied,
        onToggleTransport = viewModel::toggleTransport,
        onLocalMeshOnly = viewModel::setLocalMeshOnly,
        onGrantPermissions = { permissions ->
            permissionLauncher.launch(permissions.toTypedArray())
        },
        onOpenAppSettings = openAppSettings,
        onOpenBluetoothSettings = {
            // Guarded: some devices lack a Bluetooth settings activity.
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            Unit
        },
        onRetry = viewModel::retryDiscovery,
    )
}

@Composable
private fun MeshContent(
    state: MeshState,
    discovery: BleDiscoveryState,
    permissionDenied: Boolean,
    onToggleTransport: (TransportId, Boolean) -> Unit,
    onLocalMeshOnly: (Boolean) -> Unit,
    onGrantPermissions: (Set<String>) -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onRetry: () -> Unit,
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
                // The Bluetooth card is driven entirely by the discovery
                // lifecycle, so it never shows a misleading manual toggle. All
                // other transports keep their switch.
                val hideToggle = status.id == TransportId.BT
                MeshRadioCard(
                    status = status,
                    onToggle = { enabled -> onToggleTransport(status.id, enabled) },
                    onAttach = {},
                    showToggle = !hideToggle,
                    modifier = Modifier.padding(horizontal = MeshSpec.screenPadding),
                )

                if (status.id == TransportId.BT) {
                    DiscoveryAction(
                        discovery = discovery,
                        permissionDenied = permissionDenied,
                        onGrantPermissions = onGrantPermissions,
                        onOpenAppSettings = onOpenAppSettings,
                        onOpenBluetoothSettings = onOpenBluetoothSettings,
                        onRetry = onRetry,
                        modifier = Modifier.padding(
                            horizontal = MeshSpec.screenPadding,
                            vertical = 4.dp,
                        ),
                    )
                }
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

/**
 * Honest, inline action tied to the current BLE discovery state. No modal wall:
 * each state gets one card that either explains the situation or offers the one
 * action that can move it forward, and never promises background scanning.
 */
@Composable
private fun DiscoveryAction(
    discovery: BleDiscoveryState,
    permissionDenied: Boolean,
    onGrantPermissions: (Set<String>) -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (discovery) {
        is BleDiscoveryState.PermissionRequired -> {
            val base = if (usesNearbyDevices(discovery.permissions)) {
                "Android needs the Nearby devices permission to find mesh " +
                    "peers over Bluetooth. Scanning while this screen is open."
            } else {
                "Android requires location access to scan for Bluetooth " +
                    "peers on this version. We never use it to locate you. " +
                    "Scanning while this screen is open."
            }
            // Once denied, the system won't show the dialog again, so point the
            // user at app settings instead of re-requesting into a no-op.
            if (permissionDenied) {
                DiscoveryCard(
                    title = "Permission denied",
                    body = "$base Enable it in app settings to scan for peers.",
                    actionLabel = "Open app settings",
                    onAction = onOpenAppSettings,
                    modifier = modifier,
                )
            } else {
                DiscoveryCard(
                    title = "Nearby devices permission needed",
                    body = base,
                    actionLabel = "Grant",
                    onAction = { onGrantPermissions(discovery.permissions) },
                    modifier = modifier,
                )
            }
        }

        BleDiscoveryState.BluetoothOff ->
            DiscoveryCard(
                title = "Bluetooth is off",
                body = "Turn Bluetooth on in Settings to find nearby mesh peers.",
                actionLabel = "Open settings",
                onAction = onOpenBluetoothSettings,
                modifier = modifier,
            )

        BleDiscoveryState.Unsupported ->
            DiscoveryCard(
                title = "Bluetooth LE unavailable",
                body = "This device can't advertise and scan over Bluetooth LE, " +
                    "so it can't join the mesh over Bluetooth.",
                actionLabel = null,
                onAction = {},
                modifier = modifier,
            )

        is BleDiscoveryState.Error ->
            DiscoveryCard(
                title = "Bluetooth discovery failed",
                body = discovery.message,
                actionLabel = "Retry",
                onAction = onRetry,
                modifier = modifier,
            )

        // Idle and Scanning need no separate action; the card's own status line
        // already states scanning is tied to this screen.
        BleDiscoveryState.Idle, is BleDiscoveryState.Scanning -> Unit
    }
}

private fun usesNearbyDevices(permissions: Set<String>): Boolean =
    permissions.any { it == android.Manifest.permission.BLUETOOTH_SCAN }

@Composable
private fun DiscoveryCard(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalMeshTokens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .hairlineBorder(RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MetaTextStyle, color = tokens.meta)
        if (actionLabel != null) {
            OutlinedButton(
                onClick = onAction,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(actionLabel)
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
            modifier = Modifier.semantics { contentDescription = "Local mesh only" },
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
            discovery = BleDiscoveryState.PermissionRequired(
                setOf(android.Manifest.permission.BLUETOOTH_SCAN),
            ),
            permissionDenied = false,
            onToggleTransport = { _, _ -> },
            onLocalMeshOnly = {},
            onGrantPermissions = {},
            onOpenAppSettings = {},
            onOpenBluetoothSettings = {},
            onRetry = {},
        )
    }
}

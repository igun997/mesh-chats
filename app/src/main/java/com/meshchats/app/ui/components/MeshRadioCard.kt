package com.meshchats.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meshchats.app.core.mesh.TransportState
import com.meshchats.app.core.mesh.TransportStatus
import com.meshchats.app.ui.theme.LocalMeshTokens
import com.meshchats.app.ui.theme.MeshChatsTheme
import com.meshchats.app.ui.theme.MetaTextStyle
import com.meshchats.app.ui.theme.TabularTextStyle

/**
 * One card per transport. Shows the numbers that actually change behaviour:
 * peers, throughput, and for LoRa the regional duty cycle that legally throttles
 * how often the radio may transmit.
 */
@Composable
fun MeshRadioCard(
    status: TransportStatus,
    onToggle: (Boolean) -> Unit,
    onAttach: () -> Unit,
    modifier: Modifier = Modifier,
    showToggle: Boolean = true,
    // Bluetooth's switch reflects the user's *persisted intent*, not the derived
    // transport status: the row can read Off (Bluetooth disabled, permission
    // missing) while the user still wants BLE on. Pass the intent here so the
    // switch stays checked; null falls back to the derived availability.
    checkedOverride: Boolean? = null,
    // Gates whether the switch is actionable. Bluetooth passes its persisted
    // intent's `loaded` flag: while the stored value is still unknown the switch
    // is disabled so a transient OFF never reads as an actionable off-state the
    // user could accidentally toggle against.
    toggleEnabled: Boolean = true,
    // Overrides the switch's accessibility label. Bluetooth passes
    // "Bluetooth discovery" since it routes to the discovery intent rather than
    // the raw transport; other transports keep their transport label.
    toggleContentDescription: String? = null,
) {
    val tokens = LocalMeshTokens.current
    val shape = RoundedCornerShape(12.dp)
    val absent = status.state is TransportState.Absent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .hairlineBorder(shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportGlyph(status, size = 22.dp)

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = status.id.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(text = status.detail, style = MetaTextStyle, color = tokens.meta)
            Text(text = status.statsLine(), style = TabularTextStyle, color = tokens.secondary)

            status.constraints.dutyCyclePercent?.let { duty ->
                DutyCycleBar(duty)
            }
        }

        if (absent && showToggle) {
            TextButton(onClick = onAttach) { Text("Attach") }
        } else if (!absent && showToggle) {
            Switch(
                checked = checkedOverride ?: status.isAvailable,
                onCheckedChange = onToggle,
                enabled = toggleEnabled,
                modifier = Modifier.semantics {
                    // Label only: the Switch role already announces on/off, so
                    // appending "enabled" would contradict the off state.
                    contentDescription = toggleContentDescription ?: status.id.label
                },
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
}

@Composable
private fun DutyCycleBar(percent: Float) {
    val tokens = LocalMeshTokens.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 2.dp),
    ) {
        Box(
            Modifier
                .width(72.dp)
                .height(3.dp)
                .background(tokens.hairline),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((percent / 10f).coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(tokens.glyphActive),
            )
        }
        Text("duty ${"%.1f".format(percent)}%", style = MetaTextStyle, color = tokens.meta)
    }
}

private fun TransportStatus.statsLine(): String = when (val state = state) {
    is TransportState.Active -> {
        val peers = if (state.peers == 1) "1 peer" else "${state.peers} peers"
        "$peers · ${state.throughputBps.humanRate()} · ≤${constraints.maxPayloadBytes.humanBytes()}"
    }
    TransportState.Idle -> "on · no peers · ≤${constraints.maxPayloadBytes.humanBytes()}"
    TransportState.Off -> "off"
    TransportState.Absent -> "not attached"
}

private fun Long.humanRate(): String = when {
    this >= 1_000_000 -> "${this / 1_000_000} MB/s"
    this >= 1_000 -> "${this / 1_000} kB/s"
    else -> "$this B/s"
}

private fun Int.humanBytes(): String = when {
    this >= 1_048_576 -> "${this / 1_048_576} MB"
    this >= 1_024 -> "${this / 1_024} kB"
    else -> "$this B"
}

@Preview(backgroundColor = 0xFF000000, showBackground = true, widthDp = 360)
@Composable
private fun MeshRadioCardPreview() {
    MeshChatsTheme(darkTheme = true) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            previewMeshState(loraAttached = true).transports.forEach { status ->
                MeshRadioCard(status = status, onToggle = {}, onAttach = {})
            }
        }
    }
}

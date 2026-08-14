package com.meshchats.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meshchats.app.core.mesh.TransportId
import com.meshchats.app.core.mesh.TransportState
import com.meshchats.app.core.mesh.TransportStatus
import com.meshchats.app.ui.theme.LocalMeshTokens

/**
 * Transport state without color, using shape and weight rather than hue:
 * - **Active** (carrying traffic): filled glyph in white [glyphActive].
 * - **Idle / on / scanning**: outlined glyph, also white [glyphActive] — an
 *   enabled radio reads as present, the fill (not the tint) is what marks
 *   whether it is actually carrying traffic.
 * - **Off / Absent**: outlined glyph struck through in the dim [glyphOff].
 */
@Composable
fun TransportGlyph(
    status: TransportStatus,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    val tokens = LocalMeshTokens.current
    val filled = status.isCarrying
    val struck = status.state is TransportState.Off || status.state is TransportState.Absent
    // On (filled or idle/scanning) is white; only off/absent dims and strikes.
    val tint = if (struck) tokens.glyphOff else tokens.glyphActive

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = status.id.icon(filled = filled),
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(size)
                .clearAndSetSemantics {},
        )
        if (struck) {
            Canvas(Modifier.size(size)) {
                drawLine(
                    color = tint,
                    start = androidx.compose.ui.geometry.Offset(size.toPx() * 0.12f, size.toPx() * 0.88f),
                    end = androidx.compose.ui.geometry.Offset(size.toPx() * 0.88f, size.toPx() * 0.12f),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private fun TransportId.icon(filled: Boolean): ImageVector = when (this) {
    TransportId.WIFI -> if (filled) Icons.Filled.Wifi else Icons.Outlined.Wifi
    TransportId.BT -> if (filled) Icons.Filled.Bluetooth else Icons.Outlined.Bluetooth
    TransportId.LORA ->
        if (filled) Icons.Filled.SettingsInputAntenna else Icons.Outlined.SettingsInputAntenna
    TransportId.RELAY -> if (filled) Icons.Filled.Public else Icons.Outlined.Public
}

/** Spoken description used by the merged transport header-status node. */
fun TransportStatus.spokenState(): String = when (val state = state) {
    is TransportState.Active -> {
        val noun = if (state.peers == 1) "peer" else "peers"
        "${id.shortLabel} active, ${state.peers} $noun"
    }
    TransportState.Idle -> "${id.shortLabel} on, no peers"
    TransportState.Off -> "${id.shortLabel} off"
    TransportState.Absent -> "${id.shortLabel} not attached"
}

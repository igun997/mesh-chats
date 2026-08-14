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
 * Transport state without color: filled glyph = carrying traffic, outlined = on
 * but idle, struck through = off or hardware absent.
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
    val tint = when {
        filled -> tokens.glyphActive
        struck -> tokens.glyphOff
        else -> tokens.glyphIdle
    }

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

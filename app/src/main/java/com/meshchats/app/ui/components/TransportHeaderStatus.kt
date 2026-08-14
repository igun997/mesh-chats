package com.meshchats.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meshchats.app.core.mesh.MeshState
import com.meshchats.app.ui.theme.LocalMeshTokens
import com.meshchats.app.ui.theme.MeshChatsTheme
import com.meshchats.app.ui.theme.MeshSpec
import com.meshchats.app.ui.theme.MetaTextStyle

/**
 * Compact, always-visible mesh status for the top-app-bar action slot.
 *
 * All four transports remain visible without spending a second header row. The
 * whole cluster is one 48dp-tall target that opens Mesh; TalkBack receives one
 * status summary instead of four unlabelled glyphs.
 */
@Composable
fun TransportHeaderStatus(
    state: MeshState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalMeshTokens.current
    val peerLabel = if (state.peerCount == 1) "1 peer" else "${state.peerCount} peers"
    val spoken = remember(state) {
        buildString {
            append("Mesh: ")
            append(state.transports.joinToString(", ") { it.spokenState() })
            append(". $peerLabel reachable.")
            if (state.localMeshOnly) append(" Local mesh only.")
        }
    }

    Row(
        modifier = modifier
            .height(MeshSpec.minTouchTarget)
            .widthIn(max = 280.dp)
            .clickable(
                onClickLabel = "Open mesh",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        state.transports.forEach { status -> TransportGlyph(status) }
        Text(
            text = peerLabel,
            style = MetaTextStyle,
            color = tokens.meta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 1.dp),
        )
    }
}

@Preview(name = "header status dark", backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun TransportHeaderStatusPreviewDark() {
    MeshChatsTheme(darkTheme = true) {
        TransportHeaderStatus(state = previewMeshState(), onClick = {})
    }
}

@Preview(name = "header status light", backgroundColor = 0xFFFFFFFF, showBackground = true)
@Composable
private fun TransportHeaderStatusPreviewLight() {
    MeshChatsTheme(darkTheme = false) {
        TransportHeaderStatus(state = previewMeshState(), onClick = {})
    }
}

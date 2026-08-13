package com.meshchats.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meshchats.app.core.mesh.MeshState
import com.meshchats.app.ui.theme.LocalMeshTokens
import com.meshchats.app.ui.theme.MeshChatsTheme
import com.meshchats.app.ui.theme.MeshSpec
import com.meshchats.app.ui.theme.MetaTextStyle

/**
 * Always-on trust indicator, pinned under every top bar. One merged accessibility
 * node so TalkBack reads the whole radio picture in a single utterance instead of
 * four unlabelled icons.
 */
@Composable
fun TransportStrip(
    state: MeshState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalMeshTokens.current
    val spoken = remember(state) {
        buildString {
            append("Mesh: ")
            append(state.transports.joinToString(", ") { it.spokenState() })
            append(". ${state.peerCount} peers reachable.")
            if (state.localMeshOnly) append(" Local mesh only.")
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(MeshSpec.transportStripHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = MeshSpec.screenPadding)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.transports.forEach { status -> TransportGlyph(status) }

        Text(
            text = if (state.localMeshOnly) "local only" else "",
            style = MetaTextStyle,
            color = tokens.meta,
            modifier = Modifier.padding(start = 2.dp),
        )

        Text(
            text = "${state.peerCount} peers",
            style = MetaTextStyle,
            color = tokens.meta,
            textAlign = TextAlign.End,
            modifier = Modifier
                .weight(1f)
                .padding(start = 2.dp),
        )
    }
}

@Preview(name = "strip dark", backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun TransportStripPreviewDark() {
    MeshChatsTheme(darkTheme = true) {
        TransportStrip(state = previewMeshState(), onClick = {})
    }
}

@Preview(name = "strip light", backgroundColor = 0xFFFFFFFF, showBackground = true)
@Composable
private fun TransportStripPreviewLight() {
    MeshChatsTheme(darkTheme = false) {
        TransportStrip(state = previewMeshState(), onClick = {})
    }
}

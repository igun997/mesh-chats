package com.meshchats.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshchats.app.core.mesh.MeshState
import com.meshchats.app.core.mesh.Peer
import com.meshchats.app.ui.components.previewMeshState
import com.meshchats.app.ui.mesh.MeshViewModel
import com.meshchats.app.ui.shell.LocalShellBottomPadding
import com.meshchats.app.ui.shell.MeshScreenScaffold
import com.meshchats.app.ui.theme.FingerprintTextStyle
import com.meshchats.app.ui.theme.LocalMeshTokens
import com.meshchats.app.ui.theme.MeshChatsTheme
import com.meshchats.app.ui.theme.MeshSpec
import com.meshchats.app.ui.theme.MetaTextStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radar view: the tile-less fallback from the design. Shown when no offline region
 * covers the current area, so the map tab is useful on LoRa-only with no tiles
 * instead of showing an empty grey canvas. MapLibre tiles replace the backdrop
 * once offline regions exist; peers keep the same puck treatment.
 */
@Composable
fun MapScreen(
    onOpenMesh: () -> Unit,
    viewModel: MeshViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MapContent(state = state, onOpenMesh = onOpenMesh)
}

@Composable
private fun MapContent(state: MeshState, onOpenMesh: () -> Unit) {
    val tokens = LocalMeshTokens.current
    val measurer = rememberTextMeasurer()
    val bottomClearance = LocalShellBottomPadding.current

    MeshScreenScaffold(title = "Map", meshState = state, onOpenMesh = onOpenMesh) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MeshSpec.screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "No offline tiles for this area — showing radar",
                style = MetaTextStyle,
                color = tokens.meta,
                modifier = Modifier.padding(top = 8.dp),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(top = 12.dp),
            ) {
                Radar(
                    peers = state.peers,
                    measurer = measurer,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.peers.filter { it.isReachable }.forEach { peer ->
                    Text(
                        text = "${peer.monogram}  ${peer.bearingDegrees().toInt()}°  " +
                            "${peer.approxDistanceMeters()} m  ${peer.displayName}",
                        style = FingerprintTextStyle,
                        color = tokens.secondary,
                    )
                }
            }

            Text(
                text = "DOWNLOAD THIS AREA",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .padding(top = 20.dp, bottom = bottomClearance + 24.dp)
                    .clickable {}
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun Radar(peers: List<Peer>, measurer: TextMeasurer, modifier: Modifier = Modifier) {
    val tokens = LocalMeshTokens.current
    val ringLabels = listOf("100 m", "500 m", "2 km")

    Canvas(modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val maxRadius = size.minDimension / 2 - 8.dp.toPx()
        val hairline = 1.dp.toPx()

        // Distance rings.
        ringLabels.forEachIndexed { index, label ->
            val radius = maxRadius * (index + 1) / ringLabels.size
            drawCircle(
                color = tokens.hairline,
                radius = radius,
                center = center,
                style = Stroke(hairline),
            )
            val layout = measurer.measure(label, style = MetaTextStyle.copy(color = tokens.meta))
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(center.x + 4.dp.toPx(), center.y - radius - layout.size.height),
            )
        }

        // Cardinal ticks: heading up.
        listOf(0f, 90f, 180f, 270f).forEach { degrees ->
            val radians = (degrees - 90f) * PI.toFloat() / 180f
            drawLine(
                color = tokens.hairline,
                start = center,
                end = Offset(
                    center.x + cos(radians) * maxRadius,
                    center.y + sin(radians) * maxRadius,
                ),
                strokeWidth = hairline,
            )
        }

        // Self.
        drawCircle(color = tokens.glyphActive, radius = 4.dp.toPx(), center = center)

        // Peers by bearing and approximate distance.
        peers.filter { it.isReachable }.forEach { peer ->
            val radians = (peer.bearingDegrees() - 90f) * PI.toFloat() / 180f
            val ratio = (peer.approxDistanceMeters() / 2_000f).coerceIn(0.08f, 1f)
            val position = Offset(
                center.x + cos(radians) * maxRadius * ratio,
                center.y + sin(radians) * maxRadius * ratio,
            )
            if (peer.verified) {
                drawCircle(tokens.glyphActive, radius = 7.dp.toPx(), center = position)
            } else {
                drawCircle(
                    tokens.glyphActive,
                    radius = 7.dp.toPx(),
                    center = position,
                    style = Stroke(hairline * 1.5f),
                )
            }
            val layout = measurer.measure(
                peer.monogram,
                style = MetaTextStyle.copy(color = tokens.secondary),
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(position.x + 10.dp.toPx(), position.y - layout.size.height / 2f),
            )
        }
    }
}

/** Deterministic placeholder bearing until real position reports land. */
private fun Peer.bearingDegrees(): Float = ((id.hashCode() % 360) + 360) % 360f

/** Rough free-space estimate from RSSI, good enough for a radar ring. */
private fun Peer.approxDistanceMeters(): Int {
    val rssi = rssiDbm ?: return 2_000
    return ((-rssi - 40) * 22).coerceIn(10, 2_000)
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 360, heightDp = 780)
@Composable
private fun MapPreview() {
    MeshChatsTheme(darkTheme = true) {
        MapContent(state = previewMeshState(), onOpenMesh = {})
    }
}

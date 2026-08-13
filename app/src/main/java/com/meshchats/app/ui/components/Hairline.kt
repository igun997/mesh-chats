package com.meshchats.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import com.meshchats.app.ui.theme.LocalMeshTokens
import com.meshchats.app.ui.theme.MeshSpec

/**
 * 1dp divider at 14% onSurface. Replaces elevation everywhere: shadows read as
 * grey smudge in a monochrome UI.
 */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(MeshSpec.hairlineWidth)
            .background(LocalMeshTokens.current.hairline),
    )
}

/** Vertical variant, used between rail and content at Medium+ width. */
@Composable
fun VerticalHairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxHeight()
            .width(MeshSpec.hairlineWidth)
            .background(LocalMeshTokens.current.hairline),
    )
}

/** Hairline outline, the only "container" treatment used by cards and chips. */
@Composable
fun Modifier.hairlineBorder(shape: Shape = RectangleShape): Modifier =
    border(MeshSpec.hairlineWidth, LocalMeshTokens.current.hairline, shape)

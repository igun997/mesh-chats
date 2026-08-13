package com.meshchats.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshchats.app.ui.theme.LocalMeshTokens
import com.meshchats.app.ui.theme.MeshChatsTheme

/**
 * Avatar derived from the key fingerprint, not from a name: names are unverifiable
 * and collide, key material does not. Hairline ring = unverified, solid ring +
 * corner mark = verified.
 */
@Composable
fun PeerMonogram(
    monogram: String,
    verified: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val tokens = LocalMeshTokens.current
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (verified) 2.dp else 1.dp,
                color = if (verified) tokens.glyphActive else tokens.hairline,
                shape = shape,
            )
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = monogram.take(2) + "\n" + monogram.drop(2).take(2),
            color = if (verified) tokens.glyphActive else tokens.secondary,
            fontFamily = FontFamily.Monospace,
            fontSize = (size.value * 0.22f).sp,
            lineHeight = (size.value * 0.26f).sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PeerMonogramPreview() {
    MeshChatsTheme(darkTheme = true) {
        Box {
            PeerMonogram(monogram = "ADLN", verified = true)
        }
    }
}

@Preview(backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PeerMonogramUnverifiedPreview() {
    MeshChatsTheme(darkTheme = true) {
        Box {
            PeerMonogram(monogram = "CHMF", verified = false)
        }
    }
}

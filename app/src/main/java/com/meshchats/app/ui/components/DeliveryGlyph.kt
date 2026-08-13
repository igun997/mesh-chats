package com.meshchats.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meshchats.app.ui.theme.LocalMeshTokens

enum class DeliveryState(val spoken: String) {
    QUEUED("queued"),
    SENT("sent"),
    DELIVERED("delivered"),
    FAILED("failed"),
}

/**
 * Delivery state as shape, never color: hollow, half, filled, cross. Drawn rather
 * than typed so it renders identically regardless of the system font.
 */
@Composable
fun DeliveryGlyph(
    state: DeliveryState,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
) {
    val tokens = LocalMeshTokens.current
    val color = if (state == DeliveryState.QUEUED) tokens.glyphOff else tokens.meta

    Canvas(
        modifier
            .size(size)
            .clearAndSetSemantics {},
    ) {
        val stroke = 1.2.dp.toPx()
        val inset = stroke / 2
        val box = Rect(
            offset = Offset(inset, inset),
            size = Size(this.size.width - stroke, this.size.height - stroke),
        )
        when (state) {
            DeliveryState.QUEUED ->
                drawCircle(color, radius = box.width / 2, center = center, style = Stroke(stroke))

            DeliveryState.SENT -> {
                drawCircle(color, radius = box.width / 2, center = center, style = Stroke(stroke))
                drawArc(
                    color = color,
                    startAngle = 90f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = box.topLeft,
                    size = box.size,
                )
            }

            DeliveryState.DELIVERED -> drawCircle(color, radius = box.width / 2, center = center)

            DeliveryState.FAILED -> {
                drawLine(
                    color,
                    Offset(box.left, box.top),
                    Offset(box.right, box.bottom),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(box.right, box.top),
                    Offset(box.left, box.bottom),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

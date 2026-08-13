package com.meshchats.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshchats.app.ui.theme.LocalMeshTokens
import com.meshchats.app.ui.theme.MeshChatsTheme
import com.meshchats.app.ui.theme.MeshSpec

/**
 * The only inverted element in the app, so it always reads as the emergency
 * control without a single red pixel.
 *
 * Hold [MeshSpec.ARM_HOLD_MILLIS] to arm; releasing early aborts. A custom
 * accessibility action arms without any timed hold, because a timed gesture is
 * not a reasonable requirement for a life-safety control.
 */
@Composable
fun SosDock(
    onArmed: () -> Unit,
    modifier: Modifier = Modifier,
    onAbort: () -> Unit = {},
) {
    val tokens = LocalMeshTokens.current
    val haptics = LocalHapticFeedback.current
    val progress = remember { Animatable(0f) }
    val armed = rememberUpdatedState(onArmed)
    val aborted = rememberUpdatedState(onAbort)
    var pressed by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (pressed) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(MeshSpec.ARM_HOLD_MILLIS, easing = LinearEasing),
            )
            if (progress.value >= 1f) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                armed.value()
                progress.snapTo(0f)
            }
        } else if (progress.value > 0f) {
            progress.animateTo(0f, tween(150))
            aborted.value()
        }
    }

    // Mid-hold tick so the user feels commitment building.
    LaunchedEffect(Unit) {
        var ticked = false
        snapshotFlow { progress.value }.collect { value ->
            if (value >= 0.5f && !ticked) {
                ticked = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            if (value == 0f) ticked = false
        }
    }

    Box(
        modifier = modifier
            .size(MeshSpec.sosDockSize)
            .clip(CircleShape)
            .background(tokens.alarmBackground)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                )
            }
            .semantics {
                role = Role.Button
                contentDescription = "SOS, hold to arm"
                customActions = listOf(
                    CustomAccessibilityAction("Arm SOS") {
                        armed.value()
                        true
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "SOS",
            color = tokens.alarmContent,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        )

        val sweep = progress.value
        if (sweep > 0f) {
            Canvas(Modifier.size(MeshSpec.sosDockSize)) {
                val stroke = 3.dp.toPx()
                drawArc(
                    color = tokens.alarmContent,
                    startAngle = -90f,
                    sweepAngle = 360f * sweep,
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Preview(backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun SosDockPreviewDark() {
    MeshChatsTheme(darkTheme = true) { SosDock(onArmed = {}) }
}

@Preview(backgroundColor = 0xFFFFFFFF, showBackground = true)
@Composable
private fun SosDockPreviewLight() {
    MeshChatsTheme(darkTheme = false) { SosDock(onArmed = {}) }
}

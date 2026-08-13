package com.meshchats.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Destructive confirmation by hold, with a fill that shows progress. Used where a
 * single tap would be dangerous (stopping an active SOS). Also exposes a custom
 * accessibility action so the hold is never the only path.
 */
@Composable
fun HoldToConfirmButton(
    label: String,
    holdMillis: Int,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    fillColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    val haptics = LocalHapticFeedback.current
    val progress = remember { Animatable(0f) }
    val confirmed = rememberUpdatedState(onConfirmed)
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    LaunchedEffect(pressed) {
        if (pressed) {
            progress.animateTo(1f, tween(holdMillis, easing = LinearEasing))
            if (progress.value >= 1f) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                confirmed.value()
                progress.snapTo(0f)
            }
        } else {
            progress.animateTo(0f, tween(120))
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .hairlineBorder(shape)
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
                contentDescription = "$label, hold to confirm"
                customActions = listOf(
                    CustomAccessibilityAction(label) {
                        confirmed.value()
                        true
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.value)
                .fillMaxHeight()
                .background(fillColor),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}

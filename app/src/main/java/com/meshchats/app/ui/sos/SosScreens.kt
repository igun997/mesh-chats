package com.meshchats.app.ui.sos

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meshchats.app.ui.components.HoldToConfirmButton
import com.meshchats.app.ui.theme.LocalMeshTokens
import com.meshchats.app.ui.theme.MeshChatsTheme
import com.meshchats.app.ui.theme.MeshSpec
import com.meshchats.app.ui.theme.MetaTextStyle
import com.meshchats.app.ui.theme.TabularTextStyle
import com.meshchats.app.ui.theme.TimerTextStyle
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay

/** 10-second cancel window after the dock's hold-to-arm gesture. */
@Composable
fun SosCountdownScreen(
    onCancel: () -> Unit,
    onFire: () -> Unit,
) {
    val tokens = LocalMeshTokens.current
    val deadline = rememberSaveable {
        System.currentTimeMillis() + MeshSpec.COUNTDOWN_SECONDS * 1_000L
    }
    var seconds by remember { mutableIntStateOf(MeshSpec.COUNTDOWN_SECONDS) }

    AlarmWindow()
    BackHandler(onBack = onCancel)

    LaunchedEffect(deadline) {
        while (seconds > 0) {
            seconds = ((deadline - System.currentTimeMillis() + 999L) / 1_000L)
                .toInt()
                .coerceAtLeast(0)
            if (seconds > 0) delay(100)
        }
        onFire()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.alarmBackground)
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "SOS ARMED",
            style = MaterialTheme.typography.titleLarge,
            color = tokens.alarmContent,
            modifier = Modifier.padding(top = 24.dp),
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = seconds.toString().padStart(2, '0'),
                style = TimerTextStyle,
                color = tokens.alarmContent,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Text(
                text = "Sending to 3 verified contacts\n+ nearby mesh",
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.alarmContent.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        TextButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Text(
                text = "CANCEL",
                color = tokens.alarmContent,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
    }
}

/** Active beacon dashboard. Inversion stays on until the user stops the alert. */
@Composable
fun SosActiveScreen(
    onStop: () -> Unit,
    onCallEmergency: () -> Unit,
) {
    val tokens = LocalMeshTokens.current
    val startedAt = rememberSaveable { System.currentTimeMillis() }
    var elapsed by remember { mutableIntStateOf(0) }

    AlarmWindow()
    BackHandler(enabled = true) { /* active SOS cannot be dismissed with Back */ }

    LaunchedEffect(startedAt) {
        while (true) {
            elapsed = ((System.currentTimeMillis() - startedAt) / 1_000L).toInt()
            delay(250)
        }
    }

    val minutes = elapsed / 60
    val seconds = elapsed % 60

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.alarmBackground)
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "SOS ACTIVE",
                style = MaterialTheme.typography.titleLarge,
                color = tokens.alarmContent,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}",
                style = TabularTextStyle.copy(fontWeight = FontWeight.Bold),
                color = tokens.alarmContent,
            )
        }

        Text(
            text = "BEACON #1 · WIFI + BT",
            style = MetaTextStyle,
            color = tokens.alarmContent.copy(alpha = 0.72f),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "2 OF 3 ACKNOWLEDGED",
                style = MaterialTheme.typography.titleMedium,
                color = tokens.alarmContent,
            )
            Text(
                text = "ADLN  ✓    BQTS  ✓    DOST  ○",
                style = MetaTextStyle,
                color = tokens.alarmContent.copy(alpha = 0.72f),
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "Battery 43% · repeating every 30s",
                style = MetaTextStyle,
                color = tokens.alarmContent.copy(alpha = 0.72f),
                modifier = Modifier.padding(top = 24.dp),
            )
        }

        TextButton(onClick = onCallEmergency, modifier = Modifier.fillMaxWidth()) {
            Text(
                "CALL EMERGENCY SERVICES",
                color = tokens.alarmContent,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(12.dp),
            )
        }

        HoldToConfirmButton(
            label = "HOLD TO STOP SOS",
            holdMillis = MeshSpec.STOP_HOLD_MILLIS,
            onConfirmed = onStop,
            contentColor = tokens.alarmContent,
            fillColor = tokens.alarmContent.copy(alpha = 0.16f),
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

/**
 * White alarm surface needs dark system-bar icons. Keep screen awake and force full
 * brightness for the emergency surface; restore every window value on exit.
 */
@Composable
private fun AlarmWindow() {
    val view = LocalView.current
    if (view.isInEditMode) return
    val activity = view.context.findActivity() ?: return

    DisposableEffect(view, activity) {
        val window = activity.window
        val previousBrightness = window.attributes.screenBrightness
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1f }

        onDispose {
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = previousBrightness }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun CountdownPreview() {
    MeshChatsTheme(darkTheme = true) {
        SosCountdownScreen(onCancel = {}, onFire = {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun ActivePreview() {
    MeshChatsTheme(darkTheme = true) {
        SosActiveScreen(onStop = {}, onCallEmergency = {})
    }
}

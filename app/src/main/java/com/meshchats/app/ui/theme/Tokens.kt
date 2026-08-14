package com.meshchats.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Semantic tokens Material 3 has no slot for. Everything the monochrome design
 * needs beyond a ColorScheme lives here so components never hardcode alpha.
 */
@Immutable
data class MeshTokens(
    val hairline: Color,
    val glyphActive: Color,
    val glyphIdle: Color,
    val glyphOff: Color,
    val meta: Color,
    val secondary: Color,
    /** Inverse surface, reserved exclusively for SOS. Nothing else may use it. */
    val alarmBackground: Color,
    val alarmContent: Color,
)

val LocalMeshTokens = staticCompositionLocalOf<MeshTokens> {
    error("MeshTokens not provided; wrap content in MeshChatsTheme")
}

/** Layout constants that the insets contract and touch-target rules depend on. */
object MeshSpec {
    val hairlineWidth: Dp = 1.dp

    /** SOS dock diameter. Above the 48dp minimum on purpose. */
    val sosDockSize: Dp = 64.dp

    /** Dock float above the navigation bar. */
    val sosDockOverhang: Dp = 12.dp

    val minTouchTarget: Dp = 48.dp

    val screenPadding: Dp = 16.dp

    /** Hold duration to arm SOS. */
    const val ARM_HOLD_MILLIS = 1_500

    /** Cancel window after arming, before the beacon fires. */
    const val COUNTDOWN_SECONDS = 10

    /** Hold duration to stop an active SOS. */
    const val STOP_HOLD_MILLIS = 2_000
}

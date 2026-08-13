package com.meshchats.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Sans = FontFamily.SansSerif

/**
 * Tabular figures. Applied to every counter in the app (SOS timer, byte budget,
 * hop count, RSSI, peer count) so digits keep a fixed advance width and numbers
 * stop jittering as they tick.
 */
private const val TABULAR = "tnum"

val MeshTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
)

/** Metadata line style: 12sp, wide tracking, tabular digits. */
val MetaTextStyle = MeshTypography.labelSmall.copy(fontFeatureSettings = TABULAR)

/** Counters and timers that must not reflow while updating. */
val TabularTextStyle = MeshTypography.bodyMedium.copy(fontFeatureSettings = TABULAR)

/** Large countdown / elapsed timers on the SOS screens. */
val TimerTextStyle = MeshTypography.displaySmall.copy(
    fontFeatureSettings = TABULAR,
    fontSize = 72.sp,
    lineHeight = 80.sp,
    fontWeight = FontWeight.Bold,
)

/** Key fingerprints and IDs: monospace so word boundaries are unambiguous. */
val FingerprintTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.2.sp,
)

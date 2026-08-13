package com.meshchats.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Paper0,
    onPrimary = Ink0,
    primaryContainer = Ink3,
    onPrimaryContainer = Paper0,
    secondary = Paper0.copy(alpha = AlphaSecondary),
    onSecondary = Ink0,
    background = Ink0,
    onBackground = Paper0,
    surface = Ink1,
    onSurface = Paper0,
    surfaceVariant = Ink2,
    onSurfaceVariant = Paper0.copy(alpha = AlphaSecondary),
    surfaceContainerHighest = Ink3,
    outline = Paper0.copy(alpha = AlphaHairline),
    outlineVariant = Paper0.copy(alpha = AlphaHairline),
    inverseSurface = Paper0,
    inverseOnSurface = Ink0,
    // Monochrome by contract: errors are communicated by weight, glyph and copy.
    error = Paper0,
    onError = Ink0,
    errorContainer = Ink3,
    onErrorContainer = Paper0,
)

private val LightScheme = lightColorScheme(
    primary = Ink0,
    onPrimary = Paper0,
    primaryContainer = Paper3,
    onPrimaryContainer = Ink0,
    secondary = Ink0.copy(alpha = AlphaSecondary),
    onSecondary = Paper0,
    background = Paper0,
    onBackground = Ink0,
    surface = Paper1,
    onSurface = Ink0,
    surfaceVariant = Paper2,
    onSurfaceVariant = Ink0.copy(alpha = AlphaSecondary),
    surfaceContainerHighest = Paper3,
    outline = Ink0.copy(alpha = AlphaHairline),
    outlineVariant = Ink0.copy(alpha = AlphaHairline),
    inverseSurface = Ink0,
    inverseOnSurface = Paper0,
    error = Ink0,
    onError = Paper0,
    errorContainer = Paper3,
    onErrorContainer = Ink0,
)

private fun tokensFor(onSurface: Color, inverseSurface: Color, inverseOnSurface: Color) =
    MeshTokens(
        hairline = onSurface.copy(alpha = AlphaHairline),
        glyphActive = onSurface,
        glyphIdle = onSurface.copy(alpha = AlphaMeta),
        glyphOff = onSurface.copy(alpha = AlphaDisabled),
        meta = onSurface.copy(alpha = AlphaMeta),
        secondary = onSurface.copy(alpha = AlphaSecondary),
        alarmBackground = inverseSurface,
        alarmContent = inverseOnSurface,
    )

/**
 * Monochrome app theme. Dynamic color is intentionally unsupported: Material You
 * would inject a different hue on every device and destroy the identity.
 */
@Composable
fun MeshChatsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val tokens = remember(darkTheme) {
        tokensFor(scheme.onSurface, scheme.inverseSurface, scheme.inverseOnSurface)
    }

    CompositionLocalProvider(LocalMeshTokens provides tokens) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MeshTypography,
            content = content,
        )
    }
}

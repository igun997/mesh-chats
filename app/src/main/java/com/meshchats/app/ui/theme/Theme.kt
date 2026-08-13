package com.meshchats.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightScheme = lightColorScheme(
    primary = MeshTealDark,
    onPrimary = Color.White,
    secondary = MeshSlate,
    background = MeshMist,
    surface = Color.White,
    error = MeshCoral,
)

private val DarkScheme = darkColorScheme(
    primary = MeshTeal,
    onPrimary = MeshInk,
    secondary = MeshMist,
    background = MeshInk,
    surface = MeshSlate,
    error = MeshCoral,
)

/**
 * App theme. Material You dynamic color on Android 12+, brand palette below elsewhere.
 */
@Composable
fun MeshChatsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MeshTypography,
        content = content,
    )
}

package com.meshchats.app.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meshchats.app.core.mesh.MeshState
import com.meshchats.app.ui.components.HairlineDivider
import com.meshchats.app.ui.components.TransportStrip
import com.meshchats.app.ui.theme.MeshSpec

/**
 * Bottom clearance published by the shell: navigation bar inset + tab bar height +
 * SOS dock overhang. Screens add it to their own content padding so list tails are
 * never hidden behind the dock. One value, no per-screen guessing.
 */
val LocalShellBottomPadding = staticCompositionLocalOf { 0.dp }

@Composable
fun ProvideShellBottomPadding(value: Dp, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalShellBottomPadding provides value, content = content)
}

/**
 * Standard screen frame: top bar, always-on transport strip, and top-inset
 * handling. The shell owns the bottom edge, so this consumes top insets only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshScreenScaffold(
    title: String,
    meshState: MeshState,
    onOpenMesh: () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    showTransportStrip: Boolean = true,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = navigationIcon,
                    actions = actions,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                if (showTransportStrip) {
                    TransportStrip(state = meshState, onClick = onOpenMesh)
                    HairlineDivider()
                }
            }
        },
        bottomBar = bottomBar,
        content = content,
    )
}

/** Content padding for scrolling screens: screen insets plus dock clearance. */
@Composable
fun PaddingValues.withShellClearance(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        top = calculateTopPadding(),
        end = calculateEndPadding(direction),
        bottom = calculateBottomPadding() +
            LocalShellBottomPadding.current +
            MeshSpec.sosDockOverhang,
    )
}

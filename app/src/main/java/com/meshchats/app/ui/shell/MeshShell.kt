package com.meshchats.app.ui.shell

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.meshchats.app.ui.components.SosDock
import com.meshchats.app.ui.components.VerticalHairline
import com.meshchats.app.ui.navigation.ChatsRoute
import com.meshchats.app.ui.navigation.MapRoute
import com.meshchats.app.ui.navigation.MeshRoute
import com.meshchats.app.ui.navigation.SettingsRoute
import com.meshchats.app.ui.navigation.SosActiveRoute
import com.meshchats.app.ui.navigation.SosCountdownRoute
import com.meshchats.app.ui.navigation.TopLevelDestination
import com.meshchats.app.ui.theme.MeshSpec

/**
 * App shell: four tabs, docked SOS, and the single bottom-clearance contract every
 * screen reads. At Medium width and above the tab bar becomes a navigation rail and
 * the dock moves to the rail, so foldables and tablets do not get a phone layout.
 */
@Composable
fun MeshShell(
    navController: NavHostController,
    onSosArmed: () -> Unit,
    content: @Composable () -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val selected = remember(destination) {
        TopLevelDestination.entries.firstOrNull { dest ->
            when (dest) {
                TopLevelDestination.CHATS -> destination?.hasRoute(ChatsRoute::class) == true
                TopLevelDestination.MAP -> destination?.hasRoute(MapRoute::class) == true
                TopLevelDestination.MESH -> destination?.hasRoute(MeshRoute::class) == true
                TopLevelDestination.SETTINGS -> destination?.hasRoute(SettingsRoute::class) == true
            }
        }
    }

    val sizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val useRail = sizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val isTopLevel = selected != null
    val isSosRoute = destination?.hasRoute(SosCountdownRoute::class) == true ||
        destination?.hasRoute(SosActiveRoute::class) == true

    val onSelect: (TopLevelDestination) -> Unit = { dest ->
        navController.navigate(dest.route) {
            popUpTo(ChatsRoute) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        if (useRail && !isSosRoute) {
            Row(Modifier.fillMaxSize()) {
                MeshNavRail(
                    selected = selected,
                    onSelect = onSelect,
                    onSosArmed = onSosArmed,
                    showSos = selected != null,
                )
                VerticalHairline()
                ProvideShellBottomPadding(0.dp) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                // Dock top edge sits navBarInset + 24dp + 64dp above the bottom.
                // Reserve that full span so list tails never hide behind it.
                val dockClearance = navBarInset + 24.dp + MeshSpec.sosDockSize
                ProvideShellBottomPadding(if (isTopLevel) dockClearance else 0.dp) {
                    content()
                }

                if (isTopLevel) {
                    Column(
                        Modifier.align(Alignment.BottomCenter),
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        MeshBottomBar(selected = selected, onSelect = onSelect)
                    }

                    // Dock hides with the keyboard so it never covers the composer.
                    if (!keyboardVisible) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = navBarInset + 24.dp),
                        ) {
                            PulsingSosDock(onArmed = onSosArmed)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Slow idle pulse so the control reads as live without color. Respects the system
 * animation scale: with animations disabled it renders static.
 */
@Composable
private fun PulsingSosDock(onArmed: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "sos-pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sos-scale",
    )

    SosDock(onArmed = onArmed, modifier = Modifier.scale(scale))
}

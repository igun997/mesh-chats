package com.meshchats.app.ui

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.meshchats.app.ui.chat.ChatScreen
import com.meshchats.app.ui.chats.ChatsScreen
import com.meshchats.app.ui.map.MapScreen
import com.meshchats.app.ui.mesh.MeshScreen
import com.meshchats.app.ui.navigation.ChatRoute
import com.meshchats.app.ui.navigation.ChatsRoute
import com.meshchats.app.ui.navigation.MapRoute
import com.meshchats.app.ui.navigation.MeshRoute
import com.meshchats.app.ui.navigation.SettingsRoute
import com.meshchats.app.ui.navigation.SosActiveRoute
import com.meshchats.app.ui.navigation.SosCountdownRoute
import com.meshchats.app.ui.settings.SettingsScreen
import com.meshchats.app.ui.shell.MeshShell
import com.meshchats.app.ui.sos.SosActiveScreen
import com.meshchats.app.ui.sos.SosCountdownScreen
import com.meshchats.app.ui.startup.StorageStartupGate
import com.meshchats.app.ui.startup.StorageStartupTags
import com.meshchats.app.ui.theme.MeshChatsTheme

@Composable
fun MeshChatsApp() {
    MeshChatsTheme(darkTheme = true) {
        // Gate every DB-backed surface behind encrypted-storage startup. Until the
        // coordinator reports Ready, no navController, shell, NavHost, or
        // hiltViewModel chat graph is composed — so nothing opens the database on
        // the main thread. The existing app shell is preserved exactly once Ready.
        StorageStartupGate {
            Box(Modifier.fillMaxSize().testTag(StorageStartupTags.READY_CONTENT)) {
                MeshChatsAppContent()
            }
        }
    }
}

/**
 * The app shell and navigation, composed only after encrypted storage is Ready.
 * Extracted so the gate can withhold every DB-backed composition (navController,
 * MeshShell, NavHost, chat ViewModels) until then.
 */
@Composable
private fun MeshChatsAppContent() {
    val navController = rememberNavController()
    val context = LocalContext.current

    MeshShell(
        navController = navController,
        onSosArmed = { navController.navigate(SosCountdownRoute) },
    ) {
        NavHost(navController = navController, startDestination = ChatsRoute) {
            composable<ChatsRoute> {
                ChatsScreen(
                    onOpenChat = { id -> navController.navigate(ChatRoute(id)) },
                    onOpenMesh = { navController.navigate(MeshRoute) },
                )
            }
            composable<MapRoute> {
                MapScreen(onOpenMesh = { navController.navigate(MeshRoute) })
            }
            composable<MeshRoute> { MeshScreen() }
            composable<SettingsRoute> {
                SettingsScreen(onOpenMesh = { navController.navigate(MeshRoute) })
            }
            composable<ChatRoute> {
                ChatScreen(onBack = { navController.popBackStack() })
            }
            composable<SosCountdownRoute> {
                SosCountdownScreen(
                    onCancel = { navController.popBackStack() },
                    onFire = {
                        navController.navigate(SosActiveRoute) {
                            popUpTo(SosCountdownRoute) { inclusive = true }
                        }
                    },
                )
            }
            composable<SosActiveRoute> {
                SosActiveScreen(
                    onStop = {
                        navController.navigate(ChatsRoute) {
                            popUpTo(ChatsRoute) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onCallEmergency = {
                        // Manual handoff only. Never auto-call emergency services.
                        context.startActivity(
                            Intent(Intent.ACTION_DIAL, "tel:112".toUri()),
                        )
                    },
                )
            }
        }
    }
}

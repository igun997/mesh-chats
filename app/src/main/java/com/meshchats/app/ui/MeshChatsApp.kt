package com.meshchats.app.ui

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
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
import com.meshchats.app.ui.theme.MeshChatsTheme

@Composable
fun MeshChatsApp() {
    MeshChatsTheme(darkTheme = true) {
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
}

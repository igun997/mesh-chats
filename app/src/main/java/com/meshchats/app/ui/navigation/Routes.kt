package com.meshchats.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

/** Type-safe navigation routes (navigation-compose + kotlinx.serialization). */

@Serializable
data object ChatsRoute

@Serializable
data object MapRoute

@Serializable
data object MeshRoute

@Serializable
data object SettingsRoute

@Serializable
data class ChatRoute(val conversationId: String)

@Serializable
data object SosCountdownRoute

@Serializable
data object SosActiveRoute

/**
 * Bottom navigation destinations. Outlined/filled icon pairs carry selection state
 * because there is no accent color to do it.
 */
enum class TopLevelDestination(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    CHATS(ChatsRoute, "Chats", Icons.Filled.Forum, Icons.Outlined.Forum),
    MAP(MapRoute, "Map", Icons.Filled.Map, Icons.Outlined.Map),
    MESH(MeshRoute, "Mesh", Icons.Filled.Hub, Icons.Outlined.Hub),
    SETTINGS(SettingsRoute, "Settings", Icons.Filled.Tune, Icons.Outlined.Tune),
}

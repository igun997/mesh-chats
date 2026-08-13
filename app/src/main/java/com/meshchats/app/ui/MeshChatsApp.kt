package com.meshchats.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.meshchats.app.ui.chat.ChatScreen
import com.meshchats.app.ui.conversations.ConversationsScreen
import com.meshchats.app.ui.navigation.ChatRoute
import com.meshchats.app.ui.navigation.ConversationsRoute
import com.meshchats.app.ui.theme.MeshChatsTheme

@Composable
fun MeshChatsApp() {
    MeshChatsTheme {
        val navController = rememberNavController()

        NavHost(navController = navController, startDestination = ConversationsRoute) {
            composable<ConversationsRoute> {
                ConversationsScreen(
                    onOpenConversation = { id -> navController.navigate(ChatRoute(id)) },
                )
            }
            composable<ChatRoute> { entry ->
                val route = entry.toRoute<ChatRoute>()
                ChatScreen(
                    conversationId = route.conversationId,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

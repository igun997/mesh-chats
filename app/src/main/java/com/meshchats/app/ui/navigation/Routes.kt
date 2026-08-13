package com.meshchats.app.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation routes (navigation-compose + kotlinx.serialization). */
@Serializable
data object ConversationsRoute

@Serializable
data class ChatRoute(val conversationId: String)

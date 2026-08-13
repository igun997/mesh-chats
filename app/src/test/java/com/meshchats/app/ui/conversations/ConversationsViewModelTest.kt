package com.meshchats.app.ui.conversations

import app.cash.turbine.test
import com.meshchats.app.data.MessageRepository
import com.meshchats.app.data.local.MessageEntity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsViewModelTest {

    private val repository: MessageRepository = mockk()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `maps conversation heads into summaries`() = runTest {
        every { repository.observeConversationHeads() } returns flowOf(
            listOf(
                MessageEntity(
                    id = "1",
                    conversationId = "mesh-1",
                    authorId = "me",
                    body = "hello mesh",
                    sentAt = 42L,
                    isOutgoing = true,
                ),
            ),
        )

        val viewModel = ConversationsViewModel(repository)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.conversations.size)
            assertEquals("mesh-1", state.conversations.first().id)
            assertEquals("hello mesh", state.conversations.first().preview)
            assertEquals(false, state.isLoading)
        }
    }
}

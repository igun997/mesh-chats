package com.meshchats.app.ui.startup

import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.meshchats.app.startup.DatabaseStartupState
import com.meshchats.app.startup.StorageStartupReason
import com.meshchats.app.ui.theme.MeshChatsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Pure gate-content behaviour (no Hilt, no real database): only [Ready] composes
 * the app content, [Failed] shows the recovery screen whose Retry invokes the
 * action, and [Initializing] shows the loading state — proving the gate withholds
 * DB-backed content until Ready. Also asserts the accessibility summaries so the
 * live regions announce once without merging away the Retry action.
 */
class StorageStartupGateContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val contentTag = "test-app-content"

    @Test
    fun readyComposesAppContent() {
        composeRule.setContent {
            MeshChatsTheme(darkTheme = true) {
                StorageStartupGateContent(state = DatabaseStartupState.Ready, onRetry = {}) {
                    Text("app", modifier = Modifier.testTag(contentTag))
                }
            }
        }

        composeRule.onNodeWithTag(contentTag).assertIsDisplayed()
    }

    @Test
    fun initializingDoesNotComposeAppContent() {
        composeRule.setContent {
            MeshChatsTheme(darkTheme = true) {
                StorageStartupGateContent(state = DatabaseStartupState.Initializing, onRetry = {}) {
                    Text("app", modifier = Modifier.testTag(contentTag))
                }
            }
        }

        composeRule.onNodeWithTag(StorageStartupTags.LOADING).assertIsDisplayed()
        composeRule.onNodeWithTag(contentTag).assertDoesNotExist()
    }

    @Test
    fun initializingAnnouncesSummaryOnceWithoutDuplicateText() {
        composeRule.setContent {
            MeshChatsTheme(darkTheme = true) {
                StorageStartupGateContent(state = DatabaseStartupState.Initializing, onRetry = {}) {
                    Text("app", modifier = Modifier.testTag(contentTag))
                }
            }
        }

        // The tagged container carries the single spoken summary.
        composeRule.onNodeWithTag(StorageStartupTags.LOADING)
            .assertContentDescriptionContains("Preparing secure storage", substring = true)
        // The visible copy is cleared from the a11y tree, so no text node exposes
        // the same string to duplicate the announcement.
        composeRule.onAllNodesWithText("Preparing secure storage")
            .assertCountEquals(0)
    }

    @Test
    fun failedShowsRecoveryAndDoesNotComposeAppContent() {
        composeRule.setContent {
            MeshChatsTheme(darkTheme = true) {
                StorageStartupGateContent(
                    state = DatabaseStartupState.Failed(StorageStartupReason.KEY_UNAVAILABLE),
                    onRetry = {},
                ) {
                    Text("app", modifier = Modifier.testTag(contentTag))
                }
            }
        }

        composeRule.onNodeWithTag(StorageStartupTags.FAILED).assertIsDisplayed()
        composeRule.onNodeWithTag(StorageStartupTags.RETRY).assertIsDisplayed()
        composeRule.onNodeWithTag(contentTag).assertDoesNotExist()
    }

    @Test
    fun failedAnnouncesSummaryTitleReassuranceAndReason() {
        composeRule.setContent {
            MeshChatsTheme(darkTheme = true) {
                StorageStartupGateContent(
                    state = DatabaseStartupState.Failed(StorageStartupReason.KEY_UNAVAILABLE),
                    onRetry = {},
                ) {
                    Text("app", modifier = Modifier.testTag(contentTag))
                }
            }
        }

        // The live-region summary carries the title, the not-erased reassurance,
        // and the bounded reason detail — all on the tagged node.
        composeRule.onNodeWithTag(StorageStartupTags.FAILED)
            .assertContentDescriptionContains("Secure storage unavailable", substring = true)
        composeRule.onNodeWithTag(StorageStartupTags.FAILED)
            .assertContentDescriptionContains(
                "Your data has not been erased. The app could not open its " +
                    "encrypted storage on this device.",
                substring = true,
            )
        composeRule.onNodeWithTag(StorageStartupTags.FAILED)
            .assertContentDescriptionContains(
                "The key that unlocks your data isn't available. This can happen if " +
                    "the Android Keystore was reset, its key entry was removed, or " +
                    "the app's data was partially cleared.",
                substring = true,
            )
    }

    @Test
    fun failedRetryStaysActionableUnderTheLiveRegion() {
        var retries = 0
        composeRule.setContent {
            MeshChatsTheme(darkTheme = true) {
                StorageStartupGateContent(
                    state = DatabaseStartupState.Failed(StorageStartupReason.UNEXPECTED),
                    onRetry = { retries++ },
                ) {
                    Text("app", modifier = Modifier.testTag(contentTag))
                }
            }
        }

        // Retry keeps its own node/action; the container summary did not merge it away.
        composeRule.onNodeWithTag(StorageStartupTags.RETRY)
            .assertContentDescriptionContains("Retry opening secure storage", substring = true)
        composeRule.onNodeWithTag(StorageStartupTags.RETRY).performClick()

        assertEquals(1, retries)
    }
}

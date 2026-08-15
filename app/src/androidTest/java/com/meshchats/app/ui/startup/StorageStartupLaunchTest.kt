package com.meshchats.app.ui.startup

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshchats.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device launch test through the real Hilt graph and the real Keystore-backed
 * encrypted database: launching [MainActivity] must reach the app content, which
 * proves the coordinator forced the encrypted database open (off the main thread)
 * and the gate advanced to Ready. Uses `waitUntil` on a test tag rather than a
 * literal sleep, so it is not timing-flaky.
 */
@RunWith(AndroidJUnit4::class)
class StorageStartupLaunchTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchReachesAppContentWithEncryptedDatabase() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule
                .onAllNodes(hasTestTag(StorageStartupTags.READY_CONTENT))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule.onNodeWithTag(StorageStartupTags.READY_CONTENT).assertExists()
    }
}

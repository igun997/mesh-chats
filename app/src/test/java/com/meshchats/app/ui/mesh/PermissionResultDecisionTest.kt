package com.meshchats.app.ui.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The permission launcher hands back a per-permission grant map. This pure
 * decision keeps the "what next" logic out of the Compose-only callback so it
 * can be asserted directly: every grant means we simply retry discovery; any
 * denial means the system will no longer show a dialog, so the next action must
 * send the user to app settings instead of re-requesting forever.
 */
class PermissionResultDecisionTest {

    @Test
    fun `all granted retries and clears denial`() {
        val decision = PermissionResultDecision.from(
            mapOf(
                "android.permission.BLUETOOTH_SCAN" to true,
                "android.permission.BLUETOOTH_ADVERTISE" to true,
            ),
        )

        assertEquals(PermissionResultDecision(denied = false), decision)
    }

    @Test
    fun `any denied marks denial`() {
        val decision = PermissionResultDecision.from(
            mapOf(
                "android.permission.BLUETOOTH_SCAN" to true,
                "android.permission.BLUETOOTH_ADVERTISE" to false,
            ),
        )

        assertEquals(PermissionResultDecision(denied = true), decision)
    }

    @Test
    fun `empty result is treated as denial`() {
        // A system cancel returns an empty map; err toward app settings rather
        // than looping the request dialog.
        val decision = PermissionResultDecision.from(emptyMap())

        assertEquals(PermissionResultDecision(denied = true), decision)
    }
}

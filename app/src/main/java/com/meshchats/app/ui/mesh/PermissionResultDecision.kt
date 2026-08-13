package com.meshchats.app.ui.mesh

/**
 * Pure decision derived from a runtime permission result map. Keeps the
 * "denied vs retry" branch out of the Compose-only launcher callback so it can
 * be unit tested.
 *
 * The rule is deliberately strict: discovery needs every requested permission,
 * so a single denial (or an empty result from a system cancel) is treated as a
 * denial. Once denied, Android will stop showing the request dialog, so the UI
 * must switch its next action to "open app settings" rather than re-requesting.
 */
data class PermissionResultDecision(val denied: Boolean) {
    companion object {
        fun from(result: Map<String, Boolean>): PermissionResultDecision =
            PermissionResultDecision(denied = result.isEmpty() || result.values.any { !it })
    }
}

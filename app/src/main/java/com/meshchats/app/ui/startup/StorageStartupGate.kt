package com.meshchats.app.ui.startup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshchats.app.startup.DatabaseStartupState
import com.meshchats.app.startup.StorageStartupReason
import com.meshchats.app.startup.StorageStartupViewModel

/** Stable tags so instrumentation/UI tests can assert what the gate composes. */
object StorageStartupTags {
    const val LOADING = "storage-startup-loading"
    const val FAILED = "storage-startup-failed"
    const val RETRY = "storage-startup-retry"
    const val READY_CONTENT = "storage-startup-ready-content"
}

/**
 * Gates [content] behind encrypted-storage startup. Nothing DB-backed — no
 * navigation, no shell, no `hiltViewModel` chat graph — is composed until the
 * coordinator reports [DatabaseStartupState.Ready].
 *
 * - [DatabaseStartupState.Idle]/[DatabaseStartupState.Initializing]: a neutral,
 *   monochrome loading state.
 * - [DatabaseStartupState.Failed]: a non-destructive recovery screen that offers
 *   Retry only. Data is never erased here; a wipe/reset is a separate explicit UX.
 * - [DatabaseStartupState.Ready]: composes [content] exactly as before.
 *
 * The ViewModel injects only the coordinator, so entering this gate cannot open
 * the database on the main thread.
 */
@Composable
fun StorageStartupGate(
    modifier: Modifier = Modifier,
    viewModel: StorageStartupViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StorageStartupGateContent(
        state = state,
        onRetry = viewModel::retry,
        modifier = modifier,
        content = content,
    )
}

/**
 * Pure, ViewModel-free gate body. Kept separate so it can be exercised in tests
 * without Hilt: proves that only [DatabaseStartupState.Ready] composes [content]
 * and that the Failed screen's Retry invokes [onRetry].
 */
@Composable
fun StorageStartupGateContent(
    state: DatabaseStartupState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    when (state) {
        DatabaseStartupState.Idle,
        DatabaseStartupState.Initializing,
        -> StorageLoading(modifier)

        is DatabaseStartupState.Failed -> StorageFailed(
            reason = state.reason,
            onRetry = onRetry,
            modifier = modifier,
        )

        DatabaseStartupState.Ready -> content()
    }
}

private const val LOADING_ANNOUNCEMENT = "Preparing secure storage"

@Composable
private fun StorageLoading(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier
            .fillMaxSize()
            .testTag(StorageStartupTags.LOADING)
            // The tagged container is the single polite live region. It carries the
            // spoken summary so screen readers announce it once on appearance.
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = LOADING_ANNOUNCEMENT
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Monochrome: inherit onBackground, no accent color. Keeps its own
            // progress semantics for assistive tech.
            CircularProgressIndicator(color = MaterialTheme.colorScheme.onBackground)
            Text(
                text = LOADING_ANNOUNCEMENT,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                // Visible copy only: cleared from the a11y tree so it is not
                // announced a second time on top of the container summary.
                modifier = Modifier
                    .padding(top = 24.dp)
                    .clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun StorageFailed(
    reason: StorageStartupReason,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = "Secure storage unavailable"
    val notErased = "Your data has not been erased. The app could not open its " +
        "encrypted storage on this device."
    val detail = reasonDetail(reason)
    // Single spoken summary for the live region: title, the reassurance that data
    // is intact, and the bounded reason detail. Announced on the tagged node
    // without merging away the Retry action (that button keeps its own node).
    val summary = "$title. $notErased $detail"

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier
            .fillMaxSize()
            .testTag(StorageStartupTags.FAILED)
            .semantics {
                liveRegion = LiveRegionMode.Assertive
                contentDescription = summary
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Visible copy is cleared from the a11y tree so it is not re-announced
            // on top of the container summary above.
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Text(
                text = notErased,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .clearAndSetSemantics {},
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clearAndSetSemantics {},
            )
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .testTag(StorageStartupTags.RETRY)
                    .semantics { contentDescription = "Retry opening secure storage" },
            ) {
                Text("Retry")
            }
        }
    }
}

/**
 * Human, non-technical detail per bounded reason. Never includes exception text,
 * key material, or file paths.
 */
private fun reasonDetail(reason: StorageStartupReason): String = when (reason) {
    StorageStartupReason.KEY_UNAVAILABLE ->
        "The key that unlocks your data isn't available. This can happen if the " +
            "Android Keystore was reset, its key entry was removed, or the app's " +
            "data was partially cleared."

    StorageStartupReason.MIGRATION_FAILED ->
        "Your existing data couldn't be upgraded to the new encrypted format. It " +
            "has been left untouched."

    StorageStartupReason.UNEXPECTED ->
        "Something went wrong while opening secure storage."
}

package com.meshchats.app.startup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives encrypted-storage startup for the UI gate. It injects **only** the
 * [DatabaseStartupCoordinator] — never a database, DAO, or repository — so that
 * constructing this ViewModel can never itself trigger a DB open on the main
 * thread. The open happens on the coordinator's injected IO dispatcher.
 *
 * Initialization starts once in [viewModelScope] on construction; [retry] re-runs
 * it, but only when the current state is [DatabaseStartupState.Failed], so rapid
 * taps while [DatabaseStartupState.Initializing] (or already
 * [DatabaseStartupState.Ready]) cannot queue a backlog of redundant attempts. The
 * coordinator itself is also idempotent, so this is a belt-and-braces guard. All
 * state transitions are published by the coordinator's [StateFlow], which the gate
 * observes.
 */
@HiltViewModel
class StorageStartupViewModel @Inject constructor(
    private val coordinator: DatabaseStartupCoordinator,
) : ViewModel() {

    val state: StateFlow<DatabaseStartupState> = coordinator.state

    init {
        start()
    }

    /**
     * Re-attempt storage startup. A no-op unless the current state is
     * [DatabaseStartupState.Failed]; an in-flight or successful attempt is left
     * untouched so repeated taps do not stack redundant attempts.
     */
    fun retry() {
        if (coordinator.state.value !is DatabaseStartupState.Failed) return
        start()
    }

    private fun start() {
        viewModelScope.launch { coordinator.initialize() }
    }
}

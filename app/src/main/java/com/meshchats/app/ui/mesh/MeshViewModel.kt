package com.meshchats.app.ui.mesh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshchats.app.core.mesh.MeshState
import com.meshchats.app.core.mesh.MeshStateRepository
import com.meshchats.app.core.mesh.TransportId
import com.meshchats.app.core.transport.ble.BleDiscoveryController
import com.meshchats.app.core.transport.ble.BleDiscoveryPreference
import com.meshchats.app.core.transport.ble.BleDiscoverySettings
import com.meshchats.app.core.transport.ble.BleDiscoveryState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Backs the Mesh screen. Beyond exposing the shared [MeshState], this owns the
 * BLE discovery *lifecycle*, reconciling two independent inputs:
 *
 * 1. **Screen visibility** — the screen drives [onScreenStarted]/[onScreenStopped]
 *    from a lifecycle effect, so the app never advertises for the whole process
 *    lifetime; discovery runs only while the Mesh screen is at least STARTED.
 * 2. **User intent** — the persisted [BleDiscoverySettings] preference, so a user
 *    who switched Bluetooth OFF stays off across restarts.
 *
 * Discovery starts only when the screen is visible **and** the preference has
 * loaded **and** it is enabled. Any change to either input re-runs [reconcile],
 * which starts the controller when the gate opens and stops it when it closes;
 * [running] de-duplicates redundant starts (the controller is idempotent anyway).
 * Discovery is never started at construction — that would begin advertising the
 * moment the graph is built, and would honour a stored OFF only after a flicker.
 *
 * [setBleDiscoveryEnabled] persists the user's intent; the resulting preference
 * emission flows back through [reconcile], so the switch has a single source of
 * truth and enabling while hidden does not start scanning.
 */
@HiltViewModel
class MeshViewModel @Inject constructor(
    private val repository: MeshStateRepository,
    private val controller: BleDiscoveryController,
    private val settings: BleDiscoverySettings,
) : ViewModel() {

    val state: StateFlow<MeshState> = repository.state

    /** The raw BLE discovery state, so the screen can render permission/off UX. */
    val discovery: StateFlow<BleDiscoveryState> = controller.state

    /** The persisted BLE discovery intent, so the screen can render the switch. */
    val bleDiscovery: StateFlow<BleDiscoveryPreference> = settings.state

    private var screenStarted = false
    private var preference: BleDiscoveryPreference = settings.state.value
    private var running = false

    init {
        // Track the persisted intent; any change re-reconciles so toggling the
        // switch while visible starts/stops immediately.
        viewModelScope.launch {
            settings.state.collect { pref ->
                preference = pref
                reconcile()
            }
        }
    }

    /** Mark the Mesh screen visible and start discovery if the gate is open. */
    fun onScreenStarted() {
        screenStarted = true
        reconcile()
    }

    /**
     * Mark the Mesh screen hidden. Always stops discovery: leaving the screen (or
     * backgrounding) must never leave the radio scanning, regardless of the
     * bookkeeping. The controller's [stop] is idempotent.
     */
    fun onScreenStopped() {
        screenStarted = false
        running = false
        controller.stop()
    }

    /**
     * Re-run discovery after the user grants permissions or enables Bluetooth.
     * Applies the same gate as [reconcile]: a retry never starts scanning while
     * the preference is disabled or the screen is hidden. When the gate is open
     * it calls [BleDiscoveryController.start] directly so the controller
     * re-checks its preconditions even if it already believes it is running.
     */
    fun retryDiscovery() {
        if (shouldRun()) {
            controller.start()
            running = true
        }
    }

    /**
     * Persist the user's BLE-discovery intent; reconcile follows the emission.
     *
     * The write is best-effort: a failing persist ([IOException] from the disk
     * or store) must never crash the app. We deliberately do **not**
     * optimistically flip the switch — the persisted preference remains the
     * single source of truth, so on a write failure the UI simply keeps showing
     * the last saved value (the switch snaps back) rather than lying about a
     * change that never landed.
     */
    fun setBleDiscoveryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                settings.setEnabled(enabled)
            } catch (_: IOException) {
                // Swallow: the preference flow stays authoritative, so the
                // switch reverts to its last persisted state on the next frame.
            }
        }
    }

    fun toggleTransport(id: TransportId, enabled: Boolean) =
        repository.setTransportEnabled(id, enabled)

    fun setLocalMeshOnly(enabled: Boolean) = repository.setLocalMeshOnly(enabled)

    private fun shouldRun(): Boolean =
        screenStarted && preference.loaded && preference.enabled

    private fun reconcile() {
        val shouldRun = shouldRun()
        if (shouldRun && !running) {
            controller.start()
            running = true
        } else if (!shouldRun && running) {
            controller.stop()
            running = false
        }
    }
}

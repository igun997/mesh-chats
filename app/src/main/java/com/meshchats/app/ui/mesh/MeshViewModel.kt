package com.meshchats.app.ui.mesh

import androidx.lifecycle.ViewModel
import com.meshchats.app.core.mesh.MeshState
import com.meshchats.app.core.mesh.MeshStateRepository
import com.meshchats.app.core.mesh.TransportId
import com.meshchats.app.core.transport.ble.BleDiscoveryController
import com.meshchats.app.core.transport.ble.BleDiscoveryState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Backs the Mesh screen. Beyond exposing the shared [MeshState], this owns the
 * BLE discovery *lifecycle*: it starts the [controller] only while the screen is
 * on screen and stops it when the screen leaves, so the app never advertises and
 * scans for the whole process lifetime.
 *
 * Discovery is never started at construction — that would begin advertising the
 * moment the graph is built. The screen drives [onScreenStarted]/[onScreenStopped]
 * from a lifecycle effect, and [retryDiscovery] re-runs the precondition checks
 * after the user grants permissions or turns Bluetooth on. Each call delegates
 * one-to-one; the controller's own idempotency handles repeated starts.
 */
@HiltViewModel
class MeshViewModel @Inject constructor(
    private val repository: MeshStateRepository,
    private val controller: BleDiscoveryController,
) : ViewModel() {

    val state: StateFlow<MeshState> = repository.state

    /** The raw BLE discovery state, so the screen can render permission/off UX. */
    val discovery: StateFlow<BleDiscoveryState> = controller.state

    /** Begin discovery; called when the Mesh screen becomes visible. */
    fun onScreenStarted() = controller.start()

    /** Stop discovery; called when the Mesh screen leaves the composition. */
    fun onScreenStopped() = controller.stop()

    /** Re-run discovery after the user grants permissions or enables Bluetooth. */
    fun retryDiscovery() = controller.start()

    fun toggleTransport(id: TransportId, enabled: Boolean) =
        repository.setTransportEnabled(id, enabled)

    fun setLocalMeshOnly(enabled: Boolean) = repository.setLocalMeshOnly(enabled)
}

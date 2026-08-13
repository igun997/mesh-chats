package com.meshchats.app.ui.mesh

import com.meshchats.app.core.mesh.MeshState
import com.meshchats.app.core.mesh.MeshStateRepository
import com.meshchats.app.core.mesh.TransportId
import com.meshchats.app.core.transport.ble.BleDiscoveryController
import com.meshchats.app.core.transport.ble.BleDiscoveryState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The Mesh screen owns the discovery lifecycle: it starts scanning when the
 * screen is shown and stops when it leaves, and retries after the user grants
 * permissions or turns Bluetooth on. These tests pin that delegation to the
 * injected [BleDiscoveryController] and prove nothing auto-starts at
 * construction (advertising for the whole process lifetime would be dishonest
 * about battery and privacy).
 */
class MeshViewModelTest {

    private class FakeMeshStateRepository : MeshStateRepository {
        override val state = MutableStateFlow(MeshState.Empty)
        val transportToggles = mutableListOf<Pair<TransportId, Boolean>>()
        var localMeshOnly: Boolean? = null

        override fun setTransportEnabled(id: TransportId, enabled: Boolean) {
            transportToggles += id to enabled
        }

        override fun setLocalMeshOnly(enabled: Boolean) {
            localMeshOnly = enabled
        }
    }

    private class FakeBleDiscoveryController(
        initial: BleDiscoveryState = BleDiscoveryState.Idle,
    ) : BleDiscoveryController {
        private val _state = MutableStateFlow(initial)
        override val state: StateFlow<BleDiscoveryState> = _state
        var startCount = 0
        var stopCount = 0

        override fun start() {
            startCount++
        }

        override fun stop() {
            stopCount++
        }

        fun emit(next: BleDiscoveryState) {
            _state.value = next
        }
    }

    private fun viewModel(
        repository: FakeMeshStateRepository = FakeMeshStateRepository(),
        controller: FakeBleDiscoveryController = FakeBleDiscoveryController(),
    ) = MeshViewModel(repository, controller)

    @Test
    fun `does not start discovery at construction`() {
        val controller = FakeBleDiscoveryController()

        viewModel(controller = controller)

        assertEquals(0, controller.startCount)
        assertEquals(0, controller.stopCount)
    }

    @Test
    fun `onScreenStarted starts discovery`() {
        val controller = FakeBleDiscoveryController()
        val vm = viewModel(controller = controller)

        vm.onScreenStarted()

        assertEquals(1, controller.startCount)
        assertEquals(0, controller.stopCount)
    }

    @Test
    fun `onScreenStopped stops discovery`() {
        val controller = FakeBleDiscoveryController()
        val vm = viewModel(controller = controller)

        vm.onScreenStarted()
        vm.onScreenStopped()

        assertEquals(1, controller.startCount)
        assertEquals(1, controller.stopCount)
    }

    @Test
    fun `retryDiscovery starts discovery again`() {
        val controller = FakeBleDiscoveryController()
        val vm = viewModel(controller = controller)

        vm.onScreenStarted()
        vm.retryDiscovery()

        // Each lifecycle event delegates one-to-one; controller idempotency is
        // its own concern, so the ViewModel must not swallow calls.
        assertEquals(2, controller.startCount)
    }

    @Test
    fun `each lifecycle event delegates one to one`() {
        val controller = FakeBleDiscoveryController()
        val vm = viewModel(controller = controller)

        vm.onScreenStarted()
        vm.onScreenStopped()
        vm.onScreenStarted()

        assertEquals(2, controller.startCount)
        assertEquals(1, controller.stopCount)
    }

    @Test
    fun `exposes the repository mesh state`() {
        val repository = FakeMeshStateRepository()
        val vm = viewModel(repository = repository)

        assertSame(repository.state, vm.state)
    }

    @Test
    fun `exposes the controller discovery state`() {
        val controller = FakeBleDiscoveryController()
        val vm = viewModel(controller = controller)

        assertSame(controller.state, vm.discovery)
        controller.emit(BleDiscoveryState.BluetoothOff)
        assertEquals(BleDiscoveryState.BluetoothOff, vm.discovery.value)
    }

    @Test
    fun `toggleTransport delegates to the repository`() {
        val repository = FakeMeshStateRepository()
        val vm = viewModel(repository = repository)

        vm.toggleTransport(TransportId.WIFI, enabled = false)

        assertEquals(listOf(TransportId.WIFI to false), repository.transportToggles)
    }

    @Test
    fun `setLocalMeshOnly delegates to the repository`() {
        val repository = FakeMeshStateRepository()
        val vm = viewModel(repository = repository)

        vm.setLocalMeshOnly(true)

        assertEquals(true, repository.localMeshOnly)
    }
}

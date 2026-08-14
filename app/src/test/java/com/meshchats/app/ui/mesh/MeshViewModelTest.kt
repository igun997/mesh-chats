package com.meshchats.app.ui.mesh

import com.meshchats.app.core.mesh.MeshState
import com.meshchats.app.core.mesh.MeshStateRepository
import com.meshchats.app.core.mesh.TransportId
import com.meshchats.app.core.transport.ble.BleDiscoveryController
import com.meshchats.app.core.transport.ble.BleDiscoveryPreference
import com.meshchats.app.core.transport.ble.BleDiscoverySettings
import com.meshchats.app.core.transport.ble.BleDiscoveryState
import com.meshchats.app.util.MainDispatcherRule
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

/**
 * The Mesh screen owns the discovery lifecycle, reconciling two inputs: screen
 * visibility and the persisted user intent. These tests pin that gate — nothing
 * auto-starts at construction, discovery runs only when visible **and** the
 * preference is loaded+enabled, toggling the preference while visible starts or
 * stops immediately, enabling while hidden does not start, and leaving the screen
 * always stops.
 *
 * A [MainDispatcherRule] with an unconfined dispatcher lets the `viewModelScope`
 * `settings.state` collector run eagerly, so preference emissions reconcile
 * synchronously within each test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

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

    private class FakeBleDiscoverySettings(
        initial: BleDiscoveryPreference = BleDiscoveryPreference(loaded = true, enabled = true),
        private val failWrites: Boolean = false,
    ) : BleDiscoverySettings {
        private val _state = MutableStateFlow(initial)
        override val state: StateFlow<BleDiscoveryPreference> = _state
        val writes = mutableListOf<Boolean>()

        override suspend fun setEnabled(enabled: Boolean) {
            if (failWrites) throw IOException("disk gone")
            writes += enabled
            _state.value = BleDiscoveryPreference(loaded = true, enabled = enabled)
        }

        /** Simulate a persisted value arriving after construction. */
        fun load(enabled: Boolean) {
            _state.value = BleDiscoveryPreference(loaded = true, enabled = enabled)
        }
    }

    private fun viewModel(
        repository: FakeMeshStateRepository = FakeMeshStateRepository(),
        controller: FakeBleDiscoveryController = FakeBleDiscoveryController(),
        settings: FakeBleDiscoverySettings = FakeBleDiscoverySettings(),
    ) = MeshViewModel(repository, controller, settings)

    @Test
    fun `does not start discovery at construction`() {
        val controller = FakeBleDiscoveryController()

        viewModel(controller = controller)

        assertEquals(0, controller.startCount)
        assertEquals(0, controller.stopCount)
    }

    @Test
    fun `onScreenStarted starts discovery when preference is loaded and enabled`() {
        val controller = FakeBleDiscoveryController()
        val vm = viewModel(controller = controller)

        vm.onScreenStarted()

        assertEquals(1, controller.startCount)
        assertEquals(0, controller.stopCount)
    }

    @Test
    fun `onScreenStarted does not start discovery while preference is disabled`() {
        val controller = FakeBleDiscoveryController()
        val settings = FakeBleDiscoverySettings(
            BleDiscoveryPreference(loaded = true, enabled = false),
        )
        val vm = viewModel(controller = controller, settings = settings)

        vm.onScreenStarted()

        assertEquals(0, controller.startCount)
    }

    @Test
    fun `onScreenStarted does not start discovery before the preference has loaded`() {
        val controller = FakeBleDiscoveryController()
        val settings = FakeBleDiscoverySettings(
            BleDiscoveryPreference(loaded = false, enabled = false),
        )
        val vm = viewModel(controller = controller, settings = settings)

        vm.onScreenStarted()
        assertEquals(0, controller.startCount)

        // Once the stored ON arrives, discovery starts without another tap.
        settings.load(enabled = true)
        assertEquals(1, controller.startCount)
    }

    @Test
    fun `disabling while visible stops discovery`() {
        val controller = FakeBleDiscoveryController()
        val settings = FakeBleDiscoverySettings()
        val vm = viewModel(controller = controller, settings = settings)

        vm.onScreenStarted()
        assertEquals(1, controller.startCount)

        settings.load(enabled = false)

        assertEquals(1, controller.stopCount)
    }

    @Test
    fun `enabling while visible starts discovery`() {
        val controller = FakeBleDiscoveryController()
        val settings = FakeBleDiscoverySettings(
            BleDiscoveryPreference(loaded = true, enabled = false),
        )
        val vm = viewModel(controller = controller, settings = settings)

        vm.onScreenStarted()
        assertEquals(0, controller.startCount)

        settings.load(enabled = true)

        assertEquals(1, controller.startCount)
    }

    @Test
    fun `enabling while hidden does not start discovery`() {
        val controller = FakeBleDiscoveryController()
        val settings = FakeBleDiscoverySettings(
            BleDiscoveryPreference(loaded = true, enabled = false),
        )
        val vm = viewModel(controller = controller, settings = settings)

        // Screen never started.
        settings.load(enabled = true)

        assertEquals(0, controller.startCount)
    }

    @Test
    fun `onScreenStopped always stops discovery`() {
        val controller = FakeBleDiscoveryController()
        val vm = viewModel(controller = controller)

        vm.onScreenStarted()
        vm.onScreenStopped()

        assertEquals(1, controller.startCount)
        assertEquals(1, controller.stopCount)
    }

    @Test
    fun `retryDiscovery starts again when visible and enabled`() {
        val controller = FakeBleDiscoveryController()
        val vm = viewModel(controller = controller)

        vm.onScreenStarted()
        vm.retryDiscovery()

        // Retry re-checks preconditions on top of the initial start.
        assertEquals(2, controller.startCount)
    }

    @Test
    fun `retryDiscovery does not start while preference is disabled`() {
        val controller = FakeBleDiscoveryController()
        val settings = FakeBleDiscoverySettings(
            BleDiscoveryPreference(loaded = true, enabled = false),
        )
        val vm = viewModel(controller = controller, settings = settings)

        vm.onScreenStarted()
        vm.retryDiscovery()

        assertEquals(0, controller.startCount)
    }

    @Test
    fun `setBleDiscoveryEnabled persists the intent`() {
        val settings = FakeBleDiscoverySettings()
        val vm = viewModel(settings = settings)

        vm.setBleDiscoveryEnabled(false)

        assertEquals(listOf(false), settings.writes)
    }

    @Test
    fun `setBleDiscoveryEnabled survives a failing write without crashing`() {
        // A persist that throws IOException must be swallowed: the process must
        // not die, the controller must be untouched, and the preference stays
        // authoritative (unchanged, since the write never landed).
        val controller = FakeBleDiscoveryController()
        val settings = FakeBleDiscoverySettings(failWrites = true)
        val vm = viewModel(controller = controller, settings = settings)

        vm.onScreenStarted()
        val startsBefore = controller.startCount
        val stopsBefore = controller.stopCount

        // Would crash the test process if the exception escaped the coroutine.
        vm.setBleDiscoveryEnabled(false)

        assertEquals("failed write must not be recorded", emptyList<Boolean>(), settings.writes)
        assertEquals(BleDiscoveryPreference(loaded = true, enabled = true), settings.state.value)
        assertEquals(startsBefore, controller.startCount)
        assertEquals(stopsBefore, controller.stopCount)
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
    fun `exposes the persisted ble discovery preference`() {
        val settings = FakeBleDiscoverySettings()
        val vm = viewModel(settings = settings)

        assertSame(settings.state, vm.bleDiscovery)
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

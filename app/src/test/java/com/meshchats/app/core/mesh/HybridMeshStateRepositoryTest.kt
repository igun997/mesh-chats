package com.meshchats.app.core.mesh

import com.meshchats.app.core.transport.ble.BleCapability
import com.meshchats.app.core.transport.ble.BleDiscoveryController
import com.meshchats.app.core.transport.ble.BleDiscoveryPreference
import com.meshchats.app.core.transport.ble.BleDiscoverySettings
import com.meshchats.app.core.transport.ble.BleDiscoveryState
import com.meshchats.app.core.transport.ble.DiscoveredBlePeer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repository overlays real BLE discovery onto a still-fake baseline. These
 * tests drive it with a fake [BleDiscoveryController] whose state we control and
 * a [TestScope], asserting that only the Bluetooth transport row and `ble-*`
 * peers are discovery-driven while every other transport and seeded non-BLE peer
 * is preserved untouched.
 *
 * The jitter loop is disabled by injecting [Long.MAX_VALUE] so no wall-clock time
 * needs advancing and the still-fake rows never mutate mid-assertion. Coroutines
 * run on [TestScope.backgroundScope] so the never-completing jitter/collect loops
 * do not fail the test, and [runCurrent] pumps each StateFlow emission.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HybridMeshStateRepositoryTest {

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
    ) : BleDiscoverySettings {
        private val _state = MutableStateFlow(initial)
        override val state: StateFlow<BleDiscoveryPreference> = _state

        override suspend fun setEnabled(enabled: Boolean) {
            _state.value = BleDiscoveryPreference(loaded = true, enabled = enabled)
        }

        fun set(preference: BleDiscoveryPreference) {
            _state.value = preference
        }
    }

    private fun blePeer(nodeId: Long, rssi: Int = -50) =
        DiscoveredBlePeer(
            nodeId = nodeId,
            rssiDbm = rssi,
            lastSeenMillis = 0L,
            capabilities = setOf(BleCapability.CHAT),
        )

    private fun TestScope.repository(
        controller: FakeBleDiscoveryController = FakeBleDiscoveryController(),
        settings: FakeBleDiscoverySettings = FakeBleDiscoverySettings(),
    ): HybridMeshStateRepository =
        HybridMeshStateRepository(
            scope = backgroundScope,
            bleController = controller,
            settings = settings,
            jitterIntervalMillis = Long.MAX_VALUE,
        )

    @Test
    fun `idle discovery overlays a ready Bluetooth row and preserves the other transports`() =
        runTest {
            val repo = repository()
            runCurrent()

            val state = repo.state.value
            val bt = state.transport(TransportId.BT)!!
            assertEquals(TransportState.Idle, bt.state)
            assertEquals("Ready to scan", bt.detail)

            // Wi-Fi, LoRa and Relay are untouched by the BLE overlay.
            assertEquals(
                TransportState.Active(peers = 2, throughputBps = 1_200_000),
                state.transport(TransportId.WIFI)!!.state,
            )
            assertEquals(TransportState.Absent, state.transport(TransportId.LORA)!!.state)
            assertEquals(TransportState.Idle, state.transport(TransportId.RELAY)!!.state)
        }

    @Test
    fun `scanning overlays only the Bluetooth row and ble peers, keeping seeded non-BLE peers`() =
        runTest {
            val controller = FakeBleDiscoveryController()
            val repo = repository(controller)
            runCurrent()

            val seededIds = repo.state.value.peers.map { it.id }
            assertTrue("expected seeded Wi-Fi peer", seededIds.contains("peer-1"))
            assertTrue(seededIds.none { it.startsWith("ble-") })

            controller.emit(BleDiscoveryState.Scanning(listOf(blePeer(0x0123456789ABCDEFL, -63))))
            runCurrent()

            val state = repo.state.value
            val bt = state.transport(TransportId.BT)!!
            assertEquals(TransportState.Active(peers = 1, throughputBps = 0), bt.state)
            assertEquals("Scanning while this screen is open · 1 peer", bt.detail)

            // The seeded non-BLE peers survive the overlay untouched.
            assertTrue(state.peers.any { it.id == "peer-1" })
            assertTrue(state.peers.any { it.id == "peer-3" })
            assertTrue(state.peers.any { it.id == "peer-4" })

            // The discovered peer is appended, derived entirely from discovery.
            val ble = state.peers.single { it.id.startsWith("ble-") }
            assertEquals("ble-0123456789abcdef", ble.id)
            assertEquals(setOf(TransportId.BT), ble.reachableVia)
            assertEquals(-63, ble.rssiDbm)

            // Other transports remain exactly as seeded.
            assertEquals(
                TransportState.Active(peers = 2, throughputBps = 1_200_000),
                state.transport(TransportId.WIFI)!!.state,
            )
        }

    @Test
    fun `scanning state updates as the peer set grows`() = runTest {
        val controller = FakeBleDiscoveryController()
        val repo = repository(controller)
        runCurrent()

        controller.emit(BleDiscoveryState.Scanning(listOf(blePeer(1L))))
        runCurrent()
        assertEquals("Scanning while this screen is open · 1 peer", repo.state.value.transport(TransportId.BT)!!.detail)

        controller.emit(BleDiscoveryState.Scanning(listOf(blePeer(1L), blePeer(2L))))
        runCurrent()
        val bt = repo.state.value.transport(TransportId.BT)!!
        assertEquals(TransportState.Active(peers = 2, throughputBps = 0), bt.state)
        assertEquals("Scanning while this screen is open · 2 peers", bt.detail)
        assertEquals(2, repo.state.value.peers.count { it.id.startsWith("ble-") })
    }

    @Test
    fun `a later scan drops stale BLE peers`() = runTest {
        val controller = FakeBleDiscoveryController()
        val repo = repository(controller)
        runCurrent()

        controller.emit(BleDiscoveryState.Scanning(listOf(blePeer(0xAAAAL))))
        runCurrent()
        assertTrue(repo.state.value.peers.any { it.id == "ble-000000000000aaaa" })

        // The next scan no longer includes the first peer; it must disappear.
        controller.emit(BleDiscoveryState.Scanning(listOf(blePeer(0xBBBBL))))
        runCurrent()
        val ids = repo.state.value.peers.map { it.id }
        assertTrue(ids.none { it == "ble-000000000000aaaa" })
        assertTrue(ids.contains("ble-000000000000bbbb"))
        assertEquals(1, ids.count { it.startsWith("ble-") })
    }

    @Test
    fun `stopping scanning clears BLE peers and returns Bluetooth to idle`() = runTest {
        val controller = FakeBleDiscoveryController()
        val repo = repository(controller)
        runCurrent()

        controller.emit(BleDiscoveryState.Scanning(listOf(blePeer(1L))))
        runCurrent()
        assertTrue(repo.state.value.peers.any { it.id.startsWith("ble-") })

        controller.emit(BleDiscoveryState.Idle)
        runCurrent()
        assertTrue(repo.state.value.peers.none { it.id.startsWith("ble-") })
        assertEquals(TransportState.Idle, repo.state.value.transport(TransportId.BT)!!.state)
    }

    @Test
    fun `setTransportEnabled is a no-op for Bluetooth`() = runTest {
        val controller = FakeBleDiscoveryController()
        val repo = repository(controller)
        controller.emit(BleDiscoveryState.Scanning(listOf(blePeer(1L))))
        runCurrent()

        val before = repo.state.value.transport(TransportId.BT)!!

        repo.setTransportEnabled(TransportId.BT, enabled = false)
        assertEquals(before, repo.state.value.transport(TransportId.BT))

        repo.setTransportEnabled(TransportId.BT, enabled = true)
        assertEquals(before, repo.state.value.transport(TransportId.BT))
    }

    @Test
    fun `setTransportEnabled still toggles a non-Bluetooth transport`() = runTest {
        val repo = repository()
        runCurrent()

        repo.setTransportEnabled(TransportId.WIFI, enabled = false)

        assertEquals(TransportState.Off, repo.state.value.transport(TransportId.WIFI)!!.state)
        // Bluetooth stays discovery-driven and unaffected.
        assertEquals(TransportState.Idle, repo.state.value.transport(TransportId.BT)!!.state)
    }

    @Test
    fun `local mesh only toggles the relay transport alone`() = runTest {
        val repo = repository()
        runCurrent()

        repo.setLocalMeshOnly(true)

        val state = repo.state.value
        assertTrue(state.localMeshOnly)
        val relay = state.transport(TransportId.RELAY)!!
        assertEquals(TransportState.Off, relay.state)
        assertEquals("Disabled by Local mesh only", relay.detail)

        // Every other transport is left exactly as it was.
        assertEquals(
            TransportState.Active(peers = 2, throughputBps = 1_200_000),
            state.transport(TransportId.WIFI)!!.state,
        )
        assertEquals(TransportState.Idle, state.transport(TransportId.BT)!!.state)
        assertEquals(TransportState.Absent, state.transport(TransportId.LORA)!!.state)

        repo.setLocalMeshOnly(false)
        val restored = repo.state.value.transport(TransportId.RELAY)!!
        assertEquals(TransportState.Idle, restored.state)
        assertEquals(false, repo.state.value.localMeshOnly)
    }

    @Test
    fun `bluetooth carries the shared transport defaults`() = runTest {
        val repo = repository()
        runCurrent()

        assertEquals(
            BleTransportDefaults.CONSTRAINTS,
            repo.state.value.transport(TransportId.BT)!!.constraints,
        )
        assertNull(repo.state.value.transport(TransportId.BT)!!.constraints.dutyCyclePercent)
    }

    @Test
    fun `while preference is loading BT is off and BLE peers are hidden`() = runTest {
        val controller = FakeBleDiscoveryController()
        val settings = FakeBleDiscoverySettings(
            BleDiscoveryPreference(loaded = false, enabled = false),
        )
        val repo = repository(controller, settings)
        // Even if the controller reports live peers, an unloaded preference must
        // not surface them: we do not yet know the user's intent.
        controller.emit(BleDiscoveryState.Scanning(listOf(blePeer(1L))))
        runCurrent()

        val state = repo.state.value
        val bt = state.transport(TransportId.BT)!!
        assertEquals(TransportState.Off, bt.state)
        assertEquals("Loading preference", bt.detail)
        assertTrue(state.peers.none { it.id.startsWith("ble-") })
        // Non-BLE fake peers are preserved.
        assertTrue(state.peers.any { it.id == "peer-1" })
    }

    @Test
    fun `disabled preference maps BT to off and removes ble peers`() = runTest {
        val controller = FakeBleDiscoveryController()
        val settings = FakeBleDiscoverySettings(
            BleDiscoveryPreference(loaded = true, enabled = false),
        )
        val repo = repository(controller, settings)
        controller.emit(BleDiscoveryState.Scanning(listOf(blePeer(0xABCDL))))
        runCurrent()

        val state = repo.state.value
        val bt = state.transport(TransportId.BT)!!
        assertEquals(TransportState.Off, bt.state)
        assertEquals("Disabled in Mesh Chats", bt.detail)
        assertTrue("BLE-only peers must disappear while disabled", state.peers.none { it.id.startsWith("ble-") })

        // Non-BLE fake routes are preserved untouched.
        assertTrue(state.peers.any { it.id == "peer-1" })
        assertEquals(
            TransportState.Active(peers = 2, throughputBps = 1_200_000),
            state.transport(TransportId.WIFI)!!.state,
        )
    }

    @Test
    fun `re-enabling restores controller-derived status and peers`() = runTest {
        val controller = FakeBleDiscoveryController()
        val settings = FakeBleDiscoverySettings(
            BleDiscoveryPreference(loaded = true, enabled = false),
        )
        val repo = repository(controller, settings)
        controller.emit(BleDiscoveryState.Scanning(listOf(blePeer(0xABCDL, -70))))
        runCurrent()
        assertEquals(TransportState.Off, repo.state.value.transport(TransportId.BT)!!.state)

        // Turning the preference back on resumes the discovery-derived overlay.
        settings.set(BleDiscoveryPreference(loaded = true, enabled = true))
        runCurrent()

        val state = repo.state.value
        val bt = state.transport(TransportId.BT)!!
        assertEquals(TransportState.Active(peers = 1, throughputBps = 0), bt.state)
        assertEquals("Scanning while this screen is open · 1 peer", bt.detail)
        val ble = state.peers.single { it.id.startsWith("ble-") }
        assertEquals(-70, ble.rssiDbm)
    }
}

package com.meshchats.app.core.transport.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultBleDiscoveryControllerTest {

    private val localBeacon = BleBeacon(nodeId = 42L, capabilities = setOf(BleCapability.CHAT))

    /** Fully controllable fake radio. */
    private class FakeBleRadio(
        var supported: Boolean = true,
        var enabled: Boolean = true,
        var missing: Set<String> = emptySet(),
    ) : BleRadio {
        var startCount = 0
        var stopCount = 0
        var lastServiceUuid: UUID? = null
        var lastPayload: ByteArray? = null
        var startException: Throwable? = null

        /**
         * When set, the next [start] invokes `onError` synchronously (before
         * returning normally) and then clears itself, so a subsequent [start]
         * succeeds. Models a transient radio failure for recovery tests.
         */
        var synchronousError: String? = null
        private var onResult: ((BleScanResult) -> Unit)? = null
        private var onError: ((String) -> Unit)? = null

        override val isSupported: Boolean get() = supported
        override fun isEnabled(): Boolean = enabled
        override fun missingPermissions(): Set<String> = missing

        override fun start(
            serviceUuid: UUID,
            payload: ByteArray,
            onResult: (BleScanResult) -> Unit,
            onError: (String) -> Unit,
        ) {
            startException?.let { throw it }
            startCount++
            lastServiceUuid = serviceUuid
            lastPayload = payload
            this.onResult = onResult
            this.onError = onError
            synchronousError?.let { message ->
                synchronousError = null
                onError(message)
            }
        }

        override fun stop() {
            stopCount++
            onResult = null
            onError = null
        }

        fun emit(result: BleScanResult) = onResult?.invoke(result)
        fun fail(message: String) = onError?.invoke(message)
    }

    private fun beaconResult(nodeId: Long, rssi: Int, caps: Set<BleCapability>): BleScanResult =
        BleScanResult(
            payload = BleDiscoveryProtocol.encode(BleBeacon(nodeId, caps)),
            rssiDbm = rssi,
        )

    private fun controller(
        radio: FakeBleRadio,
        scope: TestScope,
        clock: () -> Long = { 0L },
        expiryIntervalMillis: Long = 5_000L,
        publishIntervalMillis: Long = 500L,
        registry: DiscoveredBlePeerRegistry = DiscoveredBlePeerRegistry(clock = clock),
        beaconProvider: () -> BleBeacon = { localBeacon },
    ) = DefaultBleDiscoveryController(
        radio = radio,
        registry = registry,
        scope = scope,
        beaconProvider = beaconProvider,
        expiryIntervalMillis = expiryIntervalMillis,
        publishIntervalMillis = publishIntervalMillis,
    )

    @Test
    fun `starts in Idle`() = runTest {
        val radio = FakeBleRadio()
        val controller = controller(radio, this)
        assertEquals(BleDiscoveryState.Idle, controller.state.value)
        controller.stop()
    }

    @Test
    fun `unsupported hardware yields Unsupported`() = runTest {
        val radio = FakeBleRadio(supported = false)
        val controller = controller(radio, this)

        controller.start()

        assertEquals(BleDiscoveryState.Unsupported, controller.state.value)
        assertEquals(0, radio.startCount)
        controller.stop()
    }

    @Test
    fun `missing permissions yields PermissionRequired with the permissions`() = runTest {
        val perms = setOf("android.permission.BLUETOOTH_SCAN")
        val radio = FakeBleRadio(missing = perms)
        val controller = controller(radio, this)

        controller.start()

        assertEquals(BleDiscoveryState.PermissionRequired(perms), controller.state.value)
        assertEquals(0, radio.startCount)
        controller.stop()
    }

    @Test
    fun `disabled adapter yields BluetoothOff`() = runTest {
        val radio = FakeBleRadio(enabled = false)
        val controller = controller(radio, this)

        controller.start()

        assertEquals(BleDiscoveryState.BluetoothOff, controller.state.value)
        assertEquals(0, radio.startCount)
        controller.stop()
    }

    @Test
    fun `ready start advertises and scans filtered by service uuid and goes Scanning`() = runTest {
        val radio = FakeBleRadio()
        val controller = controller(radio, this)

        controller.start()

        assertEquals(1, radio.startCount)
        assertEquals(BleDiscoveryController.SERVICE_UUID, radio.lastServiceUuid)
        assertTrue(
            radio.lastPayload!!.contentEquals(BleDiscoveryProtocol.encode(localBeacon)),
        )
        assertTrue(controller.state.value is BleDiscoveryState.Scanning)
        controller.stop()
    }

    @Test
    fun `advertised payload assembled size fits the legacy advertisement budget`() = runTest {
        val radio = FakeBleRadio()
        val controller = controller(radio, this)

        controller.start()

        // Prove the *assembled* packet (flags + service-data header + UUID +
        // payload) fits, not merely the raw payload. Current payload lands
        // exactly on the 31-byte legacy limit.
        val payloadSize = radio.lastPayload!!.size
        assertTrue(BleAdvertisementBudget.fitsLegacy(payloadSize))
        assertEquals(31, BleAdvertisementBudget.assembledSize(payloadSize))
        controller.stop()
    }

    @Test
    fun `scan result adds a peer to Scanning state`() = runTest {
        val radio = FakeBleRadio()
        val controller = controller(radio, this)
        controller.start()

        radio.emit(beaconResult(7L, -50, setOf(BleCapability.CHAT)))

        val peers = (controller.state.value as BleDiscoveryState.Scanning).peers
        assertEquals(1, peers.size)
        assertEquals(7L, peers[0].nodeId)
        assertEquals(-50, peers[0].rssiDbm)
        assertEquals(setOf(BleCapability.CHAT), peers[0].capabilities)
        controller.stop()
    }

    @Test
    fun `duplicate results update a single peer after a publish tick`() = runTest {
        val radio = FakeBleRadio()
        val controller = controller(radio, this, publishIntervalMillis = 500L)
        controller.start()

        // First sighting is a new peer: published immediately.
        radio.emit(beaconResult(7L, -80, setOf(BleCapability.CHAT)))
        assertEquals(
            -80,
            (controller.state.value as BleDiscoveryState.Scanning).peers.single().rssiDbm,
        )

        // Second sighting only refreshes an existing peer: deferred, not yet visible.
        radio.emit(beaconResult(7L, -55, setOf(BleCapability.CHAT, BleCapability.SOS)))
        assertEquals(
            -80,
            (controller.state.value as BleDiscoveryState.Scanning).peers.single().rssiDbm,
        )

        // After the publish tick the deferred update surfaces.
        advanceTimeBy(501L)
        val peers = (controller.state.value as BleDiscoveryState.Scanning).peers
        assertEquals(1, peers.size)
        assertEquals(-55, peers[0].rssiDbm)
        assertEquals(setOf(BleCapability.CHAT, BleCapability.SOS), peers[0].capabilities)
        controller.stop()
    }

    @Test
    fun `a flood of distinct peers keeps the Scanning list bounded to the cap`() = runTest {
        val radio = FakeBleRadio()
        val controller = controller(
            radio,
            this,
            registry = DiscoveredBlePeerRegistry(clock = { 0L }, maxPeers = 128),
        )
        controller.start()

        repeat(1_000) { i ->
            radio.emit(beaconResult(i.toLong(), -50, setOf(BleCapability.CHAT)))
        }
        advanceTimeBy(600L)

        val peers = (controller.state.value as BleDiscoveryState.Scanning).peers
        assertTrue(peers.size <= 128)
        controller.stop()
    }

    @Test
    fun `only the first peer publishes immediately then a flood of new ids coalesces`() = runTest {
        val radio = FakeBleRadio()
        val controller = controller(
            radio,
            this,
            registry = DiscoveredBlePeerRegistry(clock = { 0L }, maxPeers = 128),
            publishIntervalMillis = 500L,
        )
        controller.start()

        // The first peer from an empty list surfaces immediately for responsiveness.
        radio.emit(beaconResult(0L, -50, setOf(BleCapability.CHAT)))
        assertEquals(1, (controller.state.value as BleDiscoveryState.Scanning).peers.size)

        // A flood of *distinct* new ids must NOT re-sort and republish per packet;
        // without advancing time the published list stays at the single first peer.
        repeat(200) { i ->
            radio.emit(beaconResult((i + 1).toLong(), -50, setOf(BleCapability.CHAT)))
        }
        assertEquals(1, (controller.state.value as BleDiscoveryState.Scanning).peers.size)

        // One publish tick flushes the whole batch at once, bounded by the cap.
        advanceTimeBy(501L)
        assertEquals(128, (controller.state.value as BleDiscoveryState.Scanning).peers.size)
        controller.stop()
    }

    @Test
    fun `a flood emits one immediate publish then one tick, not one per packet`() = runTest {
        val radio = FakeBleRadio()
        val controller = controller(
            radio,
            this,
            registry = DiscoveredBlePeerRegistry(clock = { 0L }, maxPeers = 128),
            publishIntervalMillis = 500L,
        )

        val emissions = mutableListOf<BleDiscoveryState>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.state.toList(emissions)
        }

        controller.start()
        radio.emit(beaconResult(0L, -50, setOf(BleCapability.CHAT)))
        repeat(200) { i ->
            radio.emit(beaconResult((i + 1).toLong(), -50, setOf(BleCapability.CHAT)))
        }
        advanceTimeBy(501L)

        // Scanning emissions: the empty list at start, the immediate first peer,
        // and one coalesced publish tick for the flood -- three, not ~200.
        val scanningEmissions = emissions.count { it is BleDiscoveryState.Scanning }
        assertEquals(3, scanningEmissions)

        collector.cancel()
        controller.stop()
    }

    @Test
    fun `malformed scan result is ignored`() = runTest {
        val radio = FakeBleRadio()
        val controller = controller(radio, this)
        controller.start()

        radio.emit(BleScanResult(payload = byteArrayOf(1, 2, 3), rssiDbm = -40))

        val peers = (controller.state.value as BleDiscoveryState.Scanning).peers
        assertTrue(peers.isEmpty())
        controller.stop()
    }

    @Test
    fun `stop calls radio stop and returns to Idle`() = runTest {
        val radio = FakeBleRadio()
        val controller = controller(radio, this)
        controller.start()

        controller.stop()

        assertEquals(1, radio.stopCount)
        assertEquals(BleDiscoveryState.Idle, controller.state.value)
    }

    @Test
    fun `no scan callback delivered after stop`() = runTest {
        val radio = FakeBleRadio()
        val controller = controller(radio, this)
        controller.start()
        controller.stop()

        radio.emit(beaconResult(7L, -50, setOf(BleCapability.CHAT)))

        assertEquals(BleDiscoveryState.Idle, controller.state.value)
    }

    @Test
    fun `radio error yields bounded Error and stops the radio`() = runTest {
        val radio = FakeBleRadio()
        val controller = controller(radio, this)
        controller.start()

        radio.fail("advertise failed code 3")

        val state = controller.state.value
        assertTrue(state is BleDiscoveryState.Error)
        assertTrue((state as BleDiscoveryState.Error).message.length <= 120)
        assertEquals(1, radio.stopCount)
        controller.stop()
    }

    @Test
    fun `synchronous onError during start yields Error with no Scanning and no expiry loop`() =
        runTest {
            val radio = FakeBleRadio()
            radio.synchronousError = "advertise failed code 3"
            val controller = controller(radio, this)

            controller.start()

            // start was attempted once and torn down exactly once.
            assertEquals(1, radio.startCount)
            assertEquals(1, radio.stopCount)
            assertTrue(controller.state.value is BleDiscoveryState.Error)

            // The happy path must not have run: no loops are scheduled, so
            // advancing time cannot flip the state to Scanning.
            advanceTimeBy(60_000L)
            advanceUntilIdle()
            assertTrue(controller.state.value is BleDiscoveryState.Error)
            assertEquals(1, radio.stopCount)
            controller.stop()
        }

    @Test
    fun `start after an error recovers and scans`() = runTest {
        val radio = FakeBleRadio()
        radio.synchronousError = "transient advertise failure"
        val controller = controller(radio, this)

        controller.start()
        assertTrue(controller.state.value is BleDiscoveryState.Error)

        // The fake cleared its transient failure; a fresh start should succeed.
        controller.start()

        assertEquals(2, radio.startCount)
        assertTrue(controller.state.value is BleDiscoveryState.Scanning)
        controller.stop()
    }

    @Test
    fun `start throwing SecurityException yields Error and stops the radio`() = runTest {
        val radio = FakeBleRadio()
        radio.startException = SecurityException("no permission")
        val controller = controller(radio, this)

        controller.start()

        assertTrue(controller.state.value is BleDiscoveryState.Error)
        assertEquals(1, radio.stopCount)
        controller.stop()
    }

    @Test
    fun `start throwing generic exception yields bounded Error`() = runTest {
        val radio = FakeBleRadio()
        radio.startException = IllegalStateException("boom internal detail")
        val controller = controller(radio, this)

        controller.start()

        val state = controller.state.value
        assertTrue(state is BleDiscoveryState.Error)
        assertTrue((state as BleDiscoveryState.Error).message.length <= 120)
        controller.stop()
    }

    @Test
    fun `start is idempotent while scanning`() = runTest {
        val radio = FakeBleRadio()
        val controller = controller(radio, this)

        controller.start()
        controller.start()

        assertEquals(1, radio.startCount)
        controller.stop()
    }

    @Test
    fun `stop is idempotent`() = runTest {
        val radio = FakeBleRadio()
        val controller = controller(radio, this)
        controller.start()

        controller.stop()
        controller.stop()

        assertEquals(1, radio.stopCount)
    }

    @Test
    fun `periodic expiry drops stale peers and refreshes Scanning state`() = runTest {
        var now = 0L
        val radio = FakeBleRadio()
        val controller = controller(radio, this, clock = { now }, expiryIntervalMillis = 5_000L)
        controller.start()

        radio.emit(beaconResult(7L, -50, setOf(BleCapability.CHAT)))
        assertEquals(1, (controller.state.value as BleDiscoveryState.Scanning).peers.size)

        // Age the peer past the registry's 30s TTL, then let one expiry tick run.
        now = 40_000L
        advanceTimeBy(5_001L)

        assertTrue((controller.state.value as BleDiscoveryState.Scanning).peers.isEmpty())
        controller.stop()
    }

    @Test
    fun `expiry coroutine stops after stop`() = runTest {
        val radio = FakeBleRadio()
        val controller = controller(radio, this, expiryIntervalMillis = 5_000L)
        controller.start()
        controller.stop()

        // If the loops kept running, the scope would still have active
        // children; advancing time should complete with no lingering work.
        advanceTimeBy(60_000L)
        advanceUntilIdle()

        assertEquals(BleDiscoveryState.Idle, controller.state.value)
        assertEquals(1, radio.stopCount)
    }

    // --- I2: ephemeral identity rotation ----------------------------------

    @Test
    fun `each start draws a fresh beacon and rotates the advertised node id`() = runTest {
        val ids = ArrayDeque(listOf(111L, 222L))
        val radio = FakeBleRadio()
        val controller = controller(
            radio,
            this,
            beaconProvider = {
                BleBeacon(nodeId = ids.removeFirst(), capabilities = setOf(BleCapability.CHAT))
            },
        )

        controller.start()
        val first = BleDiscoveryProtocol.decode(radio.lastPayload!!)!!.nodeId
        controller.stop()

        controller.start()
        val second = BleDiscoveryProtocol.decode(radio.lastPayload!!)!!.nodeId
        controller.stop()

        assertEquals(111L, first)
        assertEquals(222L, second)
        assertNotEquals(first, second)
    }

    @Test
    fun `the beacon provider is invoked once per start`() = runTest {
        var calls = 0
        val radio = FakeBleRadio()
        val controller = controller(
            radio,
            this,
            beaconProvider = {
                calls++
                localBeacon
            },
        )

        controller.start()
        radio.emit(beaconResult(7L, -50, setOf(BleCapability.CHAT)))
        radio.emit(beaconResult(8L, -50, setOf(BleCapability.CHAT)))

        assertEquals(1, calls)
        controller.stop()
    }
}

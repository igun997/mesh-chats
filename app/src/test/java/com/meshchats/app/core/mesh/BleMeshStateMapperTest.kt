package com.meshchats.app.core.mesh

import com.meshchats.app.core.transport.ble.BleCapability
import com.meshchats.app.core.transport.ble.BleDiscoveryState
import com.meshchats.app.core.transport.ble.DiscoveredBlePeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapper is the only place BLE discovery vocabulary becomes UI vocabulary.
 * It is pure: no radios, no time, no randomness, so every mapping is asserted
 * directly. Peers produced here are ephemeral and unverified — there is no
 * identity exchange yet, so the "fingerprint" is only a stable rendering of the
 * ephemeral node ID, never a real key fingerprint, and the MAC is never exposed.
 */
class BleMeshStateMapperTest {

    private fun peer(nodeId: Long, rssi: Int = -50, caps: Set<BleCapability> = setOf(BleCapability.CHAT)) =
        DiscoveredBlePeer(nodeId = nodeId, rssiDbm = rssi, lastSeenMillis = 0L, capabilities = caps)

    @Test
    fun `unsupported maps to Bluetooth absent`() {
        val status = BleMeshStateMapper.toTransportStatus(BleDiscoveryState.Unsupported)

        assertEquals(TransportId.BT, status.id)
        assertEquals(TransportState.Absent, status.state)
        assertEquals("Bluetooth LE not supported", status.detail)
    }

    @Test
    fun `permission required maps to Bluetooth off with permission detail`() {
        val status = BleMeshStateMapper.toTransportStatus(
            BleDiscoveryState.PermissionRequired(setOf("android.permission.BLUETOOTH_SCAN")),
        )

        assertEquals(TransportState.Off, status.state)
        assertEquals("Nearby devices permission required", status.detail)
    }

    @Test
    fun `bluetooth off maps to off with adapter detail`() {
        val status = BleMeshStateMapper.toTransportStatus(BleDiscoveryState.BluetoothOff)

        assertEquals(TransportState.Off, status.state)
        assertEquals("Bluetooth is off", status.detail)
    }

    @Test
    fun `idle maps to idle ready to scan`() {
        val status = BleMeshStateMapper.toTransportStatus(BleDiscoveryState.Idle)

        assertEquals(TransportState.Idle, status.state)
        assertEquals("Ready to scan", status.detail)
    }

    @Test
    fun `scanning with no peers maps to idle`() {
        val status = BleMeshStateMapper.toTransportStatus(BleDiscoveryState.Scanning(emptyList()))

        assertEquals(TransportState.Idle, status.state)
        assertEquals("Scanning · no peers", status.detail)
    }

    @Test
    fun `scanning with one peer maps to active with singular detail`() {
        val status = BleMeshStateMapper.toTransportStatus(
            BleDiscoveryState.Scanning(listOf(peer(7L))),
        )

        assertEquals(TransportState.Active(peers = 1, throughputBps = 0), status.state)
        assertEquals("Scanning · 1 peer", status.detail)
    }

    @Test
    fun `scanning with several peers maps to active with plural detail and peer count`() {
        val status = BleMeshStateMapper.toTransportStatus(
            BleDiscoveryState.Scanning(listOf(peer(7L), peer(8L), peer(9L))),
        )

        assertEquals(TransportState.Active(peers = 3, throughputBps = 0), status.state)
        assertEquals("Scanning · 3 peers", status.detail)
    }

    @Test
    fun `error maps to off with bounded prefixed detail`() {
        val status = BleMeshStateMapper.toTransportStatus(
            BleDiscoveryState.Error("advertise failed code 3"),
        )

        assertEquals(TransportState.Off, status.state)
        assertEquals("Bluetooth error · advertise failed code 3", status.detail)
    }

    @Test
    fun `transport constraints are preserved from the caller`() {
        val constraints = Constraints(maxPayloadBytes = 20_480, typicalLatencyMs = 180)

        val status = BleMeshStateMapper.toTransportStatus(BleDiscoveryState.Idle, constraints)

        assertEquals(constraints, status.constraints)
    }

    @Test
    fun `non-scanning states yield no peers`() {
        assertTrue(BleMeshStateMapper.toPeers(BleDiscoveryState.Unsupported).isEmpty())
        assertTrue(BleMeshStateMapper.toPeers(BleDiscoveryState.BluetoothOff).isEmpty())
        assertTrue(BleMeshStateMapper.toPeers(BleDiscoveryState.Idle).isEmpty())
        assertTrue(BleMeshStateMapper.toPeers(BleDiscoveryState.Error("x")).isEmpty())
    }

    @Test
    fun `scanning peers map deterministically to ephemeral unverified BT peers`() {
        val peers = BleMeshStateMapper.toPeers(
            BleDiscoveryState.Scanning(listOf(peer(nodeId = 0x0123456789ABCDEFL, rssi = -63))),
        )

        assertEquals(1, peers.size)
        val p = peers.single()
        // id is derived from the unsigned 16-hex node ID, never the MAC.
        assertEquals("ble-0123456789abcdef", p.id)
        assertEquals("nearby cdef", p.displayName)
        // "fingerprint" is only a stable rendering of the ephemeral node ID.
        assertEquals(listOf("0123", "4567", "89ab", "cdef"), p.fingerprint)
        assertFalse(p.verified)
        assertEquals(setOf(TransportId.BT), p.reachableVia)
        assertEquals(-63, p.rssiDbm)
        assertEquals(1, p.hops)
        assertEquals(0, p.lastSeenMinutes)
    }

    @Test
    fun `unsigned node id edge value renders as all-f hex`() {
        val peers = BleMeshStateMapper.toPeers(
            BleDiscoveryState.Scanning(listOf(peer(nodeId = -1L))),
        )

        val p = peers.single()
        assertEquals("ble-ffffffffffffffff", p.id)
        assertEquals("nearby ffff", p.displayName)
        assertEquals(listOf("ffff", "ffff", "ffff", "ffff"), p.fingerprint)
    }

    @Test
    fun `peer order follows discovery order`() {
        val peers = BleMeshStateMapper.toPeers(
            BleDiscoveryState.Scanning(listOf(peer(2L), peer(1L), peer(3L))),
        )

        assertEquals(listOf("ble-0000000000000002", "ble-0000000000000001", "ble-0000000000000003"), peers.map { it.id })
    }
}

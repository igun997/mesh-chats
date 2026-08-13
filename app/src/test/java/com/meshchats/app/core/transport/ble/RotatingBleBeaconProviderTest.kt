package com.meshchats.app.core.transport.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RotatingBleBeaconProviderTest {

    @Test
    fun `each invocation draws a fresh random node id`() {
        val ids = ArrayDeque(listOf(1L, 2L, 3L))
        val provider = RotatingBleBeaconProvider(
            capabilities = setOf(BleCapability.CHAT, BleCapability.SOS),
            randomLong = { ids.removeFirst() },
        )

        val a = provider()
        val b = provider()

        assertEquals(1L, a.nodeId)
        assertEquals(2L, b.nodeId)
        assertNotEquals(a.nodeId, b.nodeId)
    }

    @Test
    fun `advertised capabilities are carried through unchanged`() {
        val provider = RotatingBleBeaconProvider(
            capabilities = setOf(BleCapability.CHAT, BleCapability.SOS),
            randomLong = { 42L },
        )

        val beacon = provider()

        assertEquals(setOf(BleCapability.CHAT, BleCapability.SOS), beacon.capabilities)
    }

    @Test
    fun `advertises chat and sos capabilities by default`() {
        val provider = RotatingBleBeaconProvider(randomLong = { 42L })

        assertEquals(setOf(BleCapability.CHAT, BleCapability.SOS), provider().capabilities)
    }

    @Test
    fun `a collision in the generator retries until a distinct id is drawn`() {
        // First two draws collide with the previously issued id; the third differs.
        val sequence = ArrayDeque(listOf(7L, 7L, 7L, 9L))
        val provider = RotatingBleBeaconProvider(randomLong = { sequence.removeFirst() })

        val first = provider() // 7
        val second = provider() // 7,7,9 -> 9

        assertEquals(7L, first.nodeId)
        assertEquals(9L, second.nodeId)
        assertNotEquals(first.nodeId, second.nodeId)
    }

    @Test
    fun `distinct beacons over many draws`() {
        var n = 0L
        val provider = RotatingBleBeaconProvider(randomLong = { n++ })

        val seen = (0 until 100).map { provider().nodeId }.toSet()

        assertTrue(seen.size == 100)
    }
}

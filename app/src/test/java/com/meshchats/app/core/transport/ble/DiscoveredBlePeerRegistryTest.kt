package com.meshchats.app.core.transport.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveredBlePeerRegistryTest {

    private var now = 0L
    private val registry = DiscoveredBlePeerRegistry(clock = { now })

    @Test
    fun `upsert adds a discovered peer`() {
        now = 1_000
        registry.upsert(nodeId = 7L, rssiDbm = -50, capabilities = setOf(BleCapability.CHAT))

        val peers = registry.activePeers()

        assertEquals(1, peers.size)
        assertEquals(7L, peers[0].nodeId)
        assertEquals(-50, peers[0].rssiDbm)
        assertEquals(1_000, peers[0].lastSeenMillis)
        assertEquals(setOf(BleCapability.CHAT), peers[0].capabilities)
    }

    @Test
    fun `upsert deduplicates by node id and updates rssi last-seen and caps`() {
        now = 1_000
        registry.upsert(nodeId = 7L, rssiDbm = -80, capabilities = setOf(BleCapability.CHAT))
        now = 2_000
        registry.upsert(
            nodeId = 7L,
            rssiDbm = -55,
            capabilities = setOf(BleCapability.CHAT, BleCapability.SOS),
        )

        val peers = registry.activePeers()

        assertEquals(1, peers.size)
        assertEquals(-55, peers[0].rssiDbm)
        assertEquals(2_000, peers[0].lastSeenMillis)
        assertEquals(setOf(BleCapability.CHAT, BleCapability.SOS), peers[0].capabilities)
    }

    @Test
    fun `active peers exclude entries older than the 30s window`() {
        now = 0
        registry.upsert(nodeId = 1L, rssiDbm = -60, capabilities = emptySet())
        now = 30_001

        assertTrue(registry.activePeers().isEmpty())
    }

    @Test
    fun `peer seen exactly at the 30s boundary is still active`() {
        now = 0
        registry.upsert(nodeId = 1L, rssiDbm = -60, capabilities = emptySet())
        now = 30_000

        assertEquals(1, registry.activePeers().size)
    }

    @Test
    fun `expire removes peers past the window and keeps fresh ones`() {
        now = 0
        registry.upsert(nodeId = 1L, rssiDbm = -60, capabilities = emptySet())
        now = 20_000
        registry.upsert(nodeId = 2L, rssiDbm = -60, capabilities = emptySet())
        now = 30_001 // peer 1 aged out (>30s), peer 2 still fresh (10.001s)

        val removed = registry.expire()

        assertEquals(1, removed)
        val ids = registry.activePeers().map { it.nodeId }
        assertEquals(listOf(2L), ids)
    }

    @Test
    fun `active peers order by most recently seen then unsigned node id`() {
        now = 100
        // Negative long is a large unsigned value; must sort after positive ids
        // when last-seen ties.
        registry.upsert(nodeId = -1L, rssiDbm = -60, capabilities = emptySet())
        registry.upsert(nodeId = 5L, rssiDbm = -60, capabilities = emptySet())
        now = 200
        registry.upsert(nodeId = 9L, rssiDbm = -60, capabilities = emptySet())

        val ids = registry.activePeers().map { it.nodeId }

        // 9 seen most recently, then the tie at t=100 broken by unsigned id (5 < 2^64-1)
        assertEquals(listOf(9L, 5L, -1L), ids)
    }
}

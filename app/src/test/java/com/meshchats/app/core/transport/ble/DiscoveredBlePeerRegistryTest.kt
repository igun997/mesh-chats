package com.meshchats.app.core.transport.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun `upsert reports insert versus update`() {
        now = 1_000
        val first = registry.upsert(nodeId = 7L, rssiDbm = -50, capabilities = emptySet())
        val second = registry.upsert(nodeId = 7L, rssiDbm = -40, capabilities = emptySet())

        assertTrue(first)
        assertFalse(second)
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

    // --- C1: bounded registry ---------------------------------------------

    @Test
    fun `constructor rejects a non-positive cap`() {
        assertThrows(IllegalArgumentException::class.java) {
            DiscoveredBlePeerRegistry(clock = { now }, maxPeers = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiscoveredBlePeerRegistry(clock = { now }, maxPeers = -1)
        }
    }

    @Test
    fun `flood of distinct ids never exceeds the cap`() {
        val bounded = DiscoveredBlePeerRegistry(clock = { now }, maxPeers = 128)
        now = 1_000
        repeat(1_000) { i ->
            bounded.upsert(nodeId = i.toLong(), rssiDbm = -50, capabilities = emptySet())
        }

        assertEquals(128, bounded.activePeers().size)
    }

    @Test
    fun `updating an existing peer at cap never evicts`() {
        val bounded = DiscoveredBlePeerRegistry(clock = { now }, maxPeers = 128)
        now = 1_000
        repeat(128) { i ->
            bounded.upsert(nodeId = i.toLong(), rssiDbm = -50, capabilities = emptySet())
        }

        now = 2_000
        val wasInserted = bounded.upsert(nodeId = 0L, rssiDbm = -30, capabilities = emptySet())

        assertFalse(wasInserted)
        val peers = bounded.activePeers()
        assertEquals(128, peers.size)
        val zero = peers.first { it.nodeId == 0L }
        assertEquals(-30, zero.rssiDbm)
        assertEquals(2_000, zero.lastSeenMillis)
    }

    @Test
    fun `eviction drops the oldest last-seen peer first`() {
        val bounded = DiscoveredBlePeerRegistry(clock = { now }, maxPeers = 2)
        now = 100
        bounded.upsert(nodeId = 1L, rssiDbm = -50, capabilities = emptySet())
        now = 200
        bounded.upsert(nodeId = 2L, rssiDbm = -50, capabilities = emptySet())

        now = 300
        val wasInserted = bounded.upsert(nodeId = 3L, rssiDbm = -50, capabilities = emptySet())

        assertTrue(wasInserted)
        val ids = bounded.activePeers().map { it.nodeId }.sorted()
        assertEquals(listOf(2L, 3L), ids) // oldest (id 1, t=100) evicted
    }

    @Test
    fun `eviction breaks a last-seen tie by weakest rssi`() {
        val bounded = DiscoveredBlePeerRegistry(clock = { now }, maxPeers = 2)
        now = 100
        bounded.upsert(nodeId = 1L, rssiDbm = -40, capabilities = emptySet())
        bounded.upsert(nodeId = 2L, rssiDbm = -80, capabilities = emptySet())

        bounded.upsert(nodeId = 3L, rssiDbm = -50, capabilities = emptySet())

        val ids = bounded.activePeers().map { it.nodeId }.sorted()
        assertEquals(listOf(1L, 3L), ids) // weakest rssi (id 2, -80) evicted
    }

    @Test
    fun `eviction breaks a last-seen and rssi tie by highest unsigned id`() {
        val bounded = DiscoveredBlePeerRegistry(clock = { now }, maxPeers = 2)
        now = 100
        bounded.upsert(nodeId = 5L, rssiDbm = -50, capabilities = emptySet())
        // -1L is the largest unsigned 64-bit value.
        bounded.upsert(nodeId = -1L, rssiDbm = -50, capabilities = emptySet())

        bounded.upsert(nodeId = 9L, rssiDbm = -50, capabilities = emptySet())

        val ids = bounded.activePeers().map { it.nodeId }.toSet()
        assertEquals(setOf(5L, 9L), ids) // highest unsigned (-1L) evicted
    }

    @Test
    fun `insertion at cap expires stale entries before evicting a live one`() {
        val bounded = DiscoveredBlePeerRegistry(clock = { now }, maxPeers = 2)
        now = 0
        bounded.upsert(nodeId = 1L, rssiDbm = -50, capabilities = emptySet())
        now = 20_000
        bounded.upsert(nodeId = 2L, rssiDbm = -50, capabilities = emptySet())

        // At t=35s peer 1 (t=0) is stale (>30s), peer 2 (t=20s) is still fresh.
        now = 35_000
        val wasInserted = bounded.upsert(nodeId = 3L, rssiDbm = -50, capabilities = emptySet())

        assertTrue(wasInserted)
        val ids = bounded.activePeers().map { it.nodeId }.sorted()
        // Stale peer 1 expired; the fresh peer 2 was kept rather than evicted.
        assertEquals(listOf(2L, 3L), ids)
    }
}

package com.meshchats.app.core.transport.ble

/**
 * An immutable snapshot of a peer discovered over BLE.
 *
 * Identity is the ephemeral [nodeId] from the beacon payload. The Bluetooth MAC
 * address is deliberately absent: BLE addresses rotate and must never be used
 * as identity, so the registry API offers no way to store or read one.
 */
data class DiscoveredBlePeer(
    val nodeId: Long,
    val rssiDbm: Int,
    val lastSeenMillis: Long,
    val capabilities: Set<BleCapability>,
)

/**
 * Tracks BLE peers seen during discovery, deduplicated by node ID and expired
 * after a fixed liveness window. Time is injected so tests are deterministic.
 *
 * Not thread-safe on its own; the owning controller serializes all access under
 * its private lock.
 */
class DiscoveredBlePeerRegistry(
    private val clock: () -> Long,
) {
    private val peers = mutableMapOf<Long, DiscoveredBlePeer>()

    /**
     * Record a sighting of [nodeId], inserting it or refreshing its RSSI,
     * last-seen time, and capabilities.
     */
    fun upsert(nodeId: Long, rssiDbm: Int, capabilities: Set<BleCapability>) {
        peers[nodeId] = DiscoveredBlePeer(
            nodeId = nodeId,
            rssiDbm = rssiDbm,
            lastSeenMillis = clock(),
            capabilities = capabilities,
        )
    }

    /**
     * Peers seen within [PEER_TTL_MILLIS] of now, ordered most-recently-seen
     * first and then by unsigned node ID. Does not mutate the registry; call
     * [expire] to drop stale entries.
     */
    fun activePeers(): List<DiscoveredBlePeer> {
        val cutoff = clock() - PEER_TTL_MILLIS
        return peers.values
            .filter { it.lastSeenMillis >= cutoff }
            .sortedWith(
                compareByDescending<DiscoveredBlePeer> { it.lastSeenMillis }
                    .thenComparator { a, b ->
                        java.lang.Long.compareUnsigned(a.nodeId, b.nodeId)
                    },
            )
    }

    /**
     * Drop peers not seen within the liveness window, returning how many were
     * removed (useful for discovery metrics).
     */
    fun expire(): Int {
        val cutoff = clock() - PEER_TTL_MILLIS
        val before = peers.size
        peers.values.removeAll { it.lastSeenMillis < cutoff }
        return before - peers.size
    }

    private companion object {
        const val PEER_TTL_MILLIS = 30_000L
    }
}

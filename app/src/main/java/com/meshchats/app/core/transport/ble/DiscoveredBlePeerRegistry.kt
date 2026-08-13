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
 * ### Bounded memory
 * A hostile or dense environment can advertise an unbounded number of distinct
 * ephemeral node IDs; left unchecked that would grow the map without limit. The
 * registry therefore caps live entries at [maxPeers]. Updating an existing peer
 * never evicts. Before inserting a *new* ID at capacity it first drops stale
 * entries, and if still full evicts exactly one victim, chosen deterministically:
 * oldest last-seen first, ties broken by weakest (most negative) RSSI, then by
 * highest unsigned node ID. The registry never exceeds [maxPeers].
 *
 * Not thread-safe on its own; the owning controller serializes all access under
 * its private lock.
 */
class DiscoveredBlePeerRegistry(
    private val clock: () -> Long,
    private val maxPeers: Int = DEFAULT_MAX_PEERS,
) {
    init {
        require(maxPeers > 0) { "maxPeers must be > 0, was $maxPeers" }
    }

    private val peers = mutableMapOf<Long, DiscoveredBlePeer>()

    /**
     * Record a sighting of [nodeId], inserting it or refreshing its RSSI,
     * last-seen time, and capabilities. Returns true only when a *new* peer was
     * inserted (so callers can publish new peers immediately and defer refreshes).
     *
     * Inserting a new ID at capacity expires stale entries first and, if still
     * full, evicts exactly one entry per [DiscoveredBlePeerRegistry]. Updating an
     * existing peer never evicts and never exceeds the cap.
     */
    fun upsert(nodeId: Long, rssiDbm: Int, capabilities: Set<BleCapability>): Boolean {
        val isNew = !peers.containsKey(nodeId)
        if (isNew && peers.size >= maxPeers) {
            makeRoomForInsert()
        }
        peers[nodeId] = DiscoveredBlePeer(
            nodeId = nodeId,
            rssiDbm = rssiDbm,
            lastSeenMillis = clock(),
            capabilities = capabilities,
        )
        return isNew
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

    /**
     * Free exactly one slot for a pending new insert. Expire stale entries first
     * (cheap, and often enough), then, if still at capacity, evict the single
     * deterministic victim: oldest last-seen, then weakest RSSI, then highest
     * unsigned node ID. Callers guarantee the registry is at capacity.
     */
    private fun makeRoomForInsert() {
        expire()
        if (peers.size < maxPeers) return

        val victim = peers.values.minWithOrNull(EVICTION_ORDER) ?: return
        peers.remove(victim.nodeId)
    }

    private companion object {
        const val PEER_TTL_MILLIS = 30_000L
        const val DEFAULT_MAX_PEERS = 128

        /**
         * Orders peers by *eviction preference* — the "smallest" entry is the
         * one dropped: oldest last-seen first, then weakest (most negative)
         * RSSI, then highest unsigned node ID.
         */
        val EVICTION_ORDER: Comparator<DiscoveredBlePeer> =
            compareBy<DiscoveredBlePeer> { it.lastSeenMillis }
                .thenBy { it.rssiDbm }
                .thenComparator { a, b ->
                    // Highest unsigned id evicts first => invert the comparison.
                    java.lang.Long.compareUnsigned(b.nodeId, a.nodeId)
                }
    }
}

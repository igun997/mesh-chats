package com.meshchats.protocol.routing


/** Sends an opaque packet to one next hop over a specific transport. */
fun interface LinkSender {
    /** Returns true if the packet was accepted by the link, false on failure. */
    fun send(nextHop: NodeId, transport: TransportId, packet: MeshPacket): Boolean
}

/** Why a packet was not forwarded. */
enum class DropReason {
    EXPIRED,
    DUPLICATE,
    HOP_BUDGET_EXHAUSTED,
    ROUTE_MISMATCH,
    LINK_FAILURE,
}

/** Outcome of a forward attempt. */
sealed interface ForwardResult {
    data object Sent : ForwardResult
    data class Rejected(val reason: DropReason) : ForwardResult
}

/**
 * Forwards opaque packets hop-by-hop along a discovered route.
 *
 * The forwarder validates policy before touching a radio: it drops expired
 * packets, duplicates seen within a bounded time window, packets whose hop
 * budget cannot cover the route, and routes that do not match the expected
 * origin/destination. It never decrypts, re-encrypts, or mutates the
 * ciphertext, destination tag, or origin signature; only the remaining hop
 * budget is decremented per relay.
 *
 * Duplicate suppression is payload-aware. Normal traffic is deduplicated by
 * [PacketId] alone: the same packet is forwarded once per window regardless of
 * route. SOS traffic is life-safety critical and is deliberately allowed to
 * traverse multiple *distinct* routes, so it is deduplicated by the pair
 * `(packetId, route fingerprint)`: the same SOS packet may fan out across up to
 * [maxSosRoutesPerPacket] independent routes, while a replay of an
 * already-forwarded `(packet, route)` pair is still rejected. The tracked-key
 * table is bounded globally by [maxTrackedPackets] regardless of kind.
 *
 * Thread-safety: the mutable duplicate table is guarded by an internal lock, so
 * [forward] may be called concurrently from multiple link callbacks. The lock
 * is only held while reserving/releasing a duplicate key, never while calling
 * [LinkSender.send]; a duplicate key is reserved under the lock before sending
 * and released if the send fails, so a transient link failure leaves the packet
 * eligible for a later retry rather than permanently suppressed.
 */
class PacketForwarder(
    private val sender: LinkSender,
    private val nowMillis: () -> Long,
    private val duplicateWindowMillis: Long = DEFAULT_DUPLICATE_WINDOW_MILLIS,
    private val maxTrackedPackets: Int = DEFAULT_MAX_TRACKED_PACKETS,
    private val maxSosRoutesPerPacket: Int = DEFAULT_MAX_SOS_ROUTES_PER_PACKET,
) {
    init {
        require(maxTrackedPackets > 0) { "maxTrackedPackets must be positive" }
        require(maxSosRoutesPerPacket > 0) { "maxSosRoutesPerPacket must be positive" }
    }

    private val lock = Any()

    // Insertion-ordered duplicate key -> first-seen timestamp. Bounded in both
    // time and count. Guarded by [lock]. The key is the PacketId for normal
    // traffic and "packetId|routeFingerprint" for SOS multipath traffic.
    private val seen = LinkedHashMap<String, Long>()

    // How many distinct routes an SOS packet has already been forwarded over,
    // within the window. Guarded by [lock]. Used to cap SOS fan-out.
    private val sosRouteCounts = LinkedHashMap<String, Int>()

    fun forward(
        packet: MeshPacket,
        route: MeshRoute,
        expectedOrigin: NodeId? = null,
        expectedDestination: NodeId? = null,
    ): ForwardResult {
        val now = nowMillis()

        if (packet.isExpired(now)) return ForwardResult.Rejected(DropReason.EXPIRED)

        if (expectedOrigin != null && route.origin != expectedOrigin) {
            return ForwardResult.Rejected(DropReason.ROUTE_MISMATCH)
        }
        if (expectedDestination != null && route.destination != expectedDestination) {
            return ForwardResult.Rejected(DropReason.ROUTE_MISMATCH)
        }

        // Hop budget must cover every hop on the route, and be non-zero.
        if (packet.hopsRemaining <= 0 || packet.hopsRemaining < route.hopCount) {
            return ForwardResult.Rejected(DropReason.HOP_BUDGET_EXHAUSTED)
        }

        val key = duplicateKey(packet, route)
        // Reserve the duplicate key under the lock before touching a radio.
        synchronized(lock) {
            pruneExpired(now)
            if (seen.containsKey(key)) {
                return ForwardResult.Rejected(DropReason.DUPLICATE)
            }
            if (packet.kind == PacketKind.SOS) {
                val count = sosRouteCounts.getOrDefault(packet.packetId.value, 0)
                if (count >= maxSosRoutesPerPacket) {
                    return ForwardResult.Rejected(DropReason.DUPLICATE)
                }
            }
            reserve(key, packet, now)
        }

        // Forward hop-by-hop with the lock released, decrementing the budget once
        // per relay. Ciphertext, destination tag, and signature are carried
        // untouched by withHopsRemaining.
        var remaining = packet.hopsRemaining
        for (hop in route.hops) {
            remaining -= 1
            val forwarded = packet.withHopsRemaining(remaining)
            if (!sender.send(hop.to, hop.transport, forwarded)) {
                // Release the reservation so the packet can be retried later.
                synchronized(lock) { release(key, packet) }
                return ForwardResult.Rejected(DropReason.LINK_FAILURE)
            }
        }
        return ForwardResult.Sent
    }

    /**
     * Normal traffic dedupes by packet id (one forward per window). SOS dedupes
     * by (packet id, route fingerprint) so the same emergency packet may fan out
     * across multiple distinct routes while a replay of the same route is still
     * suppressed.
     */
    private fun duplicateKey(packet: MeshPacket, route: MeshRoute): String =
        if (packet.kind == PacketKind.SOS) {
            "${packet.packetId.value}|${route.fingerprint}"
        } else {
            packet.packetId.value
        }

    private fun reserve(key: String, packet: MeshPacket, now: Long) {
        seen[key] = now
        if (packet.kind == PacketKind.SOS) {
            val id = packet.packetId.value
            sosRouteCounts[id] = sosRouteCounts.getOrDefault(id, 0) + 1
        }
        // Bound by count globally: evict oldest insertions beyond capacity.
        while (seen.size > maxTrackedPackets) {
            val oldest = seen.keys.iterator().next()
            forget(oldest)
        }
    }

    private fun release(key: String, packet: MeshPacket) {
        if (seen.remove(key) != null && packet.kind == PacketKind.SOS) {
            decrementSosCount(packet.packetId.value)
        }
    }

    private fun forget(key: String) {
        seen.remove(key)
        // If the evicted key was an SOS route key, decrement its fan-out count.
        val separator = key.indexOf('|')
        if (separator > 0) {
            decrementSosCount(key.substring(0, separator))
        }
    }

    private fun decrementSosCount(id: String) {
        val current = sosRouteCounts[id] ?: return
        if (current <= 1) sosRouteCounts.remove(id) else sosRouteCounts[id] = current - 1
    }

    private fun pruneExpired(now: Long) {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value >= duplicateWindowMillis) {
                val key = entry.key
                iterator.remove()
                val separator = key.indexOf('|')
                if (separator > 0) decrementSosCount(key.substring(0, separator))
            } else {
                // Insertion order means once we hit a fresh entry the rest are fresh.
                break
            }
        }
    }

    companion object {
        const val DEFAULT_DUPLICATE_WINDOW_MILLIS: Long = 600_000 // 10 minutes
        const val DEFAULT_MAX_TRACKED_PACKETS: Int = 4096
        const val DEFAULT_MAX_SOS_ROUTES_PER_PACKET: Int = 3
    }
}

package com.meshchats.app.core.routing

/** Why the queue refused a packet. */
enum class QueueRejection {
    EXPIRED,
    DUPLICATE,
    OVERSIZED,
}

/** Outcome of offering a packet to the relay queue. */
sealed interface OfferResult {
    data object Queued : OfferResult
    data class Rejected(val reason: QueueRejection) : OfferResult
}

/**
 * Bounded, in-memory store-and-forward queue for opaque relay packets.
 *
 * The queue only ever holds ciphertext; there is no plaintext to expose, so an
 * app lock cannot leak message contents. It is bounded three ways to defend
 * memory against a hostile sender: packet age, total bytes, and packet count.
 * When room is needed, eviction is deterministic: expired packets first, then
 * the earliest expiry, then the oldest arrival.
 *
 * A monotonic clock is injected so age is independent of wall-clock changes.
 *
 * Thread-safety: every public operation that reads or mutates the entry map or
 * the running byte count is guarded by an internal lock, so the queue may be
 * shared across the offer path and the drain/acknowledge path on different
 * threads without corrupting [byteCount] or the entry set.
 */
class RelayQueue(
    private val nowMillis: () -> Long,
    val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val maxPackets: Int = DEFAULT_MAX_PACKETS,
) {
    private class Entry(
        val packet: MeshPacket,
        val arrivalMillis: Long,
    ) {
        val sizeBytes: Int get() = packet.sizeBytes
        val expiresAtMillis: Long get() = packet.expiresAtMillis
    }

    private val lock = Any()

    // Insertion order == arrival order, which we rely on for oldest-arrival eviction.
    private val entries = LinkedHashMap<PacketId, Entry>()
    private var bytes = 0L

    val size: Int get() = synchronized(lock) { entries.size }
    val byteCount: Long get() = synchronized(lock) { bytes }

    fun contains(id: PacketId): Boolean = synchronized(lock) { entries.containsKey(id) }

    /**
     * Attempts to store [packet]. Rejects already-expired, duplicate, and
     * individually-oversized packets. Evicts to make room within the byte and
     * count caps when necessary.
     */
    fun offer(packet: MeshPacket): OfferResult = synchronized(lock) {
        val now = nowMillis()

        if (packet.isExpired(now)) return OfferResult.Rejected(QueueRejection.EXPIRED)
        if (entries.containsKey(packet.packetId)) {
            return OfferResult.Rejected(QueueRejection.DUPLICATE)
        }
        if (packet.sizeBytes > maxBytes) return OfferResult.Rejected(QueueRejection.OVERSIZED)

        purgeAged(now)

        // Make room for count and bytes using deterministic eviction.
        while (entries.size + 1 > maxPackets) {
            if (!evictOne(now)) break
        }
        while (bytes + packet.sizeBytes > maxBytes) {
            if (!evictOne(now)) break
        }

        entries[packet.packetId] = Entry(packet, now)
        bytes += packet.sizeBytes
        return OfferResult.Queued
    }

    /** Removes the packet matching [id] on delivery acknowledgement. */
    fun acknowledge(id: PacketId): Boolean = synchronized(lock) {
        val removed = entries.remove(id) ?: return false
        bytes -= removed.sizeBytes
        return true
    }

    /** Clears the whole queue, e.g. on Mesh-mode disable or panic wipe. */
    fun clear() = synchronized(lock) {
        entries.clear()
        bytes = 0
    }

    /**
     * Removes and returns packets now deliverable to [destination]. Packets past
     * their age cap or own expiry are dropped, not returned. Only packets whose
     * single-byte destination tag is in [deliverableTags] are delivered, so a
     * relay never floods every held packet at a peer. [deliverableTags] must be
     * non-empty; an empty set would deliver nothing and is a caller error.
     *
     * [destination] is a tracer placeholder: the current single-byte tag scheme
     * is matched purely on [deliverableTags], and the node id is reserved for a
     * future per-destination routing table. It is documented here so callers do
     * not assume node-level filtering yet.
     */
    fun drainDeliverable(
        @Suppress("UNUSED_PARAMETER") destination: NodeId,
        deliverableTags: Set<Byte>,
    ): List<MeshPacket> = synchronized(lock) {
        require(deliverableTags.isNotEmpty()) {
            "deliverableTags must be non-empty; an empty set delivers nothing"
        }
        val now = nowMillis()
        purgeAged(now)

        val delivered = ArrayList<MeshPacket>()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            val tag = entry.packet.destinationTag
            val tagMatches = tag.size == 1 && tag[0] in deliverableTags
            if (!tagMatches) continue
            delivered += entry.packet
            bytes -= entry.sizeBytes
            iterator.remove()
        }
        return delivered
    }

    /** Drops packets past the age cap or their own expiry. Caller holds [lock]. */
    private fun purgeAged(now: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            val aged = now - entry.arrivalMillis >= maxAgeMillis
            if (aged || entry.packet.isExpired(now)) {
                bytes -= entry.sizeBytes
                iterator.remove()
            }
        }
    }

    /**
     * Evicts a single entry using the deterministic policy: an already-expired
     * packet if any, else the earliest expiry, breaking ties by oldest arrival.
     * Returns false if the queue is empty. Caller holds [lock].
     *
     * Iteration is in arrival order, so on any tie the incumbent (earlier
     * arrival) is retained as the victim only until a strictly better candidate
     * appears; this yields oldest-arrival tie-breaking without tracking indices.
     */
    private fun evictOne(now: Long): Boolean {
        if (entries.isEmpty()) return false

        var victimId: PacketId? = null
        var victim: Entry? = null

        for ((id, entry) in entries) {
            val expiredNow = entry.packet.isExpired(now)
            val incumbent = victim
            when {
                incumbent == null -> {
                    victimId = id; victim = entry
                }
                expiredNow && !incumbent.packet.isExpired(now) -> {
                    // Prefer an already-expired packet over a live one.
                    victimId = id; victim = entry
                }
                expiredNow == incumbent.packet.isExpired(now) &&
                    entry.expiresAtMillis < incumbent.expiresAtMillis -> {
                    // Same expired-ness and a strictly earlier expiry wins. Ties on
                    // expiry fall to oldest arrival: the incumbent arrived earlier,
                    // so it is retained.
                    victimId = id; victim = entry
                }
            }
        }

        val removed = entries.remove(victimId) ?: return false
        bytes -= removed.sizeBytes
        return true
    }

    companion object {
        const val DEFAULT_MAX_AGE_MILLIS: Long = 900_000 // 15 minutes
        const val DEFAULT_MAX_BYTES: Long = 5L * 1024 * 1024 // 5 MiB
        const val DEFAULT_MAX_PACKETS: Int = 1024
    }
}

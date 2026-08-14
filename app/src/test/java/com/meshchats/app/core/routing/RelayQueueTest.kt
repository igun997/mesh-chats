package com.meshchats.app.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayQueueTest {

    @Test
    fun `accepts a fresh packet and reports counts and bytes`() {
        var now = 0L
        val queue = RelayQueue(nowMillis = { now })

        val result = queue.offer(packet("p1", ciphertext = ByteArray(100), expiresAtMillis = 1_000_000))

        assertEquals(OfferResult.Queued, result)
        assertEquals(1, queue.size)
        assertEquals(100L, queue.byteCount)
    }

    @Test
    fun `rejects an already expired packet`() {
        val queue = RelayQueue(nowMillis = { 10_000 })

        val result = queue.offer(packet("p1", expiresAtMillis = 5_000))

        assertEquals(OfferResult.Rejected(QueueRejection.EXPIRED), result)
        assertEquals(0, queue.size)
    }

    @Test
    fun `rejects a duplicate packet`() {
        val queue = RelayQueue(nowMillis = { 0 })
        queue.offer(packet("p1", expiresAtMillis = 1_000_000))

        val result = queue.offer(packet("p1", expiresAtMillis = 1_000_000))

        assertEquals(OfferResult.Rejected(QueueRejection.DUPLICATE), result)
        assertEquals(1, queue.size)
    }

    @Test
    fun `rejects a packet that alone exceeds the byte cap`() {
        val queue = RelayQueue(nowMillis = { 0 }, maxBytes = 50)

        val result = queue.offer(packet("p1", ciphertext = ByteArray(51), expiresAtMillis = 1_000_000))

        assertEquals(OfferResult.Rejected(QueueRejection.OVERSIZED), result)
    }

    @Test
    fun `enforces the fifteen minute age cap on drain`() {
        var now = 0L
        val queue = RelayQueue(nowMillis = { now }, maxAgeMillis = 900_000)
        queue.offer(packet("p1", expiresAtMillis = 5_000_000))

        now = 900_001 // older than 15 minutes since arrival
        val drained = queue.drainDeliverable(NodeId("C"), deliverableTags = setOf(9.toByte()))

        assertTrue(drained.isEmpty())
        assertEquals(0, queue.size)
    }

    @Test
    fun `evicts expired packets first when making room`() {
        var now = 0L
        // Cap allows only two 100-byte packets.
        val queue = RelayQueue(nowMillis = { now }, maxBytes = 220)
        queue.offer(packet("expired", ciphertext = ByteArray(100), expiresAtMillis = 2_000))
        queue.offer(packet("fresh", ciphertext = ByteArray(100), expiresAtMillis = 5_000_000))

        now = 3_000 // 'expired' is now past its own expiry
        val result = queue.offer(packet("new", ciphertext = ByteArray(100), expiresAtMillis = 5_000_000))

        assertEquals(OfferResult.Queued, result)
        assertFalse(queue.contains(PacketId("expired")))
        assertTrue(queue.contains(PacketId("fresh")))
        assertTrue(queue.contains(PacketId("new")))
    }

    @Test
    fun `evicts earliest expiry before oldest arrival when no packet is expired`() {
        val queue = RelayQueue(nowMillis = { 0 }, maxBytes = 220)
        // Arrival order: A then B. B expires sooner than A.
        queue.offer(packet("A", ciphertext = ByteArray(100), expiresAtMillis = 9_000_000))
        queue.offer(packet("B", ciphertext = ByteArray(100), expiresAtMillis = 1_000_000))

        val result = queue.offer(packet("C", ciphertext = ByteArray(100), expiresAtMillis = 5_000_000))

        assertEquals(OfferResult.Queued, result)
        assertFalse("earliest-expiry B should be evicted", queue.contains(PacketId("B")))
        assertTrue(queue.contains(PacketId("A")))
        assertTrue(queue.contains(PacketId("C")))
    }

    @Test
    fun `evicts oldest arrival to break an expiry tie`() {
        var now = 0L
        val queue = RelayQueue(nowMillis = { now }, maxBytes = 220)
        queue.offer(packet("first", ciphertext = ByteArray(100), expiresAtMillis = 5_000_000))
        now = 10
        queue.offer(packet("second", ciphertext = ByteArray(100), expiresAtMillis = 5_000_000))

        now = 20
        val result = queue.offer(packet("third", ciphertext = ByteArray(100), expiresAtMillis = 5_000_000))

        assertEquals(OfferResult.Queued, result)
        assertFalse("oldest arrival should be evicted on tie", queue.contains(PacketId("first")))
        assertTrue(queue.contains(PacketId("second")))
        assertTrue(queue.contains(PacketId("third")))
    }

    @Test
    fun `enforces a bounded packet count`() {
        val queue = RelayQueue(nowMillis = { 0 }, maxPackets = 2)
        queue.offer(packet("A", ciphertext = ByteArray(1), expiresAtMillis = 5_000_000))
        queue.offer(packet("B", ciphertext = ByteArray(1), expiresAtMillis = 5_000_000))

        val result = queue.offer(packet("C", ciphertext = ByteArray(1), expiresAtMillis = 5_000_000))

        assertEquals(OfferResult.Queued, result)
        assertEquals(2, queue.size)
    }

    @Test
    fun `acknowledgement removes the matching packet`() {
        val queue = RelayQueue(nowMillis = { 0 })
        queue.offer(packet("p1", ciphertext = ByteArray(100), expiresAtMillis = 5_000_000))

        val removed = queue.acknowledge(PacketId("p1"))

        assertTrue(removed)
        assertEquals(0, queue.size)
        assertEquals(0L, queue.byteCount)
    }

    @Test
    fun `clear empties the queue`() {
        val queue = RelayQueue(nowMillis = { 0 })
        queue.offer(packet("p1", expiresAtMillis = 5_000_000))
        queue.offer(packet("p2", expiresAtMillis = 5_000_000))

        queue.clear()

        assertEquals(0, queue.size)
        assertEquals(0L, queue.byteCount)
    }

    @Test
    fun `drain returns deliverable packets for a destination and removes them`() {
        val queue = RelayQueue(nowMillis = { 0 })
        queue.offer(packet("forC", destinationTag = byteArrayOf(3), expiresAtMillis = 5_000_000))

        val drained = queue.drainDeliverable(NodeId("C"), deliverableTags = setOf(3.toByte()))

        assertEquals(1, drained.size)
        assertEquals(PacketId("forC"), drained.single().packetId)
        assertEquals(0, queue.size)
    }

    @Test
    fun `drain requires a non-empty deliverable tag set`() {
        val queue = RelayQueue(nowMillis = { 0 })
        queue.offer(packet("p1", destinationTag = byteArrayOf(3), expiresAtMillis = 5_000_000))

        try {
            queue.drainDeliverable(NodeId("C"), deliverableTags = emptySet())
            throw AssertionError("expected empty deliverableTags to be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected: an empty set would deliver nothing, a caller error.
        }
        // The packet is untouched by the rejected drain.
        assertEquals(1, queue.size)
    }

    @Test
    fun `drain only returns packets whose tag is requested`() {
        val queue = RelayQueue(nowMillis = { 0 })
        queue.offer(packet("forThree", destinationTag = byteArrayOf(3), expiresAtMillis = 5_000_000))
        queue.offer(packet("forFive", destinationTag = byteArrayOf(5), expiresAtMillis = 5_000_000))

        val drained = queue.drainDeliverable(NodeId("C"), deliverableTags = setOf(3.toByte()))

        assertEquals(listOf(PacketId("forThree")), drained.map { it.packetId })
        assertTrue(queue.contains(PacketId("forFive")))
    }

    @Test
    fun `concurrent offers keep byte count consistent with size`() {
        val queue = RelayQueue(nowMillis = { 0 }, maxBytes = 10_000_000, maxPackets = 10_000)
        val threads = (0 until 8).map { t ->
            Thread {
                for (i in 0 until 500) {
                    queue.offer(
                        packet("t$t-$i", ciphertext = ByteArray(100), expiresAtMillis = 5_000_000),
                    )
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Every accepted packet is 100 bytes, so bytes must equal size * 100 with
        // no lost-update corruption of the running total.
        assertEquals(queue.size.toLong() * 100, queue.byteCount)
    }

    @Test
    fun `production defaults bound age bytes and count`() {
        val queue = RelayQueue(nowMillis = { 0 })

        assertEquals(900_000L, queue.maxAgeMillis)
        assertEquals(5L * 1024 * 1024, queue.maxBytes)
        assertEquals(1024, queue.maxPackets)
    }

    private fun packet(
        packetId: String,
        ciphertext: ByteArray = byteArrayOf(1, 2, 3),
        destinationTag: ByteArray = byteArrayOf(9),
        expiresAtMillis: Long,
    ) = MeshPacket.create(
        packetId = PacketId(packetId),
        kind = PacketKind.TEXT,
        destinationTag = destinationTag,
        expiresAtMillis = expiresAtMillis,
        hopsRemaining = 8,
        ciphertext = ciphertext,
        originSignature = byteArrayOf(7),
    )
}

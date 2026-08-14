package com.meshchats.protocol.routing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingModelsTest {

    @Test
    fun `route exposes ordered transport sequence, hop count and total latency`() {
        val route = MeshRoute(
            destination = NodeId("C"),
            hops = listOf(
                RouteHop(NodeId("A"), NodeId("B"), TransportId.BT, latencyMs = 40, linkQuality = 70),
                RouteHop(NodeId("B"), NodeId("C"), TransportId.WIFI, latencyMs = 15, linkQuality = 90),
            ),
            expiresAtMillis = 10_000,
        )

        assertEquals(listOf(TransportId.BT, TransportId.WIFI), route.transports)
        assertEquals(2, route.hopCount)
        assertEquals(55, route.totalLatencyMs)
        assertEquals(NodeId("A"), route.origin)
        assertEquals(NodeId("C"), route.destination)
    }

    @Test
    fun `route reports expiry against a clock`() {
        val route = MeshRoute(
            destination = NodeId("C"),
            hops = listOf(RouteHop(NodeId("A"), NodeId("C"), TransportId.WIFI, 10, 90)),
            expiresAtMillis = 1_000,
        )

        assertFalse(route.isExpired(999))
        assertTrue(route.isExpired(1_000))
        assertTrue(route.isExpired(1_001))
    }

    @Test
    fun `packet defensively copies ciphertext on construction`() {
        val ciphertext = byteArrayOf(1, 2, 3)
        val packet = MeshPacket.create(
            packetId = PacketId("p1"),
            kind = PacketKind.TEXT,
            destinationTag = byteArrayOf(9),
            expiresAtMillis = 5_000,
            hopsRemaining = 8,
            ciphertext = ciphertext,
            originSignature = byteArrayOf(7),
        )

        ciphertext[0] = 99

        assertArrayEquals(byteArrayOf(1, 2, 3), packet.ciphertext)
    }

    @Test
    fun `packet defensively copies ciphertext on access so relays cannot mutate the original`() {
        val packet = MeshPacket.create(
            packetId = PacketId("p1"),
            kind = PacketKind.TEXT,
            destinationTag = byteArrayOf(9),
            expiresAtMillis = 5_000,
            hopsRemaining = 8,
            ciphertext = byteArrayOf(1, 2, 3),
            originSignature = byteArrayOf(7),
        )

        val firstView = packet.ciphertext
        val secondView = packet.ciphertext
        firstView[0] = 42

        assertNotSame(firstView, secondView)
        assertArrayEquals(byteArrayOf(1, 2, 3), secondView)
        assertArrayEquals(byteArrayOf(1, 2, 3), packet.ciphertext)
    }

    @Test
    fun `packet defensively copies destination tag and signature`() {
        val tag = byteArrayOf(4, 5)
        val sig = byteArrayOf(6, 7)
        val packet = MeshPacket.create(
            packetId = PacketId("p1"),
            kind = PacketKind.CONTROL,
            destinationTag = tag,
            expiresAtMillis = 5_000,
            hopsRemaining = 8,
            ciphertext = byteArrayOf(1),
            originSignature = sig,
        )

        tag[0] = 0
        sig[0] = 0
        packet.destinationTag[1] = 0
        packet.originSignature[1] = 0

        assertArrayEquals(byteArrayOf(4, 5), packet.destinationTag)
        assertArrayEquals(byteArrayOf(6, 7), packet.originSignature)
    }

    @Test
    fun `packet reports expiry`() {
        val packet = packet(expiresAtMillis = 1_000)

        assertFalse(packet.isExpired(999))
        assertTrue(packet.isExpired(1_000))
    }

    @Test
    fun `packet rejects ciphertext larger than the configured maximum`() {
        val oversize = ByteArray(MeshPacket.MAX_CIPHERTEXT_BYTES + 1)

        try {
            MeshPacket.create(
                packetId = PacketId("p1"),
                kind = PacketKind.BULK,
                destinationTag = byteArrayOf(9),
                expiresAtMillis = 5_000,
                hopsRemaining = 8,
                ciphertext = oversize,
                originSignature = byteArrayOf(7),
            )
            throw AssertionError("expected oversized ciphertext to be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `packet accepts a custom byte ceiling`() {
        try {
            MeshPacket.create(
                packetId = PacketId("p1"),
                kind = PacketKind.BULK,
                destinationTag = byteArrayOf(9),
                expiresAtMillis = 5_000,
                hopsRemaining = 8,
                ciphertext = ByteArray(9),
                originSignature = byteArrayOf(7),
                maxCiphertextBytes = 8,
            )
            throw AssertionError("expected oversized ciphertext to be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `withHopsRemaining keeps ciphertext, tag and signature byte-identical`() {
        val packet = packet(hopsRemaining = 8)

        val relayed = packet.withHopsRemaining(7)

        assertEquals(7, relayed.hopsRemaining)
        assertEquals(packet.packetId, relayed.packetId)
        assertArrayEquals(packet.ciphertext, relayed.ciphertext)
        assertArrayEquals(packet.destinationTag, relayed.destinationTag)
        assertArrayEquals(packet.originSignature, relayed.originSignature)
    }

    @Test
    fun `routing profile favors wifi bandwidth for bulk and reliability for text`() {
        val bulk = RoutingProfile.forKind(PacketKind.BULK)
        val text = RoutingProfile.forKind(PacketKind.TEXT)

        assertTrue(bulk.bandwidthWeight > text.bandwidthWeight)
        assertTrue(text.reliabilityWeight >= bulk.reliabilityWeight)
        assertEquals(3, text.maxRoutes)
    }

    @Test
    fun `sos routing profile requests multiple disjoint routes`() {
        val sos = RoutingProfile.forKind(PacketKind.SOS)

        assertTrue(sos.maxRoutes >= 3)
        assertTrue(sos.preferDisjoint)
    }

    @Test
    fun `link edge rejects out-of-range latency quality and energy`() {
        // latency below 0
        assertThrowsIae { LinkEdge(NodeId("A"), NodeId("B"), TransportId.BT, -1, 50, 10, 1) }
        // latency above the 60s ceiling
        assertThrowsIae { LinkEdge(NodeId("A"), NodeId("B"), TransportId.BT, 60_001, 50, 10, 1) }
        // quality above 100
        assertThrowsIae { LinkEdge(NodeId("A"), NodeId("B"), TransportId.BT, 40, 101, 10, 1) }
        // quality below 0
        assertThrowsIae { LinkEdge(NodeId("A"), NodeId("B"), TransportId.BT, 40, -1, 10, 1) }
        // energy above 100
        assertThrowsIae { LinkEdge(NodeId("A"), NodeId("B"), TransportId.BT, 40, 50, 101, 1) }
        // energy below 0
        assertThrowsIae { LinkEdge(NodeId("A"), NodeId("B"), TransportId.BT, 40, 50, -1, 1) }
    }

    @Test
    fun `link edge accepts boundary values`() {
        // Min and max boundaries are valid.
        LinkEdge(NodeId("A"), NodeId("B"), TransportId.BT, 0, 0, 0, 0)
        LinkEdge(NodeId("A"), NodeId("B"), TransportId.BT, 60_000, 100, 100, 5)
    }

    @Test
    fun `route fingerprint distinguishes routes by nodes and transports`() {
        val viaB = MeshRoute(
            destination = NodeId("C"),
            hops = listOf(
                RouteHop(NodeId("A"), NodeId("B"), TransportId.BT, 40, 70),
                RouteHop(NodeId("B"), NodeId("C"), TransportId.WIFI, 15, 90),
            ),
            expiresAtMillis = 10_000,
        )
        val viaBSame = MeshRoute(
            destination = NodeId("C"),
            hops = listOf(
                RouteHop(NodeId("A"), NodeId("B"), TransportId.BT, 99, 10),
                RouteHop(NodeId("B"), NodeId("C"), TransportId.WIFI, 99, 10),
            ),
            expiresAtMillis = 99_999,
        )
        val viaD = MeshRoute(
            destination = NodeId("C"),
            hops = listOf(
                RouteHop(NodeId("A"), NodeId("D"), TransportId.WIFI, 15, 90),
                RouteHop(NodeId("D"), NodeId("C"), TransportId.WIFI, 15, 90),
            ),
            expiresAtMillis = 10_000,
        )
        // Same nodes+transports (differing only on latency/quality/expiry) share
        // a fingerprint; a different path yields a different one.
        assertEquals(viaB.fingerprint, viaBSame.fingerprint)
        assertNotEquals(viaB.fingerprint, viaD.fingerprint)
    }

    private fun assertThrowsIae(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    private fun packet(
        expiresAtMillis: Long = 5_000,
        hopsRemaining: Int = 8,
    ) = MeshPacket.create(
        packetId = PacketId("p1"),
        kind = PacketKind.TEXT,
        destinationTag = byteArrayOf(9),
        expiresAtMillis = expiresAtMillis,
        hopsRemaining = hopsRemaining,
        ciphertext = byteArrayOf(1, 2, 3),
        originSignature = byteArrayOf(7),
    )
}

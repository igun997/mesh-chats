package com.meshchats.app.core.routing

import com.meshchats.app.core.mesh.TransportId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketForwarderTest {

    private val route = MeshRoute(
        destination = NodeId("C"),
        hops = listOf(
            RouteHop(NodeId("A"), NodeId("B"), TransportId.BT, 40, 70),
            RouteHop(NodeId("B"), NodeId("C"), TransportId.WIFI, 15, 90),
        ),
        expiresAtMillis = 10_000,
    )

    @Test
    fun `forwards exact ciphertext across BLE then WIFI`() {
        val sender = RecordingLinkSender()
        val forwarder = PacketForwarder(sender = sender, nowMillis = { 0 })
        val packet = packet(ciphertext = byteArrayOf(10, 20, 30), hopsRemaining = 8)

        val result = forwarder.forward(packet, route)

        assertEquals(ForwardResult.Sent, result)
        assertEquals(listOf(TransportId.BT, TransportId.WIFI), sender.sends.map { it.transport })
        assertEquals(listOf(NodeId("B"), NodeId("C")), sender.sends.map { it.nextHop })
        sender.sends.forEach { assertArrayEquals(byteArrayOf(10, 20, 30), it.packet.ciphertext) }
    }

    @Test
    fun `signature and destination tag stay byte-identical across hops`() {
        val sender = RecordingLinkSender()
        val forwarder = PacketForwarder(sender = sender, nowMillis = { 0 })
        val packet = packet(
            ciphertext = byteArrayOf(1),
            destinationTag = byteArrayOf(5, 6),
            originSignature = byteArrayOf(7, 8),
            hopsRemaining = 8,
        )

        forwarder.forward(packet, route)

        sender.sends.forEach {
            assertArrayEquals(byteArrayOf(5, 6), it.packet.destinationTag)
            assertArrayEquals(byteArrayOf(7, 8), it.packet.originSignature)
        }
    }

    @Test
    fun `hop budget decrements once per relay`() {
        val sender = RecordingLinkSender()
        val forwarder = PacketForwarder(sender = sender, nowMillis = { 0 })
        val packet = packet(hopsRemaining = 8)

        forwarder.forward(packet, route)

        // Two hops consume two units, in order.
        assertEquals(listOf(7, 6), sender.sends.map { it.packet.hopsRemaining })
    }

    @Test
    fun `drops a duplicate packet within the window`() {
        val sender = RecordingLinkSender()
        val forwarder = PacketForwarder(sender = sender, nowMillis = { 0 })
        val packet = packet(hopsRemaining = 8)

        assertEquals(ForwardResult.Sent, forwarder.forward(packet, route))
        val second = forwarder.forward(packet, route)

        assertEquals(ForwardResult.Rejected(DropReason.DUPLICATE), second)
        assertEquals(2, sender.sends.size) // no additional sends
    }

    @Test
    fun `re-accepts a packet after the duplicate window expires`() {
        val sender = RecordingLinkSender()
        var now = 0L
        val forwarder = PacketForwarder(
            sender = sender,
            nowMillis = { now },
            duplicateWindowMillis = 600_000,
        )
        val packet = packet(hopsRemaining = 8, expiresAtMillis = 5_000_000)

        assertEquals(ForwardResult.Sent, forwarder.forward(packet, route))
        now = 600_001
        assertEquals(ForwardResult.Sent, forwarder.forward(packet, route))
    }

    @Test
    fun `drops an expired packet`() {
        val sender = RecordingLinkSender()
        val forwarder = PacketForwarder(sender = sender, nowMillis = { 10_000 })
        val packet = packet(hopsRemaining = 8, expiresAtMillis = 5_000)

        val result = forwarder.forward(packet, route)

        assertEquals(ForwardResult.Rejected(DropReason.EXPIRED), result)
        assertTrue(sender.sends.isEmpty())
    }

    @Test
    fun `drops a packet with an exhausted hop budget`() {
        val sender = RecordingLinkSender()
        val forwarder = PacketForwarder(sender = sender, nowMillis = { 0 })
        val packet = packet(hopsRemaining = 0)

        val result = forwarder.forward(packet, route)

        assertEquals(ForwardResult.Rejected(DropReason.HOP_BUDGET_EXHAUSTED), result)
        assertTrue(sender.sends.isEmpty())
    }

    @Test
    fun `drops a packet whose route needs more hops than remaining budget`() {
        val sender = RecordingLinkSender()
        val forwarder = PacketForwarder(sender = sender, nowMillis = { 0 })
        val packet = packet(hopsRemaining = 1) // route needs 2

        val result = forwarder.forward(packet, route)

        assertEquals(ForwardResult.Rejected(DropReason.HOP_BUDGET_EXHAUSTED), result)
        assertTrue(sender.sends.isEmpty())
    }

    @Test
    fun `rejects when a hop send fails`() {
        val sender = RecordingLinkSender(failOnHopIndex = 1)
        val forwarder = PacketForwarder(sender = sender, nowMillis = { 0 })
        val packet = packet(hopsRemaining = 8)

        val result = forwarder.forward(packet, route)

        assertEquals(ForwardResult.Rejected(DropReason.LINK_FAILURE), result)
        assertEquals(1, sender.sends.size) // first hop sent, second failed
    }

    @Test
    fun `duplicate filter capacity is bounded`() {
        val sender = RecordingLinkSender()
        val forwarder = PacketForwarder(
            sender = sender,
            nowMillis = { 0 },
            maxTrackedPackets = 2,
        )
        // Fill beyond capacity; oldest ids get evicted so their duplicates
        // are no longer suppressed, but capacity never grows unbounded.
        forwarder.forward(packet(packetId = "p1", hopsRemaining = 8), route)
        forwarder.forward(packet(packetId = "p2", hopsRemaining = 8), route)
        forwarder.forward(packet(packetId = "p3", hopsRemaining = 8), route)

        // p1 was evicted, so it is accepted again (not treated as duplicate).
        val reAccepted = forwarder.forward(packet(packetId = "p1", hopsRemaining = 8), route)
        assertEquals(ForwardResult.Sent, reAccepted)
    }

    @Test
    fun `drops when route origin does not match packet path start`() {
        val sender = RecordingLinkSender()
        val forwarder = PacketForwarder(sender = sender, nowMillis = { 0 })
        val mismatched = MeshRoute(
            destination = NodeId("C"),
            hops = listOf(RouteHop(NodeId("X"), NodeId("C"), TransportId.WIFI, 15, 90)),
            expiresAtMillis = 10_000,
        )

        val result = forwarder.forward(packet(hopsRemaining = 8), mismatched, expectedOrigin = NodeId("A"))

        assertEquals(ForwardResult.Rejected(DropReason.ROUTE_MISMATCH), result)
        assertTrue(sender.sends.isEmpty())
    }

    @Test
    fun `drops when route destination does not match expected destination`() {
        val sender = RecordingLinkSender()
        val forwarder = PacketForwarder(sender = sender, nowMillis = { 0 })

        val result = forwarder.forward(
            packet(hopsRemaining = 8),
            route,
            expectedDestination = NodeId("Z"),
        )

        assertEquals(ForwardResult.Rejected(DropReason.ROUTE_MISMATCH), result)
        assertTrue(sender.sends.isEmpty())
    }

    @Test
    fun `SOS packet forwards across two disjoint routes then rejects a route replay`() {
        val sender = RecordingLinkSender()
        val forwarder = PacketForwarder(sender = sender, nowMillis = { 0 })

        val routeViaB = MeshRoute(
            destination = NodeId("C"),
            hops = listOf(
                RouteHop(NodeId("A"), NodeId("B"), TransportId.BT, 40, 70),
                RouteHop(NodeId("B"), NodeId("C"), TransportId.WIFI, 15, 90),
            ),
            expiresAtMillis = 10_000,
        )
        val routeViaD = MeshRoute(
            destination = NodeId("C"),
            hops = listOf(
                RouteHop(NodeId("A"), NodeId("D"), TransportId.WIFI, 15, 90),
                RouteHop(NodeId("D"), NodeId("C"), TransportId.WIFI, 15, 90),
            ),
            expiresAtMillis = 10_000,
        )
        val sos = packet(packetId = "sos-1", hopsRemaining = 8, kind = PacketKind.SOS)

        // Same SOS packet over two disjoint routes: both delivered.
        assertEquals(ForwardResult.Sent, forwarder.forward(sos, routeViaB))
        assertEquals(ForwardResult.Sent, forwarder.forward(sos, routeViaD))

        // Replaying the first route is rejected as a duplicate of that route.
        assertEquals(
            ForwardResult.Rejected(DropReason.DUPLICATE),
            forwarder.forward(sos, routeViaB),
        )
        // Four sends total: two hops per accepted route, none for the replay.
        assertEquals(4, sender.sends.size)
    }

    @Test
    fun `SOS fan-out is capped at three routes`() {
        val sender = RecordingLinkSender()
        val forwarder = PacketForwarder(
            sender = sender,
            nowMillis = { 0 },
            maxSosRoutesPerPacket = 3,
        )
        val sos = packet(packetId = "sos-2", hopsRemaining = 8, kind = PacketKind.SOS)

        val relays = listOf("B", "D", "E", "F")
        val results = relays.map { relay ->
            val route = MeshRoute(
                destination = NodeId("C"),
                hops = listOf(
                    RouteHop(NodeId("A"), NodeId(relay), TransportId.BT, 40, 70),
                    RouteHop(NodeId(relay), NodeId("C"), TransportId.WIFI, 15, 90),
                ),
                expiresAtMillis = 10_000,
            )
            forwarder.forward(sos, route)
        }

        // First three distinct routes accepted, the fourth capped as duplicate.
        assertEquals(ForwardResult.Sent, results[0])
        assertEquals(ForwardResult.Sent, results[1])
        assertEquals(ForwardResult.Sent, results[2])
        assertEquals(ForwardResult.Rejected(DropReason.DUPLICATE), results[3])
    }

    @Test
    fun `normal traffic still dedupes by packet id across different routes`() {
        val sender = RecordingLinkSender()
        val forwarder = PacketForwarder(sender = sender, nowMillis = { 0 })

        val routeViaB = MeshRoute(
            destination = NodeId("C"),
            hops = listOf(
                RouteHop(NodeId("A"), NodeId("B"), TransportId.BT, 40, 70),
                RouteHop(NodeId("B"), NodeId("C"), TransportId.WIFI, 15, 90),
            ),
            expiresAtMillis = 10_000,
        )
        val routeViaD = MeshRoute(
            destination = NodeId("C"),
            hops = listOf(
                RouteHop(NodeId("A"), NodeId("D"), TransportId.WIFI, 15, 90),
                RouteHop(NodeId("D"), NodeId("C"), TransportId.WIFI, 15, 90),
            ),
            expiresAtMillis = 10_000,
        )
        val text = packet(packetId = "text-1", hopsRemaining = 8, kind = PacketKind.TEXT)

        // TEXT dedupes by id: the second route is a duplicate even though the
        // path differs.
        assertEquals(ForwardResult.Sent, forwarder.forward(text, routeViaB))
        assertEquals(
            ForwardResult.Rejected(DropReason.DUPLICATE),
            forwarder.forward(text, routeViaD),
        )
    }

    @Test
    fun `SOS duplicate table stays globally bounded`() {
        val sender = RecordingLinkSender()
        val forwarder = PacketForwarder(
            sender = sender,
            nowMillis = { 0 },
            maxTrackedPackets = 2,
            maxSosRoutesPerPacket = 5,
        )
        // Three distinct SOS route keys pushes the oldest out of the bounded table.
        val relays = listOf("B", "D", "E")
        relays.forEach { relay ->
            val route = MeshRoute(
                destination = NodeId("C"),
                hops = listOf(
                    RouteHop(NodeId("A"), NodeId(relay), TransportId.BT, 40, 70),
                    RouteHop(NodeId(relay), NodeId("C"), TransportId.WIFI, 15, 90),
                ),
                expiresAtMillis = 10_000,
            )
            forwarder.forward(packet(packetId = "sos-3", hopsRemaining = 8, kind = PacketKind.SOS), route)
        }

        // The first route key ("via B") was evicted, so replaying it is accepted
        // again rather than suppressed: capacity never grew unbounded.
        val reAccepted = forwarder.forward(
            packet(packetId = "sos-3", hopsRemaining = 8, kind = PacketKind.SOS),
            MeshRoute(
                destination = NodeId("C"),
                hops = listOf(
                    RouteHop(NodeId("A"), NodeId("B"), TransportId.BT, 40, 70),
                    RouteHop(NodeId("B"), NodeId("C"), TransportId.WIFI, 15, 90),
                ),
                expiresAtMillis = 10_000,
            ),
        )
        assertEquals(ForwardResult.Sent, reAccepted)
    }

    @Test
    fun `link failure releases the reservation so a retry can succeed`() {
        // Fail the very first hop, then recover so a retry can complete.
        val sender = FailFirstAttemptSender()
        val forwarder = PacketForwarder(sender = sender, nowMillis = { 0 })
        val text = packet(packetId = "retry-1", hopsRemaining = 8)

        val first = forwarder.forward(text, route)
        assertEquals(ForwardResult.Rejected(DropReason.LINK_FAILURE), first)

        // Reservation was released on failure, so the same forwarder permits a
        // retry of the same packet rather than treating it as a duplicate.
        val second = forwarder.forward(text, route)
        assertEquals(ForwardResult.Sent, second)
    }

    private fun packet(
        packetId: String = "p1",
        ciphertext: ByteArray = byteArrayOf(1, 2, 3),
        destinationTag: ByteArray = byteArrayOf(9),
        originSignature: ByteArray = byteArrayOf(7),
        expiresAtMillis: Long = 5_000,
        hopsRemaining: Int,
        kind: PacketKind = PacketKind.TEXT,
    ) = MeshPacket.create(
        packetId = PacketId(packetId),
        kind = kind,
        destinationTag = destinationTag,
        expiresAtMillis = expiresAtMillis,
        hopsRemaining = hopsRemaining,
        ciphertext = ciphertext,
        originSignature = originSignature,
    )

    private class FailFirstAttemptSender : LinkSender {
        private var attempts = 0
        override fun send(nextHop: NodeId, transport: TransportId, packet: MeshPacket): Boolean {
            attempts++
            // Fail only the first send of the whole forwarding session.
            return attempts != 1
        }
    }

    private class RecordingLinkSender(
        private val failOnHopIndex: Int? = null,
    ) : LinkSender {
        data class Send(val nextHop: NodeId, val transport: TransportId, val packet: MeshPacket)

        val sends = mutableListOf<Send>()

        override fun send(nextHop: NodeId, transport: TransportId, packet: MeshPacket): Boolean {
            if (failOnHopIndex != null && sends.size == failOnHopIndex) return false
            sends += Send(nextHop, transport, packet)
            return true
        }
    }
}

package com.meshchats.app.core.routing

import com.meshchats.app.core.mesh.TransportId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end tracer for the hybrid multi-transport mesh in pure JVM.
 *
 * Topology: A ──BLE── B ──Wi-Fi── C. B is an unverified relay; only C is a
 * verified destination. Proves discovery, byte-identical forwarding across
 * transports, store-and-forward across a transient Wi-Fi loss, and SOS disjoint
 * routing.
 */
class HybridRoutingTracerTest {

    private val A = NodeId("A")
    private val B = NodeId("B")
    private val C = NodeId("C")

    private val bleAB = edge("A", "B", TransportId.BT)
    private val bleBA = edge("B", "A", TransportId.BT)
    private val wifiBC = edge("B", "C", TransportId.WIFI)
    private val wifiCB = edge("C", "B", TransportId.WIFI)

    @Test
    fun `A discovers C only when C is verified, B stays an unverified relay`() {
        val edges = listOf(bleAB, bleBA, wifiBC, wifiCB)

        // C not yet verified: no route even though the path physically exists.
        val unverified = ReactiveRouteDiscovery(
            graph = MeshGraph.of(edges, verifiedDestinations = emptySet()),
            nowMillis = { 0 },
        )
        assertNull(unverified.discover(A, C, PacketKind.TEXT).firstOrNull())

        // C verified, B still unverified: route appears and B relays it.
        val verified = ReactiveRouteDiscovery(
            graph = MeshGraph.of(edges, verifiedDestinations = setOf(C)),
            nowMillis = { 0 },
        )
        val route = verified.discover(A, C, PacketKind.TEXT).firstOrNull()
        assertNotNull(route)
        assertEquals(listOf(B), route!!.relayNodes)
    }

    @Test
    fun `packet traverses BLE then Wi-Fi with byte-identical ciphertext`() {
        val discovery = ReactiveRouteDiscovery(
            graph = MeshGraph.of(listOf(bleAB, bleBA, wifiBC, wifiCB), verifiedDestinations = setOf(C)),
            nowMillis = { 0 },
        )
        val sender = RecordingSender()
        val forwarder = PacketForwarder(sender = sender, nowMillis = { 0 })
        val ciphertext = byteArrayOf(11, 22, 33, 44)
        val packet = MeshPacket.create(
            packetId = PacketId("msg-1"),
            kind = PacketKind.TEXT,
            destinationTag = byteArrayOf(3),
            expiresAtMillis = 60_000,
            hopsRemaining = 8,
            ciphertext = ciphertext,
            originSignature = byteArrayOf(90, 91),
        )

        val route = discovery.discover(A, C, PacketKind.TEXT).first()
        val result = forwarder.forward(packet, route, expectedOrigin = A, expectedDestination = C)

        assertEquals(ForwardResult.Sent, result)
        assertEquals(listOf(TransportId.BT, TransportId.WIFI), sender.sends.map { it.transport })
        sender.sends.forEach {
            assertArrayEquals(ciphertext, it.packet.ciphertext)
            assertArrayEquals(byteArrayOf(90, 91), it.packet.originSignature)
            assertArrayEquals(byteArrayOf(3), it.packet.destinationTag)
        }
    }

    @Test
    fun `queues on Wi-Fi loss, delivers and acknowledges on restore`() {
        var wifiUp = true
        var now = 0L
        val liveEdges: () -> List<LinkEdge> = {
            if (wifiUp) listOf(bleAB, bleBA, wifiBC, wifiCB) else listOf(bleAB, bleBA)
        }
        val discovery = ReactiveRouteDiscovery(
            graph = MeshGraph.live(liveEdges, { setOf(C) }),
            nowMillis = { now },
        )
        val queue = RelayQueue(nowMillis = { now })
        val sender = RecordingSender()
        val forwarder = PacketForwarder(sender = sender, nowMillis = { now })

        val packet = MeshPacket.create(
            packetId = PacketId("msg-2"),
            kind = PacketKind.TEXT,
            destinationTag = byteArrayOf(3),
            expiresAtMillis = 5_000_000,
            hopsRemaining = 8,
            ciphertext = byteArrayOf(7, 7, 7),
            originSignature = byteArrayOf(1),
        )

        // Wi-Fi bridge is down: no route to C, so B stores the ciphertext.
        wifiUp = false
        assertNull(discovery.discover(A, C, PacketKind.TEXT).firstOrNull())
        assertEquals(OfferResult.Queued, queue.offer(packet))
        assertEquals(1, queue.size)
        assertTrue(sender.sends.isEmpty())

        // Wi-Fi returns: rediscover, drain the queue, forward, acknowledge.
        wifiUp = true
        now = 1_000
        val route = discovery.discover(A, C, PacketKind.TEXT).first()
        val drained = queue.drainDeliverable(C, deliverableTags = setOf(3))
        assertEquals(1, drained.size)

        val forwarded = forwarder.forward(drained.single(), route, expectedDestination = C)
        assertEquals(ForwardResult.Sent, forwarded)
        assertArrayEquals(byteArrayOf(7, 7, 7), sender.sends.last().packet.ciphertext)

        // Delivery ack empties the queue.
        assertTrue(queue.acknowledge(PacketId("msg-2")) || queue.size == 0)
        assertEquals(0, queue.size)
        assertEquals(0L, queue.byteCount)
    }

    @Test
    fun `SOS selects independent routes when the topology allows`() {
        // Two disjoint A->C paths: A-B(BLE)-C(WIFI) and A-D(WIFI)-C(WIFI).
        val edges = listOf(
            bleAB, bleBA, wifiBC, wifiCB,
            edge("A", "D", TransportId.WIFI), edge("D", "A", TransportId.WIFI),
            edge("D", "C", TransportId.WIFI), edge("C", "D", TransportId.WIFI),
        )
        val discovery = ReactiveRouteDiscovery(
            graph = MeshGraph.of(edges, verifiedDestinations = setOf(C)),
            nowMillis = { 0 },
        )

        val routes = discovery.discover(A, C, PacketKind.SOS)

        assertTrue("expected at least 2 SOS routes", routes.size >= 2)
        val allRelays = routes.flatMap { it.relayNodes }
        assertEquals("SOS routes must be relay-disjoint", allRelays.size, allRelays.toSet().size)
    }

    private fun edge(from: String, to: String, transport: TransportId): LinkEdge {
        val quality: Int
        val latency: Int
        val energy: Int
        val bandwidth: Int
        when (transport) {
            TransportId.BT -> { quality = 70; latency = 40; energy = 1; bandwidth = 1 }
            TransportId.WIFI -> { quality = 90; latency = 15; energy = 4; bandwidth = 5 }
            else -> { quality = 50; latency = 100; energy = 3; bandwidth = 2 }
        }
        return LinkEdge(NodeId(from), NodeId(to), transport, latency, quality, energy, bandwidth)
    }

    private class RecordingSender : LinkSender {
        data class Send(val nextHop: NodeId, val transport: TransportId, val packet: MeshPacket)

        val sends = mutableListOf<Send>()

        override fun send(nextHop: NodeId, transport: TransportId, packet: MeshPacket): Boolean {
            sends += Send(nextHop, transport, packet)
            return true
        }
    }
}

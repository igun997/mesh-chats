package com.meshchats.protocol.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactiveRouteDiscoveryTest {

    @Test
    fun `discovers a mixed BLE then WIFI route from A to C`() {
        val graph = MeshGraph.of(
            edges = flat(
                bidirectional("A", "B", TransportId.BT),
                bidirectional("B", "C", TransportId.WIFI),
            ),
            verifiedDestinations = setOf(NodeId("C")),
        )
        val discovery = ReactiveRouteDiscovery(graph = graph, nowMillis = { 0 })

        val route = discovery.discover(NodeId("A"), NodeId("C"), PacketKind.TEXT).firstOrNull()

        assertNotNull(route)
        assertEquals(listOf(TransportId.BT, TransportId.WIFI), route!!.transports)
        assertEquals(listOf(NodeId("B")), route.relayNodes)
        assertEquals(NodeId("C"), route.destination)
    }

    @Test
    fun `refuses to route to an unverified destination`() {
        val graph = MeshGraph.of(
            edges = flat(bidirectional("A", "B", TransportId.BT)),
            verifiedDestinations = emptySet(),
        )
        val discovery = ReactiveRouteDiscovery(graph = graph, nowMillis = { 0 })

        val routes = discovery.discover(NodeId("A"), NodeId("B"), PacketKind.TEXT)

        assertTrue(routes.isEmpty())
    }

    @Test
    fun `routes through an unverified intermediate relay`() {
        // B is not a verified destination, but may relay to verified C.
        val graph = MeshGraph.of(
            edges = flat(
                bidirectional("A", "B", TransportId.BT),
                bidirectional("B", "C", TransportId.WIFI),
            ),
            verifiedDestinations = setOf(NodeId("C")),
        )
        val discovery = ReactiveRouteDiscovery(graph = graph, nowMillis = { 0 })

        val route = discovery.discover(NodeId("A"), NodeId("C"), PacketKind.TEXT).firstOrNull()

        assertNotNull(route)
        assertEquals(NodeId("B"), route!!.relayNodes.single())
    }

    @Test
    fun `suppresses cycles and still finds the destination`() {
        val graph = MeshGraph.of(
            edges = flat(
                bidirectional("A", "B", TransportId.BT),
                bidirectional("B", "A", TransportId.BT),
                bidirectional("A", "C", TransportId.BT),
                bidirectional("B", "C", TransportId.WIFI),
                bidirectional("C", "D", TransportId.WIFI),
            ),
            verifiedDestinations = setOf(NodeId("D")),
        )
        val discovery = ReactiveRouteDiscovery(graph = graph, nowMillis = { 0 })

        val route = discovery.discover(NodeId("A"), NodeId("D"), PacketKind.TEXT).firstOrNull()

        assertNotNull(route)
        // No node appears twice.
        val visited = mutableListOf(route!!.origin)
        route.hops.forEach { visited += it.to }
        assertEquals(visited.size, visited.toSet().size)
    }

    @Test
    fun `enforces the six hop request exploration limit`() {
        // Chain A-B-C-D-E-F-G-H: reaching H is 7 hops, beyond the limit.
        val chain = listOf("A", "B", "C", "D", "E", "F", "G", "H")
        val edges = chain.zipWithNext { a, b -> bidirectional(a, b, TransportId.BT) }.flatten()
        val graph = MeshGraph.of(edges = edges, verifiedDestinations = setOf(NodeId("H")))
        val discovery = ReactiveRouteDiscovery(graph = graph, nowMillis = { 0 })

        val routes = discovery.discover(NodeId("A"), NodeId("H"), PacketKind.TEXT)

        assertTrue("expected no route beyond 6 hops", routes.isEmpty())
    }

    @Test
    fun `finds a route exactly at the six hop limit`() {
        val chain = listOf("A", "B", "C", "D", "E", "F", "G")
        val edges = chain.zipWithNext { a, b -> bidirectional(a, b, TransportId.BT) }.flatten()
        val graph = MeshGraph.of(edges = edges, verifiedDestinations = setOf(NodeId("G")))
        val discovery = ReactiveRouteDiscovery(graph = graph, nowMillis = { 0 })

        val route = discovery.discover(NodeId("A"), NodeId("G"), PacketKind.TEXT).firstOrNull()

        assertNotNull(route)
        assertEquals(6, route!!.hopCount)
    }

    @Test
    fun `reflects live link removal`() {
        val edges = mutableListOf(
            *bidirectional("A", "B", TransportId.BT).toTypedArray(),
            *bidirectional("B", "C", TransportId.WIFI).toTypedArray(),
        )
        var currentEdges: List<LinkEdge> = edges
        val graph = MeshGraph.live(
            edgeProvider = { currentEdges },
            verifiedProvider = { setOf(NodeId("C")) },
        )
        val discovery = ReactiveRouteDiscovery(graph = graph, nowMillis = { 0 })

        assertNotNull(discovery.discover(NodeId("A"), NodeId("C"), PacketKind.TEXT).firstOrNull())

        // Wi-Fi bridge disappears.
        currentEdges = bidirectional("A", "B", TransportId.BT)

        assertNull(discovery.discover(NodeId("A"), NodeId("C"), PacketKind.TEXT).firstOrNull())
    }

    @Test
    fun `bulk prefers a wifi path over a longer ble path`() {
        val graph = MeshGraph.of(
            edges = flat(
                // Direct-ish BLE path A-X-C
                bidirectional("A", "X", TransportId.BT),
                bidirectional("X", "C", TransportId.BT),
                // Wi-Fi path A-Y-C
                bidirectional("A", "Y", TransportId.WIFI),
                bidirectional("Y", "C", TransportId.WIFI),
            ),
            verifiedDestinations = setOf(NodeId("C")),
        )
        val discovery = ReactiveRouteDiscovery(graph = graph, nowMillis = { 0 })

        val bulk = discovery.discover(NodeId("A"), NodeId("C"), PacketKind.BULK).first()

        assertTrue(bulk.transports.all { it == TransportId.WIFI })
    }

    @Test
    fun `route ordering is deterministic across calls`() {
        val graph = MeshGraph.of(
            edges = flat(
                bidirectional("A", "B", TransportId.BT),
                bidirectional("A", "C", TransportId.WIFI),
                bidirectional("B", "D", TransportId.WIFI),
                bidirectional("C", "D", TransportId.WIFI),
            ),
            verifiedDestinations = setOf(NodeId("D")),
        )
        val discovery = ReactiveRouteDiscovery(graph = graph, nowMillis = { 0 })

        val first = discovery.discover(NodeId("A"), NodeId("D"), PacketKind.TEXT)
        val second = discovery.discover(NodeId("A"), NodeId("D"), PacketKind.TEXT)

        assertEquals(first.map { it.transports }, second.map { it.transports })
        assertEquals(first.map { it.relayNodes }, second.map { it.relayNodes })
    }

    @Test
    fun `non-SOS text returns exactly three best routes ordered best-first`() {
        // Four valid A->D paths through disjoint relays B, C, E, F.
        val graph = MeshGraph.of(
            edges = flat(
                bidirectional("A", "B", TransportId.WIFI),
                bidirectional("B", "D", TransportId.WIFI),
                bidirectional("A", "C", TransportId.WIFI),
                bidirectional("C", "D", TransportId.BT),
                bidirectional("A", "E", TransportId.BT),
                bidirectional("E", "D", TransportId.WIFI),
                bidirectional("A", "F", TransportId.BT),
                bidirectional("F", "D", TransportId.BT),
            ),
            verifiedDestinations = setOf(NodeId("D")),
        )
        val discovery = ReactiveRouteDiscovery(graph = graph, nowMillis = { 0 })

        val routes = discovery.discover(NodeId("A"), NodeId("D"), PacketKind.TEXT)

        assertEquals(3, routes.size)
        // Ordered best-first: for TEXT the energy weight makes the all-BLE path
        // (relay F) the cheapest, so it leads. Costs are non-decreasing.
        assertEquals(listOf(TransportId.BT, TransportId.BT), routes.first().transports)
        assertEquals(NodeId("F"), routes.first().relayNodes.single())
        // The three returned are the three cheapest; the all-Wi-Fi path (relay B,
        // the most energy-hungry) is dropped.
        val relays = routes.map { it.relayNodes.single() }
        assertEquals(relays.size, relays.toSet().size)
        assertTrue("expected the costliest all-Wi-Fi path to be dropped", NodeId("B") !in relays)
    }

    @Test
    fun `sos returns multiple node-disjoint routes when available`() {
        val graph = MeshGraph.of(
            edges = flat(
                // Three independent A->C paths through P, Q, R.
                bidirectional("A", "P", TransportId.BT),
                bidirectional("P", "C", TransportId.WIFI),
                bidirectional("A", "Q", TransportId.WIFI),
                bidirectional("Q", "C", TransportId.WIFI),
                bidirectional("A", "R", TransportId.BT),
                bidirectional("R", "C", TransportId.BT),
            ),
            verifiedDestinations = setOf(NodeId("C")),
        )
        val discovery = ReactiveRouteDiscovery(graph = graph, nowMillis = { 0 })

        val routes = discovery.discover(NodeId("A"), NodeId("C"), PacketKind.SOS)

        assertTrue("expected at least 2 SOS routes", routes.size >= 2)
        // Relay nodes must not overlap between chosen routes.
        val allRelays = routes.flatMap { it.relayNodes }
        assertEquals(allRelays.size, allRelays.toSet().size)
    }

    @Test
    fun `frontier never exceeds the configured cap on a dense graph`() {
        // A short, cheap all-Wi-Fi path A->G->D coexists with a dense decoy fan:
        // hub H reaches 200 relays that each also reach D, plus lateral BT
        // cross-links. Left unbounded the decoy would explode the frontier; the
        // cap must hold while the cheap short path still survives (a low-cost
        // candidate is never the worst one dropped).
        val edges = ArrayList<LinkEdge>()
        edges += bidirectional("A", "G", TransportId.WIFI)
        edges += bidirectional("G", "D", TransportId.WIFI)
        edges += bidirectional("A", "H", TransportId.WIFI)
        val relayCount = 200
        for (i in 0 until relayCount) {
            edges += bidirectional("H", "R$i", TransportId.WIFI)
            edges += bidirectional("R$i", "D", TransportId.WIFI)
            if (i > 0) edges += bidirectional("R${i - 1}", "R$i", TransportId.BT)
        }
        val graph = MeshGraph.of(
            edges = edges,
            verifiedDestinations = setOf(NodeId("D")),
            maxNodes = 1024,
            maxEdges = 4096,
        )
        val cap = 64
        var peak = 0
        var drops = 0
        val discovery = ReactiveRouteDiscovery(
            graph = graph,
            nowMillis = { 0 },
            maxFrontierSize = cap,
            statsObserver = {
                peak = maxOf(peak, it.peakFrontierSize)
                drops = it.frontierDrops
            },
        )

        val routes = discovery.discover(NodeId("A"), NodeId("D"), PacketKind.TEXT)

        // The frontier is provably bounded and the dense fan actually forced drops.
        assertTrue("frontier peak $peak exceeded cap $cap", peak <= cap)
        assertTrue("expected the dense fan to force frontier drops", drops > 0)
        // The search completes without blowup and the cheap short path survives.
        assertTrue("expected at least one route", routes.isNotEmpty())
        assertEquals(listOf(NodeId("G")), routes.first().relayNodes)
    }

    @Test
    fun `rejects a non-positive frontier cap`() {
        val graph = MeshGraph.of(
            edges = flat(bidirectional("A", "C", TransportId.WIFI)),
            verifiedDestinations = setOf(NodeId("C")),
        )
        try {
            ReactiveRouteDiscovery(graph = graph, nowMillis = { 0 }, maxFrontierSize = 0)
            throw AssertionError("expected a non-positive frontier cap to be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `long chain path cost does not overflow`() {
        // A six-hop chain with maximum per-edge penalties. With Int accumulation
        // an adversarial dense graph could overflow; Long keeps it monotonic.
        val chain = listOf("A", "B", "C", "D", "E", "F", "G")
        val edges = chain.zipWithNext { a, b ->
            listOf(
                LinkEdge(NodeId(a), NodeId(b), TransportId.LORA, 60_000, 0, 100, 0),
                LinkEdge(NodeId(b), NodeId(a), TransportId.LORA, 60_000, 0, 100, 0),
            )
        }.flatten()
        val graph = MeshGraph.of(edges = edges, verifiedDestinations = setOf(NodeId("G")))
        val discovery = ReactiveRouteDiscovery(graph = graph, nowMillis = { 0 })

        val route = discovery.discover(NodeId("A"), NodeId("G"), PacketKind.BULK).firstOrNull()

        assertNotNull(route)
        assertEquals(6, route!!.hopCount)
    }

    @Test
    fun `sets a route expiry in the future`() {
        val graph = MeshGraph.of(
            edges = flat(bidirectional("A", "C", TransportId.WIFI)),
            verifiedDestinations = setOf(NodeId("C")),
        )
        val discovery = ReactiveRouteDiscovery(graph = graph, nowMillis = { 1_000 })

        val route = discovery.discover(NodeId("A"), NodeId("C"), PacketKind.TEXT).first()

        assertTrue(route.expiresAtMillis > 1_000)
    }

    private fun flat(vararg edges: List<LinkEdge>): List<LinkEdge> = edges.toList().flatten()

    private fun bidirectional(a: String, b: String, transport: TransportId): List<LinkEdge> {
        val (latency, quality, energy, bandwidth) = transportMetrics(transport)
        return listOf(
            LinkEdge(NodeId(a), NodeId(b), transport, latency, quality, energy, bandwidth),
            LinkEdge(NodeId(b), NodeId(a), transport, latency, quality, energy, bandwidth),
        )
    }

    private data class Metrics(val latency: Int, val quality: Int, val energy: Int, val bandwidth: Int)

    private fun transportMetrics(transport: TransportId): Metrics = when (transport) {
        TransportId.BT -> Metrics(latency = 40, quality = 70, energy = 1, bandwidth = 1)
        TransportId.WIFI -> Metrics(latency = 15, quality = 90, energy = 4, bandwidth = 5)
        else -> Metrics(latency = 100, quality = 50, energy = 3, bandwidth = 2)
    }
}

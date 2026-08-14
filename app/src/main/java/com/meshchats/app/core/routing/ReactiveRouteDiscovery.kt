package com.meshchats.app.core.routing

import java.util.PriorityQueue

/**
 * A live view of the neighbor graph. Edges come from a provider so discovery
 * always reflects the current set of reachable links, and are bounded to defend
 * against a hostile or runaway topology.
 *
 * Construct with [MeshGraph.of] for a static edge list, or [MeshGraph.live] for
 * a provider that reflects link changes over time.
 */
class MeshGraph internal constructor(
    private val edgeProvider: () -> List<LinkEdge>,
    private val verifiedProvider: () -> Set<NodeId>,
    val maxNodes: Int,
    val maxEdges: Int,
) {
    fun isVerifiedDestination(node: NodeId): Boolean = node in verifiedProvider()

    /**
     * Builds a deterministic adjacency map from the current live edges. Edges
     * are truncated to [maxEdges] and nodes to [maxNodes] so a flood of links
     * cannot force unbounded work.
     */
    fun adjacency(): Map<NodeId, List<LinkEdge>> {
        val bounded = edgeProvider()
            .asSequence()
            .filter { it.from != it.to }
            .take(maxEdges)
            .toList()

        val allowedNodes = LinkedHashSet<NodeId>()
        for (edge in bounded) {
            if (allowedNodes.size >= maxNodes) break
            allowedNodes += edge.from
            if (allowedNodes.size >= maxNodes) break
            allowedNodes += edge.to
        }

        val adjacency = HashMap<NodeId, MutableList<LinkEdge>>()
        for (edge in bounded) {
            if (edge.from !in allowedNodes || edge.to !in allowedNodes) continue
            adjacency.getOrPut(edge.from) { mutableListOf() } += edge
        }
        // Deterministic neighbor order: destination id, then transport.
        return adjacency.mapValues { (_, edges) ->
            edges.sortedWith(compareBy({ it.to.value }, { it.transport.ordinal }))
        }
    }

    companion object {
        const val DEFAULT_MAX_NODES: Int = 256
        const val DEFAULT_MAX_EDGES: Int = 1024

        /** A graph over a fixed snapshot of edges. */
        fun of(
            edges: List<LinkEdge>,
            verifiedDestinations: Set<NodeId>,
            maxNodes: Int = DEFAULT_MAX_NODES,
            maxEdges: Int = DEFAULT_MAX_EDGES,
        ): MeshGraph = MeshGraph({ edges }, { verifiedDestinations }, maxNodes, maxEdges)

        /** A graph backed by a live edge provider that reflects link changes. */
        fun live(
            edgeProvider: () -> List<LinkEdge>,
            verifiedProvider: () -> Set<NodeId>,
            maxNodes: Int = DEFAULT_MAX_NODES,
            maxEdges: Int = DEFAULT_MAX_EDGES,
        ): MeshGraph = MeshGraph(edgeProvider, verifiedProvider, maxNodes, maxEdges)
    }
}

/**
 * Diagnostics from a single [ReactiveRouteDiscovery.discover] call, used to
 * assert bounded behavior in tests without exposing a test-only production
 * method. [peakFrontierSize] is the high-water mark of the search frontier and
 * must never exceed the configured `maxFrontierSize`.
 */
data class DiscoveryStats(
    val expansions: Int,
    val peakFrontierSize: Int,
    val frontierDrops: Int,
)

/**
 * Reactive multi-hop route discovery over the live [MeshGraph].
 *
 * Only verified contacts may be multi-hop destinations; any node (even
 * unverified) may relay. Exploration is bounded by a six-hop request limit, a
 * work budget, a candidate cap, and a hard frontier-size cap so a large, dense,
 * or adversarial graph cannot force exponential search or unbounded memory.
 * Route ordering is deterministic.
 */
class ReactiveRouteDiscovery(
    private val graph: MeshGraph,
    private val nowMillis: () -> Long,
    private val maxRequestHops: Int = DEFAULT_MAX_REQUEST_HOPS,
    private val routeTtlMillis: Long = DEFAULT_ROUTE_TTL_MILLIS,
    private val maxExpansions: Int = DEFAULT_MAX_EXPANSIONS,
    private val candidateLimit: Int = DEFAULT_CANDIDATE_LIMIT,
    private val maxFrontierSize: Int = DEFAULT_MAX_FRONTIER_SIZE,
    private val statsObserver: ((DiscoveryStats) -> Unit)? = null,
) {
    init {
        require(maxFrontierSize > 0) { "maxFrontierSize must be positive" }
    }

    /**
     * Returns discovered routes to [destination], best first. Traffic returns up
     * to three routes: non-SOS returns the best distinct paths, while SOS returns
     * up to three node-disjoint routes when the topology allows it. An empty list
     * means no usable route.
     */
    fun discover(origin: NodeId, destination: NodeId, kind: PacketKind): List<MeshRoute> {
        if (origin == destination) return emptyList()
        if (!graph.isVerifiedDestination(destination)) return emptyList()

        val adjacency = graph.adjacency()
        if (adjacency.isEmpty()) return emptyList()

        val profile = RoutingProfile.forKind(kind)
        val candidates = search(origin, destination, adjacency, profile)
        if (candidates.isEmpty()) return emptyList()

        val expiresAt = nowMillis() + routeTtlMillis
        return select(candidates, profile).map { it.toRoute(expiresAt) }
    }

    private fun search(
        origin: NodeId,
        destination: NodeId,
        adjacency: Map<NodeId, List<LinkEdge>>,
        profile: RoutingProfile,
    ): List<Candidate> {
        val comparator = candidateComparator()
        // The frontier is capped: when it would exceed maxFrontierSize we drop
        // the single worst-cost candidate so memory stays bounded on a dense or
        // adversarial graph, keeping the search deterministic and complete for
        // the retained best candidates.
        val frontier = PriorityQueue(comparator)
        frontier += Candidate(edges = emptyList(), cost = 0L, current = origin)

        val completed = ArrayList<Candidate>()
        var expansions = 0
        var peakFrontierSize = frontier.size
        var frontierDrops = 0

        while (frontier.isNotEmpty() && expansions < maxExpansions && completed.size < candidateLimit) {
            val candidate = frontier.poll()
            expansions++

            if (candidate.current == destination) {
                completed += candidate
                continue
            }
            if (candidate.hopCount >= maxRequestHops) continue

            val visited = candidate.visitedNodes(origin)
            for (edge in adjacency[candidate.current].orEmpty()) {
                if (edge.to in visited) continue // suppress cycles / revisits
                frontier += Candidate(
                    edges = candidate.edges + edge,
                    cost = candidate.cost + edgeCost(edge, profile),
                    current = edge.to,
                )
                if (frontier.size > maxFrontierSize) {
                    dropWorst(frontier, comparator)
                    frontierDrops++
                }
            }
            if (frontier.size > peakFrontierSize) peakFrontierSize = frontier.size
        }

        statsObserver?.invoke(
            DiscoveryStats(
                expansions = expansions,
                peakFrontierSize = peakFrontierSize,
                frontierDrops = frontierDrops,
            ),
        )
        return completed.sortedWith(comparator)
    }

    /**
     * Removes the single worst (highest-cost) candidate from [frontier]. A
     * min-heap keeps only the best at the head, so the worst is found by a
     * linear scan; this runs only when the frontier is already at its cap.
     */
    private fun dropWorst(frontier: PriorityQueue<Candidate>, comparator: Comparator<Candidate>) {
        var worst: Candidate? = null
        for (candidate in frontier) {
            if (worst == null || comparator.compare(candidate, worst) > 0) worst = candidate
        }
        if (worst != null) frontier.remove(worst)
    }

    private fun select(candidates: List<Candidate>, profile: RoutingProfile): List<Candidate> {
        if (!profile.preferDisjoint) {
            // Deduplicate by node path, then take the best N.
            val seen = HashSet<String>()
            val unique = ArrayList<Candidate>()
            for (candidate in candidates) {
                if (seen.add(candidate.nodePath)) unique += candidate
                if (unique.size >= profile.maxRoutes) break
            }
            return unique
        }

        // SOS: greedily pick relay-node-disjoint routes, best first.
        val chosen = ArrayList<Candidate>()
        val usedRelays = HashSet<NodeId>()
        for (candidate in candidates) {
            val relays = candidate.relayNodes()
            if (relays.any { it in usedRelays }) continue
            chosen += candidate
            usedRelays += relays
            if (chosen.size >= profile.maxRoutes) break
        }
        return chosen
    }

    /**
     * Per-edge routing cost as a [Long] so a long chain of hops on a dense graph
     * cannot overflow the accumulated path cost.
     */
    private fun edgeCost(edge: LinkEdge, profile: RoutingProfile): Long {
        val reliabilityPenalty = profile.reliabilityWeight.toLong() * (LINK_QUALITY_MAX - edge.linkQuality)
        val energyPenalty = profile.energyWeight.toLong() * edge.energyCost * ENERGY_SCALE
        val bandwidthPenalty =
            profile.bandwidthWeight.toLong() * (BANDWIDTH_CLASS_MAX - edge.bandwidthClass) * BANDWIDTH_SCALE
        return reliabilityPenalty + energyPenalty + bandwidthPenalty + edge.latencyMs
    }

    private fun candidateComparator(): Comparator<Candidate> =
        compareBy<Candidate>({ it.cost }, { it.hopCount }, { it.latency }, { it.nodePath })

    private class Candidate(
        val edges: List<LinkEdge>,
        val cost: Long,
        val current: NodeId,
    ) {
        val hopCount: Int get() = edges.size

        // Cached: total latency is read repeatedly by the comparator, so it is
        // summed once at construction rather than on every comparison.
        val latency: Int = edges.sumOf { it.latencyMs }

        val nodePath: String
            get() = buildString {
                if (edges.isEmpty()) {
                    append(current.value)
                } else {
                    append(edges.first().from.value)
                    edges.forEach { append('>').append(it.to.value) }
                }
            }

        fun visitedNodes(origin: NodeId): Set<NodeId> {
            val visited = HashSet<NodeId>()
            visited += origin
            edges.forEach { visited += it.to }
            return visited
        }

        fun relayNodes(): List<NodeId> = edges.dropLast(1).map { it.to }

        fun toRoute(expiresAtMillis: Long): MeshRoute = MeshRoute(
            destination = edges.last().to,
            hops = edges.map {
                RouteHop(it.from, it.to, it.transport, it.latencyMs, it.linkQuality)
            },
            expiresAtMillis = expiresAtMillis,
        )
    }

    companion object {
        const val DEFAULT_MAX_REQUEST_HOPS: Int = 6
        const val DEFAULT_ROUTE_TTL_MILLIS: Long = 60_000
        const val DEFAULT_MAX_EXPANSIONS: Int = 20_000
        const val DEFAULT_CANDIDATE_LIMIT: Int = 64
        const val DEFAULT_MAX_FRONTIER_SIZE: Int = 4096

        private const val LINK_QUALITY_MAX = 100
        private const val BANDWIDTH_CLASS_MAX = 5
        private const val ENERGY_SCALE = 25
        private const val BANDWIDTH_SCALE = 20
    }
}

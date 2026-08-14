package com.meshchats.app.core.routing

import com.meshchats.app.core.mesh.TransportId

/**
 * Platform-free routing domain for the hybrid multi-transport mesh tracer.
 *
 * Nothing here touches Android BLE, Wi-Fi, or LoRa classes; the routing core
 * reasons only about opaque nodes, edges, and ciphertext so it can be proven in
 * pure JVM tests before real radios exist.
 */

/** Stable logical identity of a mesh node. Never a MAC or hardware address. */
@JvmInline
value class NodeId(val value: String)

/** Unique id of a packet, used for duplicate/replay suppression. */
@JvmInline
value class PacketId(val value: String)

/** What a packet carries, which drives payload-aware route selection. */
enum class PacketKind {
    /** Small control frames: route requests/replies, acks. */
    CONTROL,

    /** Human text messages. Prefer reliable, low-energy paths. */
    TEXT,

    /** Large payloads/attachments. Prefer Wi-Fi bandwidth. */
    BULK,

    /** Emergency traffic. Duplicated across independent routes. */
    SOS,
}

/**
 * How a [PacketKind] should be routed. Weights are relative preferences the
 * discovery cost function combines; higher means "matters more".
 */
data class RoutingProfile(
    val bandwidthWeight: Int,
    val reliabilityWeight: Int,
    val energyWeight: Int,
    val maxRoutes: Int,
    val preferDisjoint: Boolean,
) {
    companion object {
        fun forKind(kind: PacketKind): RoutingProfile = when (kind) {
            PacketKind.CONTROL -> RoutingProfile(
                bandwidthWeight = 1,
                reliabilityWeight = 4,
                energyWeight = 4,
                maxRoutes = 3,
                preferDisjoint = false,
            )
            PacketKind.TEXT -> RoutingProfile(
                bandwidthWeight = 1,
                reliabilityWeight = 4,
                energyWeight = 3,
                maxRoutes = 3,
                preferDisjoint = false,
            )
            PacketKind.BULK -> RoutingProfile(
                bandwidthWeight = 6,
                reliabilityWeight = 2,
                energyWeight = 1,
                maxRoutes = 3,
                preferDisjoint = false,
            )
            PacketKind.SOS -> RoutingProfile(
                bandwidthWeight = 2,
                reliabilityWeight = 5,
                energyWeight = 1,
                maxRoutes = 3,
                preferDisjoint = true,
            )
        }
    }
}

/**
 * A live, directed neighbor link between two nodes on one transport.
 *
 * [latencyMs] is the typical one-way latency in `0..MAX_LATENCY_MS`;
 * [linkQuality] is `0..100` where higher is better; [energyCost] is a relative
 * battery cost in `0..100` where higher is worse (BLE cheap, Wi-Fi bulk
 * expensive); [bandwidthClass] ranks throughput where higher is faster. The
 * ranges are validated so a malformed or hostile edge advertisement cannot
 * drive route costs negative or overflow accumulation.
 */
data class LinkEdge(
    val from: NodeId,
    val to: NodeId,
    val transport: TransportId,
    val latencyMs: Int,
    val linkQuality: Int,
    val energyCost: Int,
    val bandwidthClass: Int,
) {
    init {
        require(latencyMs in 0..MAX_LATENCY_MS) {
            "latencyMs must be in 0..$MAX_LATENCY_MS, was $latencyMs"
        }
        require(linkQuality in 0..MAX_LINK_QUALITY) {
            "linkQuality must be in 0..$MAX_LINK_QUALITY, was $linkQuality"
        }
        require(energyCost in 0..MAX_ENERGY_COST) {
            "energyCost must be in 0..$MAX_ENERGY_COST, was $energyCost"
        }
    }

    companion object {
        const val MAX_LATENCY_MS: Int = 60_000
        const val MAX_LINK_QUALITY: Int = 100
        const val MAX_ENERGY_COST: Int = 100
    }
}

/** One transport-specific step of a route. */
data class RouteHop(
    val from: NodeId,
    val to: NodeId,
    val transport: TransportId,
    val latencyMs: Int,
    val linkQuality: Int,
)

/**
 * A discovered path to [destination] as an ordered list of transport hops.
 * A route is a sequence of hops, not a single transport plus a hop count, so a
 * BLE→Wi-Fi path is first-class.
 */
data class MeshRoute(
    val destination: NodeId,
    val hops: List<RouteHop>,
    val expiresAtMillis: Long,
) {
    init {
        require(hops.isNotEmpty()) { "A route must have at least one hop" }
    }

    /** First sender of the route. */
    val origin: NodeId get() = hops.first().from

    /** Ordered transports the packet traverses. */
    val transports: List<TransportId> get() = hops.map { it.transport }

    val hopCount: Int get() = hops.size

    val totalLatencyMs: Int get() = hops.sumOf { it.latencyMs }

    fun isExpired(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis

    /** Intermediate relay nodes, i.e. everyone except origin and destination. */
    val relayNodes: List<NodeId> get() = hops.dropLast(1).map { it.to }

    /**
     * A deterministic fingerprint of the exact path (origin, each hop's next
     * node, and transport). Two routes that traverse the same nodes over the
     * same transports in the same order share a fingerprint; any divergence
     * yields a different one. Used to distinguish independent SOS routes from a
     * replay of the same route.
     */
    val fingerprint: String
        get() = buildString {
            append(origin.value)
            hops.forEach { hop ->
                append('>').append(hop.to.value).append('@').append(hop.transport.ordinal)
            }
        }
}

/**
 * An opaque, end-to-end encrypted mesh packet. Relays may read only routing
 * metadata (id, kind, blinded destination tag, expiry, hop budget); the
 * [ciphertext] and [originSignature] are never re-encrypted or mutated in
 * transit.
 *
 * All byte arrays are defensively copied on construction and on every access so
 * a buggy or malicious relay cannot mutate the original bytes. Equality is by
 * packet id, deliberately avoiding the data-class ByteArray equality trap.
 */
class MeshPacket private constructor(
    val packetId: PacketId,
    val kind: PacketKind,
    private val destinationTagBytes: ByteArray,
    val expiresAtMillis: Long,
    val hopsRemaining: Int,
    private val ciphertextBytes: ByteArray,
    private val originSignatureBytes: ByteArray,
    val maxCiphertextBytes: Int,
) {
    /** Defensive copy so callers cannot mutate internal state. */
    val ciphertext: ByteArray get() = ciphertextBytes.copyOf()
    val destinationTag: ByteArray get() = destinationTagBytes.copyOf()
    val originSignature: ByteArray get() = originSignatureBytes.copyOf()

    val sizeBytes: Int get() = ciphertextBytes.size

    fun isExpired(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis

    /**
     * Returns a copy with a new remaining hop budget, keeping ciphertext,
     * destination tag, and signature byte-identical.
     */
    fun withHopsRemaining(hopsRemaining: Int): MeshPacket = MeshPacket(
        packetId = packetId,
        kind = kind,
        destinationTagBytes = destinationTagBytes.copyOf(),
        expiresAtMillis = expiresAtMillis,
        hopsRemaining = hopsRemaining,
        ciphertextBytes = ciphertextBytes.copyOf(),
        originSignatureBytes = originSignatureBytes.copyOf(),
        maxCiphertextBytes = maxCiphertextBytes,
    )

    override fun equals(other: Any?): Boolean =
        this === other || (other is MeshPacket && other.packetId == packetId)

    override fun hashCode(): Int = packetId.hashCode()

    override fun toString(): String =
        "MeshPacket(packetId=$packetId, kind=$kind, hopsRemaining=$hopsRemaining, sizeBytes=$sizeBytes)"

    companion object {
        /** Hard ceiling on ciphertext size to bound relay memory: 1 MiB. */
        const val MAX_CIPHERTEXT_BYTES: Int = 1 shl 20

        fun create(
            packetId: PacketId,
            kind: PacketKind,
            destinationTag: ByteArray,
            expiresAtMillis: Long,
            hopsRemaining: Int,
            ciphertext: ByteArray,
            originSignature: ByteArray,
            maxCiphertextBytes: Int = MAX_CIPHERTEXT_BYTES,
        ): MeshPacket {
            require(hopsRemaining >= 0) { "hopsRemaining must be non-negative" }
            require(maxCiphertextBytes > 0) { "maxCiphertextBytes must be positive" }
            require(ciphertext.size <= maxCiphertextBytes) {
                "ciphertext ${ciphertext.size}B exceeds max ${maxCiphertextBytes}B"
            }
            return MeshPacket(
                packetId = packetId,
                kind = kind,
                destinationTagBytes = destinationTag.copyOf(),
                expiresAtMillis = expiresAtMillis,
                hopsRemaining = hopsRemaining,
                ciphertextBytes = ciphertext.copyOf(),
                originSignatureBytes = originSignature.copyOf(),
                maxCiphertextBytes = maxCiphertextBytes,
            )
        }
    }
}

package com.meshchats.app.core.mesh

/** Radios/paths a frame can travel over. Order is UI display order. */
enum class TransportId(val label: String, val shortLabel: String) {
    WIFI("Wi-Fi Aware / Direct", "WIFI"),
    BT("Bluetooth LE mesh", "BT"),
    LORA("LoRa radio", "LORA"),
    RELAY("Relay (global)", "RELAY"),
}

sealed interface TransportState {
    /** Radio exists but is switched off by the user. */
    data object Off : TransportState

    /** Hardware not present, e.g. no LoRa radio attached. */
    data object Absent : TransportState

    /** On, but no peers reachable. */
    data object Idle : TransportState

    data class Active(val peers: Int, val throughputBps: Long) : TransportState
}

/**
 * Physical limits of a transport. The composer reads these instead of hardcoding
 * payload sizes, so a LoRa byte budget is never a magic number in the UI.
 */
data class Constraints(
    val maxPayloadBytes: Int,
    val typicalLatencyMs: Int,
    /** Regional duty-cycle usage, LoRa only. */
    val dutyCyclePercent: Float? = null,
)

data class TransportStatus(
    val id: TransportId,
    val state: TransportState,
    /** Secondary line, e.g. "RAK4631 · USB · 868MHz · SF7". */
    val detail: String,
    val constraints: Constraints,
) {
    val isCarrying: Boolean get() = state is TransportState.Active
    val isAvailable: Boolean get() = state is TransportState.Idle || isCarrying
}

/** How a specific conversation is currently reachable. */
data class Route(
    val transport: TransportId,
    val hops: Int,
    val latencyMs: Int,
    val viaRelay: Boolean,
) {
    val isDirect: Boolean get() = hops <= 1 && !viaRelay
}

data class Peer(
    val id: String,
    val displayName: String,
    /** Four-word key fingerprint, e.g. ["anchor", "drift", "lantern", "nine"]. */
    val fingerprint: List<String>,
    val verified: Boolean,
    val reachableVia: Set<TransportId>,
    val rssiDbm: Int?,
    val hops: Int?,
    val lastSeenMinutes: Int,
) {
    val isReachable: Boolean get() = reachableVia.isNotEmpty()

    /** 4-glyph monogram used as the avatar: first letter of each fingerprint word. */
    val monogram: String
        get() = fingerprint.take(4).map { it.first().uppercaseChar() }.joinToString("")

    val fingerprintShort: String get() = fingerprint.take(2).joinToString(" · ")
    val fingerprintFull: String get() = fingerprint.joinToString(" · ")
}

data class MeshState(
    val transports: List<TransportStatus>,
    val peers: List<Peer>,
    val localMeshOnly: Boolean,
) {
    val peerCount: Int get() = peers.count { it.isReachable }
    val unverifiedCount: Int get() = peers.count { !it.verified }

    fun transport(id: TransportId): TransportStatus? = transports.firstOrNull { it.id == id }

    /** Transport that would carry the next frame: fastest available wins. */
    val activeTransport: TransportStatus?
        get() = transports.firstOrNull { it.isCarrying } ?: transports.firstOrNull { it.isAvailable }

    companion object {
        val Empty = MeshState(transports = emptyList(), peers = emptyList(), localMeshOnly = false)
    }
}

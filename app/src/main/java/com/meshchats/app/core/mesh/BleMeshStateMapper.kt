package com.meshchats.app.core.mesh

import com.meshchats.app.core.transport.ble.BleDiscoveryState
import com.meshchats.app.core.transport.ble.DiscoveredBlePeer

/**
 * Pure translation from BLE discovery vocabulary ([BleDiscoveryState]) into the
 * UI vocabulary the transport strip and mesh tab already read ([TransportStatus]
 * and [Peer]). Kept free of radios, time, and randomness so every mapping is
 * asserted directly in tests and the repository can overlay the result onto its
 * remaining fake transports without leaking Android types into the UI layer.
 *
 * ### Peer identity is ephemeral and unverified
 * There is no identity exchange yet: a discovered peer is only an ephemeral,
 * unsigned 64-bit node ID plus signal. So the [Peer.fingerprint] here is **not**
 * a real key fingerprint — it is just a stable four-chunk rendering of the node
 * ID, and [Peer.verified] is always false. The Bluetooth MAC is never available
 * to this mapper (the discovery API deliberately never exposes it) and never
 * surfaced.
 */
object BleMeshStateMapper {

    /** Longest error message we will render, keeping the strip line bounded. */
    private const val MAX_ERROR_DETAIL = 120

    /**
     * Map the current BLE discovery [state] onto the Bluetooth [TransportStatus].
     * [constraints] are carried through unchanged so the repository owns the
     * transport's physical limits.
     */
    fun toTransportStatus(
        state: BleDiscoveryState,
        constraints: Constraints = BleTransportDefaults.CONSTRAINTS,
    ): TransportStatus {
        val (transportState, detail) = when (state) {
            BleDiscoveryState.Unsupported ->
                TransportState.Absent to "Bluetooth LE not supported"

            is BleDiscoveryState.PermissionRequired ->
                TransportState.Off to "Nearby devices permission required"

            BleDiscoveryState.BluetoothOff ->
                TransportState.Off to "Bluetooth is off"

            BleDiscoveryState.Idle ->
                TransportState.Idle to "Ready to scan"

            is BleDiscoveryState.Scanning ->
                if (state.peers.isEmpty()) {
                    TransportState.Idle to "Scanning · no peers"
                } else {
                    val count = state.peers.size
                    val noun = if (count == 1) "peer" else "peers"
                    TransportState.Active(peers = count, throughputBps = 0L) to
                        "Scanning · $count $noun"
                }

            is BleDiscoveryState.Error ->
                TransportState.Off to boundedErrorDetail(state.message)
        }

        return TransportStatus(
            id = TransportId.BT,
            state = transportState,
            detail = detail,
            constraints = constraints,
        )
    }

    /**
     * The discovered BLE peers as UI [Peer]s, in discovery order. Non-scanning
     * states carry no peers. Each peer is ephemeral and unverified (see the
     * class note); nothing here derives identity from a key or a MAC.
     */
    fun toPeers(state: BleDiscoveryState): List<Peer> =
        when (state) {
            is BleDiscoveryState.Scanning -> state.peers.map { it.toPeer() }
            else -> emptyList()
        }

    private fun DiscoveredBlePeer.toPeer(): Peer {
        val hex = unsignedHex(nodeId)
        val chunks = listOf(
            hex.substring(0, 4),
            hex.substring(4, 8),
            hex.substring(8, 12),
            hex.substring(12, 16),
        )
        return Peer(
            id = "ble-$hex",
            displayName = "nearby ${chunks.last()}",
            // Not a real fingerprint: a stable rendering of the ephemeral node ID.
            fingerprint = chunks,
            verified = false,
            reachableVia = setOf(TransportId.BT),
            rssiDbm = rssiDbm,
            hops = 1,
            lastSeenMinutes = 0,
        )
    }

    /** Zero-padded lowercase unsigned 16-hex rendering of a 64-bit node ID. */
    private fun unsignedHex(nodeId: Long): String =
        java.lang.Long.toUnsignedString(nodeId, 16).padStart(16, '0')

    private fun boundedErrorDetail(message: String): String {
        val prefix = "Bluetooth error · "
        val room = MAX_ERROR_DETAIL - prefix.length
        val safe = message.trim().take(room)
        return prefix + if (safe.isBlank()) "discovery failed" else safe
    }
}

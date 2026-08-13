package com.meshchats.app.ui.components

import com.meshchats.app.core.mesh.Constraints
import com.meshchats.app.core.mesh.MeshState
import com.meshchats.app.core.mesh.Peer
import com.meshchats.app.core.mesh.TransportId
import com.meshchats.app.core.mesh.TransportState
import com.meshchats.app.core.mesh.TransportStatus

/** Shared fixtures for @Preview so every component previews real-shaped state. */
internal fun previewMeshState(
    localMeshOnly: Boolean = false,
    loraAttached: Boolean = false,
): MeshState = MeshState(
    transports = listOf(
        TransportStatus(
            TransportId.WIFI,
            TransportState.Active(peers = 2, throughputBps = 1_200_000),
            "Wi-Fi Aware · channel 6",
            Constraints(1_048_576, 25),
        ),
        TransportStatus(
            TransportId.BT,
            TransportState.Active(peers = 1, throughputBps = 12_000),
            "BLE mesh · 4 hops max",
            Constraints(20_480, 180),
        ),
        TransportStatus(
            TransportId.LORA,
            if (loraAttached) TransportState.Idle else TransportState.Absent,
            if (loraAttached) "RAK4631 · USB · 868MHz · SF7" else "No device attached",
            Constraints(200, 2_400, dutyCyclePercent = if (loraAttached) 1.2f else 0f),
        ),
        TransportStatus(
            TransportId.RELAY,
            if (localMeshOnly) TransportState.Off else TransportState.Idle,
            if (localMeshOnly) "Disabled by Local mesh only" else "relay.mesh.example:443",
            Constraints(1_048_576, 90),
        ),
    ),
    peers = listOf(
        Peer(
            id = "peer-1",
            displayName = "Ari",
            fingerprint = listOf("anchor", "drift", "lantern", "nine"),
            verified = true,
            reachableVia = setOf(TransportId.WIFI, TransportId.BT),
            rssiDbm = -54,
            hops = 1,
            lastSeenMinutes = 0,
        ),
        Peer(
            id = "peer-3",
            displayName = "unknown",
            fingerprint = listOf("cinder", "harbor", "maple", "four"),
            verified = false,
            reachableVia = setOf(TransportId.WIFI),
            rssiDbm = -66,
            hops = 1,
            lastSeenMinutes = 0,
        ),
        Peer(
            id = "peer-4",
            displayName = "Rae",
            fingerprint = listOf("delta", "orchid", "signal", "two"),
            verified = false,
            reachableVia = emptySet(),
            rssiDbm = null,
            hops = null,
            lastSeenMinutes = 46,
        ),
    ),
    localMeshOnly = localMeshOnly,
)

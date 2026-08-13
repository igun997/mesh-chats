package com.meshchats.app.core.mesh

/**
 * The single source of truth for the Bluetooth transport's physical limits.
 *
 * Both the [HybridMeshStateRepository] seed/overlay and [BleMeshStateMapper]'s
 * fallback read this, so the BT payload/latency budget is defined exactly once
 * and can never drift between the mapper default and the repository seed.
 */
object BleTransportDefaults {
    val CONSTRAINTS = Constraints(maxPayloadBytes = 20_480, typicalLatencyMs = 180)
}

package com.meshchats.app.core.mesh

import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for radio and peer state. The transport header status,
 * mesh tab, and chat route line all read this one flow, so the UI never queries radios
 * directly.
 */
interface MeshStateRepository {
    val state: StateFlow<MeshState>

    fun setTransportEnabled(id: TransportId, enabled: Boolean)

    /** Hard kill switch: disables the relay and every internet path. */
    fun setLocalMeshOnly(enabled: Boolean)
}

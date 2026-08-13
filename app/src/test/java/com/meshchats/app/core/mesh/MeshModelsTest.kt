package com.meshchats.app.core.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshModelsTest {

    @Test
    fun `peer monogram is derived from fingerprint not display name`() {
        val peer = peer(
            fingerprint = listOf("anchor", "drift", "lantern", "nine"),
            reachableVia = setOf(TransportId.BT),
        )

        assertEquals("ADLN", peer.monogram)
        assertEquals("anchor · drift", peer.fingerprintShort)
        assertTrue(peer.isReachable)
    }

    @Test
    fun `peer with no transport is out of range`() {
        assertFalse(peer(reachableVia = emptySet()).isReachable)
    }

    @Test
    fun `mesh active transport prefers lowest ordinal carrying traffic`() {
        val mesh = MeshState(
            transports = listOf(
                status(TransportId.BT, TransportState.Active(1, 12_000)),
                status(TransportId.WIFI, TransportState.Active(2, 1_200_000)),
            ),
            peers = emptyList(),
            localMeshOnly = false,
        )

        // Active selection intentionally follows repository list order rather
        // than enum sorting; the repository owns route priority.
        assertEquals(TransportId.BT, mesh.activeTransport?.id)
    }

    private fun peer(
        fingerprint: List<String> = listOf("beacon", "quartz", "tide", "seven"),
        reachableVia: Set<TransportId>,
    ) = Peer(
        id = "peer",
        displayName = "name can change",
        fingerprint = fingerprint,
        verified = false,
        reachableVia = reachableVia,
        rssiDbm = null,
        hops = null,
        lastSeenMinutes = 0,
    )

    private fun status(id: TransportId, state: TransportState) = TransportStatus(
        id = id,
        state = state,
        detail = "",
        constraints = Constraints(200, 100),
    )
}

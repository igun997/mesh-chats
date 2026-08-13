package com.meshchats.app.core.mesh

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Stand-in for real radios so the UI contract can be built and tested before any
 * transport exists. Ticks throughput and RSSI on purpose: the transport strip must
 * survive frequent updates without recomposing chat lists.
 */
@Singleton
class FakeMeshStateRepository @Inject constructor(
    scope: CoroutineScope,
) : MeshStateRepository {

    private val _state = MutableStateFlow(seed())
    override val state: StateFlow<MeshState> = _state.asStateFlow()

    init {
        scope.launch {
            while (true) {
                delay(2_000)
                _state.update { current -> current.jitter() }
            }
        }
    }

    override fun setTransportEnabled(id: TransportId, enabled: Boolean) {
        _state.update { current ->
            current.copy(
                transports = current.transports.map { transport ->
                    if (transport.id != id) {
                        transport
                    } else {
                        transport.copy(
                            state = when {
                                transport.state is TransportState.Absent -> TransportState.Absent
                                !enabled -> TransportState.Off
                                else -> TransportState.Idle
                            },
                        )
                    }
                },
            )
        }
    }

    override fun setLocalMeshOnly(enabled: Boolean) {
        _state.update { current ->
            current.copy(
                localMeshOnly = enabled,
                transports = current.transports.map { transport ->
                    if (transport.id != TransportId.RELAY) {
                        transport
                    } else {
                        transport.copy(
                            state = if (enabled) TransportState.Off else TransportState.Idle,
                            detail = if (enabled) "Disabled by Local mesh only" else RELAY_DETAIL,
                        )
                    }
                },
            )
        }
    }

    private fun MeshState.jitter(): MeshState = copy(
        transports = transports.map { transport ->
            val state = transport.state
            if (state is TransportState.Active) {
                transport.copy(
                    state = state.copy(
                        throughputBps = (state.throughputBps * Random.nextDouble(0.7, 1.3)).toLong(),
                    ),
                )
            } else {
                transport
            }
        },
        peers = peers.map { peer ->
            peer.rssiDbm?.let { rssi ->
                peer.copy(rssiDbm = (rssi + Random.nextInt(-3, 4)).coerceIn(-99, -40))
            } ?: peer
        },
    )

    private companion object {
        const val RELAY_DETAIL = "relay.mesh.example:443 · stores nothing"

        fun seed() = MeshState(
            transports = listOf(
                TransportStatus(
                    id = TransportId.WIFI,
                    state = TransportState.Active(peers = 2, throughputBps = 1_200_000),
                    detail = "Wi-Fi Aware · channel 6",
                    constraints = Constraints(maxPayloadBytes = 1_048_576, typicalLatencyMs = 25),
                ),
                TransportStatus(
                    id = TransportId.BT,
                    state = TransportState.Active(peers = 1, throughputBps = 12_000),
                    detail = "BLE mesh · 4 hops max",
                    constraints = Constraints(maxPayloadBytes = 20_480, typicalLatencyMs = 180),
                ),
                TransportStatus(
                    id = TransportId.LORA,
                    state = TransportState.Absent,
                    detail = "No device attached",
                    constraints = Constraints(
                        maxPayloadBytes = 200,
                        typicalLatencyMs = 2_400,
                        dutyCyclePercent = 0f,
                    ),
                ),
                TransportStatus(
                    id = TransportId.RELAY,
                    state = TransportState.Idle,
                    detail = RELAY_DETAIL,
                    constraints = Constraints(maxPayloadBytes = 1_048_576, typicalLatencyMs = 90),
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
                    id = "peer-2",
                    displayName = "Basecamp",
                    fingerprint = listOf("beacon", "quartz", "tide", "seven"),
                    verified = true,
                    reachableVia = setOf(TransportId.BT),
                    rssiDbm = -78,
                    hops = 2,
                    lastSeenMinutes = 1,
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
            localMeshOnly = false,
        )
    }
}

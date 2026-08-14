package com.meshchats.app.core.mesh

import com.meshchats.app.core.transport.ble.BleDiscoveryController
import com.meshchats.app.core.transport.ble.BleDiscoveryPreference
import com.meshchats.app.core.transport.ble.BleDiscoverySettings
import com.meshchats.app.core.transport.ble.BleDiscoveryState
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Mesh state backed by **real BLE discovery** for the Bluetooth transport and its
 * peers, with the remaining transports (Wi-Fi, LoRa, Relay) and any non-BLE peers
 * still faked until those radios land. The name reflects that split: this is no
 * longer a pure fake.
 *
 * The Bluetooth [TransportStatus] and every BLE [Peer] are derived from
 * [BleDiscoveryController.state] via the pure [BleMeshStateMapper] and overlaid on
 * top of the seeded fake state whenever discovery emits. There is no seeded fake
 * Bluetooth-only peer any more: a peer only appears on BT if the radio actually
 * discovered it, so the mesh tab never shows a peer that isn't there. Fake peers
 * keep only their non-BT transports.
 *
 * ### Lifecycle
 * This repository *observes* discovery on the injected application [scope]; it
 * deliberately does not [BleDiscoveryController.start]/[stop] the controller.
 * Starting and stopping discovery is tied to the Mesh screen's lifecycle and is
 * owned elsewhere, so the app does not advertise and scan for the whole process
 * lifetime.
 *
 * The RSSI/throughput jitter loop is retained for the still-fake transports so the
 * transport header status keeps proving it survives frequent updates without
 * recomposing chat lists; it never touches BLE-derived rows.
 */
@Singleton
class HybridMeshStateRepository(
    scope: CoroutineScope,
    private val bleController: BleDiscoveryController,
    private val settings: BleDiscoverySettings,
    // Exposed so tests can disable the still-fake jitter loop with
    // Long.MAX_VALUE; production uses the [DEFAULT_JITTER_INTERVAL_MILLIS]
    // secondary constructor below.
    private val jitterIntervalMillis: Long,
) : MeshStateRepository {

    @Inject
    constructor(
        scope: CoroutineScope,
        bleController: BleDiscoveryController,
        settings: BleDiscoverySettings,
    ) : this(scope, bleController, settings, DEFAULT_JITTER_INTERVAL_MILLIS)

    private val _state = MutableStateFlow(seed())
    override val state: StateFlow<MeshState> = _state.asStateFlow()

    init {
        // Overlay BLE status + peers onto the fake baseline on every emission,
        // gated by the user's persisted intent: while the preference is loading
        // or disabled the Bluetooth row is honestly Off and BLE peers vanish, so
        // the UI never shows scanning the user has switched off.
        scope.launch {
            combine(bleController.state, settings.state, ::Pair).collect { (discovery, pref) ->
                _state.update { current -> current.withBle(discovery, pref) }
            }
        }
        if (jitterIntervalMillis != Long.MAX_VALUE) {
            scope.launch {
                while (true) {
                    delay(jitterIntervalMillis)
                    _state.update { current -> current.jitter() }
                }
            }
        }
    }

    override fun setTransportEnabled(id: TransportId, enabled: Boolean) {
        // BT enablement is driven by the discovery controller lifecycle, not a
        // synthetic toggle; only the still-fake transports respond here.
        if (id == TransportId.BT) return
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

    /**
     * Replace the BT transport row and BLE peers with the mapped discovery state,
     * unless the user's [preference] withholds it. While loading we do not yet
     * know intent, and while disabled the user has opted out — either way BT is
     * shown Off and every BLE-only peer is dropped, while non-BLE fake routes are
     * preserved. When enabled the row and peers are fully discovery-derived.
     */
    private fun MeshState.withBle(
        discovery: BleDiscoveryState,
        preference: BleDiscoveryPreference,
    ): MeshState {
        val btConstraints = transport(TransportId.BT)?.constraints ?: BleTransportDefaults.CONSTRAINTS
        val (btStatus, blePeers) = if (preference.loaded && preference.enabled) {
            BleMeshStateMapper.toTransportStatus(discovery, btConstraints) to
                BleMeshStateMapper.toPeers(discovery)
        } else {
            val detail = if (preference.loaded) DISABLED_DETAIL else LOADING_DETAIL
            TransportStatus(
                id = TransportId.BT,
                state = TransportState.Off,
                detail = detail,
                constraints = btConstraints,
            ) to emptyList()
        }
        return copy(
            transports = transports.map { if (it.id == TransportId.BT) btStatus else it },
            // Non-BLE fake peers stay; BLE peers are entirely discovery-driven.
            peers = peers.filterNot { it.id.startsWith(BLE_PEER_PREFIX) } + blePeers,
        )
    }

    private fun MeshState.jitter(): MeshState = copy(
        transports = transports.map { transport ->
            val state = transport.state
            if (state is TransportState.Active && transport.id != TransportId.BT) {
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
            // BLE peers carry real, discovery-sourced RSSI; never jitter them.
            if (peer.id.startsWith(BLE_PEER_PREFIX)) {
                peer
            } else {
                peer.rssiDbm?.let { rssi ->
                    peer.copy(rssiDbm = (rssi + Random.nextInt(-3, 4)).coerceIn(-99, -40))
                } ?: peer
            }
        },
    )

    private companion object {
        const val RELAY_DETAIL = "relay.mesh.example:443 · stores nothing"
        const val DISABLED_DETAIL = "Disabled in Mesh Chats"
        const val LOADING_DETAIL = "Loading preference"
        const val BLE_PEER_PREFIX = "ble-"
        const val DEFAULT_JITTER_INTERVAL_MILLIS = 2_000L

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
                    // Placeholder until the first discovery emission overlays real state.
                    state = TransportState.Idle,
                    detail = "Ready to scan",
                    constraints = BleTransportDefaults.CONSTRAINTS,
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
                // Ari is Wi-Fi only now: the fake BT reachability was removed, since
                // BT peers must come from real discovery, not a seed.
                Peer(
                    id = "peer-1",
                    displayName = "Ari",
                    fingerprint = listOf("anchor", "drift", "lantern", "nine"),
                    verified = true,
                    reachableVia = setOf(TransportId.WIFI),
                    rssiDbm = -54,
                    hops = 1,
                    lastSeenMinutes = 0,
                ),
                // The seeded BT-only "Basecamp" peer was removed to avoid showing a
                // peer that no radio has actually discovered.
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

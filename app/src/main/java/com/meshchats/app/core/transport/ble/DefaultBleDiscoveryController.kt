package com.meshchats.app.core.transport.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Default [BleDiscoveryController].
 *
 * Discovery policy lives here; the [radio] only drives hardware. On [start] the
 * controller checks preconditions in order (hardware support, permissions,
 * adapter state), draws a fresh beacon from [beaconProvider], then advertises it
 * and scans filtered to [BleDiscoveryController.SERVICE_UUID]. Scan results are
 * decoded with the fail-closed [BleDiscoveryProtocol]; malformed payloads are
 * dropped. Discovered peers are deduplicated by node ID in [registry] and
 * expired on a periodic tick driven by [expiryIntervalMillis] (injectable so
 * coroutine tests are deterministic).
 *
 * ### Ephemeral, rotating identity
 * The advertised node ID is drawn once per [start] via [beaconProvider]. Because
 * every foreground scan session (re)starts discovery, a device that leaves and
 * returns advertises a brand-new ID, so there is no process- or install-lifetime
 * identifier for a passive observer to correlate. Nothing is persisted.
 *
 * ### Publish throttling
 * A dense or hostile environment re-advertises many peers — including a flood of
 * *distinct* ephemeral IDs — many times a second. Re-sorting and republishing the
 * whole peer list on *every* packet would be O(n log n) work under [lock] per
 * advertisement, which a hostile advertiser could amplify into steady churn.
 * Instead, only the *first* peer that lifts the published list off empty is
 * surfaced immediately (for responsiveness when discovery has nothing yet); every
 * later sighting — whether a brand-new ID or a refresh of an existing peer — only
 * sets a dirty flag and is coalesced by a publish tick every [publishIntervalMillis].
 * The registry caps its own size, so a single tick flushes one bounded, sorted
 * list regardless of how many packets arrived. Once expiry empties the published
 * list, the next first peer again publishes immediately.
 *
 * ### Concurrency
 * Scan results, radio errors, and the expiry/publish ticks can all arrive on
 * different threads (the injected [scope] uses `Dispatchers.Default`, and the
 * platform radio invokes callbacks on its own binder threads). The controller is
 * *not* confined to a single thread, so every read and write of the mutable
 * discovery fields — [scanning], [radioActive], the [expiryJob]/[publishJob],
 * the [registry], the [pendingPublish] flag, and the state transition decisions
 * in [start]/[stop] — is serialised behind [lock]. The lock is reentrant, which
 * is what lets a radio that reports failure *synchronously* from within
 * [BleRadio.start] re-enter through [onRadioError] on the same thread without
 * deadlocking.
 *
 * ### Failure handling
 * All radio interaction crosses a `runCatching` boundary so that a
 * [SecurityException] or any other radio failure becomes a bounded [Error]
 * state and tears the radio down rather than crashing the caller. If the radio
 * fails synchronously during [start], the happy-path transition to [Scanning]
 * is skipped, so a synchronous error never masks itself behind a live scanning
 * state. Both [start] and [stop] are idempotent, and no scan callback mutates
 * state after [stop].
 */
class DefaultBleDiscoveryController(
    private val radio: BleRadio,
    private val registry: DiscoveredBlePeerRegistry,
    private val scope: CoroutineScope,
    private val beaconProvider: () -> BleBeacon,
    private val expiryIntervalMillis: Long = 5_000L,
    private val publishIntervalMillis: Long = 500L,
) : BleDiscoveryController {

    private val _state = MutableStateFlow<BleDiscoveryState>(BleDiscoveryState.Idle)
    override val state: StateFlow<BleDiscoveryState> = _state

    /** Guards all mutable discovery state; reentrant so synchronous radio
     * failure callbacks can re-enter on the calling thread. */
    private val lock = Any()

    /** True once the radio has been started and not yet stopped. */
    private var radioActive = false
    private var scanning = false
    private var expiryJob: Job? = null
    private var publishJob: Job? = null

    /** Set when a sighting is deferred (any sighting past the first); coalesced
     * by the publish tick. */
    private var pendingPublish = false

    /** True while the published Scanning list is non-empty. Lets the first peer
     * off an empty list publish immediately while later sightings coalesce; reset
     * whenever expiry empties the published list. */
    private var hasPublishedPeer = false

    override fun start() {
        synchronized(lock) {
            if (scanning) return

            if (!radio.isSupported) {
                _state.value = BleDiscoveryState.Unsupported
                return
            }
            val missing = radio.missingPermissions()
            if (missing.isNotEmpty()) {
                _state.value = BleDiscoveryState.PermissionRequired(missing)
                return
            }
            if (!radio.isEnabled()) {
                _state.value = BleDiscoveryState.BluetoothOff
                return
            }

            // Draw a fresh ephemeral identity for this session; see class note.
            val payload = BleDiscoveryProtocol.encode(beaconProvider())
            val started = runCatching {
                radioActive = true
                // May invoke onRadioError synchronously; the reentrant lock and
                // the radioActive guard below keep that path safe.
                radio.start(
                    serviceUuid = BleDiscoveryController.SERVICE_UUID,
                    payload = payload,
                    onResult = ::onScanResult,
                    onError = ::onRadioError,
                )
            }.isSuccess

            if (!started) {
                failAndStop()
                return
            }

            // If the radio reported failure synchronously, teardown already ran
            // and moved us to Error. Do not proceed onto the happy path.
            if (!radioActive) return

            scanning = true
            pendingPublish = false
            hasPublishedPeer = false
            publishScanning()
            startExpiryLoop()
            startPublishLoop()
        }
    }

    override fun stop() {
        synchronized(lock) {
            val hadWork = teardown()
            if (hadWork || _state.value != BleDiscoveryState.Idle) {
                _state.value = BleDiscoveryState.Idle
            }
        }
    }

    private fun onScanResult(result: BleScanResult) {
        synchronized(lock) {
            if (!scanning) return
            val beacon = BleDiscoveryProtocol.decode(result.payload) ?: return
            val wasInserted = registry.upsert(
                nodeId = beacon.nodeId,
                rssiDbm = result.rssiDbm,
                capabilities = beacon.capabilities,
            )
            // Only the first peer that lifts the list off empty is surfaced
            // immediately, for responsiveness. Every later sighting -- a new ID
            // or a refresh -- is deferred to the publish tick, so a flood of
            // distinct IDs cannot force a sort+emission per advertisement.
            if (wasInserted && !hasPublishedPeer) {
                pendingPublish = false
                publishScanning()
            } else {
                pendingPublish = true
            }
        }
    }

    private fun onRadioError(message: String) {
        synchronized(lock) {
            if (!radioActive) return
            teardown()
            _state.value = BleDiscoveryState.Error(bounded(message))
        }
    }

    private fun failAndStop() {
        teardown()
        _state.value = BleDiscoveryState.Error("BLE discovery could not start.")
    }

    private fun startExpiryLoop() {
        expiryJob = scope.launch {
            while (isActive) {
                delay(expiryIntervalMillis)
                synchronized(lock) {
                    registry.expire()
                    if (scanning) {
                        pendingPublish = false
                        publishScanning()
                    }
                }
            }
        }
    }

    private fun startPublishLoop() {
        publishJob = scope.launch {
            while (isActive) {
                delay(publishIntervalMillis)
                synchronized(lock) {
                    if (scanning && pendingPublish) {
                        pendingPublish = false
                        publishScanning()
                    }
                }
            }
        }
    }

    /**
     * Cancel the periodic loops and stop the radio (once). Returns true if any
     * work was actually torn down, so [stop] can distinguish a real stop from a
     * no-op. Callers must hold [lock].
     */
    private fun teardown(): Boolean {
        val hadWork = radioActive || scanning || expiryJob != null || publishJob != null
        scanning = false
        pendingPublish = false
        hasPublishedPeer = false
        expiryJob?.cancel()
        expiryJob = null
        publishJob?.cancel()
        publishJob = null
        if (radioActive) {
            radioActive = false
            runCatching { radio.stop() }
        }
        return hadWork
    }

    private fun publishScanning() {
        val peers = registry.activePeers()
        _state.value = BleDiscoveryState.Scanning(peers)
        // When expiry empties the list, allow the next first peer to publish
        // immediately again; otherwise keep coalescing subsequent sightings.
        hasPublishedPeer = peers.isNotEmpty()
    }

    private fun bounded(message: String): String {
        val safe = message.take(MAX_ERROR_LENGTH)
        return if (safe.isBlank()) "BLE discovery failed." else safe
    }

    private companion object {
        const val MAX_ERROR_LENGTH = 120
    }
}

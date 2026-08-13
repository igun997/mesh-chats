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
 * adapter state), then advertises the local [localBeacon] and scans filtered to
 * [BleDiscoveryController.SERVICE_UUID]. Scan results are decoded with the
 * fail-closed [BleDiscoveryProtocol]; malformed payloads are dropped. Discovered
 * peers are deduplicated by node ID in [registry] and expired on a periodic tick
 * driven by [expiryIntervalMillis] (injectable so coroutine tests are
 * deterministic).
 *
 * ### Concurrency
 * Scan results, radio errors, and expiry ticks can all arrive on different
 * threads (the injected [scope] uses `Dispatchers.Default`, and the platform
 * radio invokes callbacks on its own binder threads). The controller is *not*
 * confined to a single thread, so every read and write of the mutable discovery
 * fields — [scanning], [radioActive], the [expiryJob], the [registry], and the
 * state transition decisions in [start]/[stop] — is serialised behind [lock].
 * The lock is reentrant, which is what lets a radio that reports failure
 * *synchronously* from within [BleRadio.start] re-enter through [onRadioError]
 * on the same thread without deadlocking.
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
    private val localBeacon: BleBeacon,
    private val expiryIntervalMillis: Long = 5_000L,
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

            val payload = BleDiscoveryProtocol.encode(localBeacon)
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
            publishScanning()
            startExpiryLoop()
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
            registry.upsert(
                nodeId = beacon.nodeId,
                rssiDbm = result.rssiDbm,
                capabilities = beacon.capabilities,
            )
            publishScanning()
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
                    if (scanning) publishScanning()
                }
            }
        }
    }

    /**
     * Cancel the expiry loop and stop the radio (once). Returns true if any work
     * was actually torn down, so [stop] can distinguish a real stop from a no-op.
     * Callers must hold [lock].
     */
    private fun teardown(): Boolean {
        val hadWork = radioActive || scanning || expiryJob != null
        scanning = false
        expiryJob?.cancel()
        expiryJob = null
        if (radioActive) {
            radioActive = false
            runCatching { radio.stop() }
        }
        return hadWork
    }

    private fun publishScanning() {
        _state.value = BleDiscoveryState.Scanning(registry.activePeers())
    }

    private fun bounded(message: String): String {
        val safe = message.take(MAX_ERROR_LENGTH)
        return if (safe.isBlank()) "BLE discovery failed." else safe
    }

    private companion object {
        const val MAX_ERROR_LENGTH = 120
    }
}

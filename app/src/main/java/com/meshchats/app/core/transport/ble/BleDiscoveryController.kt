package com.meshchats.app.core.transport.ble

import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Drives BLE peer discovery and exposes it as observable [BleDiscoveryState].
 *
 * Implementations own the discovery lifecycle: checking preconditions, starting
 * the [BleRadio], deduplicating and expiring peers, and tearing everything down
 * on [stop]. Both [start] and [stop] are idempotent.
 */
interface BleDiscoveryController {

    /** The current discovery state; emits a new value on every transition. */
    val state: StateFlow<BleDiscoveryState>

    /** Begin discovery. No-op if already scanning. */
    fun start()

    /** Stop discovery and release the radio. No-op if not scanning. */
    fun stop()

    companion object {
        /**
         * Stable 128-bit service UUID for mesh-chats BLE discovery. Both the
         * advertisement and the scan filter use this; it is part of the wire
         * contract and must not change across releases.
         */
        val SERVICE_UUID: UUID = UUID.fromString("6d657368-6368-4174-8100-000000000001")
    }
}

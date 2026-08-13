package com.meshchats.app.core.transport.ble

import java.util.UUID

/**
 * A scan result surfaced by [BleRadio]. Carries only the advertised service-data
 * [payload] and signal strength; the Bluetooth device address is deliberately
 * never exposed, since it rotates and must not be treated as identity.
 */
data class BleScanResult(
    val payload: ByteArray,
    val rssiDbm: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleScanResult) return false
        return rssiDbm == other.rssiDbm && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * payload.contentHashCode() + rssiDbm
}

/**
 * Thin boundary over the platform BLE advertiser and scanner. The controller
 * owns all discovery policy; a [BleRadio] only starts/stops the hardware and
 * pumps results back through the supplied callbacks. This keeps the Android
 * dependency at the edge and lets the controller be tested with a fake.
 */
interface BleRadio {

    /** True if this device can both advertise and scan BLE. */
    val isSupported: Boolean

    /** True if the Bluetooth adapter is currently on. */
    fun isEnabled(): Boolean

    /** Runtime permissions still missing for discovery; empty when all granted. */
    fun missingPermissions(): Set<String>

    /**
     * Begin advertising [payload] under [serviceUuid] and scanning for peers
     * filtered to the same UUID.
     *
     * @param onResult invoked for each scan result while scanning.
     * @param onError invoked at most once if the radio fails (advertise or scan
     *   failure); the radio is considered stopped after this fires.
     */
    fun start(
        serviceUuid: UUID,
        payload: ByteArray,
        onResult: (BleScanResult) -> Unit,
        onError: (String) -> Unit,
    )

    /** Stop advertising and scanning. Safe to call when not started. */
    fun stop()
}

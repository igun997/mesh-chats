package com.meshchats.app.core.transport.ble

import android.Manifest
import android.os.Build

/**
 * Pure policy that maps a platform SDK level to the set of runtime permissions
 * required for BLE discovery (scanning + advertising).
 *
 * The matrix mirrors the manifest declarations:
 * - API 31 (Android 12, [Build.VERSION_CODES.S]) and above use the granular
 *   Bluetooth runtime permissions and no longer need location for BLE.
 * - API 26..30 must request [Manifest.permission.ACCESS_FINE_LOCATION] because
 *   BLE scan results are gated behind location on those releases.
 *
 * This function is deterministic and side-effect free so it can be unit tested
 * across SDK boundaries without a device.
 */
object BlePermissionPolicy {

    fun requiredPermissions(sdkInt: Int): Set<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            setOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            setOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
}

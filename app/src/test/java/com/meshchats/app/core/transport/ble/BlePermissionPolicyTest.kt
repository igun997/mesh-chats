package com.meshchats.app.core.transport.ble

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class BlePermissionPolicyTest {

    @Test
    fun `API 31 requires the runtime bluetooth permissions`() {
        assertEquals(
            setOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
            BlePermissionPolicy.requiredPermissions(31),
        )
    }

    @Test
    fun `target sdk 37 uses the nearby-device permissions`() {
        assertEquals(
            setOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
            BlePermissionPolicy.requiredPermissions(37),
        )
    }

    @Test
    fun `API 30 boundary requires fine location`() {
        assertEquals(
            setOf(Manifest.permission.ACCESS_FINE_LOCATION),
            BlePermissionPolicy.requiredPermissions(30),
        )
    }

    @Test
    fun `API 26 minimum requires fine location`() {
        assertEquals(
            setOf(Manifest.permission.ACCESS_FINE_LOCATION),
            BlePermissionPolicy.requiredPermissions(26),
        )
    }
}

package com.meshchats.app.core.transport.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleAdvertisementBudgetTest {

    @Test
    fun `legacy limit is 31 bytes`() {
        assertEquals(31, BleAdvertisementBudget.LEGACY_MAX_BYTES)
    }

    @Test
    fun `assembled size accounts for flags service-data header and 128-bit uuid`() {
        // flags AD (3) + service-data header (2) + 128-bit UUID (16) + payload.
        assertEquals(21 + 10, BleAdvertisementBudget.assembledSize(10))
        assertEquals(21, BleAdvertisementBudget.assembledSize(0))
    }

    @Test
    fun `current discovery payload fits exactly at the legacy limit`() {
        val payloadSize = BleDiscoveryProtocol.PAYLOAD_SIZE
        assertEquals(31, BleAdvertisementBudget.assembledSize(payloadSize))
        assertTrue(BleAdvertisementBudget.fitsLegacy(payloadSize))
    }

    @Test
    fun `oversized payload does not fit`() {
        assertFalse(BleAdvertisementBudget.fitsLegacy(BleDiscoveryProtocol.PAYLOAD_SIZE + 1))
    }
}

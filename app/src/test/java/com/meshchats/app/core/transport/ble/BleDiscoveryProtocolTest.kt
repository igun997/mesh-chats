package com.meshchats.app.core.transport.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleDiscoveryProtocolTest {

    @Test
    fun `encode produces fixed-size payload with version and capability bits`() {
        val beacon = BleBeacon(
            nodeId = 0x0102030405060708L,
            capabilities = setOf(BleCapability.CHAT, BleCapability.RELAY),
        )

        val payload = BleDiscoveryProtocol.encode(beacon)

        assertEquals(BleDiscoveryProtocol.PAYLOAD_SIZE, payload.size)
        assertEquals(BleDiscoveryProtocol.VERSION, payload[0])
        // chat=1 | relay=2 => 3
        assertEquals(3.toByte(), payload[1])
        // node id big-endian in bytes 2..9
        assertArrayEquals(
            byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08),
            payload.copyOfRange(2, 10),
        )
    }

    @Test
    fun `encode then decode round-trips node id and capabilities`() {
        val beacon = BleBeacon(
            nodeId = -1L, // all bits set, exercises unsigned handling
            capabilities = setOf(
                BleCapability.CHAT,
                BleCapability.RELAY,
                BleCapability.LORA,
                BleCapability.SOS,
            ),
        )

        val decoded = BleDiscoveryProtocol.decode(BleDiscoveryProtocol.encode(beacon))

        assertEquals(beacon, decoded)
    }

    @Test
    fun `encode then decode round-trips an empty capability set`() {
        val beacon = BleBeacon(nodeId = 42L, capabilities = emptySet())

        val decoded = BleDiscoveryProtocol.decode(BleDiscoveryProtocol.encode(beacon))

        assertEquals(beacon, decoded)
    }

    @Test
    fun `decode rejects payload with wrong length`() {
        assertNull(BleDiscoveryProtocol.decode(ByteArray(BleDiscoveryProtocol.PAYLOAD_SIZE - 1)))
        assertNull(BleDiscoveryProtocol.decode(ByteArray(BleDiscoveryProtocol.PAYLOAD_SIZE + 1)))
        assertNull(BleDiscoveryProtocol.decode(ByteArray(0)))
    }

    @Test
    fun `decode rejects unknown protocol version`() {
        val payload = BleDiscoveryProtocol.encode(BleBeacon(1L, setOf(BleCapability.CHAT)))
        payload[0] = 2 // bump version

        assertNull(BleDiscoveryProtocol.decode(payload))
    }

    @Test
    fun `decode rejects unknown capability bits fail-closed`() {
        val payload = BleDiscoveryProtocol.encode(BleBeacon(1L, setOf(BleCapability.CHAT)))
        payload[1] = 0x10 // bit outside chat/relay/lora/sos (1|2|4|8 = 0x0F)

        assertNull(BleDiscoveryProtocol.decode(payload))
    }

    @Test
    fun `decode rejects capability byte mixing known and unknown bits`() {
        val payload = BleDiscoveryProtocol.encode(BleBeacon(1L, setOf(BleCapability.CHAT)))
        payload[1] = (0x01 or 0x80).toByte() // chat + unknown high bit

        assertNull(BleDiscoveryProtocol.decode(payload))
    }
}

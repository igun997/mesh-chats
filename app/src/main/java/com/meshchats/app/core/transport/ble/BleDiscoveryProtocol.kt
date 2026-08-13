package com.meshchats.app.core.transport.ble

/**
 * A capability a node advertises over BLE discovery. Each entry owns a single
 * bit inside the beacon's capability byte. Bits are part of the wire format and
 * must never be renumbered.
 */
enum class BleCapability(val bit: Int) {
    CHAT(1),
    RELAY(2),
    LORA(4),
    SOS(8),
}

/**
 * A decoded BLE discovery beacon. [nodeId] is an ephemeral, unsigned 64-bit
 * identifier; it is deliberately not the Bluetooth MAC address, which can
 * rotate and must never be treated as identity.
 */
data class BleBeacon(
    val nodeId: Long,
    val capabilities: Set<BleCapability>,
)

/**
 * Fixed-layout codec for the BLE discovery advertisement payload.
 *
 * ```text
 * byte 0     protocol version = 1
 * byte 1     capability bits: chat=1, relay=2, lora=4, sos=8
 * bytes 2-9  unsigned 64-bit ephemeral node ID, big-endian
 * ```
 *
 * The codec is fail-closed: [decode] returns null for any payload whose length,
 * version, or capability bits it does not recognise, rather than guessing.
 */
object BleDiscoveryProtocol {
    const val VERSION: Byte = 1
    const val PAYLOAD_SIZE = 10

    private const val VERSION_INDEX = 0
    private const val CAPABILITY_INDEX = 1
    private const val NODE_ID_INDEX = 2

    /** Mask of every capability bit this protocol version understands. */
    private val KNOWN_CAPABILITY_MASK: Int =
        BleCapability.entries.fold(0) { acc, capability -> acc or capability.bit }

    fun encode(beacon: BleBeacon): ByteArray {
        val payload = ByteArray(PAYLOAD_SIZE)
        payload[VERSION_INDEX] = VERSION
        payload[CAPABILITY_INDEX] =
            beacon.capabilities.fold(0) { acc, capability -> acc or capability.bit }.toByte()
        var nodeId = beacon.nodeId
        for (i in 7 downTo 0) {
            payload[NODE_ID_INDEX + i] = (nodeId and 0xFF).toByte()
            nodeId = nodeId ushr 8
        }
        return payload
    }

    fun decode(payload: ByteArray): BleBeacon? {
        if (payload.size != PAYLOAD_SIZE) return null
        if (payload[VERSION_INDEX] != VERSION) return null

        val capabilityBits = payload[CAPABILITY_INDEX].toInt() and 0xFF
        if (capabilityBits and KNOWN_CAPABILITY_MASK.inv() != 0) return null
        val capabilities = BleCapability.entries
            .filter { capabilityBits and it.bit != 0 }
            .toSet()

        var nodeId = 0L
        for (i in 0 until 8) {
            nodeId = (nodeId shl 8) or (payload[NODE_ID_INDEX + i].toLong() and 0xFF)
        }

        return BleBeacon(nodeId = nodeId, capabilities = capabilities)
    }
}

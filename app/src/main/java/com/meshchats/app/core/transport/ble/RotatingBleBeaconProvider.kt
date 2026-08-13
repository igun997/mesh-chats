package com.meshchats.app.core.transport.ble

/**
 * Supplies a fresh [BleBeacon] for each discovery session so the advertised
 * node ID rotates every time scanning (re)starts.
 *
 * ### Why rotate
 * The advertised [BleBeacon.nodeId] is the only stable handle a passive
 * observer can correlate across time. Drawing a new random ID on every
 * foreground scan session means a device that leaves and returns looks like a
 * new node, so there is no process- or install-lifetime identifier to track. No
 * ID is persisted; identity here is deliberately ephemeral until a real
 * identity/key exchange lands.
 *
 * ### Thread safety
 * [invoke] is `@Synchronized`, so the mutable [lastNodeId] collision guard is
 * safe when discovery (re)starts from different threads (e.g. a UI action versus
 * a radio callback thread).
 *
 * [randomLong] is injected (production wires a [java.security.SecureRandom]) so
 * tests can drive a deterministic sequence and exercise collision handling. On
 * the vanishingly unlikely chance a draw repeats the previously issued ID, the
 * provider re-draws (bounded by [MAX_ATTEMPTS]) so two consecutive sessions
 * never collide.
 */
class RotatingBleBeaconProvider(
    private val capabilities: Set<BleCapability> = setOf(BleCapability.CHAT, BleCapability.SOS),
    private val randomLong: () -> Long,
) : () -> BleBeacon {

    private var lastNodeId: Long? = null

    @Synchronized
    override fun invoke(): BleBeacon {
        var nodeId = randomLong()
        var attempts = 1
        while (nodeId == lastNodeId && attempts < MAX_ATTEMPTS) {
            nodeId = randomLong()
            attempts++
        }
        lastNodeId = nodeId
        return BleBeacon(nodeId = nodeId, capabilities = capabilities)
    }

    private companion object {
        const val MAX_ATTEMPTS = 8
    }
}

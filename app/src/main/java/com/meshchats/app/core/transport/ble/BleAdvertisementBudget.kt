package com.meshchats.app.core.transport.ble

/**
 * Pure size accounting for the legacy (pre-extended) BLE advertisement packet.
 *
 * A legacy advertising PDU carries at most [LEGACY_MAX_BYTES] bytes of AD
 * structures. The discovery advertisement uses exactly two structures:
 *
 * ```text
 * Flags               : 1 len + 1 type + 1 value            = 3 bytes
 * Service Data (128b) : 1 len + 1 type + 16 UUID + N payload = 18 + N bytes
 * ```
 *
 * so the assembled size is `FIXED_OVERHEAD + payload.size`. Advertising the
 * 128-bit service UUID *as well* would add another 2 + 16 bytes and blow the
 * budget, which is why the radio advertises service data only and filters scans
 * on service data instead of the UUID list.
 *
 * This object is deliberately platform-free so the budget can be asserted in a
 * plain unit test and checked before any platform advertise call.
 */
object BleAdvertisementBudget {

    /** Maximum AD-structure bytes in a legacy advertising PDU. */
    const val LEGACY_MAX_BYTES = 31

    /** Flags AD structure: length + type + value. */
    private const val FLAGS_AD_BYTES = 3

    /** Service-data AD header: length + type + 128-bit UUID (16 bytes). */
    private const val SERVICE_DATA_HEADER_BYTES = 2 + 16

    /** Bytes consumed before any payload is added. */
    private const val FIXED_OVERHEAD = FLAGS_AD_BYTES + SERVICE_DATA_HEADER_BYTES

    /** Total assembled advertisement size for a service-data [payloadSize]. */
    fun assembledSize(payloadSize: Int): Int = FIXED_OVERHEAD + payloadSize

    /** True if the assembled advertisement fits inside the legacy limit. */
    fun fitsLegacy(payloadSize: Int): Boolean =
        assembledSize(payloadSize) <= LEGACY_MAX_BYTES
}

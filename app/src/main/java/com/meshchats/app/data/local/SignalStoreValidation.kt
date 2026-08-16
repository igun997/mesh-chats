package com.meshchats.app.data.local

/**
 * Fail-fast bounds for the values the blocking libsignal Room store accepts,
 * checked *before* any database access. Kept as a pure, Room-free object so the
 * bounds can be proven in fast JVM tests and reused verbatim inside
 * [RoomSignalProtocolStore].
 *
 * These guard against caller bugs (non-positive ids, empty/unbounded address
 * names) reaching an encrypted row; they are not a trust or authorization check.
 * Protocol-address names are the app's stable identity fingerprints plus a device
 * id — never rotating BLE ids — so the ceiling is generous but finite.
 *
 * ## Positive key-id invariant (app-wide)
 * Pre/signed/Kyber key ids are required to be **strictly positive** here, and this
 * is a hard invariant across the whole app, not merely defensive input scrubbing.
 * Two reasons make it load-bearing:
 *  1. Each `signal_prekeys` / `signal_signed_prekeys` / `signal_kyber_prekeys`
 *     table uses the id as an `INTEGER PRIMARY KEY`, which SQLite aliases to the
 *     table's `rowid`. A non-positive id would collide with SQLite's automatic
 *     rowid assignment space (rowids are positive and auto-filled when absent) and
 *     invites accidental row aliasing. Constraining ids to `1..Int.MAX_VALUE`
 *     keeps every stored id inside the positive rowid range and decoupled from any
 *     auto-generated value.
 *  2. libsignal itself only ever mints positive pre/signed/Kyber key ids.
 * Consequently the Task 4 key generator MUST draw ids from `1..Int.MAX_VALUE`
 * (positive only) — never 0 or negative — so generated keys satisfy this store's
 * contract and the rowid coupling above.
 */
object SignalStoreValidation {

    /**
     * Upper bound on a protocol-address `name`. The app's stable identity address
     * is a hex/base fingerprint well under this; the ceiling refuses an unbounded
     * blob without constraining any legitimate identity string.
     */
    const val MAX_NAME_LENGTH: Int = 256

    /**
     * @throws IllegalArgumentException if [name] is empty or exceeds
     *   [MAX_NAME_LENGTH].
     */
    fun requireValidAddressName(name: String) {
        require(name.isNotEmpty()) { "address name must not be empty" }
        require(name.length <= MAX_NAME_LENGTH) {
            "address name exceeds $MAX_NAME_LENGTH chars, was ${name.length}"
        }
    }

    /**
     * @throws IllegalArgumentException if [deviceId] is not strictly positive.
     *   libsignal device ids start at 1 (the primary device); 0 and negatives are
     *   never valid.
     */
    fun requireValidDeviceId(deviceId: Int) {
        require(deviceId > 0) { "device id must be positive, was $deviceId" }
    }

    /**
     * @throws IllegalArgumentException if [keyId] is not strictly positive.
     *   libsignal pre/signed/Kyber key ids are positive in-range integers, and the
     *   id is the table's `INTEGER PRIMARY KEY` (SQLite `rowid`) — see the class
     *   KDoc for why the positive range is load-bearing, not just defensive.
     */
    fun requireValidKeyId(keyId: Int) {
        require(keyId > 0) { "key id must be positive, was $keyId" }
    }
}

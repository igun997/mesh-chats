package com.meshchats.app.crypto.prekey

/**
 * Bounded, documented inventory targets for the device's PQXDH prekey pools.
 *
 * These constants are the single source of truth for "how many keys should a
 * healthy device keep on hand" and for the hysteresis that governs replenishment.
 * They are deliberately modest: this is a peer-to-peer mesh where each device
 * publishes its own bundle directly, not a server distributing thousands of
 * one-time keys, so a small pool covers many concurrent first-contacts without
 * bloating the encrypted database.
 *
 * ## Replenishment hysteresis
 * A pool is refilled back to its `*_TARGET` only once its unused count drops
 * **strictly below** the `*_THRESHOLD`. Between threshold and target no work is
 * done, so a device that consumes a single key does not regenerate on the next
 * ensure — avoiding churn while still guaranteeing a floor of available keys.
 *
 * ## Signed / last-resort
 * Exactly one active signed EC prekey and exactly one reusable last-resort
 * Kyber-1024 prekey are maintained. Both are singletons here; rotation of the
 * signed key is scheduled in a later task, and the last-resort Kyber key is
 * reusable by design (it may be published and used repeatedly).
 */
object PreKeyInventoryTargets {

    /** Target number of unused one-time EC prekeys to keep on hand. */
    const val EC_ONE_TIME_TARGET: Int = 32

    /** Refill EC one-time prekeys once the unused count drops below this. */
    const val EC_ONE_TIME_THRESHOLD: Int = 10

    /** Target number of unused one-time Kyber-1024 prekeys to keep on hand. */
    const val KYBER_ONE_TIME_TARGET: Int = 16

    /** Refill one-time Kyber prekeys once the unused count drops below this. */
    const val KYBER_ONE_TIME_THRESHOLD: Int = 5

    /** Exactly one reusable last-resort Kyber-1024 prekey is maintained. */
    const val LAST_RESORT_KYBER_TARGET: Int = 1

    /** Exactly one active signed EC prekey is maintained. */
    const val SIGNED_TARGET: Int = 1

    init {
        require(EC_ONE_TIME_THRESHOLD in 1 until EC_ONE_TIME_TARGET)
        require(KYBER_ONE_TIME_THRESHOLD in 1 until KYBER_ONE_TIME_TARGET)
    }
}

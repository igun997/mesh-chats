package com.meshchats.app.data.local

/**
 * Size bounds for a serialized libsignal `ECPublicKey` base key persisted as
 * replay state in `signal_kyber_base_keys`. Kept as a pure, Room-free object so
 * the bound can be proven in fast JVM tests and reused verbatim inside the
 * transactional DAO method before any row is inserted.
 *
 * A Curve25519 `ECPublicKey.serialize()` is 33 bytes (a 1-byte type prefix plus
 * the 32-byte point). The ceiling leaves generous headroom for format evolution
 * while still refusing to let an unbounded blob reach the table — the base key is
 * caller-supplied bytes and must never be stored unbounded.
 */
object SignalKyberBaseKeyBounds {

    /**
     * Hard ceiling on the serialized base-key blob. Comfortably above the 33-byte
     * Curve25519 public key, small enough that a malformed/oversized input is
     * rejected rather than persisted.
     */
    const val MAX_BASE_KEY_BYTES: Int = 128

    /**
     * @throws IllegalArgumentException if [baseKey] is empty or exceeds
     *   [MAX_BASE_KEY_BYTES]. Returns normally when the blob is in bounds.
     */
    fun requireValid(baseKey: ByteArray) {
        require(baseKey.isNotEmpty()) { "base key must not be empty" }
        require(baseKey.size <= MAX_BASE_KEY_BYTES) {
            "base key exceeds $MAX_BASE_KEY_BYTES bytes, was ${baseKey.size}"
        }
    }
}

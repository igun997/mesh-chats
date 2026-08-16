package com.meshchats.app.crypto.session

import java.util.Base64

/**
 * Derives the app's stable libsignal protocol-address `name` from a device's
 * authoritative Ed25519 identity fingerprint.
 *
 * The name is `"mc1:"` + base64url-no-padding of the **full 32-byte** SHA-256
 * fingerprint. It is derived ONLY from the full fingerprint — never from the
 * four-word display (which encodes only the first 44 bits and is not
 * collision-safe), a rotating BLE id, or a user-facing display name — so two
 * distinct identities can never collide onto one protocol address and an address
 * can never be spoofed by matching only the short display.
 *
 * The `mc1:` prefix versions the scheme so a future derivation change is
 * distinguishable at the address level. base64url (no `+`, `/`, or `=`) keeps the
 * name within [com.meshchats.app.data.local.SignalStoreValidation]'s bounded,
 * non-empty address contract and free of characters that need escaping.
 */
object SignalProtocolName {

    /** Length in bytes of the authoritative SHA-256 identity fingerprint. */
    const val FINGERPRINT_BYTES: Int = 32

    /** Scheme/version prefix on every derived protocol name. */
    const val PREFIX: String = "mc1:"

    /**
     * Returns the canonical protocol name for [fingerprintSha256].
     *
     * @throws IllegalArgumentException if [fingerprintSha256] is not exactly
     *   [FINGERPRINT_BYTES] bytes — a short or oversized fingerprint is never
     *   silently truncated or padded into an address.
     */
    fun fromFingerprint(fingerprintSha256: ByteArray): String {
        require(fingerprintSha256.size == FINGERPRINT_BYTES) {
            "fingerprint must be $FINGERPRINT_BYTES bytes, was ${fingerprintSha256.size}"
        }
        // Encode a defensive copy so a concurrent mutation of the caller's array
        // cannot change the derived name mid-encode.
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(fingerprintSha256.copyOf())
        return PREFIX + encoded
    }
}

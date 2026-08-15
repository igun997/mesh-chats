package com.meshchats.app.crypto.identity

import java.security.SecureRandom

/**
 * A pure-JVM [SignalIdentityFactory] standing in for libsignal, whose native
 * library is unavailable on the host JVM. It models the contract faithfully:
 *
 * - a "serialized key pair" is a random opaque blob whose first 32 bytes are the
 *   "public identity bytes" (so parse can recover the same public deterministically);
 * - [create] draws a registration id in the same 14-bit range libsignal uses;
 * - [parse] recovers the public bytes from a stored blob, or fails if the blob is
 *   too short to be a valid serialized pair.
 *
 * Real registration-id range and real libsignal serialization are exercised in the
 * on-device instrumented tests.
 */
class FakeSignalIdentityFactory(
    private val random: SecureRandom = SecureRandom(),
) : SignalIdentityFactory {

    /** When true, [create] reports a generation failure. */
    var failCreate: Boolean = false

    /** When set, [parse] fails for any blob equal to this (simulated corruption). */
    var failParse: Boolean = false

    private companion object {
        const val PUBLIC_PREFIX = 33 // libsignal identity public key is 33 bytes (type + 32)
        const val PAIR_SIZE = 64
        const val REG_ID_BOUND = 1 shl 14 // libsignal registration ids are 14-bit
    }

    override fun create(): SignalIdentityResult {
        if (failCreate) return SignalIdentityResult.Failure(SignalIdentityError.GENERATE_FAILED)
        val pair = ByteArray(PAIR_SIZE).also { random.nextBytes(it) }
        val regId = 1 + random.nextInt(REG_ID_BOUND - 1)
        return SignalIdentityResult.Success(
            SignalLocalIdentity(
                registrationId = regId,
                serializedKeyPair = pair,
                publicIdentityBytes = pair.copyOfRange(0, PUBLIC_PREFIX),
            ),
        )
    }

    override fun parse(serializedKeyPair: ByteArray, registrationId: Int): SignalIdentityResult {
        if (failParse || serializedKeyPair.size < PUBLIC_PREFIX) {
            return SignalIdentityResult.Failure(SignalIdentityError.PARSE_FAILED)
        }
        return SignalIdentityResult.Success(
            SignalLocalIdentity(
                registrationId = registrationId,
                serializedKeyPair = serializedKeyPair,
                publicIdentityBytes = serializedKeyPair.copyOfRange(0, PUBLIC_PREFIX),
            ),
        )
    }
}

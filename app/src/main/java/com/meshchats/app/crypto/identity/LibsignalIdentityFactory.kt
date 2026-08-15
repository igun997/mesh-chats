package com.meshchats.app.crypto.identity

import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.util.KeyHelper

/**
 * Production [SignalIdentityFactory] backed by the official libsignal 0.100.0 API.
 *
 * [create] generates a fresh `IdentityKeyPair` and a cryptographically random,
 * in-range registration id via `KeyHelper.generateRegistrationId(false)` — the
 * `false` selects the standard (non-extended) 14-bit range libsignal defines for
 * registration ids, i.e. `[1, 16380]`. The serialized key pair (which contains
 * the Signal private key) is returned opaque and is stored only inside the
 * SQLCipher-encrypted database and the device-key-wrapped identity secret; it is
 * never logged or stringified.
 *
 * [parse] reconstructs an `IdentityKeyPair` from its serialized form to prove a
 * stored blob is intact and to recover the public identity bytes for the binding
 * check on reopen.
 *
 * libsignal's native library is unavailable on the host JVM, so this class is
 * covered by the on-device instrumented tests; the platform-free repository logic
 * is covered on the JVM with [FakeSignalIdentityFactory].
 */
class LibsignalIdentityFactory : SignalIdentityFactory {

    override fun create(): SignalIdentityResult {
        return try {
            val keyPair = IdentityKeyPair.generate()
            // false = standard registration-id range [1, 16380]; never 0.
            val registrationId = KeyHelper.generateRegistrationId(false)
            SignalIdentityResult.Success(
                SignalLocalIdentity(
                    registrationId = registrationId,
                    serializedKeyPair = keyPair.serialize(),
                    publicIdentityBytes = keyPair.publicKey.serialize(),
                ),
            )
        } catch (_: Throwable) {
            SignalIdentityResult.Failure(SignalIdentityError.GENERATE_FAILED)
        }
    }

    override fun parse(serializedKeyPair: ByteArray, registrationId: Int): SignalIdentityResult {
        return try {
            val keyPair = IdentityKeyPair(serializedKeyPair)
            SignalIdentityResult.Success(
                SignalLocalIdentity(
                    registrationId = registrationId,
                    serializedKeyPair = serializedKeyPair,
                    publicIdentityBytes = keyPair.publicKey.serialize(),
                ),
            )
        } catch (_: Throwable) {
            SignalIdentityResult.Failure(SignalIdentityError.PARSE_FAILED)
        }
    }
}

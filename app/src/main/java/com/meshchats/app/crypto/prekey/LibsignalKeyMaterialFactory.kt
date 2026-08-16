package com.meshchats.app.crypto.prekey

import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * Production [SignalKeyMaterialFactory] backed by the exact libsignal 0.100.0 API
 * (`javap`-verified signatures):
 *
 *  - `ECKeyPair.generate()` → `PreKeyRecord(int, ECKeyPair)` for one-time EC keys;
 *  - `SignedPreKeyRecord(int, long, ECKeyPair, byte[])` with the signature computed
 *    as `identityPrivate.calculateSignature(signedPublic.serialize())`;
 *  - `KEMKeyPair.generate(KEMKeyType.KYBER_1024)` → `KyberPreKeyRecord(int, long,
 *    KEMKeyPair, byte[])` with the signature over `kemPublic.serialize()`.
 *
 * Signatures are verified with `ECPublicKey.verifySignature(message, signature)`
 * against the identity public key **before** any record is returned, so a bad
 * signature never reaches storage or a published bundle.
 *
 * ## Secret handling
 * The Signal identity private key is parsed transiently from the opaque
 * `IdentityKeyPair.serialize()` blob only to compute a signature and is never
 * copied out, logged, or stringified. Generated record blobs contain private key
 * material and are returned opaque for the manager to persist inside the
 * SQLCipher-encrypted database; this class logs nothing.
 *
 * libsignal's native library is unavailable on the host JVM, so this class is
 * covered by the on-device instrumented prekey tests; the app-owned manager and
 * planner logic are covered on the JVM with fakes.
 */
class LibsignalKeyMaterialFactory : SignalKeyMaterialFactory {

    override fun generateOneTimeEcPreKey(id: Int): GeneratedPreKeyResult {
        return try {
            val keyPair = ECKeyPair.generate()
            val record = PreKeyRecord(id, keyPair)
            GeneratedPreKeyResult.Success(
                GeneratedPreKey(
                    id = id,
                    serialized = record.serialize(),
                    publicKey = keyPair.publicKey.serialize(),
                    signature = null,
                ),
            )
        } catch (_: Exception) {
            GeneratedPreKeyResult.Failure(SignalKeyMaterialError.GENERATE_FAILED)
        }
    }

    override fun generateSignedPreKey(id: Int, timestamp: Long, identityKeyPair: ByteArray): GeneratedPreKeyResult {
        val identityPrivate = parseIdentityPrivate(identityKeyPair)
            ?: return GeneratedPreKeyResult.Failure(SignalKeyMaterialError.PARSE_FAILED)
        return try {
            val keyPair = ECKeyPair.generate()
            val publicBytes = keyPair.publicKey.serialize()
            val signature = try {
                identityPrivate.calculateSignature(publicBytes)
            } catch (_: Exception) {
                return GeneratedPreKeyResult.Failure(SignalKeyMaterialError.SIGN_FAILED)
            }
            if (!verifyWithIdentity(identityKeyPair, publicBytes, signature)) {
                return GeneratedPreKeyResult.Failure(SignalKeyMaterialError.SIGNATURE_INVALID)
            }
            val record = SignedPreKeyRecord(id, timestamp, keyPair, signature)
            GeneratedPreKeyResult.Success(
                GeneratedPreKey(
                    id = id,
                    serialized = record.serialize(),
                    publicKey = publicBytes,
                    signature = signature,
                ),
            )
        } catch (_: Exception) {
            GeneratedPreKeyResult.Failure(SignalKeyMaterialError.GENERATE_FAILED)
        }
    }

    override fun generateKyberPreKey(
        id: Int,
        timestamp: Long,
        identityKeyPair: ByteArray,
        lastResort: Boolean,
    ): GeneratedPreKeyResult {
        val identityPrivate = parseIdentityPrivate(identityKeyPair)
            ?: return GeneratedPreKeyResult.Failure(SignalKeyMaterialError.PARSE_FAILED)
        return try {
            val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val publicBytes = keyPair.publicKey.serialize()
            val signature = try {
                identityPrivate.calculateSignature(publicBytes)
            } catch (_: Exception) {
                return GeneratedPreKeyResult.Failure(SignalKeyMaterialError.SIGN_FAILED)
            }
            if (!verifyWithIdentity(identityKeyPair, publicBytes, signature)) {
                return GeneratedPreKeyResult.Failure(SignalKeyMaterialError.SIGNATURE_INVALID)
            }
            val record = KyberPreKeyRecord(id, timestamp, keyPair, signature)
            GeneratedPreKeyResult.Success(
                GeneratedPreKey(
                    id = id,
                    serialized = record.serialize(),
                    publicKey = publicBytes,
                    signature = signature,
                ),
            )
        } catch (_: Exception) {
            GeneratedPreKeyResult.Failure(SignalKeyMaterialError.GENERATE_FAILED)
        }
    }

    override fun identityPublicBytes(identityKeyPair: ByteArray): IdentityPublicResult {
        return try {
            val pair = IdentityKeyPair(identityKeyPair)
            IdentityPublicResult.Success(pair.publicKey.serialize())
        } catch (_: Exception) {
            IdentityPublicResult.Failure(SignalKeyMaterialError.PARSE_FAILED)
        }
    }

    override fun readOneTimeEcPublic(recordBytes: ByteArray): PreKeyPublicResult {
        return try {
            val record = PreKeyRecord(recordBytes)
            PreKeyPublicResult.Success(
                PreKeyPublicMaterial(
                    id = record.id,
                    publicKey = record.keyPair.publicKey.serialize(),
                    signature = null,
                ),
            )
        } catch (_: Exception) {
            PreKeyPublicResult.Failure(SignalKeyMaterialError.PARSE_FAILED)
        }
    }

    override fun readSignedPublic(recordBytes: ByteArray, identityKeyPair: ByteArray): PreKeyPublicResult {
        return try {
            val record = SignedPreKeyRecord(recordBytes)
            val publicBytes = record.keyPair.publicKey.serialize()
            val signature = record.signature
            if (!verifyWithIdentity(identityKeyPair, publicBytes, signature)) {
                return PreKeyPublicResult.Failure(SignalKeyMaterialError.SIGNATURE_INVALID)
            }
            PreKeyPublicResult.Success(
                PreKeyPublicMaterial(id = record.id, publicKey = publicBytes, signature = signature),
            )
        } catch (_: Exception) {
            PreKeyPublicResult.Failure(SignalKeyMaterialError.PARSE_FAILED)
        }
    }

    override fun readKyberPublic(recordBytes: ByteArray, identityKeyPair: ByteArray): PreKeyPublicResult {
        return try {
            val record = KyberPreKeyRecord(recordBytes)
            val publicBytes = record.keyPair.publicKey.serialize()
            val signature = record.signature
            if (!verifyWithIdentity(identityKeyPair, publicBytes, signature)) {
                return PreKeyPublicResult.Failure(SignalKeyMaterialError.SIGNATURE_INVALID)
            }
            PreKeyPublicResult.Success(
                PreKeyPublicMaterial(id = record.id, publicKey = publicBytes, signature = signature),
            )
        } catch (_: Exception) {
            PreKeyPublicResult.Failure(SignalKeyMaterialError.PARSE_FAILED)
        }
    }

    /**
     * Parses the identity private key transiently from the opaque serialized key
     * pair. Returns null on any parse failure; the private key is used only to sign
     * and is never copied out or logged.
     */
    private fun parseIdentityPrivate(identityKeyPair: ByteArray): ECPrivateKey? =
        try {
            IdentityKeyPair(identityKeyPair).privateKey
        } catch (_: Exception) {
            null
        }

    /** Verifies [signature] over [message] against the identity public key in [identityKeyPair]. */
    private fun verifyWithIdentity(identityKeyPair: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        try {
            IdentityKeyPair(identityKeyPair).publicKey.publicKey.verifySignature(message, signature)
        } catch (_: Exception) {
            false
        }
}

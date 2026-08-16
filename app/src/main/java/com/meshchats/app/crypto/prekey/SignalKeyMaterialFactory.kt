package com.meshchats.app.crypto.prekey

/**
 * A freshly generated prekey ready to persist and (for signed / Kyber keys)
 * publish. [serialized] is the opaque libsignal record blob stored verbatim;
 * [publicKey] and [signature] are the public material a peer needs in the
 * published bundle. [signature] is null for unsigned one-time EC prekeys.
 *
 * The record blob may contain private key bytes; it is never logged or
 * stringified and lives only inside the SQLCipher-encrypted database.
 */
class GeneratedPreKey(
    val id: Int,
    val serialized: ByteArray,
    val publicKey: ByteArray,
    val signature: ByteArray?,
)

/**
 * Public material extracted from a stored prekey record for publication in a
 * bundle. [signature] is null for one-time EC prekeys and set for signed / Kyber
 * prekeys.
 */
class PreKeyPublicMaterial(
    val id: Int,
    val publicKey: ByteArray,
    val signature: ByteArray?,
)

/** A bounded reason a key-material operation failed. Never carries key bytes. */
enum class SignalKeyMaterialError {
    /** libsignal could not generate a fresh EC / KEM key pair or record. */
    GENERATE_FAILED,

    /** Signing the serialized public key with the Signal identity private key failed. */
    SIGN_FAILED,

    /** A freshly generated or stored signature did not verify against the identity key. */
    SIGNATURE_INVALID,

    /** A stored record or identity key pair blob could not be parsed. */
    PARSE_FAILED,
}

/** Result of generating a prekey. */
sealed interface GeneratedPreKeyResult {
    data class Success(val key: GeneratedPreKey) : GeneratedPreKeyResult
    data class Failure(val error: SignalKeyMaterialError) : GeneratedPreKeyResult
}

/** Result of reading public material from a stored record. */
sealed interface PreKeyPublicResult {
    data class Success(val material: PreKeyPublicMaterial) : PreKeyPublicResult
    data class Failure(val error: SignalKeyMaterialError) : PreKeyPublicResult
}

/** Result of reading the identity public bytes from a serialized identity key pair. */
sealed interface IdentityPublicResult {
    data class Success(val publicKey: ByteArray) : IdentityPublicResult
    data class Failure(val error: SignalKeyMaterialError) : IdentityPublicResult
}

/**
 * The libsignal boundary for PQXDH prekey generation. Every method takes and
 * returns only bytes and ids, so the app-owned [SignalPreKeyManager] never imports
 * a libsignal type. Implementations use the exact libsignal 0.100.0 APIs
 * (`ECKeyPair.generate`, `PreKeyRecord`, `SignedPreKeyRecord`, `KEMKeyPair.generate`,
 * `KyberPreKeyRecord`, `ECPrivateKey.calculateSignature`) and never log or
 * stringify any private key or record blob.
 *
 * Signed and Kyber prekeys are signed with the **Signal identity private key**
 * parsed from [identityKeyPair] (the opaque `IdentityKeyPair.serialize()` blob);
 * the signature is over the serialized public key, and implementations verify it
 * before returning so a bad signature never reaches storage or a bundle.
 */
interface SignalKeyMaterialFactory {

    /** Generates an unsigned one-time EC prekey with the given [id]. */
    fun generateOneTimeEcPreKey(id: Int): GeneratedPreKeyResult

    /**
     * Generates a signed EC prekey ([id], [timestamp]) whose serialized public key
     * is signed by the identity private key in [identityKeyPair]. The signature is
     * verified before return.
     */
    fun generateSignedPreKey(id: Int, timestamp: Long, identityKeyPair: ByteArray): GeneratedPreKeyResult

    /**
     * Generates a Kyber-1024 prekey ([id], [timestamp]) whose serialized public key
     * is signed by the identity private key in [identityKeyPair]. The signature is
     * verified before return. [lastResort] is metadata for the caller's store row;
     * generation itself is identical for one-time and last-resort keys.
     */
    fun generateKyberPreKey(id: Int, timestamp: Long, identityKeyPair: ByteArray, lastResort: Boolean): GeneratedPreKeyResult

    /** Public identity bytes (`IdentityKey.serialize()`) from a serialized identity key pair. */
    fun identityPublicBytes(identityKeyPair: ByteArray): IdentityPublicResult

    /** Reads the public key from a stored one-time EC prekey record (no signature). */
    fun readOneTimeEcPublic(recordBytes: ByteArray): PreKeyPublicResult

    /**
     * Reads public + signature from a stored signed EC prekey record and verifies
     * the signature against [identityKeyPair]'s public key before returning.
     */
    fun readSignedPublic(recordBytes: ByteArray, identityKeyPair: ByteArray): PreKeyPublicResult

    /**
     * Reads public + signature from a stored Kyber prekey record and verifies the
     * signature against [identityKeyPair]'s public key before returning.
     */
    fun readKyberPublic(recordBytes: ByteArray, identityKeyPair: ByteArray): PreKeyPublicResult
}

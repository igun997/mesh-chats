package com.meshchats.app.crypto.identity

/**
 * The device + Signal identity rows as they live in the database, in the
 * platform-free shapes the identity core reasons about. The Room adapter maps
 * these to/from `DeviceIdentityEntity` and `SignalIdentityEntity`.
 */
class StoredDeviceIdentity(
    val publicKeyX509: ByteArray,
    val fingerprintSha256: ByteArray,
    val createdAt: Long,
    val signalPublicBinding: ByteArray,
    val signalBindingSignature: ByteArray,
    val bindingVersion: Int,
)

class StoredSignalIdentity(
    val registrationId: Int,
    val serializedKeyPair: ByteArray,
    val schemaVersion: Int,
    val createdAt: Long,
)

/** Both identity rows read together. Null fields mean the row is absent. */
class StoredIdentity(
    val device: StoredDeviceIdentity?,
    val signal: StoredSignalIdentity?,
)

/** A bounded reason an identity-store operation failed. */
enum class IdentityStoreError {
    /** The underlying database was unavailable or the operation failed. */
    STORAGE_FAILED,
}

/** Result of reading both identity rows. */
sealed interface IdentityReadResult {
    data class Success(val identity: StoredIdentity) : IdentityReadResult
    data class Failure(val error: IdentityStoreError) : IdentityReadResult
}

/** Result of the atomic device+Signal insert. */
sealed interface IdentityWriteResult {
    data object Success : IdentityWriteResult
    data class Failure(val error: IdentityStoreError) : IdentityWriteResult
}

/**
 * Persists the device and Signal identity rows, always **together** in one
 * database transaction, and reads them back.
 *
 * A port so the identity core can be tested on the host JVM with an in-memory
 * fake, while production is backed by SQLCipher-encrypted Room. The Signal
 * serialized key pair (which contains the Signal private key) is stored only
 * inside the encrypted database via this port; the Ed25519 private key is never
 * given to this port.
 *
 * [insertBoth] must be atomic: on any failure neither row is written, so the DB
 * never lands in a half-created state that the crash-recovery protocol would have
 * to untangle.
 */
interface IdentityStore {

    /** Reads both singleton rows in one consistent read. */
    fun read(): IdentityReadResult

    /**
     * Atomically inserts the device identity row and the Signal identity row in a
     * single transaction. Either both land or neither does.
     */
    fun insertBoth(device: StoredDeviceIdentity, signal: StoredSignalIdentity): IdentityWriteResult
}

/**
 * Computes the identity fingerprint. A port only so tests can assert the exact
 * bytes without re-hashing; production is SHA-256.
 */
fun interface FingerprintHasher {
    /** Returns SHA-256 over [publicKeyX509]. */
    fun fingerprint(publicKeyX509: ByteArray): ByteArray

    companion object {
        /** Production SHA-256 hasher. */
        val Sha256: FingerprintHasher = FingerprintHasher { publicKeyX509 ->
            java.security.MessageDigest.getInstance("SHA-256").digest(publicKeyX509)
        }
    }
}

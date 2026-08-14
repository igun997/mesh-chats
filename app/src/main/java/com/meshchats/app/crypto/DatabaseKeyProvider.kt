package com.meshchats.app.crypto

import java.security.SecureRandom

/**
 * A bounded reason the database key could not be provided. Every failure is a
 * fail-closed outcome: the caller must not fall back to an unencrypted database
 * or silently regenerate a key when a wrapped file already exists, because that
 * would strand or destroy the encrypted data the lost key protects.
 */
enum class DatabaseKeyError {
    /**
     * A wrapped key file exists but its wrapping key is gone (device credential
     * reset, partial data clear). The encrypted database is unrecoverable; the
     * caller must surface this as key loss, never regenerate over it.
     */
    KEY_LOST,

    /**
     * A wrapped key file exists but failed authentication or structural checks —
     * it was tampered with or corrupted. Treated as hostile: never silently
     * replaced.
     */
    TAMPERED,

    /** The wrapping backend was unavailable (Keystore error, wrap/unwrap failure). */
    WRAPPER_UNAVAILABLE,

    /** The wrapped key file could not be read or durably written. */
    STORAGE_FAILED,
}

/** Result of obtaining the database key. [Success] carries freshly copied key bytes the caller owns. */
sealed interface DatabaseKeyResult {
    /**
     * The 32-byte database key. This is a defensive copy; the caller owns it and
     * must zero it after handing it to the storage layer.
     */
    data class Success(val key: ByteArray) : DatabaseKeyResult {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Success && key.contentEquals(other.key))

        override fun hashCode(): Int = key.contentHashCode()
    }

    data class Failure(val error: DatabaseKeyError) : DatabaseKeyResult
}

/**
 * Provides the 256-bit database encryption key, generating it exactly once and
 * persisting it wrapped by a non-exportable device key.
 *
 * Lifecycle:
 * - First call with no wrapped file: generate 32 [SecureRandom] bytes, wrap them
 *   via the [SecretWrapper] (binding [associatedData] for domain separation),
 *   durably store the wrapped record, and return a defensive copy of the key.
 * - Subsequent calls / new process: read the wrapped file and unwrap it.
 *
 * Fail-closed guarantees:
 * - If a wrapped file already exists, the key is **only** ever recovered by
 *   unwrapping it. A tamper or key-loss failure is reported as such and never
 *   triggers regeneration — regenerating would silently discard the encrypted
 *   database the old key protected.
 * - Generation is guarded by a lock so two threads racing on first launch produce
 *   and persist exactly one key.
 *
 * Secrets are never logged or stringified. Plaintext key bytes live only in
 * transient arrays that callers are expected to zero.
 */
class DatabaseKeyProvider(
    private val wrapper: SecretWrapper,
    private val file: AtomicSecretFile,
    private val secureRandom: SecureRandom = SecureRandom(),
) {

    private val lock = Any()

    /** Number of bytes in the database key (256-bit). */
    companion object {
        const val KEY_SIZE_BYTES: Int = 32

        /**
         * Domain-separation tag bound as AEAD associated data so a database-key
         * record can never be unwrapped as, say, an identity-key record.
         */
        private val ASSOCIATED_DATA: ByteArray = "mesh-chats/db-key/v1".toByteArray(Charsets.US_ASCII)
    }

    /**
     * Returns the database key, creating and persisting it once if absent.
     * Concurrency-safe: concurrent first-run callers serialize on an internal
     * lock so exactly one key is generated. Never throws; every failure is a
     * bounded [DatabaseKeyResult.Failure].
     */
    fun getOrCreateKey(): DatabaseKeyResult {
        synchronized(lock) {
            // If a wrapped record exists, it is authoritative. We must recover the
            // key from it and never regenerate, even on failure. This fast path
            // avoids taking the file lock when a key is already present.
            if (file.exists()) {
                return recoverExisting()
            }
            // No file yet: serialize first-creation across threads AND processes.
            // Another instance (even in another OS process) may create the file
            // while we wait for the lock, so re-check inside the critical section.
            return file.withCreationLock {
                if (file.exists()) {
                    recoverExisting()
                } else {
                    generateAndPersist()
                }
            }
        }
    }

    private fun recoverExisting(): DatabaseKeyResult {
        val record = when (val r = file.read()) {
            is SecretFileReadResult.Success -> r
            is SecretFileReadResult.Failure -> return when (r.error) {
                // A structurally corrupt file is tampering, not a reason to regenerate.
                SecretFileReadError.CORRUPT -> DatabaseKeyResult.Failure(DatabaseKeyError.TAMPERED)
                SecretFileReadError.UNSAFE_PATH -> DatabaseKeyResult.Failure(DatabaseKeyError.TAMPERED)
                SecretFileReadError.IO_FAILED -> DatabaseKeyResult.Failure(DatabaseKeyError.STORAGE_FAILED)
                // exists() said true but read said NOT_FOUND: a race/removal. Fail closed.
                SecretFileReadError.NOT_FOUND -> DatabaseKeyResult.Failure(DatabaseKeyError.STORAGE_FAILED)
            }
        }

        return when (val u = wrapper.unwrap(record.nonce, record.ciphertext, ASSOCIATED_DATA)) {
            is UnwrapResult.Success -> {
                // Validate size defensively; a wrong-sized plaintext means the record
                // is not a database key of the expected shape.
                if (u.plaintext.size != KEY_SIZE_BYTES) {
                    u.plaintext.fill(0)
                    return DatabaseKeyResult.Failure(DatabaseKeyError.TAMPERED)
                }
                val copy = u.plaintext.copyOf()
                u.plaintext.fill(0)
                DatabaseKeyResult.Success(copy)
            }
            is UnwrapResult.Failure -> when (u.error) {
                SecretUnwrapError.KEY_LOST -> DatabaseKeyResult.Failure(DatabaseKeyError.KEY_LOST)
                SecretUnwrapError.TAMPERED -> DatabaseKeyResult.Failure(DatabaseKeyError.TAMPERED)
                SecretUnwrapError.UNWRAP_FAILED -> DatabaseKeyResult.Failure(DatabaseKeyError.WRAPPER_UNAVAILABLE)
            }
        }
    }

    private fun generateAndPersist(): DatabaseKeyResult {
        val key = ByteArray(KEY_SIZE_BYTES)
        secureRandom.nextBytes(key)

        val wrapped = when (val w = wrapper.wrap(key, ASSOCIATED_DATA)) {
            is WrapResult.Success -> w.wrapped
            is WrapResult.Failure -> {
                key.fill(0)
                return when (w.error) {
                    SecretWrapError.KEY_UNAVAILABLE -> DatabaseKeyResult.Failure(DatabaseKeyError.WRAPPER_UNAVAILABLE)
                    SecretWrapError.WRAP_FAILED -> DatabaseKeyResult.Failure(DatabaseKeyError.WRAPPER_UNAVAILABLE)
                }
            }
        }

        val write = file.write(wrapped.nonce, wrapped.ciphertext)
        if (write is SecretFileWriteResult.Failure) {
            key.fill(0)
            return when (write.error) {
                SecretFileWriteError.UNSAFE_PATH -> DatabaseKeyResult.Failure(DatabaseKeyError.STORAGE_FAILED)
                SecretFileWriteError.DIRECTORY_UNAVAILABLE -> DatabaseKeyResult.Failure(DatabaseKeyError.STORAGE_FAILED)
                SecretFileWriteError.ENCODE_INVALID -> DatabaseKeyResult.Failure(DatabaseKeyError.STORAGE_FAILED)
                SecretFileWriteError.IO_FAILED -> DatabaseKeyResult.Failure(DatabaseKeyError.STORAGE_FAILED)
            }
        }

        val copy = key.copyOf()
        key.fill(0)
        return DatabaseKeyResult.Success(copy)
    }
}

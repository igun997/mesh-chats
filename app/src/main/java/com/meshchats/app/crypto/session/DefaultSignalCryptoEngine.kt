package com.meshchats.app.crypto.session

import android.database.SQLException
import com.meshchats.app.crypto.identity.DeviceIdentityError
import com.meshchats.app.crypto.identity.DeviceIdentityRepository
import com.meshchats.app.crypto.identity.DeviceIdentityResult
import com.meshchats.app.crypto.prekey.PreKeyEnsureResult
import com.meshchats.app.crypto.prekey.PreKeyManagerError
import com.meshchats.app.crypto.prekey.PublishedBundleResult
import com.meshchats.app.crypto.prekey.SignalPreKeyManager
import com.meshchats.app.crypto.prekey.SignalTransactionRunner
import com.meshchats.app.data.local.BlockingSignalStoreDao
import com.meshchats.app.data.local.RoomSignalProtocolStore
import com.meshchats.app.data.local.SignalStoreException
import com.meshchats.protocol.wire.PublishedPreKeyBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.InvalidVersionException
import org.signal.libsignal.protocol.LegacyMessageException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import java.time.Instant

/**
 * Default [SignalCryptoEngine] over libsignal 0.100.0 and the SQLCipher/Room
 * [RoomSignalProtocolStore].
 *
 * ## Threading and atomicity
 * Session operations copy their mutable inputs, hop to the single-parallelism
 * [dispatcher], and hold [engineMutex] so two callers serialize. Every native
 * `SessionBuilder.process` / `SessionCipher.encrypt` / `SessionCipher.decrypt` —
 * and all the synchronous store callbacks it triggers — runs inside ONE
 * [transactionRunner] transaction. Exceptions escape the transaction (rolling it
 * back) and are mapped to bounded [SignalCryptoError]s **outside** the
 * transaction; nothing inside a transaction catches-and-converts, so a native
 * failure never commits a half-written session. [ensureInventory] and
 * [createPublishedBundle] delegate to the [preKeyManager], which owns its own
 * dispatcher/mutex/transaction; both share the same single-parallelism dispatcher,
 * so inventory and session work are serialized at the dispatcher level.
 *
 * ## Identity binding (no blind TOFU)
 * The device identity is provisioned/verified first on every call. Establishment
 * and incoming PREKEY messages are checked against the [VerifiedSignalPeer]'s
 * expected identity key BEFORE any transactional write; WHISPER operations require
 * a stored trusted identity that already equals the expected binding. The engine
 * therefore never relies on libsignal's trust-on-first-use for an unverified key.
 *
 * ## Secret handling
 * Logs nothing; stringifies no plaintext, key, or ciphertext. Recovered plaintext
 * is defensively copied out of libsignal before returning.
 */
class DefaultSignalCryptoEngine(
    private val identityRepository: DeviceIdentityRepository,
    private val preKeyManager: SignalPreKeyManager,
    private val dao: BlockingSignalStoreDao,
    private val transactionRunner: SignalTransactionRunner,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Instant,
    private val schemaVersion: Int = 1,
) : SignalCryptoEngine {

    private val engineMutex = Mutex()

    private val store: RoomSignalProtocolStore =
        RoomSignalProtocolStore(dao, schemaVersion = schemaVersion, now = { clock().toEpochMilli() })

    // === Inventory (delegated) ============================================

    override suspend fun ensureInventory(): SignalEnsureResult =
        when (val r = preKeyManager.ensureInventory()) {
            is PreKeyEnsureResult.Success -> SignalEnsureResult.Success
            is PreKeyEnsureResult.Failure -> SignalEnsureResult.Failure(mapPreKeyError(r.error))
        }

    override suspend fun createPublishedBundle(): SignalBundleResult =
        when (val r = preKeyManager.createPublishedBundle()) {
            is PublishedBundleResult.Success -> SignalBundleResult.Success(r.bundle)
            is PublishedBundleResult.Failure -> SignalBundleResult.Failure(mapPreKeyError(r.error))
        }

    // === Establish outbound session =======================================

    override suspend fun establishSession(
        peer: VerifiedSignalPeer,
        bundle: PublishedPreKeyBundle,
    ): SignalSessionResult = withContext(dispatcher) {
        engineMutex.withLock {
            val localAddress = when (val la = resolveLocalAddress()) {
                is LocalAddress.Resolved -> la.address
                is LocalAddress.Unavailable -> return@withLock SignalSessionResult.Failure(la.error)
            }

            // Bind the bundle to the verified peer BEFORE any parse or session write.
            if (bundle.deviceId != peer.deviceId ||
                !bundle.identityKey.contentEquals(peer.expectedSignalIdentityKey)
            ) {
                return@withLock SignalSessionResult.Failure(SignalCryptoError.REMOTE_IDENTITY_MISMATCH)
            }

            // Convert to a libsignal bundle with exact constructors; a malformed
            // remote key is bounded INVALID_INPUT and never reaches a transaction.
            // The EC/identity/Kyber constructors run on attacker-controlled bytes and
            // may throw any checked or unchecked Exception, so catch Exception broadly
            // while still letting JVM Errors (OOM, stack overflow) and coroutine
            // cancellation propagate.
            val libBundle = try {
                toLibsignalBundle(bundle)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withLock SignalSessionResult.Failure(SignalCryptoError.INVALID_INPUT)
            }

            val remoteAddress = SignalProtocolAddress(peer.protocolName, peer.deviceId)
            val now = clock()
            try {
                transactionRunner.runInTransaction {
                    // SessionBuilder arg order (libsignal 0.100.0): stores, remote, local.
                    val builder = SessionBuilder(store, store, store, store, remoteAddress, localAddress)
                    builder.process(libBundle, now)
                }
                SignalSessionResult.Success
            } catch (e: CancellationException) {
                throw e
            } catch (_: UntrustedIdentityException) {
                SignalSessionResult.Failure(SignalCryptoError.UNTRUSTED_IDENTITY)
            } catch (_: InvalidKeyException) {
                SignalSessionResult.Failure(SignalCryptoError.INVALID_INPUT)
            } catch (_: SignalStoreException) {
                SignalSessionResult.Failure(SignalCryptoError.CORRUPT_STORE)
            } catch (_: SQLException) {
                SignalSessionResult.Failure(SignalCryptoError.STORAGE_UNAVAILABLE)
            } catch (_: Exception) {
                SignalSessionResult.Failure(SignalCryptoError.CRYPTO_UNAVAILABLE)
            }
        }
    }

    // === Encrypt ==========================================================

    override suspend fun encrypt(peer: VerifiedSignalPeer, plaintext: ByteArray): SignalEncryptResult {
        // Copy and bound the mutable input BEFORE dispatch.
        val payload = plaintext.copyOf()
        if (payload.isEmpty() || payload.size > MAX_PLAINTEXT_BYTES) {
            return SignalEncryptResult.Failure(SignalCryptoError.INVALID_INPUT)
        }
        return withContext(dispatcher) {
            engineMutex.withLock {
                val localAddress = when (val la = resolveLocalAddress()) {
                    is LocalAddress.Resolved -> la.address
                    is LocalAddress.Unavailable -> return@withLock SignalEncryptResult.Failure(la.error)
                }

                val remoteAddress = SignalProtocolAddress(peer.protocolName, peer.deviceId)
                val now = clock()
                val message: CiphertextMessage = try {
                    transactionRunner.runInTransaction {
                        // WHISPER/encrypt has no embedded identity: require a stored
                        // trusted identity equal to the expected binding. This read runs
                        // INSIDE the same transaction, immediately before the cipher op,
                        // so no identity write can slip between the check and the encrypt
                        // (TOCTOU-free). A non-match throws a bounded marker that rolls
                        // the whole transaction back; it is mapped outside.
                        val stored = checkStoredIdentity(peer)
                        if (stored != StoredIdentity.MATCH) throw StoredIdentityMarker(stored)
                        // SessionCipher arg order (libsignal 0.100.0):
                        // session, preKey, signedPreKey, kyber, identity, local, remote.
                        val cipher = SessionCipher(store, store, store, store, store, localAddress, remoteAddress)
                        cipher.encrypt(payload, now)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StoredIdentityMarker) {
                    return@withLock SignalEncryptResult.Failure(mapStoredIdentity(e.stored))
                } catch (_: NoSessionException) {
                    return@withLock SignalEncryptResult.Failure(SignalCryptoError.NO_SESSION)
                } catch (_: UntrustedIdentityException) {
                    return@withLock SignalEncryptResult.Failure(SignalCryptoError.UNTRUSTED_IDENTITY)
                } catch (_: SignalStoreException) {
                    return@withLock SignalEncryptResult.Failure(SignalCryptoError.CORRUPT_STORE)
                } catch (_: SQLException) {
                    return@withLock SignalEncryptResult.Failure(SignalCryptoError.STORAGE_UNAVAILABLE)
                } catch (_: Exception) {
                    return@withLock SignalEncryptResult.Failure(SignalCryptoError.CRYPTO_UNAVAILABLE)
                }

                val type = ciphertextType(message.type)
                    ?: return@withLock SignalEncryptResult.Failure(SignalCryptoError.CRYPTO_UNAVAILABLE)
                SignalEncryptResult.Success(SignalCiphertext(type, message.serialize()))
            }
        }
    }

    // === Decrypt ==========================================================

    override suspend fun decrypt(peer: VerifiedSignalPeer, ciphertext: SignalCiphertext): SignalDecryptResult {
        // SignalCiphertext.bytes already yields a defensive copy.
        val bytes = ciphertext.bytes
        if (bytes.isEmpty() || bytes.size > MAX_CIPHERTEXT_BYTES) {
            return SignalDecryptResult.Failure(SignalCryptoError.INVALID_INPUT)
        }
        return withContext(dispatcher) {
            engineMutex.withLock {
                val localAddress = when (val la = resolveLocalAddress()) {
                    is LocalAddress.Resolved -> la.address
                    is LocalAddress.Unavailable -> return@withLock SignalDecryptResult.Failure(la.error)
                }
                val remoteAddress = SignalProtocolAddress(peer.protocolName, peer.deviceId)

                when (ciphertext.type) {
                    SignalCiphertextType.PREKEY -> {
                        val message = try {
                            PreKeySignalMessage(bytes)
                        } catch (_: Exception) {
                            return@withLock SignalDecryptResult.Failure(SignalCryptoError.MALFORMED_MESSAGE)
                        }
                        // Compare the embedded identity to the verified binding BEFORE
                        // any transactional decrypt. A first PREKEY from an unknown key
                        // is accepted ONLY after this explicit match — never blind TOFU.
                        if (!message.identityKey.serialize().contentEquals(peer.expectedSignalIdentityKey)) {
                            return@withLock SignalDecryptResult.Failure(SignalCryptoError.REMOTE_IDENTITY_MISMATCH)
                        }
                        decryptInTransaction(localAddress, remoteAddress, storedIdentityPeer = null) { cipher ->
                            cipher.decrypt(message)
                        }
                    }

                    SignalCiphertextType.WHISPER -> {
                        val message = try {
                            SignalMessage(bytes)
                        } catch (_: Exception) {
                            return@withLock SignalDecryptResult.Failure(SignalCryptoError.MALFORMED_MESSAGE)
                        }
                        // WHISPER carries no identity: the stored trusted-identity check
                        // runs INSIDE the same transaction as the decrypt (see
                        // [decryptInTransaction]), immediately before the cipher op, so no
                        // identity write can slip between check and decrypt.
                        decryptInTransaction(localAddress, remoteAddress, peer) { cipher -> cipher.decrypt(message) }
                    }
                }
            }
        }
    }

    /**
     * Runs the supplied native decrypt inside one transaction and maps escaping
     * exceptions outside it. When [storedIdentityPeer] is non-null (WHISPER), the
     * stored trusted-identity check runs INSIDE the same transaction, immediately
     * before the cipher op, and a non-match throws a bounded [StoredIdentityMarker]
     * that rolls the transaction back; PREKEY passes null because its embedded
     * identity was already matched before dispatch. [ReusedBaseKeyException] is
     * caught before [InvalidMessageException] because it is a subclass;
     * [CancellationException] is rethrown; anything unrecognized is bounded
     * [SignalCryptoError.CRYPTO_UNAVAILABLE].
     */
    private fun decryptInTransaction(
        localAddress: SignalProtocolAddress,
        remoteAddress: SignalProtocolAddress,
        storedIdentityPeer: VerifiedSignalPeer?,
        decrypt: (SessionCipher) -> ByteArray,
    ): SignalDecryptResult {
        val plaintext: ByteArray = try {
            transactionRunner.runInTransaction {
                if (storedIdentityPeer != null) {
                    val stored = checkStoredIdentity(storedIdentityPeer)
                    if (stored != StoredIdentity.MATCH) throw StoredIdentityMarker(stored)
                }
                val cipher = SessionCipher(store, store, store, store, store, localAddress, remoteAddress)
                decrypt(cipher)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StoredIdentityMarker) {
            return SignalDecryptResult.Failure(mapStoredIdentity(e.stored))
        } catch (_: DuplicateMessageException) {
            return SignalDecryptResult.Failure(SignalCryptoError.DUPLICATE_MESSAGE)
        } catch (_: NoSessionException) {
            return SignalDecryptResult.Failure(SignalCryptoError.NO_SESSION)
        } catch (_: UntrustedIdentityException) {
            return SignalDecryptResult.Failure(SignalCryptoError.UNTRUSTED_IDENTITY)
        } catch (_: InvalidKeyIdException) {
            return SignalDecryptResult.Failure(SignalCryptoError.MISSING_PREKEY)
        } catch (_: ReusedBaseKeyException) {
            // MUST precede InvalidMessageException — ReusedBaseKeyException is a subclass.
            return SignalDecryptResult.Failure(SignalCryptoError.REUSED_BASE_KEY)
        } catch (_: InvalidVersionException) {
            return SignalDecryptResult.Failure(SignalCryptoError.MALFORMED_MESSAGE)
        } catch (_: LegacyMessageException) {
            return SignalDecryptResult.Failure(SignalCryptoError.MALFORMED_MESSAGE)
        } catch (_: InvalidMessageException) {
            return SignalDecryptResult.Failure(SignalCryptoError.MALFORMED_MESSAGE)
        } catch (_: InvalidKeyException) {
            return SignalDecryptResult.Failure(SignalCryptoError.MALFORMED_MESSAGE)
        } catch (_: SignalStoreException) {
            return SignalDecryptResult.Failure(SignalCryptoError.CORRUPT_STORE)
        } catch (_: SQLException) {
            return SignalDecryptResult.Failure(SignalCryptoError.STORAGE_UNAVAILABLE)
        } catch (_: Exception) {
            return SignalDecryptResult.Failure(SignalCryptoError.CRYPTO_UNAVAILABLE)
        }
        // Defensive copy out of libsignal's returned array.
        return SignalDecryptResult.Success(plaintext.copyOf())
    }

    // === hasSession =======================================================

    override suspend fun hasSession(peer: VerifiedSignalPeer): SignalHasSessionResult = withContext(dispatcher) {
        engineMutex.withLock {
            val remoteAddress = SignalProtocolAddress(peer.protocolName, peer.deviceId)
            try {
                val has = transactionRunner.runInTransaction {
                    val sessionPresent = store.containsSession(remoteAddress)
                    val identity = store.getIdentity(remoteAddress)
                    val trusted = identity != null &&
                        identity.serialize().contentEquals(peer.expectedSignalIdentityKey)
                    sessionPresent && trusted
                }
                SignalHasSessionResult.Success(has)
            } catch (e: CancellationException) {
                throw e
            } catch (_: SignalStoreException) {
                SignalHasSessionResult.Failure(SignalCryptoError.CORRUPT_STORE)
            } catch (_: SQLException) {
                SignalHasSessionResult.Failure(SignalCryptoError.STORAGE_UNAVAILABLE)
            } catch (_: Exception) {
                SignalHasSessionResult.Failure(SignalCryptoError.CRYPTO_UNAVAILABLE)
            }
        }
    }

    // === helpers ==========================================================

    /**
     * Outcome of resolving the local protocol address: either the resolved
     * [SignalProtocolAddress] or a bounded [SignalCryptoError]. Replaces a shared
     * mutable out-param so a concurrent caller can never observe another call's
     * failure reason.
     */
    private sealed interface LocalAddress {
        data class Resolved(val address: SignalProtocolAddress) : LocalAddress
        data class Unavailable(val error: SignalCryptoError) : LocalAddress
    }

    /**
     * Provisions/verifies the device identity and returns the stable local
     * protocol address (device 1) as [LocalAddress.Resolved], or
     * [LocalAddress.Unavailable] carrying the bounded reason. The local name is
     * derived from the local device's FULL Ed25519 fingerprint via
     * [SignalProtocolName]. Returning the reason keeps it local to the call, so no
     * shared field leaks one call's failure into another.
     */
    private fun resolveLocalAddress(): LocalAddress {
        return when (val r = identityRepository.getOrCreateIdentity()) {
            is DeviceIdentityResult.Success -> {
                val name = try {
                    SignalProtocolName.fromFingerprint(r.identity.fingerprintSha256)
                } catch (_: IllegalArgumentException) {
                    return LocalAddress.Unavailable(SignalCryptoError.IDENTITY_UNAVAILABLE)
                }
                LocalAddress.Resolved(SignalProtocolAddress(name, LOCAL_DEVICE_ID))
            }
            is DeviceIdentityResult.Failure -> LocalAddress.Unavailable(mapIdentityError(r.error))
        }
    }

    private enum class StoredIdentity { MATCH, MISMATCH, ABSENT, CORRUPT, STORAGE }

    /**
     * Internal marker thrown INSIDE a transaction when the stored trusted identity
     * for a WHISPER/encrypt peer is not an exact match, so the surrounding Room
     * transaction rolls back before any session write commits. Never escapes the
     * engine: each transactional caller catches it and maps [stored] to a bounded
     * [SignalCryptoError] via [mapStoredIdentity]. Carries no key bytes.
     */
    private class StoredIdentityMarker(val stored: StoredIdentity) : RuntimeException()

    /** Maps a non-[StoredIdentity.MATCH] classification to its bounded error. */
    private fun mapStoredIdentity(stored: StoredIdentity): SignalCryptoError = when (stored) {
        StoredIdentity.MATCH -> SignalCryptoError.CRYPTO_UNAVAILABLE // unreachable; MATCH never mapped
        StoredIdentity.ABSENT -> SignalCryptoError.NO_SESSION
        StoredIdentity.MISMATCH -> SignalCryptoError.REMOTE_IDENTITY_MISMATCH
        StoredIdentity.CORRUPT -> SignalCryptoError.CORRUPT_STORE
        StoredIdentity.STORAGE -> SignalCryptoError.STORAGE_UNAVAILABLE
    }

    /**
     * Reads the stored trusted identity for [peer]'s address and classifies it
     * against the expected binding. A single blocking read on the crypto
     * dispatcher; parse/storage faults are bounded, never surfaced raw.
     */
    private fun checkStoredIdentity(peer: VerifiedSignalPeer): StoredIdentity {
        val row = try {
            dao.trustedIdentity(peer.protocolName, peer.deviceId)
        } catch (_: SignalStoreException) {
            return StoredIdentity.CORRUPT
        } catch (_: SQLException) {
            return StoredIdentity.STORAGE
        } catch (_: Exception) {
            return StoredIdentity.STORAGE
        } ?: return StoredIdentity.ABSENT
        return if (row.identityKey.contentEquals(peer.expectedSignalIdentityKey)) {
            StoredIdentity.MATCH
        } else {
            StoredIdentity.MISMATCH
        }
    }

    /**
     * Strictly converts a [PublishedPreKeyBundle] to a libsignal [PreKeyBundle],
     * parsing every key with its exact constructor. The optional one-time EC prekey
     * is represented canonically: present → its id + parsed key; absent →
     * [PreKeyBundle.NULL_PRE_KEY_ID] + null. Throws [InvalidKeyException] /
     * [IllegalArgumentException] on any malformed key, mapped to INVALID_INPUT by
     * the caller.
     */
    private fun toLibsignalBundle(bundle: PublishedPreKeyBundle): PreKeyBundle {
        val oneTimeId = bundle.oneTimePreKeyId ?: PreKeyBundle.NULL_PRE_KEY_ID
        val oneTimeKey: ECPublicKey? = bundle.oneTimePreKeyPublic?.let { ECPublicKey(it) }
        return PreKeyBundle(
            bundle.registrationId,
            bundle.deviceId,
            oneTimeId,
            oneTimeKey,
            bundle.signedPreKeyId,
            ECPublicKey(bundle.signedPreKeyPublic),
            bundle.signedPreKeySignature,
            IdentityKey(bundle.identityKey),
            bundle.kyberPreKeyId,
            KEMPublicKey(bundle.kyberPreKeyPublic),
            bundle.kyberPreKeySignature,
        )
    }

    private fun ciphertextType(type: Int): SignalCiphertextType? = when (type) {
        CiphertextMessage.PREKEY_TYPE -> SignalCiphertextType.PREKEY
        CiphertextMessage.WHISPER_TYPE -> SignalCiphertextType.WHISPER
        else -> null
    }

    private fun mapIdentityError(error: DeviceIdentityError): SignalCryptoError = when (error) {
        DeviceIdentityError.DATABASE_UNAVAILABLE,
        DeviceIdentityError.STORAGE_FAILED,
        -> SignalCryptoError.STORAGE_UNAVAILABLE
        DeviceIdentityError.CRYPTO_UNAVAILABLE,
        DeviceIdentityError.WRAPPER_UNAVAILABLE,
        -> SignalCryptoError.CRYPTO_UNAVAILABLE
        DeviceIdentityError.KEY_LOST,
        DeviceIdentityError.TAMPERED,
        DeviceIdentityError.MESSAGE_TOO_LARGE,
        -> SignalCryptoError.IDENTITY_UNAVAILABLE
    }

    private fun mapPreKeyError(error: PreKeyManagerError): SignalCryptoError = when (error) {
        PreKeyManagerError.IDENTITY_UNAVAILABLE -> SignalCryptoError.IDENTITY_UNAVAILABLE
        PreKeyManagerError.CORRUPT_STORE -> SignalCryptoError.CORRUPT_STORE
        PreKeyManagerError.STORAGE_FAILED -> SignalCryptoError.STORAGE_UNAVAILABLE
        PreKeyManagerError.NO_PUBLISHABLE_KEY -> SignalCryptoError.MISSING_PREKEY
        PreKeyManagerError.KEY_GENERATION_FAILED,
        PreKeyManagerError.ID_EXHAUSTED,
        -> SignalCryptoError.CRYPTO_UNAVAILABLE
    }

    companion object {
        /** This app runs a single device per identity; the primary device id is 1. */
        const val LOCAL_DEVICE_ID: Int = 1

        /**
         * Maximum plaintext accepted for encryption, in bytes (64 KiB). A single
         * chat message is far smaller; the ceiling refuses an unbounded payload
         * without constraining any legitimate message.
         */
        const val MAX_PLAINTEXT_BYTES: Int = 64 * 1024

        /**
         * Maximum ciphertext accepted for decryption, in bytes (1 MiB) — the mesh
         * protocol's hard ciphertext ceiling. A larger frame is rejected before any
         * parse or storage work.
         */
        const val MAX_CIPHERTEXT_BYTES: Int = 1 shl 20
    }
}

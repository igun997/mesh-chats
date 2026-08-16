package com.meshchats.app.crypto.prekey

import androidx.annotation.VisibleForTesting
import com.meshchats.app.crypto.identity.DeviceIdentityRepository
import com.meshchats.app.crypto.identity.DeviceIdentityResult
import com.meshchats.app.data.local.BlockingSignalStoreDao
import com.meshchats.app.data.local.SignalKyberPreKeyEntity
import com.meshchats.app.data.local.SignalPreKeyEntity
import com.meshchats.app.data.local.SignalSignedPreKeyEntity
import com.meshchats.protocol.wire.PublishedPreKeyBundle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Default [SignalPreKeyManager].
 *
 * ## Threading and atomicity
 * Every method hops to the injected single-parallelism [dispatcher] and takes
 * [mutex] so two concurrent callers serialize (no double-provisioning beyond
 * targets). All libsignal generation runs on that dispatcher; the resulting rows
 * are inserted in a single [transactionRunner] transaction, so a failure mid-batch
 * rolls back and leaves no partial inventory.
 *
 * ## Concurrency with other processes
 * Inserts use IGNORE-on-conflict DAO methods and check the returned rowid: a
 * non-positive rowid means another process inserted the same id, so the whole
 * transaction is rolled back and the attempt retried (recount → replan → redraw)
 * up to [maxProvisionAttempts]. This bounds *clobbering* — an existing key or its
 * metadata is never overwritten — but does NOT prevent *overfill* across
 * processes: two processes drawing disjoint random ids both insert successfully.
 * See [SignalPreKeyManager] for the full single-process idempotence scope and the
 * multi-process coordination requirement.
 *
 * ## Secret handling
 * The Signal identity key pair blob is read from the encrypted store and handed to
 * the [factory] only to sign / verify; it is never logged or stringified, and the
 * loaded copy is zeroed in a `finally` once the operation completes. Freshly
 * generated record blobs contain private key material: they are inserted verbatim
 * (Room's `bindBlob` + `executeInsert` copies the bytes into the SQLite page
 * synchronously, within the transaction call) and then zeroed in a `finally` on
 * every path — success, generation failure, storage failure, and conflict-retry —
 * so no plaintext private key lingers on the heap after the transaction commits or
 * rolls back. Published bundle bytes are PUBLIC key / signature material and are
 * never zeroed.
 */
class DefaultSignalPreKeyManager(
    private val identityRepository: DeviceIdentityRepository,
    private val dao: BlockingSignalStoreDao,
    private val factory: SignalKeyMaterialFactory,
    private val idGenerator: PreKeyIdGenerator,
    private val transactionRunner: SignalTransactionRunner,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val schemaVersion: Int = 1,
    private val maxProvisionAttempts: Int = DEFAULT_MAX_PROVISION_ATTEMPTS,
) : SignalPreKeyManager {

    private val mutex = Mutex()

    /** A local identity snapshot needed for generation, signing, and publication. */
    private class LocalIdentity(val registrationId: Int, val serializedKeyPair: ByteArray)

    /** Rolled-back-and-retry signal: another process inserted one of our drawn ids. */
    private class InsertConflictException : RuntimeException()

    override suspend fun ensureInventory(): PreKeyEnsureResult = withContext(dispatcher) {
        mutex.withLock { ensureLocked() }
    }

    override suspend fun createPublishedBundle(): PublishedBundleResult = withContext(dispatcher) {
        mutex.withLock {
            when (val ensured = ensureLocked()) {
                is PreKeyEnsureResult.Failure -> return@withLock PublishedBundleResult.Failure(ensured.error)
                is PreKeyEnsureResult.Success -> Unit
            }
            snapshotBundleLocked()
        }
    }

    /**
     * Snapshots a publishable bundle WITHOUT first ensuring the inventory.
     *
     * Test-only seam: production callers use [createPublishedBundle], which ensures
     * first. It exists because the last-resort Kyber fallback in [buildBundle]
     * ([BlockingSignalStoreDao.oldestUnusedOneTimeKyber] `?:`
     * [BlockingSignalStoreDao.latestLastResortKyber]) is by construction
     * unreachable through the public API — [createPublishedBundle] always
     * replenishes the one-time Kyber pool first, so a fresh one-time key is
     * published rather than the last-resort. Exercising that fallback
     * deterministically on-device therefore requires snapshotting a state that has
     * only the reusable last-resort key, without an intervening ensure.
     *
     * Marked `@VisibleForTesting(otherwise = PRIVATE)` so Android lint fails the
     * build if any production code calls it — the idiomatic way to expose a
     * test-only seam to an on-device (`androidTest`) suite, which (unlike the JVM
     * `test` source set) has no Kotlin friend-module access to `internal` members
     * of `main` and so cannot see an `internal` function. It still runs on the
     * crypto [dispatcher] under [mutex] inside one transaction, exactly like the
     * production path.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    suspend fun snapshotBundleForTest(): PublishedBundleResult = withContext(dispatcher) {
        mutex.withLock { snapshotBundleLocked() }
    }

    private fun snapshotBundleLocked(): PublishedBundleResult {
        val identity = loadIdentity()
            ?: return PublishedBundleResult.Failure(PreKeyManagerError.IDENTITY_UNAVAILABLE)
        return try {
            transactionRunner.runInTransaction { buildBundle(identity) }
        } catch (_: Throwable) {
            PublishedBundleResult.Failure(PreKeyManagerError.STORAGE_FAILED)
        } finally {
            // The identity private key was needed only to sign/verify; drop the
            // plaintext copy loaded from the store.
            identity.serializedKeyPair.fill(0)
        }
    }

    // --- ensure ------------------------------------------------------------

    private fun ensureLocked(): PreKeyEnsureResult {
        val identity = loadIdentity() ?: return PreKeyEnsureResult.Failure(PreKeyManagerError.IDENTITY_UNAVAILABLE)

        try {
            var attempt = 0
            while (attempt < maxProvisionAttempts) {
                attempt++
                when (val outcome = tryProvisionOnce(identity)) {
                    is ProvisionOutcome.Done -> return outcome.result
                    ProvisionOutcome.Conflict -> Unit // recount / replan / retry
                }
            }
            // Bounded retries exhausted by external insertion races.
            return PreKeyEnsureResult.Failure(PreKeyManagerError.STORAGE_FAILED)
        } finally {
            // Drop the plaintext identity private key loaded from the store once
            // all attempts (and their signing) are done.
            identity.serializedKeyPair.fill(0)
        }
    }

    private sealed interface ProvisionOutcome {
        data class Done(val result: PreKeyEnsureResult) : ProvisionOutcome
        data object Conflict : ProvisionOutcome
    }

    private fun tryProvisionOnce(identity: LocalIdentity): ProvisionOutcome {
        val counts = PreKeyInventoryCounts(
            unusedEcOneTime = dao.oneTimePreKeyCount(),
            unusedKyberOneTime = dao.unusedOneTimeKyberCount(),
            lastResortKyber = dao.lastResortKyberCount(),
            activeSigned = dao.signedPreKeyTotal(),
        )
        val plan = PreKeyReplenishmentPlanner.plan(counts)
        if (plan.isNoOp) {
            return ProvisionOutcome.Done(
                PreKeyEnsureResult.Success(
                    generatedEcOneTime = 0,
                    generatedKyberOneTime = 0,
                    generatedLastResort = false,
                    generatedSigned = false,
                ),
            )
        }

        val now = clock()
        val existingEc = dao.oneTimePreKeyIds().toHashSet()
        val existingSigned = dao.signedPreKeyIds().toHashSet()
        val existingKyber = dao.kyberPreKeyIds().toHashSet()

        // --- draw ids ------------------------------------------------------
        val ecIds = when (val r = idGenerator.batch(plan.ecOneTimeToGenerate) { it in existingEc }) {
            is PreKeyIdResult.Batch -> r.ids
            is PreKeyIdResult.Failure -> return ProvisionOutcome.Done(fail(PreKeyManagerError.ID_EXHAUSTED))
            is PreKeyIdResult.Success -> error("unreachable")
        }
        val totalKyber = plan.kyberOneTimeToGenerate + if (plan.generateLastResort) 1 else 0
        val kyberIds = when (val r = idGenerator.batch(totalKyber) { it in existingKyber }) {
            is PreKeyIdResult.Batch -> r.ids
            is PreKeyIdResult.Failure -> return ProvisionOutcome.Done(fail(PreKeyManagerError.ID_EXHAUSTED))
            is PreKeyIdResult.Success -> error("unreachable")
        }
        val signedId = if (plan.generateSigned) {
            when (val r = idGenerator.next { it in existingSigned }) {
                is PreKeyIdResult.Success -> r.id
                is PreKeyIdResult.Failure -> return ProvisionOutcome.Done(fail(PreKeyManagerError.ID_EXHAUSTED))
                is PreKeyIdResult.Batch -> error("unreachable")
            }
        } else {
            null
        }

        val oneTimeKyberCount = plan.kyberOneTimeToGenerate
        // Every freshly serialized record blob holds PRIVATE key material; tracked
        // here so it can be zeroed on every exit path once the transaction has
        // copied it into the SQLite page (or rolled back).
        val generatedSecrets = ArrayList<ByteArray>(ecIds.size + kyberIds.size + 1)
        try {
            // --- generate material (outside the transaction) ---------------
            val ecKeys = ArrayList<GeneratedPreKey>(ecIds.size)
            for (id in ecIds) {
                when (val g = factory.generateOneTimeEcPreKey(id)) {
                    is GeneratedPreKeyResult.Success -> {
                        ecKeys += g.key
                        generatedSecrets += g.key.serialized
                    }
                    is GeneratedPreKeyResult.Failure -> return ProvisionOutcome.Done(fail(mapGenerate(g.error)))
                }
            }
            val kyberKeys = ArrayList<Pair<GeneratedPreKey, Boolean>>(kyberIds.size) // key to lastResort
            kyberIds.forEachIndexed { index, id ->
                val lastResort = index >= oneTimeKyberCount
                when (val g = factory.generateKyberPreKey(id, now, identity.serializedKeyPair, lastResort)) {
                    is GeneratedPreKeyResult.Success -> {
                        kyberKeys += g.key to lastResort
                        generatedSecrets += g.key.serialized
                    }
                    is GeneratedPreKeyResult.Failure -> return ProvisionOutcome.Done(fail(mapGenerate(g.error)))
                }
            }
            val signedKey = if (signedId != null) {
                when (val g = factory.generateSignedPreKey(signedId, now, identity.serializedKeyPair)) {
                    is GeneratedPreKeyResult.Success -> {
                        generatedSecrets += g.key.serialized
                        g.key
                    }
                    is GeneratedPreKeyResult.Failure -> return ProvisionOutcome.Done(fail(mapGenerate(g.error)))
                }
            } else {
                null
            }

            // --- provision in one transaction ------------------------------
            return try {
                transactionRunner.runInTransaction {
                    for (key in ecKeys) {
                        val rowId = dao.insertPreKeyIfAbsent(
                            SignalPreKeyEntity(
                                preKeyId = key.id,
                                record = key.serialized,
                                schemaVersion = schemaVersion,
                                createdAt = now,
                            ),
                        )
                        if (rowId <= 0L) throw InsertConflictException()
                    }
                    for ((key, lastResort) in kyberKeys) {
                        val rowId = dao.insertKyberPreKeyMetadataIfAbsent(
                            SignalKyberPreKeyEntity(
                                kyberPreKeyId = key.id,
                                record = key.serialized,
                                used = false,
                                lastResort = lastResort,
                                schemaVersion = schemaVersion,
                                createdAt = now,
                            ),
                        )
                        if (rowId <= 0L) throw InsertConflictException()
                    }
                    if (signedKey != null) {
                        val rowId = dao.insertSignedPreKeyIfAbsent(
                            SignalSignedPreKeyEntity(
                                signedPreKeyId = signedKey.id,
                                record = signedKey.serialized,
                                schemaVersion = schemaVersion,
                                createdAt = now,
                            ),
                        )
                        if (rowId <= 0L) throw InsertConflictException()
                    }
                }
                ProvisionOutcome.Done(
                    PreKeyEnsureResult.Success(
                        generatedEcOneTime = ecKeys.size,
                        generatedKyberOneTime = oneTimeKyberCount,
                        generatedLastResort = plan.generateLastResort,
                        generatedSigned = signedKey != null,
                    ),
                )
            } catch (_: InsertConflictException) {
                // Another process inserted one of our drawn ids: the whole
                // transaction rolled back. Recount / replan / redraw on the next
                // attempt; the finally zeroes this attempt's generated secrets.
                ProvisionOutcome.Conflict
            } catch (_: Throwable) {
                ProvisionOutcome.Done(fail(PreKeyManagerError.STORAGE_FAILED))
            }
        } finally {
            // Room copied each blob into the SQLite page synchronously during the
            // transaction (or the transaction rolled back), so zeroing our heap
            // copies now is safe on every path and never blanks a stored row.
            for (secret in generatedSecrets) secret.fill(0)
        }
    }

    // --- bundle ------------------------------------------------------------

    private fun buildBundle(identity: LocalIdentity): PublishedBundleResult {
        val identityKey = when (val r = factory.identityPublicBytes(identity.serializedKeyPair)) {
            is IdentityPublicResult.Success -> r.publicKey
            is IdentityPublicResult.Failure -> return PublishedBundleResult.Failure(PreKeyManagerError.CORRUPT_STORE)
        }

        // Optional one-time EC prekey: publish the oldest if present; never mark
        // consumed. NOTE: repeated publication returns the SAME one-time key until
        // a peer consumes it — see SignalPreKeyManager's publication contract.
        var oneTimeId: Int? = null
        var oneTimePublic: ByteArray? = null
        dao.oldestOneTimePreKey()?.let { row ->
            when (val r = factory.readOneTimeEcPublic(row.record)) {
                is PreKeyPublicResult.Success -> {
                    oneTimeId = r.material.id
                    oneTimePublic = r.material.publicKey
                }
                is PreKeyPublicResult.Failure -> return PublishedBundleResult.Failure(PreKeyManagerError.CORRUPT_STORE)
            }
        }

        // Active signed EC prekey is mandatory for a bundle.
        val signedRow = dao.latestSignedPreKey()
            ?: return PublishedBundleResult.Failure(PreKeyManagerError.NO_PUBLISHABLE_KEY)
        val signed = when (val r = factory.readSignedPublic(signedRow.record, identity.serializedKeyPair)) {
            is PreKeyPublicResult.Success -> r.material
            is PreKeyPublicResult.Failure -> return PublishedBundleResult.Failure(PreKeyManagerError.CORRUPT_STORE)
        }
        val signedSignature = signed.signature
            ?: return PublishedBundleResult.Failure(PreKeyManagerError.CORRUPT_STORE)

        // Prefer an unused one-time Kyber prekey; fall back to the reusable
        // last-resort. Never publish a used one-time Kyber key.
        val kyberRow = dao.oldestUnusedOneTimeKyber() ?: dao.latestLastResortKyber()
            ?: return PublishedBundleResult.Failure(PreKeyManagerError.NO_PUBLISHABLE_KEY)
        val kyber = when (val r = factory.readKyberPublic(kyberRow.record, identity.serializedKeyPair)) {
            is PreKeyPublicResult.Success -> r.material
            is PreKeyPublicResult.Failure -> return PublishedBundleResult.Failure(PreKeyManagerError.CORRUPT_STORE)
        }
        val kyberSignature = kyber.signature
            ?: return PublishedBundleResult.Failure(PreKeyManagerError.CORRUPT_STORE)

        return PublishedBundleResult.Success(
            PublishedPreKeyBundle(
                registrationId = identity.registrationId,
                deviceId = DEVICE_ID,
                oneTimePreKeyId = oneTimeId,
                oneTimePreKeyPublic = oneTimePublic,
                signedPreKeyId = signed.id,
                signedPreKeyPublic = signed.publicKey,
                signedPreKeySignature = signedSignature,
                identityKey = identityKey,
                kyberPreKeyId = kyber.id,
                kyberPreKeyPublic = kyber.publicKey,
                kyberPreKeySignature = kyberSignature,
                issuedAtEpochMillis = clock(),
            ),
        )
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Ensures the device identity exists, then reads the local Signal identity row.
     * Returns null (→ IDENTITY_UNAVAILABLE) if the identity cannot be obtained or
     * the Signal row is absent. The returned [LocalIdentity.serializedKeyPair] is a
     * fresh Room-loaded copy; callers zero it in a `finally` once done signing.
     */
    private fun loadIdentity(): LocalIdentity? {
        when (identityRepository.getOrCreateIdentity()) {
            is DeviceIdentityResult.Success -> Unit
            is DeviceIdentityResult.Failure -> return null
        }
        val row = dao.localIdentity() ?: return null
        return LocalIdentity(registrationId = row.registrationId, serializedKeyPair = row.identityKeyPair)
    }

    private fun fail(error: PreKeyManagerError) = PreKeyEnsureResult.Failure(error)

    private fun mapGenerate(error: SignalKeyMaterialError): PreKeyManagerError = when (error) {
        SignalKeyMaterialError.GENERATE_FAILED,
        SignalKeyMaterialError.SIGN_FAILED,
        SignalKeyMaterialError.SIGNATURE_INVALID,
        -> PreKeyManagerError.KEY_GENERATION_FAILED
        SignalKeyMaterialError.PARSE_FAILED -> PreKeyManagerError.CORRUPT_STORE
    }

    companion object {
        /** libsignal primary device id; this app runs a single device per identity. */
        const val DEVICE_ID: Int = 1

        /** Bounded retry budget for external-process insertion races. */
        const val DEFAULT_MAX_PROVISION_ATTEMPTS: Int = 4

        // TODO(phase2b, later task): used one-time key cleanup + signed-key rotation.
        // This manager only PROVISIONS and PUBLISHES inventory; it does not own the
        // lifecycle of *consumed* keys. Deleting used one-time EC prekeys and
        // used-but-non-last-resort one-time Kyber prekeys, and rotating the signed
        // EC prekey on an age/interval policy, belong to the Signal crypto engine
        // (Task 5) which observes actual consumption during session establishment.
        // Tracking ownership here so replenishment and cleanup are not conflated.
    }
}

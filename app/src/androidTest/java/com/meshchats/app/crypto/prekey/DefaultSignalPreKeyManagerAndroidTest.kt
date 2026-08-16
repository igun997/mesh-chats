package com.meshchats.app.crypto.prekey

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meshchats.app.crypto.identity.DeviceIdentity
import com.meshchats.app.crypto.identity.DeviceIdentityRepository
import com.meshchats.app.crypto.identity.DeviceIdentityResult
import com.meshchats.app.crypto.identity.IdentitySignResult
import com.meshchats.app.crypto.identity.ScannedIdentityResult
import com.meshchats.app.data.local.MeshDatabase
import com.meshchats.app.data.local.SignalIdentityEntity
import com.meshchats.app.data.local.SqlCipherNative
import com.meshchats.app.data.local.SqlCipherRawKey
import com.meshchats.protocol.wire.PublishedBundleDecodeResult
import com.meshchats.protocol.wire.PublishedBundleEncodeResult
import com.meshchats.protocol.wire.PublishedPreKeyBundleCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.util.KeyHelper

/**
 * On-device spec for [DefaultSignalPreKeyManager] against a real SQLCipher Room
 * database and the real libsignal native library. Proves inventory provisioning,
 * idempotence, below-threshold refill, positive unique ids, signature validity,
 * exactly-one last-resort Kyber, used-key exclusion, bundle roundtrip through the
 * protocol codec and libsignal's PreKeyBundle constructor, last-resort fallback,
 * concurrent-ensure no overfill, and transaction rollback on injected failure.
 */
@RunWith(AndroidJUnit4::class)
class DefaultSignalPreKeyManagerAndroidTest {

    private lateinit var db: MeshDatabase
    private lateinit var identityKeyPair: IdentityKeyPair
    private var registrationId: Int = 0

    private val rawKey = SqlCipherRawKey.encode(ByteArray(32) { (it + 41).toByte() })

    @Before
    fun setUp() {
        SqlCipherNative.ensureLoaded()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, MeshDatabase::class.java)
            .openHelperFactory(SupportOpenHelperFactory(rawKey))
            .build()
        identityKeyPair = IdentityKeyPair.generate()
        registrationId = KeyHelper.generateRegistrationId(false)
        seedLocalIdentity(identityKeyPair, registrationId)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- Fakes / helpers ---------------------------------------------------

    private fun seedLocalIdentity(pair: IdentityKeyPair, regId: Int) {
        db.runInTransaction {
            db.openHelper.writableDatabase.execSQL(
                "INSERT OR REPLACE INTO signal_identity " +
                    "(id, registration_id, identity_key_pair, schema_version, created_at) VALUES (?,?,?,?,?)",
                arrayOf<Any>(SignalIdentityEntity.SINGLETON_ID, regId, pair.serialize(), 1, 1_000L),
            )
        }
    }

    /** Identity already provisioned in the DB; getOrCreate always succeeds. */
    private class FakeIdentityRepository : DeviceIdentityRepository {
        override fun getOrCreateIdentity(): DeviceIdentityResult =
            DeviceIdentityResult.Success(
                DeviceIdentity(
                    edPublicX509 = ByteArray(44),
                    fingerprintSha256 = ByteArray(32),
                    signalPublicBinding = ByteArray(1),
                    bindingSignature = ByteArray(1),
                    bindingVersion = 1,
                    createdAt = 1_000L,
                    fourWords = listOf("a", "b", "c", "d"),
                )
            )

        override fun sign(message: ByteArray): IdentitySignResult =
            IdentitySignResult.Failure(com.meshchats.app.crypto.identity.DeviceIdentityError.CRYPTO_UNAVAILABLE)

        override fun verify(publicKeyX509: ByteArray, message: ByteArray, signature: ByteArray): Boolean = false
        override fun qrPayload(): DeviceIdentityResult = getOrCreateIdentity()
        override fun verifyScannedPayload(text: String): ScannedIdentityResult =
            ScannedIdentityResult.Rejected(com.meshchats.app.crypto.identity.ScannedIdentityRejection.MALFORMED)
    }

    private val realRunner = object : SignalTransactionRunner {
        override fun <T> runInTransaction(block: () -> T): T = db.runInTransaction(java.util.concurrent.Callable { block() })
    }

    private fun manager(
        idGenerator: PreKeyIdGenerator = PreKeyIdGenerator(randomInt = java.security.SecureRandom()::nextInt),
        runner: SignalTransactionRunner = realRunner,
        identityRepository: DeviceIdentityRepository = FakeIdentityRepository(),
    ) = DefaultSignalPreKeyManager(
        identityRepository = identityRepository,
        dao = db.blockingSignalStoreDao(),
        factory = LibsignalKeyMaterialFactory(),
        idGenerator = idGenerator,
        transactionRunner = runner,
        dispatcher = Dispatchers.IO.limitedParallelism(1),
        clock = { 5_000L },
    )

    // --- Tests -------------------------------------------------------------

    @Test
    fun emptyInventoryProvisionsToTargets() = runBlocking {
        val result = manager().ensureInventory()
        assertTrue(result is PreKeyEnsureResult.Success)
        result as PreKeyEnsureResult.Success
        assertEquals(PreKeyInventoryTargets.EC_ONE_TIME_TARGET, result.generatedEcOneTime)
        assertEquals(PreKeyInventoryTargets.KYBER_ONE_TIME_TARGET, result.generatedKyberOneTime)
        assertTrue(result.generatedLastResort)
        assertTrue(result.generatedSigned)

        val dao = db.blockingSignalStoreDao()
        assertEquals(PreKeyInventoryTargets.EC_ONE_TIME_TARGET, dao.oneTimePreKeyCount())
        assertEquals(PreKeyInventoryTargets.KYBER_ONE_TIME_TARGET, dao.unusedOneTimeKyberCount())
        assertEquals(1, dao.lastResortKyberCount())
        assertEquals(1, dao.signedPreKeyTotal())
    }

    @Test
    fun secondEnsureIsIdempotent() = runBlocking {
        val mgr = manager()
        mgr.ensureInventory()
        val second = mgr.ensureInventory() as PreKeyEnsureResult.Success
        assertEquals(0, second.generatedEcOneTime)
        assertEquals(0, second.generatedKyberOneTime)
        assertFalse(second.generatedLastResort)
        assertFalse(second.generatedSigned)

        val dao = db.blockingSignalStoreDao()
        assertEquals(PreKeyInventoryTargets.EC_ONE_TIME_TARGET, dao.oneTimePreKeyCount())
        assertEquals(1, dao.lastResortKyberCount())
    }

    @Test
    fun belowThresholdRefillsBackToTarget() = runBlocking {
        val mgr = manager()
        mgr.ensureInventory()
        val dao = db.blockingSignalStoreDao()

        // Delete EC one-time keys until below threshold.
        val ids = dao.oneTimePreKeyIds()
        val toRemove = PreKeyInventoryTargets.EC_ONE_TIME_TARGET - (PreKeyInventoryTargets.EC_ONE_TIME_THRESHOLD - 1)
        db.runInTransaction {
            ids.take(toRemove).forEach { dao.deletePreKey(it) }
        }
        assertEquals(PreKeyInventoryTargets.EC_ONE_TIME_THRESHOLD - 1, dao.oneTimePreKeyCount())

        val refill = mgr.ensureInventory() as PreKeyEnsureResult.Success
        assertEquals(toRemove, refill.generatedEcOneTime)
        assertEquals(PreKeyInventoryTargets.EC_ONE_TIME_TARGET, dao.oneTimePreKeyCount())
    }

    @Test
    fun generatedIdsArePositiveAndUnique() = runBlocking {
        manager().ensureInventory()
        val dao = db.blockingSignalStoreDao()
        val ec = dao.oneTimePreKeyIds()
        val kyber = dao.kyberPreKeyIds()
        val signed = dao.signedPreKeyIds()
        (ec + kyber + signed).forEach { assertTrue("id must be positive: $it", it > 0) }
        assertEquals(ec.size, ec.toSet().size)
        assertEquals(kyber.size, kyber.toSet().size)
    }

    @Test
    fun exactlyOneLastResortKyber() = runBlocking {
        val mgr = manager()
        mgr.ensureInventory()
        mgr.ensureInventory()
        assertEquals(1, db.blockingSignalStoreDao().lastResortKyberCount())
    }

    @Test
    fun usedOneTimeKyberIsExcludedAndRefilled() = runBlocking {
        val mgr = manager()
        mgr.ensureInventory()
        val dao = db.blockingSignalStoreDao()

        // Mark enough one-time Kyber keys used to drop unused below threshold.
        val kyberIds = dao.allKyberPreKeys().filter { !it.lastResort && !it.used }.map { it.kyberPreKeyId }
        val markUsed = PreKeyInventoryTargets.KYBER_ONE_TIME_TARGET - (PreKeyInventoryTargets.KYBER_ONE_TIME_THRESHOLD - 1)
        db.runInTransaction {
            kyberIds.take(markUsed).forEach {
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE signal_kyber_prekeys SET used = 1 WHERE kyber_prekey_id = ?",
                    arrayOf<Any>(it),
                )
            }
        }
        // Unused (non-last-resort) dropped below threshold; used ones don't count.
        assertEquals(PreKeyInventoryTargets.KYBER_ONE_TIME_THRESHOLD - 1, dao.unusedOneTimeKyberCount())

        val refill = mgr.ensureInventory() as PreKeyEnsureResult.Success
        assertEquals(markUsed, refill.generatedKyberOneTime)
        assertEquals(PreKeyInventoryTargets.KYBER_ONE_TIME_TARGET, dao.unusedOneTimeKyberCount())
        // Still exactly one last-resort.
        assertEquals(1, dao.lastResortKyberCount())
    }

    @Test
    fun bundleFieldsVerifyAndRoundTripThroughCodecAndPreKeyBundle() = runBlocking {
        val result = manager().createPublishedBundle()
        assertTrue(result is PublishedBundleResult.Success)
        val bundle = (result as PublishedBundleResult.Success).bundle

        assertEquals(registrationId, bundle.registrationId)
        assertEquals(1, bundle.deviceId)
        assertTrue(bundle.hasOneTimePreKey)
        assertEquals(5_000L, bundle.issuedAtEpochMillis)

        // Signatures verify against the identity public key.
        val identityPublic: ECPublicKey = identityKeyPair.publicKey.publicKey
        assertTrue(identityPublic.verifySignature(bundle.signedPreKeyPublic, bundle.signedPreKeySignature))
        assertTrue(identityPublic.verifySignature(bundle.kyberPreKeyPublic, bundle.kyberPreKeySignature))

        // Roundtrip through the protocol codec.
        val encoded = PublishedPreKeyBundleCodec.encode(bundle) as PublishedBundleEncodeResult.Success
        val decoded = PublishedPreKeyBundleCodec.decode(encoded.bytes) as PublishedBundleDecodeResult.Success
        assertEquals(bundle, decoded.bundle)

        // Rebuild libsignal's PreKeyBundle from the decoded public material.
        val d = decoded.bundle
        val preKeyBundle = PreKeyBundle(
            d.registrationId,
            d.deviceId,
            d.oneTimePreKeyId!!,
            ECPublicKey(d.oneTimePreKeyPublic!!),
            d.signedPreKeyId,
            ECPublicKey(d.signedPreKeyPublic),
            d.signedPreKeySignature,
            IdentityKey(d.identityKey),
            d.kyberPreKeyId,
            KEMPublicKey(d.kyberPreKeyPublic),
            d.kyberPreKeySignature,
        )
        assertEquals(registrationId, preKeyBundle.registrationId)
        assertNotNull(preKeyBundle.kyberPreKey)
    }

    @Test
    fun bundleFallsBackToLastResortWhenNoUnusedOneTimeKyber() = runBlocking {
        val mgr = manager()
        mgr.ensureInventory()
        val dao = db.blockingSignalStoreDao()

        // Mark ALL one-time Kyber keys used, leaving only the reusable last-resort.
        db.runInTransaction {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE signal_kyber_prekeys SET used = 1 WHERE last_resort = 0",
            )
        }
        assertEquals(0, dao.unusedOneTimeKyberCount())
        val lastResortId = dao.latestLastResortKyber()!!.kyberPreKeyId

        // Snapshot WITHOUT ensuring: the only publishable Kyber key is the last-resort,
        // so the bundle must fall back to it and never publish a used one-time key.
        val result = mgr.snapshotBundleForTest() as PublishedBundleResult.Success
        assertEquals(lastResortId, result.bundle.kyberPreKeyId)

        // Last-resort remains present and publishable (used or not, reusable).
        assertEquals(1, dao.lastResortKyberCount())
        assertEquals(lastResortId, dao.latestLastResortKyber()!!.kyberPreKeyId)
    }

    @Test
    fun publicationDoesNotConsumeKeys() = runBlocking {
        val mgr = manager()
        mgr.ensureInventory()
        val dao = db.blockingSignalStoreDao()
        val ecBefore = dao.oneTimePreKeyCount()
        val kyberUnusedBefore = dao.unusedOneTimeKyberCount()

        mgr.createPublishedBundle()

        assertEquals(ecBefore, dao.oneTimePreKeyCount())
        assertEquals(kyberUnusedBefore, dao.unusedOneTimeKyberCount())
    }

    @Test
    fun concurrentEnsureDoesNotOverfill() = runBlocking {
        val mgr = manager()
        val jobs = (1..4).map { async(Dispatchers.Default) { mgr.ensureInventory() } }
        jobs.awaitAll().forEach { assertTrue(it is PreKeyEnsureResult.Success) }

        val dao = db.blockingSignalStoreDao()
        assertEquals(PreKeyInventoryTargets.EC_ONE_TIME_TARGET, dao.oneTimePreKeyCount())
        assertEquals(PreKeyInventoryTargets.KYBER_ONE_TIME_TARGET, dao.unusedOneTimeKyberCount())
        assertEquals(1, dao.lastResortKyberCount())
        assertEquals(1, dao.signedPreKeyTotal())
    }

    @Test
    fun transactionFailureRollsBackWholeBatch() = runBlocking {
        // A runner that throws mid-transaction: nothing must persist.
        val failingRunner = object : SignalTransactionRunner {
            override fun <T> runInTransaction(block: () -> T): T =
                db.runInTransaction(
                    java.util.concurrent.Callable {
                        block()
                        throw IllegalStateException("boom")
                    },
                )
        }
        val result = manager(runner = failingRunner).ensureInventory()
        assertTrue(result is PreKeyEnsureResult.Failure)
        assertEquals(PreKeyManagerError.STORAGE_FAILED, (result as PreKeyEnsureResult.Failure).error)

        val dao = db.blockingSignalStoreDao()
        assertEquals(0, dao.oneTimePreKeyCount())
        assertEquals(0, dao.unusedOneTimeKyberCount())
        assertEquals(0, dao.lastResortKyberCount())
        assertEquals(0, dao.signedPreKeyTotal())
    }

    @Test
    fun missingLocalIdentityFailsClosed() = runBlocking {
        // Wipe the seeded identity row.
        db.runInTransaction {
            db.openHelper.writableDatabase.execSQL("DELETE FROM signal_identity")
        }
        val result = manager().ensureInventory()
        assertEquals(
            PreKeyManagerError.IDENTITY_UNAVAILABLE,
            (result as PreKeyEnsureResult.Failure).error,
        )
    }
}

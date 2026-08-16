package com.meshchats.app.crypto.session

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meshchats.app.crypto.identity.DeviceIdentity
import com.meshchats.app.crypto.identity.DeviceIdentityError
import com.meshchats.app.crypto.identity.DeviceIdentityRepository
import com.meshchats.app.crypto.identity.DeviceIdentityResult
import com.meshchats.app.crypto.identity.IdentitySignResult
import com.meshchats.app.crypto.identity.ScannedIdentityRejection
import com.meshchats.app.crypto.identity.ScannedIdentityResult
import com.meshchats.app.crypto.prekey.DefaultSignalPreKeyManager
import com.meshchats.app.crypto.prekey.LibsignalKeyMaterialFactory
import com.meshchats.app.crypto.prekey.PreKeyIdGenerator
import com.meshchats.app.crypto.prekey.PublishedBundleResult
import com.meshchats.app.crypto.prekey.SignalPreKeyManager
import com.meshchats.app.crypto.prekey.SignalTransactionRunner
import com.meshchats.app.data.local.MeshDatabase
import com.meshchats.app.data.local.SignalIdentityEntity
import com.meshchats.app.data.local.SqlCipherNative
import com.meshchats.app.data.local.SqlCipherRawKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.util.KeyHelper
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device focused spec for [DefaultSignalCryptoEngine] against real
 * SQLCipher-encrypted Room databases and the real libsignal native library.
 *
 * These prove the engine's boundary behaviour: bundle identity binding (rejected
 * without any session/trust write), no-session encrypt, successful establish
 * (session + trusted identity written), PREKEY identity-mismatch rejection before
 * any decrypt write, malformed/oversized input, transaction rollback on injected
 * store failure, and that every native operation runs inside an open Room
 * transaction. The full Alice→Bob PQXDH round trip lives in the Task 6 proof.
 */
@RunWith(AndroidJUnit4::class)
class DefaultSignalCryptoEngineAndroidTest {

    private lateinit var aliceDb: MeshDatabase
    private lateinit var bobDb: MeshDatabase

    private lateinit var aliceIdentity: IdentityKeyPair
    private lateinit var bobIdentity: IdentityKeyPair

    private val aliceKey = SqlCipherRawKey.encode(ByteArray(32) { (it + 11).toByte() })
    private val bobKey = SqlCipherRawKey.encode(ByteArray(32) { (it + 71).toByte() })

    // A fingerprint is just the address seed here; the engine only derives the
    // protocol name from it, never re-checks it against the identity key.
    private val bobFingerprint = ByteArray(32) { (it + 3).toByte() }

    @Before
    fun setUp() {
        SqlCipherNative.ensureLoaded()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        aliceDb = Room.inMemoryDatabaseBuilder(ctx, MeshDatabase::class.java)
            .openHelperFactory(SupportOpenHelperFactory(aliceKey))
            .build()
        bobDb = Room.inMemoryDatabaseBuilder(ctx, MeshDatabase::class.java)
            .openHelperFactory(SupportOpenHelperFactory(bobKey))
            .build()
        aliceIdentity = IdentityKeyPair.generate()
        bobIdentity = IdentityKeyPair.generate()
        seedIdentity(aliceDb, aliceIdentity)
        seedIdentity(bobDb, bobIdentity)
    }

    @After
    fun tearDown() {
        aliceDb.close()
        bobDb.close()
    }

    // --- Helpers -----------------------------------------------------------

    private fun seedIdentity(db: MeshDatabase, pair: IdentityKeyPair) {
        db.runInTransaction {
            db.openHelper.writableDatabase.execSQL(
                "INSERT OR REPLACE INTO signal_identity " +
                    "(id, registration_id, identity_key_pair, schema_version, created_at) VALUES (?,?,?,?,?)",
                arrayOf<Any>(
                    SignalIdentityEntity.SINGLETON_ID,
                    KeyHelper.generateRegistrationId(false),
                    pair.serialize(),
                    1,
                    1_000L,
                ),
            )
        }
    }

    /** getOrCreate always succeeds, reporting [fingerprint] as the local fingerprint. */
    private class FakeIdentityRepository(private val fingerprint: ByteArray) : DeviceIdentityRepository {
        override fun getOrCreateIdentity(): DeviceIdentityResult =
            DeviceIdentityResult.Success(
                DeviceIdentity(
                    edPublicX509 = ByteArray(44),
                    fingerprintSha256 = fingerprint.copyOf(),
                    signalPublicBinding = ByteArray(1),
                    bindingSignature = ByteArray(1),
                    bindingVersion = 1,
                    createdAt = 1_000L,
                    fourWords = listOf("a", "b", "c", "d"),
                ),
            )

        override fun sign(message: ByteArray): IdentitySignResult =
            IdentitySignResult.Failure(DeviceIdentityError.CRYPTO_UNAVAILABLE)

        override fun verify(publicKeyX509: ByteArray, message: ByteArray, signature: ByteArray): Boolean = false
        override fun qrPayload(): DeviceIdentityResult = getOrCreateIdentity()
        override fun verifyScannedPayload(text: String): ScannedIdentityResult =
            ScannedIdentityResult.Rejected(ScannedIdentityRejection.MALFORMED)
    }

    private fun runner(db: MeshDatabase) = object : SignalTransactionRunner {
        override fun <T> runInTransaction(block: () -> T): T = db.runInTransaction(Callable { block() })
    }

    private fun preKeyManager(db: MeshDatabase, fingerprint: ByteArray): SignalPreKeyManager =
        DefaultSignalPreKeyManager(
            identityRepository = FakeIdentityRepository(fingerprint),
            dao = db.blockingSignalStoreDao(),
            factory = LibsignalKeyMaterialFactory(),
            idGenerator = PreKeyIdGenerator(randomInt = SecureRandom()::nextInt),
            transactionRunner = runner(db),
            dispatcher = Dispatchers.IO.limitedParallelism(1),
            clock = { 5_000L },
        )

    private fun engine(
        db: MeshDatabase,
        fingerprint: ByteArray,
        transactionRunner: SignalTransactionRunner = runner(db),
        preKeyManager: SignalPreKeyManager = preKeyManager(db, fingerprint),
    ) = DefaultSignalCryptoEngine(
        identityRepository = FakeIdentityRepository(fingerprint),
        preKeyManager = preKeyManager,
        dao = db.blockingSignalStoreDao(),
        transactionRunner = transactionRunner,
        dispatcher = Dispatchers.IO.limitedParallelism(1),
        clock = { Instant.ofEpochMilli(5_000L) },
    )

    private val aliceFingerprint = ByteArray(32) { (it + 99).toByte() }

    private fun bobBundleAndPeer(): Pair<com.meshchats.protocol.wire.PublishedPreKeyBundle, VerifiedSignalPeer> {
        val bob = engine(bobDb, bobFingerprint)
        val result = runBlocking { bob.createPublishedBundle() }
        assertTrue(result is SignalBundleResult.Success)
        val bundle = (result as SignalBundleResult.Success).bundle
        val peer = VerifiedSignalPeer(
            fingerprintSha256 = bobFingerprint,
            deviceId = bundle.deviceId,
            expectedSignalIdentityKey = bundle.identityKey,
        )
        return bundle to peer
    }

    private fun bobAddress(peer: VerifiedSignalPeer) = SignalProtocolAddress(peer.protocolName, peer.deviceId)

    /**
     * Rebuilds [base] replacing selected public-key fields, so a test can present a
     * structurally malformed EC/identity/Kyber key to the engine while keeping the
     * rest of the bundle valid.
     */
    private fun copyBundle(
        base: com.meshchats.protocol.wire.PublishedPreKeyBundle,
        signedPreKeyPublic: ByteArray = base.signedPreKeyPublic,
        identityKey: ByteArray = base.identityKey,
        kyberPreKeyPublic: ByteArray = base.kyberPreKeyPublic,
    ) = com.meshchats.protocol.wire.PublishedPreKeyBundle(
        registrationId = base.registrationId,
        deviceId = base.deviceId,
        oneTimePreKeyId = base.oneTimePreKeyId,
        oneTimePreKeyPublic = base.oneTimePreKeyPublic,
        signedPreKeyId = base.signedPreKeyId,
        signedPreKeyPublic = signedPreKeyPublic,
        signedPreKeySignature = base.signedPreKeySignature,
        identityKey = identityKey,
        kyberPreKeyId = base.kyberPreKeyId,
        kyberPreKeyPublic = kyberPreKeyPublic,
        kyberPreKeySignature = base.kyberPreKeySignature,
        issuedAtEpochMillis = base.issuedAtEpochMillis,
    )

    // --- Tests -------------------------------------------------------------

    @Test
    fun mismatchedBundleIdentityRejectedWithoutSessionOrTrustWrite() = runBlocking {
        val (bundle, peer) = bobBundleAndPeer()
        // A peer whose expected identity key does NOT match the bundle.
        val wrongPeer = VerifiedSignalPeer(
            fingerprintSha256 = bobFingerprint,
            deviceId = bundle.deviceId,
            expectedSignalIdentityKey = ByteArray(bundle.identityKey.size) { 0 },
        )
        val alice = engine(aliceDb, aliceFingerprint)
        val result = alice.establishSession(wrongPeer, bundle)
        assertEquals(SignalCryptoError.REMOTE_IDENTITY_MISMATCH, (result as SignalSessionResult.Failure).error)

        // No session and no trusted identity were written.
        val dao = aliceDb.blockingSignalStoreDao()
        assertEquals(0, dao.sessionCount(wrongPeer.protocolName, wrongPeer.deviceId))
        assertEquals(null, dao.trustedIdentity(wrongPeer.protocolName, wrongPeer.deviceId))
        // hasSession reflects the absence.
        val has = alice.hasSession(peer) as SignalHasSessionResult.Success
        assertFalse(has.hasSession)
    }

    @Test
    fun mismatchedBundleDeviceIdRejected() = runBlocking {
        val (bundle, peer) = bobBundleAndPeer()
        val wrongDevicePeer = VerifiedSignalPeer(
            fingerprintSha256 = bobFingerprint,
            deviceId = peer.deviceId + 1,
            expectedSignalIdentityKey = bundle.identityKey,
        )
        val alice = engine(aliceDb, aliceFingerprint)
        val result = alice.establishSession(wrongDevicePeer, bundle)
        assertEquals(SignalCryptoError.REMOTE_IDENTITY_MISMATCH, (result as SignalSessionResult.Failure).error)
    }

    @Test
    fun establishCreatesSessionAndTrustedIdentity() = runBlocking {
        val (bundle, peer) = bobBundleAndPeer()
        val alice = engine(aliceDb, aliceFingerprint)

        val result = alice.establishSession(peer, bundle)
        assertTrue(result is SignalSessionResult.Success)

        val dao = aliceDb.blockingSignalStoreDao()
        assertEquals(1, dao.sessionCount(peer.protocolName, peer.deviceId))
        val trusted = dao.trustedIdentity(peer.protocolName, peer.deviceId)!!
        assertArrayEquals(peer.expectedSignalIdentityKey, trusted.identityKey)

        val has = alice.hasSession(peer) as SignalHasSessionResult.Success
        assertTrue(has.hasSession)
    }

    @Test
    fun encryptWithoutSessionFailsClosed() = runBlocking {
        val (_, peer) = bobBundleAndPeer()
        val alice = engine(aliceDb, aliceFingerprint)
        // No establish: no stored trusted identity for the peer.
        val result = alice.encrypt(peer, "hi".toByteArray())
        assertEquals(SignalCryptoError.NO_SESSION, (result as SignalEncryptResult.Failure).error)
    }

    @Test
    fun encryptAfterEstablishProducesPreKeyCiphertext() = runBlocking {
        val (bundle, peer) = bobBundleAndPeer()
        val alice = engine(aliceDb, aliceFingerprint)
        assertTrue(alice.establishSession(peer, bundle) is SignalSessionResult.Success)

        val result = alice.encrypt(peer, "hello bob".toByteArray())
        assertTrue(result is SignalEncryptResult.Success)
        // The first message on a fresh outbound session is a PREKEY message.
        assertEquals(SignalCiphertextType.PREKEY, (result as SignalEncryptResult.Success).ciphertext.type)
    }

    @Test
    fun emptyPlaintextRejected() = runBlocking {
        val (_, peer) = bobBundleAndPeer()
        val alice = engine(aliceDb, aliceFingerprint)
        val result = alice.encrypt(peer, ByteArray(0))
        assertEquals(SignalCryptoError.INVALID_INPUT, (result as SignalEncryptResult.Failure).error)
    }

    @Test
    fun oversizedPlaintextRejected() = runBlocking {
        val (_, peer) = bobBundleAndPeer()
        val alice = engine(aliceDb, aliceFingerprint)
        val result = alice.encrypt(peer, ByteArray(DefaultSignalCryptoEngine.MAX_PLAINTEXT_BYTES + 1))
        assertEquals(SignalCryptoError.INVALID_INPUT, (result as SignalEncryptResult.Failure).error)
    }

    @Test
    fun malformedPreKeyCiphertextRejectedBeforeDecrypt() = runBlocking {
        val (_, peer) = bobBundleAndPeer()
        val alice = engine(aliceDb, aliceFingerprint)
        val garbage = SignalCiphertext(SignalCiphertextType.PREKEY, ByteArray(64) { 0x7F })
        val result = alice.decrypt(peer, garbage)
        assertEquals(SignalCryptoError.MALFORMED_MESSAGE, (result as SignalDecryptResult.Failure).error)
    }

    @Test
    fun preKeyIdentityMismatchRejectedBeforeDecryptWrites() = runBlocking {
        // Bob builds a real PREKEY message to Alice, but Alice's peer record for Bob
        // expects a DIFFERENT identity key, so the embedded-identity check fails.
        val alice = engine(aliceDb, aliceFingerprint)
        val aliceBundleResult = alice.createPublishedBundle() as SignalBundleResult.Success
        val aliceBundle = aliceBundleResult.bundle
        val alicePeerForBob = VerifiedSignalPeer(
            fingerprintSha256 = aliceFingerprint,
            deviceId = aliceBundle.deviceId,
            expectedSignalIdentityKey = aliceBundle.identityKey,
        )
        val bob = engine(bobDb, bobFingerprint)
        assertTrue(bob.establishSession(alicePeerForBob, aliceBundle) is SignalSessionResult.Success)
        val prekeyMsg = bob.encrypt(alicePeerForBob, "hi alice".toByteArray()) as SignalEncryptResult.Success
        assertEquals(SignalCiphertextType.PREKEY, prekeyMsg.ciphertext.type)

        // Alice decrypts, but binds Bob's address to a WRONG expected identity key.
        val wrongBobPeer = VerifiedSignalPeer(
            fingerprintSha256 = bobFingerprint,
            deviceId = 1,
            expectedSignalIdentityKey = ByteArray(bobIdentity.publicKey.serialize().size) { 1 },
        )
        val result = alice.decrypt(wrongBobPeer, prekeyMsg.ciphertext)
        assertEquals(SignalCryptoError.REMOTE_IDENTITY_MISMATCH, (result as SignalDecryptResult.Failure).error)

        // No session or trusted identity written for the mismatched peer.
        val dao = aliceDb.blockingSignalStoreDao()
        assertEquals(0, dao.sessionCount(wrongBobPeer.protocolName, wrongBobPeer.deviceId))
    }

    @Test
    fun malformedWhisperFailsBeforeSessionLookup() = runBlocking {
        val (_, peer) = bobBundleAndPeer()
        val alice = engine(aliceDb, aliceFingerprint)
        // Parsing attacker-controlled bytes happens before any store lookup. An
        // invalid WHISPER is MALFORMED even when no session exists, avoiding DB
        // work and preventing state-dependent parsing errors.
        val fake = SignalCiphertext(SignalCiphertextType.WHISPER, ByteArray(48) { 0x33 })
        val result = alice.decrypt(peer, fake)
        assertEquals(SignalCryptoError.MALFORMED_MESSAGE, (result as SignalDecryptResult.Failure).error)
    }

    @Test
    fun oversizedCiphertextRejected() = runBlocking {
        val (_, peer) = bobBundleAndPeer()
        val alice = engine(aliceDb, aliceFingerprint)
        val huge = SignalCiphertext(
            SignalCiphertextType.WHISPER,
            ByteArray(DefaultSignalCryptoEngine.MAX_CIPHERTEXT_BYTES + 1) { 1 },
        )
        val result = alice.decrypt(peer, huge)
        assertEquals(SignalCryptoError.INVALID_INPUT, (result as SignalDecryptResult.Failure).error)
    }

    @Test
    fun establishRunsInsideOpenTransactionAndRollsBackOnFailure() = runBlocking {
        val (bundle, peer) = bobBundleAndPeer()

        // A runner that asserts the DB IS in a transaction during the operation, then
        // throws AFTER the block runs to force a rollback of every store write.
        val sawTransaction = AtomicBoolean(false)
        val failingRunner = object : SignalTransactionRunner {
            override fun <T> runInTransaction(block: () -> T): T =
                aliceDb.runInTransaction(
                    Callable {
                        val r = block()
                        // Every store callback the block triggered ran inside this open transaction.
                        if (aliceDb.openHelper.writableDatabase.inTransaction()) sawTransaction.set(true)
                        throw IllegalStateException("boom")
                        @Suppress("UNREACHABLE_CODE")
                        r
                    },
                )
        }
        val alice = engine(aliceDb, aliceFingerprint, transactionRunner = failingRunner)
        val result = alice.establishSession(peer, bundle)
        // An arbitrary injected operation failure maps to the bounded catch-all.
        assertEquals(SignalCryptoError.CRYPTO_UNAVAILABLE, (result as SignalSessionResult.Failure).error)
        assertTrue("operation must run inside an open Room transaction", sawTransaction.get())

        // Rolled back: no session, no trusted identity.
        val dao = aliceDb.blockingSignalStoreDao()
        assertEquals(0, dao.sessionCount(peer.protocolName, peer.deviceId))
        assertEquals(null, dao.trustedIdentity(peer.protocolName, peer.deviceId))
    }

    @Test
    fun missingLocalIdentityFailsClosed() = runBlocking {
        aliceDb.runInTransaction {
            aliceDb.openHelper.writableDatabase.execSQL("DELETE FROM signal_identity")
        }
        val (bundle, peer) = bobBundleAndPeer()
        // The prekey manager fake reports identity present, but the store read of the
        // local identity happens inside libsignal; with the row deleted the native
        // process fails. Establish still binds identity first (fake ok), then the
        // transaction fails on the missing local identity → bounded, non-success.
        val alice = engine(aliceDb, aliceFingerprint)
        val result = alice.establishSession(peer, bundle)
        assertTrue(result is SignalSessionResult.Failure)
    }

    @Test
    fun malformedSignedPreKeyBundleRejectedAsInvalidInput() = runBlocking {
        val (bundle, _) = bobBundleAndPeer()
        // Corrupt the signed EC prekey so ECPublicKey(...) cannot parse it. The peer
        // still expects the (unchanged, valid) identity key, so the identity bind
        // passes and the malformed EC key is what fails — bounded INVALID_INPUT.
        val bad = copyBundle(bundle, signedPreKeyPublic = ByteArray(5) { 0x7F })
        val peer = VerifiedSignalPeer(
            fingerprintSha256 = bobFingerprint,
            deviceId = bad.deviceId,
            expectedSignalIdentityKey = bad.identityKey,
        )
        val alice = engine(aliceDb, aliceFingerprint)
        val result = alice.establishSession(peer, bad)
        assertEquals(SignalCryptoError.INVALID_INPUT, (result as SignalSessionResult.Failure).error)

        // Nothing written for the malformed bundle.
        val dao = aliceDb.blockingSignalStoreDao()
        assertEquals(0, dao.sessionCount(peer.protocolName, peer.deviceId))
        assertEquals(null, dao.trustedIdentity(peer.protocolName, peer.deviceId))
    }

    @Test
    fun malformedIdentityKeyBundleRejectedAsInvalidInput() = runBlocking {
        val (bundle, _) = bobBundleAndPeer()
        // Corrupt the identity key AND expect the same corrupt bytes so the identity
        // bind check passes; IdentityKey(...) then fails to parse → INVALID_INPUT.
        val badIdentity = ByteArray(bundle.identityKey.size) { 0x00 }
        val bad = copyBundle(bundle, identityKey = badIdentity)
        val peer = VerifiedSignalPeer(
            fingerprintSha256 = bobFingerprint,
            deviceId = bad.deviceId,
            expectedSignalIdentityKey = badIdentity,
        )
        val alice = engine(aliceDb, aliceFingerprint)
        val result = alice.establishSession(peer, bad)
        assertEquals(SignalCryptoError.INVALID_INPUT, (result as SignalSessionResult.Failure).error)
    }

    @Test
    fun malformedKyberPreKeyBundleRejectedAsInvalidInput() = runBlocking {
        val (bundle, _) = bobBundleAndPeer()
        // Corrupt the Kyber prekey so KEMPublicKey(...) cannot parse it.
        val bad = copyBundle(bundle, kyberPreKeyPublic = ByteArray(9) { 0x55 })
        val peer = VerifiedSignalPeer(
            fingerprintSha256 = bobFingerprint,
            deviceId = bad.deviceId,
            expectedSignalIdentityKey = bad.identityKey,
        )
        val alice = engine(aliceDb, aliceFingerprint)
        val result = alice.establishSession(peer, bad)
        assertEquals(SignalCryptoError.INVALID_INPUT, (result as SignalSessionResult.Failure).error)

        val dao = aliceDb.blockingSignalStoreDao()
        assertEquals(0, dao.sessionCount(peer.protocolName, peer.deviceId))
        assertEquals(null, dao.trustedIdentity(peer.protocolName, peer.deviceId))
    }

    @Test
    fun encryptWithChangedKnownIdentityRejectedAndRolledBack() = runBlocking {
        // Establish a real session (stores Bob's trusted identity), then encrypt with
        // a peer that binds Bob's address to a DIFFERENT expected identity key. The
        // in-transaction stored-identity check sees a mismatch and fails closed with
        // REMOTE_IDENTITY_MISMATCH, rolling back before any write.
        val (bundle, peer) = bobBundleAndPeer()
        val alice = engine(aliceDb, aliceFingerprint)
        assertTrue(alice.establishSession(peer, bundle) is SignalSessionResult.Success)

        val changedPeer = VerifiedSignalPeer(
            fingerprintSha256 = bobFingerprint,
            deviceId = peer.deviceId,
            expectedSignalIdentityKey = ByteArray(peer.expectedSignalIdentityKey.size) { 0x01 },
        )
        val result = alice.encrypt(changedPeer, "hello".toByteArray())
        assertEquals(SignalCryptoError.REMOTE_IDENTITY_MISMATCH, (result as SignalEncryptResult.Failure).error)

        // The originally stored trusted identity is unchanged.
        val dao = aliceDb.blockingSignalStoreDao()
        val trusted = dao.trustedIdentity(peer.protocolName, peer.deviceId)!!
        assertArrayEquals(peer.expectedSignalIdentityKey, trusted.identityKey)
    }

}

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
import com.meshchats.app.crypto.prekey.SignalTransactionRunner
import com.meshchats.app.data.local.MarkKyberUsedResult
import com.meshchats.app.data.local.MeshDatabase
import com.meshchats.app.data.local.SignalIdentityEntity
import com.meshchats.app.data.local.SqlCipherNative
import com.meshchats.app.data.local.SqlCipherRawKey
import com.meshchats.protocol.wire.PublishedPreKeyBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.util.KeyHelper
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Phase 2B Task 6 end-to-end proof of the Signal PQXDH + Double Ratchet stack:
 * a full Alice ⇄ Bob round trip over TWO isolated, FILE-BACKED, SQLCipher-encrypted
 * Room v3 databases and the real libsignal 0.100 native library, driving the real
 * [DefaultSignalPreKeyManager] and [DefaultSignalCryptoEngine] end to end.
 *
 * Where [DefaultSignalCryptoEngineAndroidTest] proves the engine's *boundary*
 * behaviour against a single store, this proves the *protocol* works between two
 * independent identities: a PREKEY handshake that consumes Bob's one-time keys and
 * records Kyber base-key replay state, ordered/out-of-order/duplicate/tampered
 * Double Ratchet delivery, session + trust survival across a full database
 * close/reopen with the same SQLCipher keys, the last-resort Kyber fallback with
 * exact base-key replay rejection, and that a rejected identity substitution leaves
 * the live session usable.
 *
 * Alice and Bob are fully separate: distinct SQLCipher raw keys, distinct Signal
 * identity key pairs, and distinct 32-byte fingerprints (so their derived protocol
 * addresses can never collide). Each [Party] owns its own single-parallelism crypto
 * dispatcher, prekey manager, and engine over its own encrypted file DB. Every
 * [VerifiedSignalPeer]'s expected identity key is the peer's real serialized Signal
 * identity public key, and its name is derived from the peer's full fingerprint.
 * No sleeps or timing; [runBlocking] is used only at the test boundary while the
 * production engine/manager stay dispatcher-based.
 */
@RunWith(AndroidJUnit4::class)
class SignalCryptoEngineRoundTripAndroidTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    // Distinct, deterministic SQLCipher raw keys — fixed byte patterns, never a
    // production key alias, so a reopen re-derives the identical database key.
    private val aliceDbKey = ByteArray(32) { (it + 21).toByte() }
    private val bobDbKey = ByteArray(32) { (it + 81).toByte() }

    // Distinct, deterministic full 32-byte identity fingerprints. The protocol
    // address name is derived canonically from the FULL fingerprint, so Alice and
    // Bob resolve to different, non-colliding libsignal addresses.
    private val aliceFingerprint = ByteArray(32) { (it * 7 + 1).toByte() }
    private val bobFingerprint = ByteArray(32) { (it * 5 + 2).toByte() }

    private lateinit var aliceDbName: String
    private lateinit var bobDbName: String
    private lateinit var aliceIdentity: IdentityKeyPair
    private lateinit var bobIdentity: IdentityKeyPair

    // Every DB instance opened during a test, closed and file-deleted in teardown.
    private val openDatabases = ArrayList<MeshDatabase>()
    private val databaseNames = LinkedHashSet<String>()

    private lateinit var alice: Party
    private lateinit var bob: Party

    @Before
    fun setUp() {
        SqlCipherNative.ensureLoaded()
        val suffix = uniqueSuffix.incrementAndGet()
        aliceDbName = "roundtrip-alice-$suffix.db"
        bobDbName = "roundtrip-bob-$suffix.db"
        // Delete any leftovers from a crashed prior run so each test starts clean.
        deleteDatabaseFiles(aliceDbName)
        deleteDatabaseFiles(bobDbName)

        aliceIdentity = IdentityKeyPair.generate()
        bobIdentity = IdentityKeyPair.generate()

        val aliceDb = openDatabase(aliceDbName, aliceDbKey)
        val bobDb = openDatabase(bobDbName, bobDbKey)
        seedIdentity(aliceDb, aliceIdentity)
        seedIdentity(bobDb, bobIdentity)

        alice = Party(aliceDb, aliceFingerprint)
        bob = Party(bobDb, bobFingerprint)
    }

    @After
    fun tearDown() {
        openDatabases.forEach { db ->
            try {
                db.close()
            } catch (_: Throwable) {
                // Already closed (e.g. reopened during the persistence test) — ignore.
            }
        }
        openDatabases.clear()
        databaseNames.forEach { deleteDatabaseFiles(it) }
        databaseNames.clear()
    }

    // === Scenario 1: core PQXDH handshake + prekey consumption ============

    @Test
    fun pqxdhHandshakeConsumesOneTimeKeysAndTracksBaseKeyThenReplies() = runBlocking {
        val hs = handshake(alice, bob)
        val bundle = hs.bundle

        // Bob consumed the published one-time EC prekey: its row is gone.
        val oneTimeId = bundle.oneTimePreKeyId!!
        assertNull("one-time EC prekey must be consumed", bob.db.signalPreKeyDao().load(oneTimeId))

        // The selected one-time Kyber prekey is marked used (retained, not deleted).
        val kyber = bob.db.signalKyberPreKeyDao().load(bundle.kyberPreKeyId)
        assertNotNull(kyber)
        assertTrue("selected Kyber prekey must be marked used", kyber!!.used)
        assertFalse("handshake selected a one-time (non-last-resort) Kyber key", kyber.lastResort)

        // Exactly one Kyber base-key replay row was inserted for the consumption.
        assertEquals(1, bob.db.signalKyberBaseKeyDao().baseKeyCount())
        assertEquals(1, bob.db.signalKyberBaseKeyDao().baseKeysFor(bundle.kyberPreKeyId).size)

        // Both sides report a trusted, established session.
        assertTrue((alice.engine.hasSession(hs.peerBob) as SignalHasSessionResult.Success).hasSession)
        assertTrue((bob.engine.hasSession(hs.peerAlice) as SignalHasSessionResult.Success).hasSession)

        // Trusted identities are byte-exact: Alice trusts Bob's real key, Bob trusts Alice's.
        val bobTrustedByAlice = alice.db.signalIdentityDao()
            .getTrusted(hs.peerBob.protocolName, hs.peerBob.deviceId)
        assertNotNull(bobTrustedByAlice)
        assertArrayEquals(bundle.identityKey, bobTrustedByAlice!!.identityKey)

        val aliceTrustedByBob = bob.db.signalIdentityDao()
            .getTrusted(hs.peerAlice.protocolName, hs.peerAlice.deviceId)
        assertNotNull(aliceTrustedByBob)
        assertArrayEquals(aliceIdentity.publicKey.serialize(), aliceTrustedByBob!!.identityKey)
    }

    // === Scenario 2: Double Ratchet — order, duplicates, tampering ========

    @Test
    fun doubleRatchetHandlesOutOfOrderInterleaveDuplicateAndTamper() = runBlocking {
        val hs = handshake(alice, bob)
        val peerBob = hs.peerBob
        val peerAlice = hs.peerAlice

        // Three Alice→Bob WHISPER messages, produced in order (ratchet advances).
        val m1 = encryptWhisper(alice, peerBob, "msg-one")
        val m2 = encryptWhisper(alice, peerBob, "msg-two")
        val m3 = encryptWhisper(alice, peerBob, "msg-three")

        // Delivered out of order 3, 1, 2 — libsignal buffers skipped keys.
        decryptExpect(bob, peerAlice, m3, "msg-three")
        decryptExpect(bob, peerAlice, m1, "msg-one")
        decryptExpect(bob, peerAlice, m2, "msg-two")

        // Interleave both directions on the live session.
        val fromBob = encryptWhisper(bob, peerAlice, "bob-reply")
        decryptExpect(alice, peerBob, fromBob, "bob-reply")
        val fromAlice = encryptWhisper(alice, peerBob, "alice-again")
        decryptExpect(bob, peerAlice, fromAlice, "alice-again")

        // Replaying an already-decrypted ciphertext is rejected as a duplicate.
        val dup = bob.engine.decrypt(peerAlice, m1)
        assertEquals(SignalCryptoError.DUPLICATE_MESSAGE, (dup as SignalDecryptResult.Failure).error)

        // A tampered ciphertext fails bounded (never a spurious success) and the
        // session stays usable for the next valid message.
        val victim = encryptWhisper(alice, peerBob, "tamper-target")
        val mutated = victim.bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val tampered = bob.engine.decrypt(peerAlice, SignalCiphertext(SignalCiphertextType.WHISPER, mutated))
        assertTrue(tampered is SignalDecryptResult.Failure)
        assertTrue(
            "tampered ciphertext must fail MALFORMED or DUPLICATE, was ${(tampered as SignalDecryptResult.Failure).error}",
            tampered.error == SignalCryptoError.MALFORMED_MESSAGE ||
                tampered.error == SignalCryptoError.DUPLICATE_MESSAGE,
        )
        // The pristine victim message still decrypts: state was not corrupted.
        decryptExpect(bob, peerAlice, victim, "tamper-target")
        // And the ratchet keeps advancing afterwards.
        val after = encryptWhisper(alice, peerBob, "after-tamper")
        decryptExpect(bob, peerAlice, after, "after-tamper")
    }

    // === Scenario 3: persistence across a full DB close/reopen ============

    @Test
    fun sessionAndTrustSurviveDatabaseCloseAndReopen() = runBlocking {
        val hs = handshake(alice, bob)

        // Close BOTH databases entirely.
        alice.db.close()
        bob.db.close()

        // Reopen the SAME files with the SAME SQLCipher keys and rebuild the stack.
        val aliceDb = openDatabase(aliceDbName, aliceDbKey)
        val bobDb = openDatabase(bobDbName, bobDbKey)
        alice = Party(aliceDb, aliceFingerprint)
        bob = Party(bobDb, bobFingerprint)

        // The persisted session/trust must still be present after reopen.
        assertTrue((alice.engine.hasSession(hs.peerBob) as SignalHasSessionResult.Success).hasSession)
        assertTrue((bob.engine.hasSession(hs.peerAlice) as SignalHasSessionResult.Success).hasSession)

        // Bidirectional exchange continues as WHISPER — no PREKEY re-establishment.
        val a2b = encryptWhisper(alice, hs.peerBob, "after-reopen-a")
        decryptExpect(bob, hs.peerAlice, a2b, "after-reopen-a")
        val b2a = encryptWhisper(bob, hs.peerAlice, "after-reopen-b")
        decryptExpect(alice, hs.peerBob, b2a, "after-reopen-b")

        // Trusted identity bytes survived the reopen unchanged.
        val bobTrustedByAlice = alice.db.signalIdentityDao()
            .getTrusted(hs.peerBob.protocolName, hs.peerBob.deviceId)
        assertNotNull(bobTrustedByAlice)
        assertArrayEquals(bobIdentity.publicKey.serialize(), bobTrustedByAlice!!.identityKey)
    }

    // === Scenario 4: last-resort Kyber fallback + exact base-key replay ===

    @Test
    fun lastResortKyberHandshakeSucceedsAndExactReplayIsRejected() = runBlocking {
        // Provision Bob's full inventory, then exhaust every one-time Kyber key so
        // only the reusable last-resort remains publishable.
        assertTrue(bob.engine.ensureInventory() is SignalEnsureResult.Success)
        bob.db.runInTransaction {
            bob.db.openHelper.writableDatabase.execSQL(
                "UPDATE signal_kyber_prekeys SET used = 1 WHERE last_resort = 0",
            )
        }
        val bobDao = bob.db.blockingSignalStoreDao()
        assertEquals(0, bobDao.unusedOneTimeKyberCount())
        val lastResortId = bobDao.latestLastResortKyber()!!.kyberPreKeyId

        // Snapshot a bundle WITHOUT re-ensuring (the test seam): it must fall back
        // to the last-resort Kyber key.
        val snapshot = bob.preKeyManager.snapshotBundleForTest() as PublishedBundleResult.Success
        val bundle = snapshot.bundle
        assertEquals(lastResortId, bundle.kyberPreKeyId)

        val peerBob = peerForBob(bundle)
        val peerAlice = peerForAlice()

        // Alice establishes and sends the first PREKEY; Bob decrypts it successfully
        // using the last-resort Kyber key.
        assertTrue(alice.engine.establishSession(peerBob, bundle) is SignalSessionResult.Success)
        val prekey = alice.engine.encrypt(peerBob, "first-with-last-resort".toByteArray())
            as SignalEncryptResult.Success
        assertEquals(SignalCiphertextType.PREKEY, prekey.ciphertext.type)
        val decrypted = bob.engine.decrypt(peerAlice, prekey.ciphertext) as SignalDecryptResult.Success
        assertArrayEquals("first-with-last-resort".toByteArray(), decrypted.plaintext)

        // The last-resort record REMAINS (reusable) and is now marked used.
        val lastResort = bob.db.signalKyberPreKeyDao().load(lastResortId)
        assertNotNull(lastResort)
        assertTrue(lastResort!!.lastResort)
        assertTrue("last-resort Kyber must be marked used after consumption", lastResort.used)

        // A base-key replay tracking row exists for the consumption.
        val baseRows = bob.db.signalKyberBaseKeyDao().baseKeysFor(lastResortId)
        assertEquals(1, baseRows.size)

        // Exact ciphertext replay is normally rejected as DUPLICATE_MESSAGE before
        // libsignal reaches its Kyber base-key callback. Accept REUSED_BASE_KEY too
        // because native validation order is an implementation detail; either is a
        // fail-closed replay rejection. The focused engine test separately pins the
        // ReusedBaseKeyException → REUSED_BASE_KEY mapper, and the direct callback
        // below proves the durable exact-triple guard itself.
        val replay = bob.engine.decrypt(peerAlice, prekey.ciphertext)
        assertTrue(replay is SignalDecryptResult.Failure)
        assertTrue(
            "exact replay must be DUPLICATE or REUSED_BASE_KEY, was ${(replay as SignalDecryptResult.Failure).error}",
            replay.error == SignalCryptoError.DUPLICATE_MESSAGE ||
                replay.error == SignalCryptoError.REUSED_BASE_KEY,
        )

        // Direct store-callback proof of the exact-triple replay guard: re-marking
        // the same (kyber, signed, base-key) triple returns REUSED while the
        // last-resort record remains present and used.
        val row = baseRows.first()
        val reused = bobDao.markKyberUsedWithBaseKeyBlocking(
            kyberId = lastResortId,
            signedPreKeyId = row.signedPreKeyId,
            baseKey = row.baseKey,
            now = 5_000L,
        )
        assertEquals(MarkKyberUsedResult.REUSED, reused)
        val stillPresent = bob.db.signalKyberPreKeyDao().load(lastResortId)
        assertNotNull(stillPresent)
        assertTrue(stillPresent!!.used)
    }

    // === Scenario 5: rejected identity substitution leaves session usable =

    @Test
    fun rejectedIdentitySubstitutionLeavesLiveSessionUsable() = runBlocking {
        val hs = handshake(alice, bob)

        // Encrypt to Bob's address but bind it to a WRONG expected identity key.
        val wrongPeer = VerifiedSignalPeer(
            fingerprintSha256 = bobFingerprint,
            deviceId = hs.peerBob.deviceId,
            expectedSignalIdentityKey = ByteArray(hs.peerBob.expectedSignalIdentityKey.size) { 0x01 },
        )
        val rejected = alice.engine.encrypt(wrongPeer, "should-not-send".toByteArray())
        assertEquals(SignalCryptoError.REMOTE_IDENTITY_MISMATCH, (rejected as SignalEncryptResult.Failure).error)

        // The correctly-bound session is unaffected and still exchanges messages.
        val ok = encryptWhisper(alice, hs.peerBob, "still-working")
        decryptExpect(bob, hs.peerAlice, ok, "still-working")
    }

    // === Handshake + message helpers (suspend; runBlocking stays at the boundary) =

    /** Result of a completed PREKEY handshake: the bound peers and Bob's bundle. */
    private class HandshakeResult(
        val peerBob: VerifiedSignalPeer,
        val peerAlice: VerifiedSignalPeer,
        val bundle: PublishedPreKeyBundle,
    )

    /**
     * Drives a full initial handshake: Bob publishes a bundle, Alice establishes and
     * sends the first PREKEY, Bob decrypts it, Bob replies with a WHISPER, and Alice
     * decrypts the reply — leaving both sides with a live, WHISPER-ready session.
     */
    private suspend fun handshake(alice: Party, bob: Party): HandshakeResult {
        val bundleResult = bob.engine.createPublishedBundle()
        assertTrue(bundleResult is SignalBundleResult.Success)
        val bundle = (bundleResult as SignalBundleResult.Success).bundle

        val peerBob = peerForBob(bundle)
        val peerAlice = peerForAlice()

        assertTrue(alice.engine.establishSession(peerBob, bundle) is SignalSessionResult.Success)

        val prekey = alice.engine.encrypt(peerBob, "hello-bob".toByteArray()) as SignalEncryptResult.Success
        assertEquals(SignalCiphertextType.PREKEY, prekey.ciphertext.type)
        val bobRecovered = bob.engine.decrypt(peerAlice, prekey.ciphertext) as SignalDecryptResult.Success
        assertArrayEquals("hello-bob".toByteArray(), bobRecovered.plaintext)

        val reply = bob.engine.encrypt(peerAlice, "hi-alice".toByteArray()) as SignalEncryptResult.Success
        assertEquals(SignalCiphertextType.WHISPER, reply.ciphertext.type)
        val aliceRecovered = alice.engine.decrypt(peerBob, reply.ciphertext) as SignalDecryptResult.Success
        assertArrayEquals("hi-alice".toByteArray(), aliceRecovered.plaintext)

        return HandshakeResult(peerBob, peerAlice, bundle)
    }

    /** Bob's verified peer, bound to Bob's real identity from his published bundle. */
    private fun peerForBob(bundle: PublishedPreKeyBundle) = VerifiedSignalPeer(
        fingerprintSha256 = bobFingerprint,
        deviceId = bundle.deviceId,
        expectedSignalIdentityKey = bundle.identityKey,
    )

    /** Alice's verified peer, bound to Alice's real serialized Signal identity key. */
    private fun peerForAlice() = VerifiedSignalPeer(
        fingerprintSha256 = aliceFingerprint,
        deviceId = DefaultSignalCryptoEngine.LOCAL_DEVICE_ID,
        expectedSignalIdentityKey = aliceIdentity.publicKey.serialize(),
    )

    private suspend fun encryptWhisper(from: Party, to: VerifiedSignalPeer, text: String): SignalCiphertext {
        val result = from.engine.encrypt(to, text.toByteArray())
        assertTrue("encrypt('$text') must succeed", result is SignalEncryptResult.Success)
        val ciphertext = (result as SignalEncryptResult.Success).ciphertext
        assertEquals(SignalCiphertextType.WHISPER, ciphertext.type)
        return ciphertext
    }

    private suspend fun decryptExpect(
        on: Party,
        from: VerifiedSignalPeer,
        ciphertext: SignalCiphertext,
        expected: String,
    ) {
        val result = on.engine.decrypt(from, ciphertext)
        assertTrue("decrypt must succeed for '$expected'", result is SignalDecryptResult.Success)
        assertArrayEquals(expected.toByteArray(), (result as SignalDecryptResult.Success).plaintext)
    }

    // === Party / DB plumbing ==============================================

    /**
     * One end of the conversation: a real engine + prekey manager over one encrypted
     * file DB, on its own single-parallelism crypto dispatcher. The
     * [DeviceIdentityRepository] reports [fingerprint] as the local fingerprint so
     * the engine derives this party's stable local protocol address from it.
     */
    private inner class Party(val db: MeshDatabase, fingerprint: ByteArray) {
        private val dispatcher = Dispatchers.IO.limitedParallelism(1)
        private val identityRepository = FakeIdentityRepository(fingerprint)
        private val runner = object : SignalTransactionRunner {
            override fun <T> runInTransaction(block: () -> T): T = db.runInTransaction(Callable { block() })
        }
        val preKeyManager = DefaultSignalPreKeyManager(
            identityRepository = identityRepository,
            dao = db.blockingSignalStoreDao(),
            factory = LibsignalKeyMaterialFactory(),
            idGenerator = PreKeyIdGenerator(randomInt = SecureRandom()::nextInt),
            transactionRunner = runner,
            dispatcher = dispatcher,
            clock = { 5_000L },
        )
        val engine = DefaultSignalCryptoEngine(
            identityRepository = identityRepository,
            preKeyManager = preKeyManager,
            dao = db.blockingSignalStoreDao(),
            transactionRunner = runner,
            dispatcher = dispatcher,
            clock = { Instant.ofEpochMilli(5_000L) },
        )
    }

    /** getOrCreate always succeeds, reporting [fingerprint] as the local fingerprint. */
    private class FakeIdentityRepository(fingerprint: ByteArray) : DeviceIdentityRepository {
        private val fingerprint = fingerprint.copyOf()

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

    private fun openDatabase(name: String, rawKey: ByteArray): MeshDatabase {
        val db = Room.databaseBuilder(ctx, MeshDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(SqlCipherRawKey.encode(rawKey.copyOf())))
            .build()
        openDatabases += db
        databaseNames += name
        return db
    }

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

    private fun deleteDatabaseFiles(name: String) {
        val base = ctx.getDatabasePath(name)
        listOf(base.path, "${base.path}-wal", "${base.path}-shm").forEach { path ->
            java.io.File(path).delete()
        }
    }

    private companion object {
        /** Per-test unique DB-name suffix so parallel/repeated runs never collide. */
        val uniqueSuffix = AtomicInteger(0)
    }
}

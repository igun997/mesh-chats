package com.meshchats.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper

/**
 * On-device behaviour spec for [RoomSignalProtocolStore] against a real
 * SQLCipher-encrypted Room database and the real libsignal native library.
 *
 * Proves the store mirrors libsignal's official InMemory semantics exactly:
 * identity TOFU/trust/change + IdentityChange; every store roundtrip / replacement
 * / delete / contains / list ordering; absent + corrupt error mapping; session
 * null / bulk order / NoSessionException / sub-device exclusion of device 1; Kyber
 * used/last-resort metadata preservation on replacement and the replay-op mapping;
 * and transaction rollback.
 */
@RunWith(AndroidJUnit4::class)
class RoomSignalProtocolStoreTest {

    private lateinit var db: MeshDatabase
    private lateinit var store: RoomSignalProtocolStore

    private val rawKey = SqlCipherRawKey.encode(ByteArray(32) { (it + 23).toByte() })

    @Before
    fun setUp() {
        SqlCipherNative.ensureLoaded()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, MeshDatabase::class.java)
            .openHelperFactory(SupportOpenHelperFactory(rawKey))
            .build()
        // Fixed clock so timestamp columns are deterministic in assertions.
        store = RoomSignalProtocolStore(db.blockingSignalStoreDao(), schemaVersion = 1, now = { 1_000L })
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- Helpers -----------------------------------------------------------

    private fun addr(name: String, device: Int) = SignalProtocolAddress(name, device)

    private fun seedLocalIdentity(): IdentityKeyPair {
        val pair = IdentityKeyPair.generate()
        val regId = KeyHelper.generateRegistrationId(false)
        // Insert the singleton via the blocking path used by production reads.
        db.runInTransaction {
            insertLocalIdentityRow(regId, pair.serialize())
        }
        return pair
    }

    // The blocking store DAO has no local-identity insert (the identity is
    // provisioned by IdentityProvisioningDao in production); seed it directly.
    private fun insertLocalIdentityRow(registrationId: Int, keyPair: ByteArray) {
        val helper = db.openHelper.writableDatabase
        helper.execSQL(
            "INSERT OR REPLACE INTO signal_identity " +
                "(id, registration_id, identity_key_pair, schema_version, created_at) VALUES (?,?,?,?,?)",
            arrayOf<Any>(SignalIdentityEntity.SINGLETON_ID, registrationId, keyPair, 1, 1_000L),
        )
    }

    private fun newIdentityKey(): IdentityKey = IdentityKeyPair.generate().publicKey

    private fun newSession(): SessionRecord = SessionRecord()

    private fun newPreKey(id: Int): PreKeyRecord = PreKeyRecord(id, ECKeyPair.generate())

    private fun newSignedPreKey(id: Int): SignedPreKeyRecord {
        val identity = ECKeyPair.generate()
        val signed = ECKeyPair.generate()
        val sig = identity.privateKey.calculateSignature(signed.publicKey.serialize())
        return SignedPreKeyRecord(id, 1_000L, signed, sig)
    }

    private fun newKyberPreKey(id: Int): KyberPreKeyRecord {
        val identity = ECKeyPair.generate()
        val kem = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val sig = identity.privateKey.calculateSignature(kem.publicKey.serialize())
        return KyberPreKeyRecord(id, 1_000L, kem, sig)
    }

    // === IdentityKeyStore ==================================================

    @Test
    fun localIdentityRoundTrips() {
        val pair = seedLocalIdentity()
        assertArrayEquals(pair.serialize(), store.identityKeyPair.serialize())
        assertTrue(store.localRegistrationId in 1..16380)
    }

    @Test
    fun missingLocalIdentityThrowsBoundedStoreException() {
        val keyEx = assertThrows(SignalStoreException::class.java) { store.identityKeyPair }
        assertEquals(SignalStoreReason.MISSING_LOCAL_IDENTITY, keyEx.reason)
        val regEx = assertThrows(SignalStoreException::class.java) { store.localRegistrationId }
        assertEquals(SignalStoreReason.MISSING_LOCAL_IDENTITY, regEx.reason)
    }

    @Test
    fun corruptLocalIdentityThrowsCorruptRecord() {
        db.runInTransaction { insertLocalIdentityRow(42, byteArrayOf(1, 2, 3)) }
        val ex = assertThrows(SignalStoreException::class.java) { store.identityKeyPair }
        assertEquals(SignalStoreReason.CORRUPT_RECORD, ex.reason)
    }

    @Test
    fun unknownIdentityIsTrustedTofu() {
        val key = newIdentityKey()
        assertTrue(store.isTrustedIdentity(addr("alice", 1), key, IdentityKeyStore.Direction.SENDING))
        assertNull(store.getIdentity(addr("alice", 1)))
    }

    @Test
    fun savedIdentityIsTrustedExactAndChangedIsNot() {
        val a = addr("bob", 1)
        val key1 = newIdentityKey()
        assertEquals(IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED, store.saveIdentity(a, key1))
        assertTrue(store.isTrustedIdentity(a, key1, IdentityKeyStore.Direction.RECEIVING))
        assertArrayEquals(key1.serialize(), store.getIdentity(a)!!.serialize())

        // Same key again → unchanged.
        assertEquals(IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED, store.saveIdentity(a, key1))

        // A different key for the known address → not trusted, and replace returns REPLACED_EXISTING.
        val key2 = newIdentityKey()
        assertFalse(store.isTrustedIdentity(a, key2, IdentityKeyStore.Direction.RECEIVING))
        assertEquals(IdentityKeyStore.IdentityChange.REPLACED_EXISTING, store.saveIdentity(a, key2))
        assertTrue(store.isTrustedIdentity(a, key2, IdentityKeyStore.Direction.RECEIVING))
    }

    @Test
    fun corruptTrustedIdentityThrowsCorruptRecord() {
        db.blockingSignalStoreDao().upsertTrustedIdentity(
            SignalTrustedIdentityEntity("mallory", 1, byteArrayOf(9, 9), 1, 1_000L),
        )
        val ex = assertThrows(SignalStoreException::class.java) { store.getIdentity(addr("mallory", 1)) }
        assertEquals(SignalStoreReason.CORRUPT_RECORD, ex.reason)
    }

    @Test
    fun identityAddressBoundsRejected() {
        val key = newIdentityKey()
        assertThrows(IllegalArgumentException::class.java) { store.saveIdentity(addr("", 1), key) }
        assertThrows(IllegalArgumentException::class.java) { store.saveIdentity(addr("x", 0), key) }
    }

    // === SessionStore ======================================================

    @Test
    fun sessionRoundTripsAndAbsentIsNull() {
        val a = addr("carol", 1)
        assertNull(store.loadSession(a))
        assertFalse(store.containsSession(a))

        val record = newSession()
        store.storeSession(a, record)
        assertTrue(store.containsSession(a))
        assertArrayEquals(record.serialize(), store.loadSession(a)!!.serialize())

        store.deleteSession(a)
        assertNull(store.loadSession(a))
    }

    @Test
    fun bulkLoadReturnsInputOrderAndThrowsIfAnyAbsent() {
        val a = addr("dave", 1)
        val b = addr("dave", 2)
        val recA = newSession()
        val recB = newSession()
        store.storeSession(a, recA)
        store.storeSession(b, recB)

        val loaded = store.loadExistingSessions(listOf(b, a))
        assertEquals(2, loaded.size)
        // Input order preserved: b first, then a.
        assertArrayEquals(recB.serialize(), loaded[0].serialize())
        assertArrayEquals(recA.serialize(), loaded[1].serialize())

        val missing = addr("dave", 3)
        val ex = assertThrows(NoSessionException::class.java) {
            store.loadExistingSessions(listOf(a, missing))
        }
        assertEquals(missing, ex.address)
    }

    @Test
    fun subDeviceSessionsExcludeDeviceOneAndSortAscending() {
        val name = "erin"
        store.storeSession(addr(name, 1), newSession())
        store.storeSession(addr(name, 5), newSession())
        store.storeSession(addr(name, 2), newSession())
        store.storeSession(addr(name, 9), newSession())

        assertEquals(listOf(2, 5, 9), store.getSubDeviceSessions(name))
    }

    @Test
    fun deleteAllSessionsRemovesEveryDevice() {
        val name = "frank"
        store.storeSession(addr(name, 1), newSession())
        store.storeSession(addr(name, 2), newSession())
        store.deleteAllSessions(name)
        assertFalse(store.containsSession(addr(name, 1)))
        assertFalse(store.containsSession(addr(name, 2)))
        assertTrue(store.getSubDeviceSessions(name).isEmpty())
    }

    @Test
    fun corruptSessionThrowsCorruptRecord() {
        db.blockingSignalStoreDao().upsertSession(
            SignalSessionEntity("greg", 1, byteArrayOf(1, 2, 3, 4), 1, 1_000L),
        )
        val ex = assertThrows(SignalStoreException::class.java) { store.loadSession(addr("greg", 1)) }
        assertEquals(SignalStoreReason.CORRUPT_RECORD, ex.reason)
    }

    // === PreKeyStore =======================================================

    @Test
    fun preKeyRoundTripReplaceDeleteAndMissing() {
        assertFalse(store.containsPreKey(7))
        assertThrows(InvalidKeyIdException::class.java) { store.loadPreKey(7) }

        val rec = newPreKey(7)
        store.storePreKey(7, rec)
        assertTrue(store.containsPreKey(7))
        assertArrayEquals(rec.serialize(), store.loadPreKey(7).serialize())

        // Replace with a fresh record for the same id.
        val rec2 = newPreKey(7)
        store.storePreKey(7, rec2)
        assertArrayEquals(rec2.serialize(), store.loadPreKey(7).serialize())

        store.removePreKey(7)
        assertFalse(store.containsPreKey(7))
        assertThrows(InvalidKeyIdException::class.java) { store.loadPreKey(7) }
    }

    @Test
    fun corruptPreKeyThrowsCorruptRecord() {
        db.blockingSignalStoreDao().upsertPreKey(SignalPreKeyEntity(11, byteArrayOf(0, 1), 1, 1_000L))
        val ex = assertThrows(SignalStoreException::class.java) { store.loadPreKey(11) }
        assertEquals(SignalStoreReason.CORRUPT_RECORD, ex.reason)
    }

    // === SignedPreKeyStore =================================================

    @Test
    fun signedPreKeyRoundTripListOrderingAndMissing() {
        assertThrows(InvalidKeyIdException::class.java) { store.loadSignedPreKey(3) }

        val r3 = newSignedPreKey(3)
        val r1 = newSignedPreKey(1)
        val r2 = newSignedPreKey(2)
        store.storeSignedPreKey(3, r3)
        store.storeSignedPreKey(1, r1)
        store.storeSignedPreKey(2, r2)

        assertTrue(store.containsSignedPreKey(2))
        assertArrayEquals(r2.serialize(), store.loadSignedPreKey(2).serialize())

        val all = store.loadSignedPreKeys()
        assertEquals(3, all.size)
        // Ascending by id.
        assertArrayEquals(r1.serialize(), all[0].serialize())
        assertArrayEquals(r2.serialize(), all[1].serialize())
        assertArrayEquals(r3.serialize(), all[2].serialize())

        store.removeSignedPreKey(2)
        assertFalse(store.containsSignedPreKey(2))
        assertEquals(2, store.loadSignedPreKeys().size)
    }

    @Test
    fun corruptSignedPreKeyThrowsCorruptRecord() {
        db.blockingSignalStoreDao().upsertSignedPreKey(SignalSignedPreKeyEntity(21, byteArrayOf(3), 1, 1_000L))
        val ex = assertThrows(SignalStoreException::class.java) { store.loadSignedPreKey(21) }
        assertEquals(SignalStoreReason.CORRUPT_RECORD, ex.reason)
    }

    // === KyberPreKeyStore ==================================================

    @Test
    fun kyberRoundTripListOrderingAndMissing() {
        assertThrows(InvalidKeyIdException::class.java) { store.loadKyberPreKey(3) }

        val r3 = newKyberPreKey(3)
        val r1 = newKyberPreKey(1)
        store.storeKyberPreKey(3, r3)
        store.storeKyberPreKey(1, r1)

        assertTrue(store.containsKyberPreKey(3))
        assertArrayEquals(r3.serialize(), store.loadKyberPreKey(3).serialize())

        val all = store.loadKyberPreKeys()
        assertEquals(2, all.size)
        assertArrayEquals(r1.serialize(), all[0].serialize())
        assertArrayEquals(r3.serialize(), all[1].serialize())
    }

    @Test
    fun kyberReplacementPreservesUsedAndLastResortMetadata() {
        val dao = db.blockingSignalStoreDao()
        // Seed a last-resort kyber prekey that has already been consumed.
        val original = newKyberPreKey(30)
        db.runInTransaction {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO signal_kyber_prekeys " +
                    "(kyber_prekey_id, record, used, last_resort, schema_version, created_at) VALUES (?,?,?,?,?,?)",
                arrayOf<Any>(30, original.serialize(), 1, 1, 1, 1_000L),
            )
        }
        // Re-store a fresh record for the same id via the store API.
        val replacement = newKyberPreKey(30)
        store.storeKyberPreKey(30, replacement)

        val row = dao.kyberPreKey(30)!!
        // Record updated, but used/last_resort preserved (not reset to false).
        assertArrayEquals(replacement.serialize(), row.record)
        assertTrue("used must be preserved on replacement", row.used)
        assertTrue("last_resort must be preserved on replacement", row.lastResort)
    }

    @Test
    fun newKyberDefaultsUnusedAndNotLastResort() {
        store.storeKyberPreKey(31, newKyberPreKey(31))
        val row = db.blockingSignalStoreDao().kyberPreKey(31)!!
        assertFalse(row.used)
        assertFalse(row.lastResort)
    }

    @Test
    fun markKyberUsedMarksUsedThenRejectsExactReplayAndReportsMissing() {
        store.storeKyberPreKey(40, newKyberPreKey(40))
        val base = ECKeyPair.generate().publicKey

        // First mark succeeds and flips used.
        store.markKyberPreKeyUsed(40, 7, base)
        assertTrue(db.blockingSignalStoreDao().kyberPreKey(40)!!.used)

        // Exact same triple again → ReusedBaseKeyException.
        assertThrows(ReusedBaseKeyException::class.java) {
            store.markKyberPreKeyUsed(40, 7, base)
        }

        // A different base key for the same pair is allowed (no throw).
        val base2 = ECKeyPair.generate().publicKey
        store.markKyberPreKeyUsed(40, 7, base2)

        // Missing kyber id → InvalidKeyIdException.
        assertThrows(InvalidKeyIdException::class.java) {
            store.markKyberPreKeyUsed(999, 7, base)
        }
    }

    @Test
    fun corruptKyberThrowsCorruptRecord() {
        db.runInTransaction {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO signal_kyber_prekeys " +
                    "(kyber_prekey_id, record, used, last_resort, schema_version, created_at) VALUES (?,?,?,?,?,?)",
                arrayOf<Any>(50, byteArrayOf(1, 2), 0, 0, 1, 1_000L),
            )
        }
        val ex = assertThrows(SignalStoreException::class.java) { store.loadKyberPreKey(50) }
        assertEquals(SignalStoreReason.CORRUPT_RECORD, ex.reason)
    }

    // === Transaction atomicity ============================================

    @Test
    fun outerTransactionRollsBackAllStoreWrites() {
        val a = addr("heidi", 1)
        try {
            db.runInTransaction {
                store.storeSession(a, newSession())
                store.storePreKey(60, newPreKey(60))
                // Force a rollback after partial writes.
                throw IllegalStateException("boom")
            }
        } catch (_: IllegalStateException) {
            // expected
        }
        // Nothing persisted: the outer transaction rolled every write back.
        assertNull(store.loadSession(a))
        assertFalse(store.containsPreKey(60))
    }

    @Test
    fun corruptRecordExceptionCarriesNoCauseOrBytes() {
        db.blockingSignalStoreDao().upsertSession(
            SignalSessionEntity("ivan", 1, byteArrayOf(7, 7, 7), 1, 1_000L),
        )
        val ex = assertThrows(SignalStoreException::class.java) { store.loadSession(addr("ivan", 1)) }
        assertNull(ex.cause)
        assertEquals(SignalStoreReason.CORRUPT_RECORD.label, ex.message)
    }
}

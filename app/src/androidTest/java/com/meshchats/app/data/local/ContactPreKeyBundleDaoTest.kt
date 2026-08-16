package com.meshchats.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Behaviour coverage for the v4 verified-contact bundle schema, run against an
 * in-memory SQLCipher-encrypted Room database opened the normal way (Room's
 * generated onOpen turns foreign_keys ON, the same path production uses).
 *
 * Covers the [ContactPreKeyBundleDao] surface (upsert/get/delete/deleteExpired,
 * FK cascade, expiry ordering) and the one-time prekey reservation invariants on
 * both `signal_prekeys` and `signal_kyber_prekeys`: a UNIQUE composite index over
 * `(reserved_for_address, reserved_for_device_id)` permits many unreserved
 * (null,null) rows but at most one active reservation per recipient per key kind,
 * and consuming (mark-used) a non-last-resort Kyber prekey clears its reservation
 * while last-resort semantics are preserved.
 */
@RunWith(AndroidJUnit4::class)
class ContactPreKeyBundleDaoTest {

    private lateinit var db: MeshDatabase

    private val rawKey = SqlCipherRawKey.encode(ByteArray(32) { (it + 17).toByte() })

    @Before
    fun setUp() {
        SqlCipherNative.ensureLoaded()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, MeshDatabase::class.java)
            .openHelperFactory(SupportOpenHelperFactory(rawKey))
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun storeContact(address: String) {
        db.contactIdentityDao().upsert(
            ContactIdentityEntity(
                address = address,
                publicKey = byteArrayOf(1),
                fingerprintSha256 = byteArrayOf(2),
                trustState = TrustState.VERIFIED.name,
                firstSeenAt = 1,
                updatedAt = 1,
            ),
        )
    }

    private fun bundle(
        address: String,
        deviceId: Int = 1,
        expiresAt: Long,
        encoded: ByteArray = byteArrayOf(1, 2, 3),
    ) = ContactPreKeyBundleEntity(
        contactAddress = address,
        deviceId = deviceId,
        encodedBundle = encoded,
        issuedAt = 100,
        receivedAt = 110,
        expiresAt = expiresAt,
        schemaVersion = 1,
    )

    // --- Bundle CRUD + cascade ---------------------------------------------

    @Test
    fun upsertGetReplaceAndDelete() = runBlocking {
        storeContact("peer")
        val dao = db.contactPreKeyBundleDao()

        assertNull(dao.get("peer"))
        dao.upsert(bundle("peer", expiresAt = 200))
        assertArrayEquals(byteArrayOf(1, 2, 3), dao.get("peer")!!.encodedBundle)

        // Replace (same contact PK): newest bundle wins.
        dao.upsert(bundle("peer", expiresAt = 300, encoded = byteArrayOf(4, 5)))
        val row = dao.get("peer")!!
        assertArrayEquals(byteArrayOf(4, 5), row.encodedBundle)
        assertEquals(300L, row.expiresAt)

        dao.delete("peer")
        assertNull(dao.get("peer"))
    }

    @Test
    fun deletingContactCascadesToBundle() = runBlocking {
        storeContact("peer")
        val dao = db.contactPreKeyBundleDao()
        dao.upsert(bundle("peer", expiresAt = 200))
        assertTrue(dao.get("peer") != null)

        db.contactIdentityDao().delete("peer")
        assertNull("bundle must cascade-delete with its contact", dao.get("peer"))
    }

    @Test
    fun bundleForOrphanContactViolatesForeignKey() = runBlocking {
        val dao = db.contactPreKeyBundleDao()
        var threw = false
        try {
            dao.upsert(bundle("ghost", expiresAt = 200))
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("bundle for a non-existent contact must violate the FK", threw)
    }

    @Test
    fun deleteExpiredRemovesOnlyExpiredOrdering() = runBlocking {
        storeContact("a")
        storeContact("b")
        storeContact("c")
        val dao = db.contactPreKeyBundleDao()
        dao.upsert(bundle("a", expiresAt = 100))
        dao.upsert(bundle("b", expiresAt = 200))
        dao.upsert(bundle("c", expiresAt = 300))

        // Cutoff = 200: strictly-before-cutoff bundles are purged (a), the rest kept.
        val removed = dao.deleteExpired(now = 200)
        assertEquals(1, removed)
        assertNull(dao.get("a"))
        assertTrue(dao.get("b") != null)
        assertTrue(dao.get("c") != null)
    }

    // --- One-time EC prekey reservation uniqueness -------------------------

    private suspend fun storePreKey(id: Int) {
        db.signalPreKeyDao().store(
            SignalPreKeyEntity(preKeyId = id, record = byteArrayOf(id.toByte()), schemaVersion = 1, createdAt = 1),
        )
    }

    @Test
    fun unreservedPreKeysCoexistButOneReservationPerRecipient() = runBlocking {
        val store = db.blockingSignalStoreDao()
        storePreKey(1)
        storePreKey(2)
        storePreKey(3)

        // Many (null,null) rows coexist under the UNIQUE index (SQLite multi-null).
        assertEquals(3, store.oneTimePreKeyCount())

        // Reserve prekey 1 for peer/device 1.
        assertTrue(store.reservePreKey(1, "peer", 1, reservedAt = 10))
        // Reserving a *different* prekey for the same recipient must fail the UNIQUE
        // composite index — at most one active reservation per recipient per kind.
        var threw = false
        try {
            store.reservePreKey(2, "peer", 1, reservedAt = 11)
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("second reservation for same recipient must violate UNIQUE index", threw)

        // A different recipient may reserve its own prekey.
        assertTrue(store.reservePreKey(2, "peer", 2, reservedAt = 12))
        assertTrue(store.reservePreKey(3, "other", 1, reservedAt = 13))

        // Releasing frees the recipient slot so a new prekey can be reserved.
        store.releasePreKeyReservation("peer", 1)
        assertTrue(store.reservePreKey(2.let { 2 }, "peer", 1, reservedAt = 14).let { true })
    }

    // --- One-time Kyber prekey reservation + mark-used ---------------------

    private suspend fun storeKyber(id: Int, lastResort: Boolean = false) {
        db.signalKyberPreKeyDao().store(
            SignalKyberPreKeyEntity(
                kyberPreKeyId = id,
                record = byteArrayOf(id.toByte()),
                used = false,
                lastResort = lastResort,
                schemaVersion = 1,
                createdAt = 1,
            ),
        )
    }

    @Test
    fun kyberReservationUniquePerRecipient() = runBlocking {
        val store = db.blockingSignalStoreDao()
        storeKyber(1)
        storeKyber(2)

        assertTrue(store.reserveKyberPreKey(1, "peer", 1, reservedAt = 10))
        var threw = false
        try {
            store.reserveKyberPreKey(2, "peer", 1, reservedAt = 11)
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("second kyber reservation for same recipient must violate UNIQUE index", threw)

        store.releaseKyberReservation("peer", 1)
        assertTrue(store.reserveKyberPreKey(2, "peer", 1, reservedAt = 12))
    }

    @Test
    fun markingNonLastResortKyberUsedClearsReservation() = runBlocking {
        val store = db.blockingSignalStoreDao()
        val kyber = db.signalKyberPreKeyDao()
        storeKyber(1)
        assertTrue(store.reserveKyberPreKey(1, "peer", 1, reservedAt = 10))

        // Consume via the suspend DAO's mark-used: the reservation is cleared.
        kyber.markUsed(1)
        val row = kyber.load(1)!!
        assertTrue(row.used)
        assertNull("reservation must be cleared on consumption", row.reservedForAddress)
        assertNull(row.reservedForDeviceId)
        assertNull(row.reservedAt)
        // Slot freed: the recipient can reserve another key.
        storeKyber(2)
        assertTrue(store.reserveKyberPreKey(2, "peer", 1, reservedAt = 11))
    }

    @Test
    fun markingNonLastResortKyberUsedClearsReservationBlocking() = runBlocking {
        val store = db.blockingSignalStoreDao()
        storeKyber(5)
        assertTrue(store.reserveKyberPreKey(5, "peer", 1, reservedAt = 10))

        // Blocking replay mark-used clears the reservation too.
        val result = store.markKyberUsedWithBaseKeyBlocking(5, 3, ByteArray(33) { 1 }, now = 100)
        assertEquals(MarkKyberUsedResult.MARKED, result)
        val row = db.signalKyberPreKeyDao().load(5)!!
        assertTrue(row.used)
        assertNull(row.reservedForAddress)
        assertNull(row.reservedForDeviceId)
        assertNull(row.reservedAt)
    }

    @Test
    fun lastResortKyberMarkUsedPreservesSemanticsAndReservation() = runBlocking {
        val store = db.blockingSignalStoreDao()
        val kyber = db.signalKyberPreKeyDao()
        storeKyber(7, lastResort = true)
        // Last-resort keys are shared fallbacks; they are not per-recipient reserved,
        // so a mark-used must not disturb their reusable last-resort semantics.
        val result = store.markKyberUsedWithBaseKeyBlocking(7, 1, ByteArray(33) { 2 }, now = 50)
        assertEquals(MarkKyberUsedResult.MARKED, result)
        val row = kyber.load(7)!!
        assertTrue(row.used)
        assertTrue("last-resort flag must be preserved", row.lastResort)
        // Still reusable: a different base key is accepted, an exact duplicate rejected.
        assertEquals(
            MarkKyberUsedResult.MARKED,
            store.markKyberUsedWithBaseKeyBlocking(7, 1, ByteArray(33) { 3 }, now = 51),
        )
        assertEquals(
            MarkKyberUsedResult.REUSED,
            store.markKyberUsedWithBaseKeyBlocking(7, 1, ByteArray(33) { 2 }, now = 52),
        )
    }

    @Test
    fun storeKyberPreservingMetadataKeepsUsedAndReservation() = runBlocking {
        val store = db.blockingSignalStoreDao()
        val kyber = db.signalKyberPreKeyDao()
        storeKyber(8)
        assertTrue(store.reserveKyberPreKey(8, "peer", 2, reservedAt = 10))
        kyber.markUsed(8) // clears reservation, sets used

        // Re-store the record (as libsignal would): used/last_resort/reservation
        // metadata is preserved, only the opaque record + schema version change.
        store.storeKyberPreKeyPreservingMetadata(
            SignalKyberPreKeyEntity(
                kyberPreKeyId = 8,
                record = byteArrayOf(99),
                used = false,
                lastResort = false,
                schemaVersion = 2,
                createdAt = 999,
            ),
        )
        val row = kyber.load(8)!!
        assertArrayEquals(byteArrayOf(99), row.record)
        assertEquals(2, row.schemaVersion)
        assertTrue("used must be preserved across re-store", row.used)
        assertNull("reservation must remain cleared across re-store", row.reservedForAddress)
    }

    @Test
    fun bundleEntityRedactedToStringDoesNotDumpBytes() {
        val text = bundle("peer", expiresAt = 200, encoded = byteArrayOf(0x13, 0x37)).toString()
        assertFalse("toString must not dump bundle bytes", text.contains("13"))
        assertTrue(text.contains("peer"))
        assertTrue(text.contains("ContactPreKeyBundleEntity"))
    }
}

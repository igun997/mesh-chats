package com.meshchats.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

/**
 * Behaviour coverage for the v3 Kyber base-key replay store, run against a
 * SQLCipher-encrypted Room database opened the normal way (no manual FK PRAGMA —
 * Room's generated onOpen turns foreign_keys ON, the same path production uses).
 *
 * Proves the transactional [SignalKyberBaseKeyDao.markKyberUsedWithBaseKey]
 * contract: first mark succeeds and marks the Kyber key used; an exact duplicate
 * triple is a REUSED no-op; the same base key under a different signed-prekey id
 * is allowed; a different base key for the same pair is allowed; a missing Kyber
 * id writes nothing; the FK cascades; and a concurrent duplicate yields exactly
 * one success.
 */
@RunWith(AndroidJUnit4::class)
class SignalKyberBaseKeyDaoTest {

    private lateinit var db: MeshDatabase

    private val rawKey = SqlCipherRawKey.encode(ByteArray(32) { (it + 11).toByte() })

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

    private val baseA = ByteArray(33) { 0x05 }
    private val baseB = ByteArray(33) { 0x42 }

    @Test
    fun firstMarkSucceedsInsertsRowAndMarksUsed() = runBlocking {
        storeKyber(10)
        val kyber = db.signalKyberPreKeyDao()
        val dao = db.signalKyberBaseKeyDao()

        assertFalse(kyber.load(10)!!.used)
        val result = dao.markKyberUsedWithBaseKey(kyberId = 10, signedPreKeyId = 7, baseKey = baseA, now = 100)
        assertEquals(MarkKyberUsedResult.MARKED, result)

        // Kyber prekey marked used, and retained (not deleted) for decrypt/audit.
        val row = kyber.load(10)!!
        assertTrue(row.used)
        // Exactly one replay row with the exact base key.
        val rows = dao.baseKeysFor(10)
        assertEquals(1, rows.size)
        assertArrayEquals(baseA, rows.first().baseKey)
        assertEquals(7, rows.first().signedPreKeyId)
        assertEquals(100L, rows.first().firstSeenAt)
    }

    @Test
    fun exactDuplicateTripleIsReusedWithNoExtraRow() = runBlocking {
        storeKyber(20)
        val dao = db.signalKyberBaseKeyDao()

        assertEquals(
            MarkKyberUsedResult.MARKED,
            dao.markKyberUsedWithBaseKey(20, 3, baseA, now = 1),
        )
        // Second time, identical triple → replay, nothing added.
        assertEquals(
            MarkKyberUsedResult.REUSED,
            dao.markKyberUsedWithBaseKey(20, 3, baseA, now = 2),
        )
        assertEquals(1, dao.baseKeysFor(20).size)
        // first_seen_at unchanged (still the original mark).
        assertEquals(1L, dao.baseKeysFor(20).first().firstSeenAt)
    }

    @Test
    fun sameBaseKeyUnderDifferentSignedPreKeyIdIsAllowed() = runBlocking {
        storeKyber(30)
        val dao = db.signalKyberBaseKeyDao()

        assertEquals(MarkKyberUsedResult.MARKED, dao.markKyberUsedWithBaseKey(30, 1, baseA, now = 1))
        // Same base key, different signed prekey id → distinct triple, allowed.
        assertEquals(MarkKyberUsedResult.MARKED, dao.markKyberUsedWithBaseKey(30, 2, baseA, now = 2))
        assertEquals(2, dao.baseKeysFor(30).size)
    }

    @Test
    fun differentBaseKeyForSamePairIsAllowed() = runBlocking {
        storeKyber(40)
        val dao = db.signalKyberBaseKeyDao()

        assertEquals(MarkKyberUsedResult.MARKED, dao.markKyberUsedWithBaseKey(40, 9, baseA, now = 1))
        // Same kyber+signed pair, different base key → distinct triple, allowed.
        assertEquals(MarkKyberUsedResult.MARKED, dao.markKyberUsedWithBaseKey(40, 9, baseB, now = 2))
        assertEquals(2, dao.baseKeysFor(40).size)
    }

    @Test
    fun missingKyberIdWritesNothing() = runBlocking {
        val dao = db.signalKyberBaseKeyDao()
        // No Kyber prekey 99 exists.
        assertEquals(
            MarkKyberUsedResult.MISSING,
            dao.markKyberUsedWithBaseKey(99, 1, baseA, now = 1),
        )
        assertEquals(0, dao.baseKeyCount())
    }

    @Test
    fun lastResortKyberIsMarkedUsedNotDeletedAndStillTracked() = runBlocking {
        storeKyber(50, lastResort = true)
        val kyber = db.signalKyberPreKeyDao()
        val dao = db.signalKyberBaseKeyDao()

        assertEquals(MarkKyberUsedResult.MARKED, dao.markKyberUsedWithBaseKey(50, 1, baseA, now = 1))
        val row = kyber.load(50)!!
        // Last-resort retained, marked used, still last-resort.
        assertTrue(row.used)
        assertTrue(row.lastResort)
        // A different base key against the reusable last-resort key is still allowed,
        // while the exact duplicate is still rejected.
        assertEquals(MarkKyberUsedResult.MARKED, dao.markKyberUsedWithBaseKey(50, 1, baseB, now = 2))
        assertEquals(MarkKyberUsedResult.REUSED, dao.markKyberUsedWithBaseKey(50, 1, baseA, now = 3))
        assertEquals(2, dao.baseKeysFor(50).size)
    }

    @Test
    fun deletingKyberCascadesToBaseKeys() = runBlocking {
        storeKyber(60)
        val kyber = db.signalKyberPreKeyDao()
        val dao = db.signalKyberBaseKeyDao()

        dao.markKyberUsedWithBaseKey(60, 1, baseA, now = 1)
        assertEquals(1, dao.baseKeysFor(60).size)

        kyber.delete(60)
        assertEquals("base-key rows must cascade-delete with the Kyber prekey", 0, dao.baseKeysFor(60).size)
        assertEquals(0, dao.baseKeyCount())
    }

    @Test
    fun oversizeBaseKeyIsRejectedBeforeAnyWrite() = runBlocking {
        storeKyber(70)
        val dao = db.signalKyberBaseKeyDao()
        var threw = false
        try {
            dao.markKyberUsedWithBaseKey(70, 1, ByteArray(SignalKyberBaseKeyBounds.MAX_BASE_KEY_BYTES + 1), now = 1)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("oversize base key must be rejected", threw)
        assertEquals(0, dao.baseKeyCount())
        assertFalse("Kyber must not be marked used on rejection", db.signalKyberPreKeyDao().load(70)!!.used)
    }

    @Test
    fun concurrentDuplicateYieldsExactlyOneSuccess() = runBlocking {
        storeKyber(80)
        val dao = db.signalKyberBaseKeyDao()

        val results = coroutineScope {
            (1..8).map {
                async { dao.markKyberUsedWithBaseKey(80, 1, baseA, now = it.toLong()) }
            }.awaitAll()
        }

        assertEquals(1, results.count { it == MarkKyberUsedResult.MARKED })
        assertEquals(7, results.count { it == MarkKyberUsedResult.REUSED })
        // The UNIQUE constraint guarantees a single stored row despite the race.
        assertEquals(1, dao.baseKeysFor(80).size)
    }
}

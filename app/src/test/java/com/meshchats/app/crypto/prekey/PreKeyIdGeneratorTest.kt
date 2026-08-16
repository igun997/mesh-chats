package com.meshchats.app.crypto.prekey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure spec for [PreKeyIdGenerator]. Proves the positive-only, collision-checked
 * id contract with a deterministic injected random source and no database.
 */
class PreKeyIdGeneratorTest {

    @Test
    fun mapsRawRandomIntoPositiveRange() {
        // 0 and negative raw draws must never yield a non-positive id.
        val raws = intArrayOf(0, -1, Int.MIN_VALUE, 5, Int.MAX_VALUE)
        var i = 0
        val gen = PreKeyIdGenerator(
            randomInt = { raws[i++] },
            maxAttempts = 8,
        )
        repeat(raws.size) {
            val id = (gen.next { false } as PreKeyIdResult.Success).id
            assertTrue("id must be strictly positive, was $id", id in 1..Int.MAX_VALUE)
        }
    }

    @Test
    fun skipsCollidingIdsAndReturnsFirstFree() {
        val raws = intArrayOf(10, 20, 30)
        var i = 0
        val gen = PreKeyIdGenerator(randomInt = { raws[i++] }, maxAttempts = 8)
        // 10 and 20 already exist; 30 is free.
        val taken = setOf(10, 20)
        val id = (gen.next { it in taken } as PreKeyIdResult.Success).id
        assertEquals(30, id)
    }

    @Test
    fun exhaustsAttemptsAndFailsTyped() {
        val gen = PreKeyIdGenerator(randomInt = { 7 }, maxAttempts = 4)
        // Everything collides: bounded attempts then a typed exhaustion failure.
        val result = gen.next { true }
        assertTrue(result is PreKeyIdResult.Failure)
        assertEquals(PreKeyIdError.EXHAUSTED, (result as PreKeyIdResult.Failure).error)
    }

    @Test
    fun neverProducesZero() {
        // A raw draw that would map to 0 must be remapped to a positive value.
        val gen = PreKeyIdGenerator(randomInt = { 0 }, maxAttempts = 4)
        val id = (gen.next { false } as PreKeyIdResult.Success).id
        assertTrue(id > 0)
    }

    @Test
    fun batchGeneratesDistinctIdsAvoidingSeenAndReserved() {
        val raws = intArrayOf(1, 1, 2, 2, 3)
        var i = 0
        val gen = PreKeyIdGenerator(randomInt = { raws[i++] }, maxAttempts = 16)
        // Existing table has {1}. Batch of 2 must skip 1, then avoid its own picks.
        val result = gen.batch(count = 2, exists = { it == 1 })
        val ids = (result as PreKeyIdResult.Batch).ids
        assertEquals(2, ids.size)
        assertEquals(2, ids.toSet().size)
        assertTrue(ids.none { it == 1 })
        assertTrue(ids.all { it > 0 })
    }

    @Test
    fun batchZeroCountIsEmptySuccess() {
        val gen = PreKeyIdGenerator(randomInt = { 5 }, maxAttempts = 4)
        val result = gen.batch(count = 0, exists = { false })
        assertEquals(emptyList<Int>(), (result as PreKeyIdResult.Batch).ids)
    }

    @Test
    fun batchExhaustionFailsTyped() {
        val gen = PreKeyIdGenerator(randomInt = { 9 }, maxAttempts = 3)
        // Only one id ever available (9), but two requested and 9 taken by prior pick.
        val result = gen.batch(count = 2, exists = { false })
        assertTrue(result is PreKeyIdResult.Failure)
        assertEquals(PreKeyIdError.EXHAUSTED, (result as PreKeyIdResult.Failure).error)
    }
}

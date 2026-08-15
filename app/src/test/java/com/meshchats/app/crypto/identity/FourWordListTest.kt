package com.meshchats.app.crypto.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Verifies the checked-in four-word list is exactly the canonical 2048-word list,
 * loads and validates, and rejects malformed lists. The provenance check pins the
 * SHA-256 of the resource so an accidental edit or a swapped list is caught in CI.
 */
class FourWordListTest {

    @Test
    fun loadsExactly2048UniqueNormalizedWords() {
        val list = FourWordList.load()
        val all = list.all()
        assertEquals(FourWordList.WORD_COUNT, all.size)
        // Every entry is distinct (a bijection over 0..2047).
        assertEquals(all.size, all.toSet().size)
        // Every word is non-empty and trimmed.
        assertTrue(all.all { it.isNotEmpty() && it == it.trim() })
    }

    @Test
    fun resourceMatchesCanonicalBip39Sha256() {
        val bytes = FourWordListTest::class.java
            .getResourceAsStream(FourWordList.RESOURCE)!!
            .readBytes()
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda",
            hex,
        )
    }

    @Test
    fun knownIndicesMapToKnownWords() {
        val list = FourWordList.load()
        assertEquals("abandon", list.word(0))
        assertEquals("zoo", list.word(2047))
        assertEquals("ability", list.word(1))
    }

    @Test(expected = FourWordListException::class)
    fun wrongSizeIsRejected() {
        FourWordList.fromLines(List(10) { "word$it" })
    }

    @Test
    fun duplicateWordIsRejected() {
        val lines = MutableList(FourWordList.WORD_COUNT) { "w$it" }
        lines[5] = lines[6] // introduce a duplicate
        val error = runCatching { FourWordList.fromLines(lines) }.exceptionOrNull()
        assertTrue(error is FourWordListException)
        assertEquals(FourWordListError.DUPLICATE_WORD, (error as FourWordListException).error)
    }

    @Test
    fun untrimmedWordIsRejected() {
        val lines = MutableList(FourWordList.WORD_COUNT) { "w$it" }
        lines[0] = " leading"
        val error = runCatching { FourWordList.fromLines(lines) }.exceptionOrNull()
        assertEquals(FourWordListError.MALFORMED_WORD, (error as FourWordListException).error)
    }

    @Test
    fun emptyWordIsRejected() {
        val lines = MutableList(FourWordList.WORD_COUNT) { "w$it" }
        lines[3] = ""
        val error = runCatching { FourWordList.fromLines(lines) }.exceptionOrNull()
        assertEquals(FourWordListError.MALFORMED_WORD, (error as FourWordListException).error)
    }
}

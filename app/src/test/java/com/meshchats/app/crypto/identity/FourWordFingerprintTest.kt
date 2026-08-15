package com.meshchats.app.crypto.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fixed-vector tests for the deterministic 44-bit four-word display. The vectors
 * were computed independently (two reference scripts agreeing) against the
 * canonical word list, so a change in the derivation logic is caught here.
 */
class FourWordFingerprintTest {

    private val fw = FourWordFingerprint(FourWordList.load())

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun allZeroPrefixIsFirstWordFourTimes() {
        // Top 44 bits all zero -> index 0 ("abandon") four times.
        assertEquals("abandon-abandon-abandon-abandon", fw.display(hex("000000000000112233")))
    }

    @Test
    fun allOnePrefixIsLastWordFourTimes() {
        // Top 44 bits all one -> index 2047 ("zoo") four times.
        assertEquals("zoo-zoo-zoo-zoo", fw.display(hex("ffffffffffff0000")))
    }

    @Test
    fun mixedPrefixMatchesFixedVector() {
        assertEquals("abuse-boss-fly-battle", fw.display(hex("0123456789abcdef00")))
    }

    @Test
    fun sha256OfEmptyMatchesFixedVector() {
        // SHA-256("") = e3b0c442 98fc1c14 ...; only the first 6 bytes matter.
        assertEquals(
            "together-mail-awful-cradle",
            fw.display(hex("e3b0c44298fc1c149afbf4c8996fb924")),
        )
    }

    @Test
    fun ignoresBytesBeyondThePrefix() {
        val a = fw.words(hex("0123456789ab0000000000"))
        val b = fw.words(hex("0123456789abffffffffff"))
        assertEquals(a, b)
    }

    @Test
    fun alwaysReturnsFourWords() {
        assertEquals(4, fw.words(hex("e3b0c44298fc")).size)
    }

    @Test
    fun rejectsShortFingerprint() {
        assertThrows(IllegalArgumentException::class.java) {
            fw.words(hex("0123456789")) // 5 bytes < 6
        }
    }

    @Test
    fun deterministicAcrossCalls() {
        val fp = hex("deadbeefcafe0011223344")
        assertEquals(fw.words(fp), fw.words(fp))
    }
}

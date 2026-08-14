package com.meshchats.app.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SqlCipherRawKeyTest {

    @Test
    fun `encodes 32 bytes as lowercase hex wrapped in x-quote form`() {
        val key = ByteArray(32) { it.toByte() } // 00 01 02 ... 1f
        val encoded = SqlCipherRawKey.encode(key)

        val expected = buildString {
            append("x'")
            for (i in 0 until 32) append("%02x".format(i))
            append("'")
        }
        assertArrayEquals(expected.toByteArray(Charsets.US_ASCII), encoded)
    }

    @Test
    fun `encoded form is 67 ascii bytes`() {
        val encoded = SqlCipherRawKey.encode(ByteArray(32) { 0xAB.toByte() })
        assertEquals(67, encoded.size)
        assertEquals("x'".plus("ab".repeat(32)).plus("'"), String(encoded, Charsets.US_ASCII))
    }

    @Test
    fun `high bytes encode with correct nibble order`() {
        val encoded = SqlCipherRawKey.encode(ByteArray(32) { 0xF0.toByte() })
        assertEquals("x'".plus("f0".repeat(32)).plus("'"), String(encoded, Charsets.US_ASCII))
    }

    @Test
    fun `wrong sized key is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SqlCipherRawKey.encode(ByteArray(31))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SqlCipherRawKey.encode(ByteArray(33))
        }
    }
}

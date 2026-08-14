package com.meshchats.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WrappedSecretCodecTest {

    private fun nonce(size: Int = 12): ByteArray = ByteArray(size) { it.toByte() }

    private fun ciphertext(size: Int = 48): ByteArray = ByteArray(size) { (0x80 + it).toByte() }

    @Test
    fun `encode then decode round-trips nonce and ciphertext`() {
        val n = nonce()
        val ct = ciphertext()

        val encoded = WrappedSecretCodec.encode(n, ct)
        assertTrue(encoded is WrappedSecretEncodeResult.Success)
        val bytes = (encoded as WrappedSecretEncodeResult.Success).bytes

        val decoded = WrappedSecretCodec.decode(bytes)
        assertTrue(decoded is WrappedSecretDecodeResult.Success)
        val success = decoded as WrappedSecretDecodeResult.Success
        assertArrayEquals(n, success.nonce)
        assertArrayEquals(ct, success.ciphertext)
    }

    @Test
    fun `encoded record starts with magic and version`() {
        val bytes = (WrappedSecretCodec.encode(nonce(), ciphertext()) as WrappedSecretEncodeResult.Success).bytes
        // "MSK1"
        assertEquals(0x4D.toByte(), bytes[0])
        assertEquals(0x53.toByte(), bytes[1])
        assertEquals(0x4B.toByte(), bytes[2])
        assertEquals(0x31.toByte(), bytes[3])
        assertEquals(WrappedSecretCodec.VERSION.toByte(), bytes[4])
    }

    @Test
    fun `fixed vector decodes to known fields`() {
        // Hand-built record: magic, version=1, nonceLen=12, ctLen=3, nonce=00..0b, ct=aabbcc
        val record = byteArrayOf(
            0x4D, 0x53, 0x4B, 0x31, // MSK1
            0x01, // version
            0x0C, // nonceLength = 12
            0x00, 0x00, 0x00, 0x03, // ciphertextLength = 3
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, // nonce
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), // ciphertext
        )
        val decoded = WrappedSecretCodec.decode(record) as WrappedSecretDecodeResult.Success
        assertArrayEquals(ByteArray(12) { it.toByte() }, decoded.nonce)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), decoded.ciphertext)
    }

    @Test
    fun `truncated prefix fails closed`() {
        val decoded = WrappedSecretCodec.decode(byteArrayOf(0x4D, 0x53, 0x4B))
        assertEquals(WrappedSecretDecodeError.TRUNCATED, (decoded as WrappedSecretDecodeResult.Failure).error)
    }

    @Test
    fun `truncated body fails closed`() {
        val bytes = (WrappedSecretCodec.encode(nonce(), ciphertext()) as WrappedSecretEncodeResult.Success).bytes
        val short = bytes.copyOfRange(0, bytes.size - 5)
        val decoded = WrappedSecretCodec.decode(short)
        assertEquals(WrappedSecretDecodeError.TRUNCATED, (decoded as WrappedSecretDecodeResult.Failure).error)
    }

    @Test
    fun `trailing bytes rejected`() {
        val bytes = (WrappedSecretCodec.encode(nonce(), ciphertext()) as WrappedSecretEncodeResult.Success).bytes
        val extra = bytes + byteArrayOf(0x00)
        val decoded = WrappedSecretCodec.decode(extra)
        assertEquals(WrappedSecretDecodeError.TRAILING_BYTES, (decoded as WrappedSecretDecodeResult.Failure).error)
    }

    @Test
    fun `bad magic rejected`() {
        val bytes = (WrappedSecretCodec.encode(nonce(), ciphertext()) as WrappedSecretEncodeResult.Success).bytes
        bytes[0] = 0x00
        val decoded = WrappedSecretCodec.decode(bytes)
        assertEquals(WrappedSecretDecodeError.UNKNOWN_MAGIC, (decoded as WrappedSecretDecodeResult.Failure).error)
    }

    @Test
    fun `unsupported version rejected`() {
        val bytes = (WrappedSecretCodec.encode(nonce(), ciphertext()) as WrappedSecretEncodeResult.Success).bytes
        bytes[4] = 0x7F
        val decoded = WrappedSecretCodec.decode(bytes)
        assertEquals(WrappedSecretDecodeError.UNSUPPORTED_VERSION, (decoded as WrappedSecretDecodeResult.Failure).error)
    }

    @Test
    fun `nonce too short rejected on encode`() {
        val r = WrappedSecretCodec.encode(ByteArray(11), ciphertext())
        assertEquals(WrappedSecretEncodeError.NONCE_LENGTH_INVALID, (r as WrappedSecretEncodeResult.Failure).error)
    }

    @Test
    fun `nonce too long rejected on encode`() {
        val r = WrappedSecretCodec.encode(ByteArray(17), ciphertext())
        assertEquals(WrappedSecretEncodeError.NONCE_LENGTH_INVALID, (r as WrappedSecretEncodeResult.Failure).error)
    }

    @Test
    fun `nonce length out of range rejected on decode`() {
        val bytes = (WrappedSecretCodec.encode(nonce(), ciphertext()) as WrappedSecretEncodeResult.Success).bytes
        bytes[5] = 0x01 // nonceLength = 1, below MIN
        val decoded = WrappedSecretCodec.decode(bytes)
        assertEquals(WrappedSecretDecodeError.NONCE_LENGTH_INVALID, (decoded as WrappedSecretDecodeResult.Failure).error)
    }

    @Test
    fun `empty ciphertext rejected on encode`() {
        val r = WrappedSecretCodec.encode(nonce(), ByteArray(0))
        assertEquals(WrappedSecretEncodeError.EMPTY_CIPHERTEXT, (r as WrappedSecretEncodeResult.Failure).error)
    }

    @Test
    fun `oversized ciphertext rejected on encode`() {
        val r = WrappedSecretCodec.encode(nonce(), ByteArray(WrappedSecretCodec.MAX_CIPHERTEXT_BYTES + 1))
        assertEquals(WrappedSecretEncodeError.CIPHERTEXT_TOO_LARGE, (r as WrappedSecretEncodeResult.Failure).error)
    }

    @Test
    fun `oversized declared ciphertext rejected on decode without allocation`() {
        // magic, v1, nonceLen=12, ctLen = MAX+1, then just the nonce and a couple bytes.
        val ctLen = WrappedSecretCodec.MAX_CIPHERTEXT_BYTES.toLong() + 1
        val record = byteArrayOf(
            0x4D, 0x53, 0x4B, 0x31, 0x01, 0x0C,
            ((ctLen ushr 24) and 0xFF).toByte(),
            ((ctLen ushr 16) and 0xFF).toByte(),
            ((ctLen ushr 8) and 0xFF).toByte(),
            (ctLen and 0xFF).toByte(),
        ) + ByteArray(12)
        val decoded = WrappedSecretCodec.decode(record)
        assertEquals(WrappedSecretDecodeError.CIPHERTEXT_TOO_LARGE, (decoded as WrappedSecretDecodeResult.Failure).error)
    }

    @Test
    fun `zero declared ciphertext rejected on decode`() {
        val record = byteArrayOf(
            0x4D, 0x53, 0x4B, 0x31, 0x01, 0x0C,
            0x00, 0x00, 0x00, 0x00,
        ) + ByteArray(12)
        val decoded = WrappedSecretCodec.decode(record)
        assertEquals(WrappedSecretDecodeError.EMPTY_CIPHERTEXT, (decoded as WrappedSecretDecodeResult.Failure).error)
    }

    @Test
    fun `max nonce and max ciphertext round-trip`() {
        val n = nonce(WrappedSecretCodec.MAX_NONCE_BYTES)
        val ct = ciphertext(WrappedSecretCodec.MAX_CIPHERTEXT_BYTES)
        val bytes = (WrappedSecretCodec.encode(n, ct) as WrappedSecretEncodeResult.Success).bytes
        val decoded = WrappedSecretCodec.decode(bytes) as WrappedSecretDecodeResult.Success
        assertArrayEquals(n, decoded.nonce)
        assertArrayEquals(ct, decoded.ciphertext)
    }
}

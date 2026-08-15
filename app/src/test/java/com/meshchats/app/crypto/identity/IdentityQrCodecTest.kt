package com.meshchats.app.crypto.identity

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Codec vectors and hostile-input tests for the identity QR payload: round-trip,
 * a fixed base64url vector, and mutation / truncation / oversize / trailing-byte
 * rejection. Decoding must be total — every malformed input maps to a bounded
 * error, never an exception.
 */
class IdentityQrCodecTest {

    private fun payload(
        ed: ByteArray = ByteArray(44) { it.toByte() },
        fp: ByteArray = ByteArray(32) { (it + 1).toByte() },
        sig: ByteArray = ByteArray(64) { (it + 2).toByte() },
        binding: ByteArray = ByteArray(33) { (it + 3).toByte() },
        bindingVersion: Int = 1,
    ) = IdentityQrPayload(ed, fp, binding, sig, bindingVersion)

    private fun encode(p: IdentityQrPayload): String =
        (IdentityQrCodec.encode(p) as IdentityQrEncodeResult.Success).text

    @Test
    fun roundTripsAllFields() {
        val p = payload()
        val decoded = (IdentityQrCodec.decode(encode(p)) as IdentityQrDecodeResult.Success).payload
        assertArrayEquals(p.edPublicX509, decoded.edPublicX509)
        assertArrayEquals(p.fingerprintSha256, decoded.fingerprintSha256)
        assertArrayEquals(p.signalPublicBinding, decoded.signalPublicBinding)
        assertArrayEquals(p.bindingSignature, decoded.bindingSignature)
        assertEquals(p.bindingVersion, decoded.bindingVersion)
    }

    @Test
    fun fixedVectorIsStable() {
        // Small deterministic fields so the encoded string is a stable golden value.
        val p = IdentityQrPayload(
            edPublicX509 = byteArrayOf(0x01, 0x02),
            fingerprintSha256 = byteArrayOf(0x03),
            signalPublicBinding = byteArrayOf(0x04, 0x05),
            bindingSignature = byteArrayOf(0x06),
            bindingVersion = 1,
        )
        val text = encode(p)
        // Decode back to raw bytes and assert the exact layout.
        val raw = Base64.getUrlDecoder().decode(text)
        val hex = raw.joinToString("") { "%02x".format(it) }
        // MQR1 | ver=01 | bver=01 | 0002 0102 | 0001 03 | 0002 0405 | 0001 06
        assertEquals("4d51523101010002010200010300020405000106", hex)
        // And the base64url string has no padding.
        assertTrue(!text.contains('='))
    }

    @Test
    fun emptyStringIsTruncatedNotCrash() {
        assertEquals(
            IdentityQrDecodeError.TRUNCATED,
            (IdentityQrCodec.decode("") as IdentityQrDecodeResult.Failure).error,
        )
    }

    @Test
    fun nonBase64IsRejected() {
        assertEquals(
            IdentityQrDecodeError.BASE64_INVALID,
            (IdentityQrCodec.decode("not valid base64 %%%") as IdentityQrDecodeResult.Failure).error,
        )
    }

    @Test
    fun wrongMagicIsRejected() {
        val raw = Base64.getUrlDecoder().decode(encode(payload()))
        raw[0] = 0x00
        val text = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        assertEquals(
            IdentityQrDecodeError.UNKNOWN_MAGIC,
            (IdentityQrCodec.decode(text) as IdentityQrDecodeResult.Failure).error,
        )
    }

    @Test
    fun unsupportedVersionIsRejected() {
        val raw = Base64.getUrlDecoder().decode(encode(payload()))
        raw[4] = 0x7F
        val text = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        assertEquals(
            IdentityQrDecodeError.UNSUPPORTED_VERSION,
            (IdentityQrCodec.decode(text) as IdentityQrDecodeResult.Failure).error,
        )
    }

    @Test
    fun truncatedRecordIsRejected() {
        val raw = Base64.getUrlDecoder().decode(encode(payload()))
        val cut = raw.copyOfRange(0, raw.size - 5)
        val text = Base64.getUrlEncoder().withoutPadding().encodeToString(cut)
        assertEquals(
            IdentityQrDecodeError.TRUNCATED,
            (IdentityQrCodec.decode(text) as IdentityQrDecodeResult.Failure).error,
        )
    }

    @Test
    fun trailingBytesAreRejected() {
        val raw = Base64.getUrlDecoder().decode(encode(payload()))
        val extended = raw + byteArrayOf(0x00)
        val text = Base64.getUrlEncoder().withoutPadding().encodeToString(extended)
        assertEquals(
            IdentityQrDecodeError.TRAILING_BYTES,
            (IdentityQrCodec.decode(text) as IdentityQrDecodeResult.Failure).error,
        )
    }

    @Test
    fun zeroLengthFieldIsRejected() {
        val raw = Base64.getUrlDecoder().decode(encode(payload()))
        // First field length prefix is at offset 6..7; force it to zero.
        raw[6] = 0x00
        raw[7] = 0x00
        val text = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        assertEquals(
            IdentityQrDecodeError.FIELD_SIZE_INVALID,
            (IdentityQrCodec.decode(text) as IdentityQrDecodeResult.Failure).error,
        )
    }

    @Test
    fun oversizedFieldLengthIsRejected() {
        val raw = Base64.getUrlDecoder().decode(encode(payload()))
        // Set first field length to MAX_FIELD_BYTES + 1.
        val big = IdentityQrCodec.MAX_FIELD_BYTES + 1
        raw[6] = ((big ushr 8) and 0xFF).toByte()
        raw[7] = (big and 0xFF).toByte()
        val text = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        val error = (IdentityQrCodec.decode(text) as IdentityQrDecodeResult.Failure).error
        // Either flagged as an invalid field size or truncated (buffer can't hold it).
        assertTrue(
            error == IdentityQrDecodeError.FIELD_SIZE_INVALID ||
                error == IdentityQrDecodeError.TRUNCATED,
        )
    }

    @Test
    fun oversizedBase64IsRejected() {
        val huge = "A".repeat(IdentityQrCodec.MAX_TOTAL_BYTES * 2 + 4)
        assertEquals(
            IdentityQrDecodeError.OVERSIZE,
            (IdentityQrCodec.decode(huge) as IdentityQrDecodeResult.Failure).error,
        )
    }

    @Test
    fun emptyFieldIsRejectedOnEncode() {
        val p = payload(ed = ByteArray(0))
        assertEquals(
            IdentityQrEncodeError.FIELD_SIZE_INVALID,
            (IdentityQrCodec.encode(p) as IdentityQrEncodeResult.Failure).error,
        )
    }

    @Test
    fun oversizedFieldIsRejectedOnEncode() {
        val p = payload(ed = ByteArray(IdentityQrCodec.MAX_FIELD_BYTES + 1))
        assertEquals(
            IdentityQrEncodeError.FIELD_SIZE_INVALID,
            (IdentityQrCodec.encode(p) as IdentityQrEncodeResult.Failure).error,
        )
    }

    @Test
    fun outOfRangeBindingVersionRejectedOnEncode() {
        val tooBig = payload(bindingVersion = 256)
        assertEquals(
            IdentityQrEncodeError.BINDING_VERSION_INVALID,
            (IdentityQrCodec.encode(tooBig) as IdentityQrEncodeResult.Failure).error,
        )
        val negative = payload(bindingVersion = -1)
        assertEquals(
            IdentityQrEncodeError.BINDING_VERSION_INVALID,
            (IdentityQrCodec.encode(negative) as IdentityQrEncodeResult.Failure).error,
        )
    }

    @Test
    fun maxByteBindingVersionEncodesWithoutTruncation() {
        val p = payload(bindingVersion = 255)
        val decoded = (IdentityQrCodec.decode(encode(p)) as IdentityQrDecodeResult.Success).payload
        assertEquals(255, decoded.bindingVersion)
    }
}

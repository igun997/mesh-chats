package com.meshchats.app.crypto.identity

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip and hostile-input tests for the wrapped identity-secret payload
 * codec. Decoding is total: every malformed input maps to a bounded error.
 */
class IdentitySecretCodecTest {

    private fun payload(
        priv: ByteArray = ByteArray(83) { it.toByte() },
        ed: ByteArray = ByteArray(44) { (it + 1).toByte() },
        fp: ByteArray = ByteArray(32) { (it + 2).toByte() },
        binding: ByteArray = ByteArray(33) { (it + 3).toByte() },
        sig: ByteArray = ByteArray(64) { (it + 4).toByte() },
        pair: ByteArray = ByteArray(64) { (it + 5).toByte() },
        regId: Int = 12345,
        schema: Int = 1,
        bindingVersion: Int = 1,
        createdAt: Long = 1_700_000_000_000L,
    ) = IdentitySecretPayload(
        version = IdentitySecretCodec.VERSION,
        privatePkcs8 = priv,
        edPublicX509 = ed,
        fingerprintSha256 = fp,
        signalPublicBinding = binding,
        bindingSignature = sig,
        bindingVersion = bindingVersion,
        signalRegistrationId = regId,
        signalSerializedKeyPair = pair,
        signalSchemaVersion = schema,
        createdAt = createdAt,
    )

    private fun encode(p: IdentitySecretPayload): ByteArray =
        (IdentitySecretCodec.encode(p) as IdentitySecretEncodeResult.Success).bytes

    @Test
    fun roundTripsAllFields() {
        val p = payload()
        val bytes = encode(p)
        val decoded = (IdentitySecretCodec.decode(bytes) as IdentitySecretDecodeResult.Success).payload
        assertArrayEquals(p.privatePkcs8, decoded.privatePkcs8)
        assertArrayEquals(p.edPublicX509, decoded.edPublicX509)
        assertArrayEquals(p.fingerprintSha256, decoded.fingerprintSha256)
        assertArrayEquals(p.signalPublicBinding, decoded.signalPublicBinding)
        assertArrayEquals(p.bindingSignature, decoded.bindingSignature)
        assertArrayEquals(p.signalSerializedKeyPair, decoded.signalSerializedKeyPair)
        assertEquals(p.signalRegistrationId, decoded.signalRegistrationId)
        assertEquals(p.signalSchemaVersion, decoded.signalSchemaVersion)
        assertEquals(p.bindingVersion, decoded.bindingVersion)
        assertEquals(p.createdAt, decoded.createdAt)
    }

    @Test
    fun truncatedIsRejected() {
        val bytes = encode(payload())
        val cut = bytes.copyOfRange(0, bytes.size - 3)
        assertEquals(
            IdentitySecretDecodeError.TRUNCATED,
            (IdentitySecretCodec.decode(cut) as IdentitySecretDecodeResult.Failure).error,
        )
    }

    @Test
    fun trailingBytesAreRejected() {
        val bytes = encode(payload()) + byteArrayOf(0)
        assertEquals(
            IdentitySecretDecodeError.TRAILING_BYTES,
            (IdentitySecretCodec.decode(bytes) as IdentitySecretDecodeResult.Failure).error,
        )
    }

    @Test
    fun wrongMagicIsRejected() {
        val bytes = encode(payload())
        bytes[1] = 0x00
        assertEquals(
            IdentitySecretDecodeError.UNKNOWN_MAGIC,
            (IdentitySecretCodec.decode(bytes) as IdentitySecretDecodeResult.Failure).error,
        )
    }

    @Test
    fun unsupportedVersionIsRejected() {
        val bytes = encode(payload())
        bytes[4] = 0x7F
        assertEquals(
            IdentitySecretDecodeError.UNSUPPORTED_VERSION,
            (IdentitySecretCodec.decode(bytes) as IdentitySecretDecodeResult.Failure).error,
        )
    }

    @Test
    fun emptyFieldRejectedOnEncode() {
        assertEquals(
            IdentitySecretEncodeError.FIELD_SIZE_INVALID,
            (IdentitySecretCodec.encode(payload(priv = ByteArray(0))) as IdentitySecretEncodeResult.Failure).error,
        )
    }

    @Test
    fun oversizedFieldRejectedOnEncode() {
        val big = ByteArray(IdentitySecretCodec.MAX_FIELD_BYTES + 1)
        assertEquals(
            IdentitySecretEncodeError.FIELD_SIZE_INVALID,
            (IdentitySecretCodec.encode(payload(priv = big)) as IdentitySecretEncodeResult.Failure).error,
        )
    }

    @Test
    fun zeroPrivateWipesBothSecrets() {
        val p = payload()
        p.zeroPrivate()
        assertTrue(p.privatePkcs8.all { it == 0.toByte() })
        assertTrue(p.signalSerializedKeyPair.all { it == 0.toByte() })
    }

    @Test
    fun outOfRangeBindingVersionRejectedOnEncode() {
        assertEquals(
            IdentitySecretEncodeError.VERSION_OUT_OF_RANGE,
            (IdentitySecretCodec.encode(payload(bindingVersion = 256)) as IdentitySecretEncodeResult.Failure).error,
        )
        assertEquals(
            IdentitySecretEncodeError.VERSION_OUT_OF_RANGE,
            (IdentitySecretCodec.encode(payload(bindingVersion = -1)) as IdentitySecretEncodeResult.Failure).error,
        )
    }

    @Test
    fun outOfRangeSignalSchemaVersionRejectedOnEncode() {
        assertEquals(
            IdentitySecretEncodeError.VERSION_OUT_OF_RANGE,
            (IdentitySecretCodec.encode(payload(schema = 256)) as IdentitySecretEncodeResult.Failure).error,
        )
    }

    @Test
    fun maxByteVersionsEncodeWithoutTruncation() {
        val p = payload(bindingVersion = 255, schema = 255)
        val decoded = (IdentitySecretCodec.decode(encode(p)) as IdentitySecretDecodeResult.Success).payload
        assertEquals(255, decoded.bindingVersion)
        assertEquals(255, decoded.signalSchemaVersion)
    }
}

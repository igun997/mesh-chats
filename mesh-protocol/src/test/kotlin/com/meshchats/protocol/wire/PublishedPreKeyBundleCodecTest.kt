package com.meshchats.protocol.wire

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PublishedPreKeyBundleCodecTest {

    // --- canonical sample values -------------------------------------------------

    private val oneTimePublic = byteArrayOf(0xA1.toByte(), 0xA2.toByte())
    private val signedPublic = byteArrayOf(0xB1.toByte(), 0xB2.toByte(), 0xB3.toByte())
    private val signedSignature = byteArrayOf(0xC1.toByte(), 0xC2.toByte())
    private val identityKey = byteArrayOf(0xD1.toByte(), 0xD2.toByte(), 0xD3.toByte(), 0xD4.toByte())
    private val kyberPublic = byteArrayOf(0xE1.toByte(), 0xE2.toByte())
    private val kyberSignature = byteArrayOf(0xF1.toByte())

    private fun bundle(
        registrationId: Int = 4660,
        deviceId: Int = 1,
        oneTimePreKeyId: Int? = 7,
        oneTimePreKeyPublic: ByteArray? = oneTimePublic,
        signedPreKeyId: Int = 9,
        signedPreKeyPublic: ByteArray = signedPublic,
        signedPreKeySignature: ByteArray = signedSignature,
        identity: ByteArray = identityKey,
        kyberPreKeyId: Int = 11,
        kyberPreKeyPublic: ByteArray = kyberPublic,
        kyberPreKeySignature: ByteArray = kyberSignature,
        issuedAtEpochMillis: Long = 256,
    ): PublishedPreKeyBundle = PublishedPreKeyBundle(
        registrationId = registrationId,
        deviceId = deviceId,
        oneTimePreKeyId = oneTimePreKeyId,
        oneTimePreKeyPublic = oneTimePreKeyPublic,
        signedPreKeyId = signedPreKeyId,
        signedPreKeyPublic = signedPreKeyPublic,
        signedPreKeySignature = signedPreKeySignature,
        identityKey = identity,
        kyberPreKeyId = kyberPreKeyId,
        kyberPreKeyPublic = kyberPreKeyPublic,
        kyberPreKeySignature = kyberPreKeySignature,
        issuedAtEpochMillis = issuedAtEpochMillis,
    )

    private fun encoded(b: PublishedPreKeyBundle): ByteArray {
        val r = PublishedPreKeyBundleCodec.encode(b)
        assertTrue("expected Success, got $r", r is PublishedBundleEncodeResult.Success)
        return (r as PublishedBundleEncodeResult.Success).bytes
    }

    private fun decoded(bytes: ByteArray): PublishedPreKeyBundle {
        val r = PublishedPreKeyBundleCodec.decode(bytes)
        assertTrue("expected Success, got $r", r is PublishedBundleDecodeResult.Success)
        return (r as PublishedBundleDecodeResult.Success).bundle
    }

    private fun decodeError(bytes: ByteArray): PublishedBundleDecodeError {
        val r = PublishedPreKeyBundleCodec.decode(bytes)
        assertTrue("expected Failure, got $r", r is PublishedBundleDecodeResult.Failure)
        return (r as PublishedBundleDecodeResult.Failure).error
    }

    // --- exact fixture -----------------------------------------------------------

    @Test
    fun `encodes the exact expected frame bytes`() {
        val frame = encoded(bundle())
        val expected = buildList {
            addAll(listOf(0x4D, 0x42, 0x4B, 0x31)) // magic MBK1
            add(0x01) // version
            add(0x01) // flags: has one-time EC prekey
            addAll(listOf(0x00, 0x00, 0x12, 0x34)) // registrationId 4660
            addAll(listOf(0x00, 0x00, 0x00, 0x01)) // deviceId 1
            addAll(listOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00)) // issuedAt 256
            addAll(listOf(0x00, 0x00, 0x00, 0x07)) // oneTimePreKeyId 7
            addAll(listOf(0x00, 0x02, 0xA1, 0xA2)) // oneTimePreKeyPublic
            addAll(listOf(0x00, 0x00, 0x00, 0x09)) // signedPreKeyId 9
            addAll(listOf(0x00, 0x03, 0xB1, 0xB2, 0xB3)) // signedPreKeyPublic
            addAll(listOf(0x00, 0x02, 0xC1, 0xC2)) // signedPreKeySignature
            addAll(listOf(0x00, 0x04, 0xD1, 0xD2, 0xD3, 0xD4)) // identityKey
            addAll(listOf(0x00, 0x00, 0x00, 0x0B)) // kyberPreKeyId 11
            addAll(listOf(0x00, 0x02, 0xE1, 0xE2)) // kyberPreKeyPublic
            addAll(listOf(0x00, 0x01, 0xF1)) // kyberPreKeySignature
        }.map { it.toByte() }.toByteArray()

        assertArrayEquals(expected, frame)
    }

    @Test
    fun `encoding is deterministic`() {
        val b = bundle()
        assertArrayEquals(encoded(b), encoded(b))
    }

    // --- round trips -------------------------------------------------------------

    @Test
    fun `round trips a bundle with a one-time prekey`() {
        val back = decoded(encoded(bundle()))
        assertEquals(4660, back.registrationId)
        assertEquals(1, back.deviceId)
        assertTrue(back.hasOneTimePreKey)
        assertEquals(7, back.oneTimePreKeyId)
        assertArrayEquals(oneTimePublic, back.oneTimePreKeyPublic)
        assertEquals(9, back.signedPreKeyId)
        assertArrayEquals(signedPublic, back.signedPreKeyPublic)
        assertArrayEquals(signedSignature, back.signedPreKeySignature)
        assertArrayEquals(identityKey, back.identityKey)
        assertEquals(11, back.kyberPreKeyId)
        assertArrayEquals(kyberPublic, back.kyberPreKeyPublic)
        assertArrayEquals(kyberSignature, back.kyberPreKeySignature)
        assertEquals(256L, back.issuedAtEpochMillis)
    }

    @Test
    fun `round trips a bundle without a one-time prekey`() {
        val b = bundle(oneTimePreKeyId = null, oneTimePreKeyPublic = null)
        val frame = encoded(b)
        // flags byte at offset 5 must have bit0 clear
        assertEquals(0x00.toByte(), frame[5])
        val back = decoded(frame)
        assertFalse(back.hasOneTimePreKey)
        assertNull(back.oneTimePreKeyId)
        assertNull(back.oneTimePreKeyPublic)
        assertArrayEquals(signedPublic, back.signedPreKeyPublic)
        assertArrayEquals(kyberPublic, back.kyberPreKeyPublic)
    }

    @Test
    fun `round trips field boundary maxima`() {
        val b = bundle(
            registrationId = PublishedPreKeyBundleCodec.MAX_REGISTRATION_ID,
            deviceId = Int.MAX_VALUE,
            oneTimePreKeyId = Int.MAX_VALUE,
            oneTimePreKeyPublic = ByteArray(PublishedPreKeyBundleCodec.MAX_EC_KEY_BYTES) { it.toByte() },
            signedPreKeyId = Int.MAX_VALUE,
            signedPreKeyPublic = ByteArray(PublishedPreKeyBundleCodec.MAX_EC_KEY_BYTES) { (it + 1).toByte() },
            signedPreKeySignature = ByteArray(PublishedPreKeyBundleCodec.MAX_SIGNATURE_BYTES) { it.toByte() },
            identity = ByteArray(PublishedPreKeyBundleCodec.MAX_EC_KEY_BYTES) { (it + 2).toByte() },
            kyberPreKeyId = Int.MAX_VALUE,
            kyberPreKeyPublic = ByteArray(PublishedPreKeyBundleCodec.MAX_KYBER_KEY_BYTES) { it.toByte() },
            kyberPreKeySignature = ByteArray(PublishedPreKeyBundleCodec.MAX_SIGNATURE_BYTES) { (it + 3).toByte() },
            issuedAtEpochMillis = Long.MAX_VALUE,
        )
        val back = decoded(encoded(b))
        assertEquals(PublishedPreKeyBundleCodec.MAX_REGISTRATION_ID, back.registrationId)
        assertEquals(Int.MAX_VALUE, back.deviceId)
        assertEquals(PublishedPreKeyBundleCodec.MAX_KYBER_KEY_BYTES, back.kyberPreKeyPublic.size)
        assertEquals(Long.MAX_VALUE, back.issuedAtEpochMillis)
    }

    @Test
    fun `round trips minimum registration id and single byte keys`() {
        val b = bundle(
            registrationId = 1,
            deviceId = 1,
            oneTimePreKeyId = 1,
            oneTimePreKeyPublic = byteArrayOf(1),
            signedPreKeyId = 1,
            signedPreKeyPublic = byteArrayOf(2),
            signedPreKeySignature = byteArrayOf(3),
            identity = byteArrayOf(4),
            kyberPreKeyId = 1,
            kyberPreKeyPublic = byteArrayOf(5),
            kyberPreKeySignature = byteArrayOf(6),
            issuedAtEpochMillis = 0,
        )
        val back = decoded(encoded(b))
        assertEquals(1, back.registrationId)
        assertEquals(0L, back.issuedAtEpochMillis)
    }

    // --- DTO invariants ----------------------------------------------------------

    @Test
    fun `dto defensively copies inputs`() {
        val ident = identityKey.copyOf()
        val b = bundle(identity = ident)
        ident[0] = 0x00
        // stored copy is unchanged
        assertArrayEquals(identityKey, b.identityKey)
    }

    @Test
    fun `dto getters return fresh copies`() {
        val b = bundle()
        val a = b.kyberPreKeyPublic
        val c = b.kyberPreKeyPublic
        assertNotSame(a, c)
        a[0] = 0x00
        assertArrayEquals(kyberPublic, b.kyberPreKeyPublic)
    }

    @Test
    fun `equal bundles are equal with equal hashcodes`() {
        assertEquals(bundle(), bundle())
        assertEquals(bundle().hashCode(), bundle().hashCode())
    }

    @Test
    fun `bundles differing in key bytes are unequal`() {
        assertFalse(bundle() == bundle(identity = byteArrayOf(9, 9, 9)))
    }

    @Test
    fun `toString exposes no key or signature bytes`() {
        val b = bundle()
        val s = b.toString()
        assertFalse("leaked identity key", s.contains("D1") || s.contains("d1"))
        assertFalse("leaked signature", s.contains("C1") || s.contains("c1"))
        // still useful: ids and sizes present
        assertTrue(s.contains("registrationId=4660"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `dto rejects only a one-time id without public`() {
        bundle(oneTimePreKeyId = 7, oneTimePreKeyPublic = null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `dto rejects only a one-time public without id`() {
        bundle(oneTimePreKeyId = null, oneTimePreKeyPublic = oneTimePublic)
    }

    // --- encode validation -------------------------------------------------------

    private fun encodeError(b: PublishedPreKeyBundle): PublishedBundleEncodeError {
        val r = PublishedPreKeyBundleCodec.encode(b)
        assertTrue("expected Failure, got $r", r is PublishedBundleEncodeResult.Failure)
        return (r as PublishedBundleEncodeResult.Failure).error
    }

    @Test
    fun `encode rejects registration id below range`() {
        assertEquals(PublishedBundleEncodeError.INVALID_REGISTRATION_ID, encodeError(bundle(registrationId = 0)))
    }

    @Test
    fun `encode rejects registration id above range`() {
        assertEquals(
            PublishedBundleEncodeError.INVALID_REGISTRATION_ID,
            encodeError(bundle(registrationId = PublishedPreKeyBundleCodec.MAX_REGISTRATION_ID + 1)),
        )
    }

    @Test
    fun `encode rejects non-positive device id`() {
        assertEquals(PublishedBundleEncodeError.INVALID_DEVICE_ID, encodeError(bundle(deviceId = 0)))
        assertEquals(PublishedBundleEncodeError.INVALID_DEVICE_ID, encodeError(bundle(deviceId = -1)))
    }

    @Test
    fun `encode rejects non-positive one-time id`() {
        assertEquals(
            PublishedBundleEncodeError.INVALID_ONE_TIME_PREKEY_ID,
            encodeError(bundle(oneTimePreKeyId = 0, oneTimePreKeyPublic = oneTimePublic)),
        )
    }

    @Test
    fun `encode rejects empty and oversized one-time public`() {
        assertEquals(
            PublishedBundleEncodeError.INVALID_ONE_TIME_PREKEY_PUBLIC,
            encodeError(bundle(oneTimePreKeyId = 7, oneTimePreKeyPublic = ByteArray(0))),
        )
        assertEquals(
            PublishedBundleEncodeError.INVALID_ONE_TIME_PREKEY_PUBLIC,
            encodeError(
                bundle(
                    oneTimePreKeyId = 7,
                    oneTimePreKeyPublic = ByteArray(PublishedPreKeyBundleCodec.MAX_EC_KEY_BYTES + 1),
                ),
            ),
        )
    }

    @Test
    fun `encode rejects non-positive signed prekey id`() {
        assertEquals(PublishedBundleEncodeError.INVALID_SIGNED_PREKEY_ID, encodeError(bundle(signedPreKeyId = 0)))
    }

    @Test
    fun `encode rejects empty and oversized signed public`() {
        assertEquals(
            PublishedBundleEncodeError.INVALID_SIGNED_PREKEY_PUBLIC,
            encodeError(bundle(signedPreKeyPublic = ByteArray(0))),
        )
        assertEquals(
            PublishedBundleEncodeError.INVALID_SIGNED_PREKEY_PUBLIC,
            encodeError(bundle(signedPreKeyPublic = ByteArray(PublishedPreKeyBundleCodec.MAX_EC_KEY_BYTES + 1))),
        )
    }

    @Test
    fun `encode rejects empty and oversized signed signature`() {
        assertEquals(
            PublishedBundleEncodeError.INVALID_SIGNED_PREKEY_SIGNATURE,
            encodeError(bundle(signedPreKeySignature = ByteArray(0))),
        )
        assertEquals(
            PublishedBundleEncodeError.INVALID_SIGNED_PREKEY_SIGNATURE,
            encodeError(bundle(signedPreKeySignature = ByteArray(PublishedPreKeyBundleCodec.MAX_SIGNATURE_BYTES + 1))),
        )
    }

    @Test
    fun `encode rejects empty and oversized identity key`() {
        assertEquals(PublishedBundleEncodeError.INVALID_IDENTITY_KEY, encodeError(bundle(identity = ByteArray(0))))
        assertEquals(
            PublishedBundleEncodeError.INVALID_IDENTITY_KEY,
            encodeError(bundle(identity = ByteArray(PublishedPreKeyBundleCodec.MAX_EC_KEY_BYTES + 1))),
        )
    }

    @Test
    fun `encode rejects non-positive kyber id`() {
        assertEquals(PublishedBundleEncodeError.INVALID_KYBER_PREKEY_ID, encodeError(bundle(kyberPreKeyId = 0)))
    }

    @Test
    fun `encode rejects empty and oversized kyber public`() {
        assertEquals(
            PublishedBundleEncodeError.INVALID_KYBER_PREKEY_PUBLIC,
            encodeError(bundle(kyberPreKeyPublic = ByteArray(0))),
        )
        assertEquals(
            PublishedBundleEncodeError.INVALID_KYBER_PREKEY_PUBLIC,
            encodeError(bundle(kyberPreKeyPublic = ByteArray(PublishedPreKeyBundleCodec.MAX_KYBER_KEY_BYTES + 1))),
        )
    }

    @Test
    fun `encode rejects empty and oversized kyber signature`() {
        assertEquals(
            PublishedBundleEncodeError.INVALID_KYBER_PREKEY_SIGNATURE,
            encodeError(bundle(kyberPreKeySignature = ByteArray(0))),
        )
        assertEquals(
            PublishedBundleEncodeError.INVALID_KYBER_PREKEY_SIGNATURE,
            encodeError(bundle(kyberPreKeySignature = ByteArray(PublishedPreKeyBundleCodec.MAX_SIGNATURE_BYTES + 1))),
        )
    }

    @Test
    fun `encode rejects negative timestamp`() {
        assertEquals(PublishedBundleEncodeError.INVALID_TIMESTAMP, encodeError(bundle(issuedAtEpochMillis = -1)))
    }

    @Test
    fun `encode success bytes are independent of later mutation`() {
        val r = PublishedPreKeyBundleCodec.encode(bundle())
        val bytes = (r as PublishedBundleEncodeResult.Success).bytes
        bytes[0] = 0x00
        assertEquals(0x4D.toByte(), encoded(bundle())[0])
    }

    // --- decode validation -------------------------------------------------------

    @Test
    fun `decode rejects an empty input`() {
        assertEquals(PublishedBundleDecodeError.TRUNCATED, decodeError(ByteArray(0)))
    }

    @Test
    fun `decode rejects unknown magic`() {
        val frame = encoded(bundle())
        frame[0] = 0x00
        assertEquals(PublishedBundleDecodeError.UNKNOWN_MAGIC, decodeError(frame))
    }

    @Test
    fun `decode rejects an unsupported version`() {
        val frame = encoded(bundle())
        frame[4] = 0x7F
        assertEquals(PublishedBundleDecodeError.UNSUPPORTED_VERSION, decodeError(frame))
    }

    @Test
    fun `decode rejects unknown flag bits`() {
        val frame = encoded(bundle())
        frame[5] = 0x02 // bit1 is not defined
        assertEquals(PublishedBundleDecodeError.INVALID_FLAGS, decodeError(frame))
    }

    @Test
    fun `decode rejects a reserved flag bit set alongside the one-time bit`() {
        val frame = encoded(bundle())
        // bit0 (one-time) legitimately set, plus a reserved bit7 that must be 0.
        frame[5] = (0x01 or 0x80).toByte()
        assertEquals(PublishedBundleDecodeError.INVALID_FLAGS, decodeError(frame))
    }

    @Test
    fun `decode rejects every individual reserved flag bit`() {
        for (bit in 1..7) {
            val frame = encoded(bundle())
            // keep bit0 as-is, additionally set one reserved bit
            frame[5] = (frame[5].toInt() or (1 shl bit)).toByte()
            assertEquals(
                "reserved bit $bit not rejected",
                PublishedBundleDecodeError.INVALID_FLAGS,
                decodeError(frame),
            )
        }
    }

    @Test
    fun `decode rejects a frame larger than the maximum`() {
        val huge = ByteArray(PublishedPreKeyBundleCodec.MAX_FRAME_BYTES + 1)
        // give it a valid magic so the size check is what fires
        huge[0] = 0x4D; huge[1] = 0x42; huge[2] = 0x4B; huge[3] = 0x31
        assertEquals(PublishedBundleDecodeError.FRAME_TOO_LARGE, decodeError(huge))
    }

    @Test
    fun `decode rejects registration id out of range`() {
        val frame = encoded(bundle())
        // registrationId is u32 at offset 6
        frame[6] = 0x00; frame[7] = 0x00; frame[8] = 0x00; frame[9] = 0x00 // 0
        assertEquals(PublishedBundleDecodeError.INVALID_REGISTRATION_ID, decodeError(frame))
    }

    @Test
    fun `decode rejects non-positive device id`() {
        val frame = encoded(bundle())
        // deviceId at offset 10..13
        frame[10] = 0x00; frame[11] = 0x00; frame[12] = 0x00; frame[13] = 0x00
        assertEquals(PublishedBundleDecodeError.INVALID_DEVICE_ID, decodeError(frame))
    }

    @Test
    fun `decode rejects negative timestamp`() {
        val frame = encoded(bundle())
        // issuedAt at offset 14..21, set high bit
        frame[14] = 0x80.toByte()
        assertEquals(PublishedBundleDecodeError.INVALID_TIMESTAMP, decodeError(frame))
    }

    @Test
    fun `decode rejects a zero-length signed public`() {
        // Build a frame by hand where signedPreKeyPublic length is 0.
        val frame = encoded(bundle())
        // Find signedPreKeyPublic length: after prefix(6) + reg(4)+dev(4)+ts(8) + otId(4)+otLen(2)+ot(2) + signedId(4)
        val signedLenOff = 6 + 4 + 4 + 8 + 4 + 2 + 2 + 4
        // The two bytes there are the u16 length (currently 3). Set to 0.
        frame[signedLenOff] = 0x00
        frame[signedLenOff + 1] = 0x00
        // A declared length of 0 is below the 1-byte minimum and the bytes for
        // the prefix are present, so this is a semantic bound violation (not
        // truncation) and maps deterministically to the signed-public error.
        assertEquals(PublishedBundleDecodeError.INVALID_SIGNED_PREKEY_PUBLIC, decodeError(frame))
    }

    @Test
    fun `decode rejects trailing bytes`() {
        val frame = encoded(bundle()) + byteArrayOf(0x00)
        assertEquals(PublishedBundleDecodeError.TRAILING_BYTES, decodeError(frame))
    }

    @Test
    fun `decode rejects a one-time public that overflows the buffer without allocating`() {
        // Hand-build a frame declaring an enormous one-time public length.
        val out = ArrayList<Byte>()
        out.addAll(listOf(0x4D, 0x42, 0x4B, 0x31).map { it.toByte() })
        out.add(0x01) // version
        out.add(0x01) // flags: has one-time
        out.addAll(listOf(0x00, 0x00, 0x12, 0x34).map { it.toByte() }) // reg
        out.addAll(listOf(0x00, 0x00, 0x00, 0x01).map { it.toByte() }) // device
        out.addAll(List(8) { 0.toByte() }) // issuedAt 0
        out.addAll(listOf(0x00, 0x00, 0x00, 0x07).map { it.toByte() }) // oneTimeId
        out.addAll(listOf(0xFF, 0xFF).map { it.toByte() }) // oneTimeLen 65535
        // no bytes follow
        assertEquals(PublishedBundleDecodeError.TRUNCATED, decodeError(out.toByteArray()))
    }

    // --- truncation at every byte ------------------------------------------------

    @Test
    fun `decode never throws and rejects truncation at every prefix length`() {
        val frame = encoded(bundle())
        for (cut in 0 until frame.size) {
            val r = PublishedPreKeyBundleCodec.decode(frame.copyOf(cut))
            assertTrue("cut=$cut expected Failure", r is PublishedBundleDecodeResult.Failure)
        }
        // the full frame decodes
        assertTrue(PublishedPreKeyBundleCodec.decode(frame) is PublishedBundleDecodeResult.Success)
    }

    // --- fuzzing -----------------------------------------------------------------

    /**
     * Asserts every field of a fuzz-decoded bundle sits within its declared
     * bound and within the enclosing buffer, so a successful decode can never
     * hand back an over-sized or over-allocated array.
     */
    private fun assertDecodedWithinBounds(bundle: PublishedPreKeyBundle, bufferLen: Int) {
        bundle.oneTimePreKeyPublic?.let {
            assertTrue(it.size in 1..PublishedPreKeyBundleCodec.MAX_EC_KEY_BYTES)
            assertTrue(it.size <= bufferLen)
        }
        assertTrue(bundle.signedPreKeyPublic.size in 1..PublishedPreKeyBundleCodec.MAX_EC_KEY_BYTES)
        assertTrue(bundle.signedPreKeyPublic.size <= bufferLen)
        assertTrue(bundle.signedPreKeySignature.size in 1..PublishedPreKeyBundleCodec.MAX_SIGNATURE_BYTES)
        assertTrue(bundle.signedPreKeySignature.size <= bufferLen)
        assertTrue(bundle.identityKey.size in 1..PublishedPreKeyBundleCodec.MAX_EC_KEY_BYTES)
        assertTrue(bundle.identityKey.size <= bufferLen)
        assertTrue(bundle.kyberPreKeyPublic.size in 1..PublishedPreKeyBundleCodec.MAX_KYBER_KEY_BYTES)
        assertTrue(bundle.kyberPreKeyPublic.size <= bufferLen)
        assertTrue(bundle.kyberPreKeySignature.size in 1..PublishedPreKeyBundleCodec.MAX_SIGNATURE_BYTES)
        assertTrue(bundle.kyberPreKeySignature.size <= bufferLen)
    }

    @Test
    fun `fuzzing random garbage never throws and never over-allocates`() {
        val rng = Random(20260816)
        repeat(5_000) {
            val len = rng.nextInt(0, 2_048)
            val bytes = ByteArray(len).also { rng.nextBytes(it) }
            val r = PublishedPreKeyBundleCodec.decode(bytes) // must not throw
            if (r is PublishedBundleDecodeResult.Success) {
                assertDecodedWithinBounds(r.bundle, len)
            }
        }
    }

    /**
     * Fully random bytes almost never survive the magic/version/flags prefix,
     * so the deep parser paths (length reads, field slicing, bound checks) go
     * largely unexercised. Seeding a valid "MBK1" + version + flags prefix and
     * then filling the remaining bytes randomly forces the decoder down those
     * paths for the deterministic fraction, while the fully-random branch keeps
     * covering the reject-at-prefix behavior. Either way decoding must never
     * throw, and any success must respect every declared bound.
     */
    @Test
    fun `fuzzing with a valid frame prefix exercises deep parser paths`() {
        val rng = Random(0x5eed_1234L)
        var reachedDeepReject = false
        repeat(20_000) {
            val len = rng.nextInt(6, 512) // >= prefix (magic+version+flags)
            val bytes = ByteArray(len)
            if (rng.nextInt(2) == 0) {
                // Valid MBK1 prefix + version 1 + a flags byte with only defined
                // bits, so the parser proceeds past the prefix and into the body.
                bytes[0] = 0x4D; bytes[1] = 0x42; bytes[2] = 0x4B; bytes[3] = 0x31
                bytes[4] = 0x01
                bytes[5] = (rng.nextInt(2)).toByte() // 0 or 1: no reserved bits
                for (i in 6 until len) bytes[i] = rng.nextInt(256).toByte()
            } else {
                rng.nextBytes(bytes)
            }
            val r = PublishedPreKeyBundleCodec.decode(bytes) // must not throw
            when (r) {
                is PublishedBundleDecodeResult.Success -> {
                    assertDecodedWithinBounds(r.bundle, len)
                }
                is PublishedBundleDecodeResult.Failure -> {
                    // Any body-level failure means we got past the prefix gate.
                    if (r.error != PublishedBundleDecodeError.UNKNOWN_MAGIC &&
                        r.error != PublishedBundleDecodeError.UNSUPPORTED_VERSION &&
                        r.error != PublishedBundleDecodeError.INVALID_FLAGS &&
                        r.error != PublishedBundleDecodeError.FRAME_TOO_LARGE
                    ) {
                        reachedDeepReject = true
                    }
                }
            }
        }
        assertTrue("fuzz never reached a deep (post-prefix) reject path", reachedDeepReject)
    }

    @Test
    fun `valid-frame mutation fuzz never throws and respects bounds`() {
        val valid = encoded(bundle())
        val rng = Random(987654321)
        var cases = 0
        repeat(3_000) {
            val mutated = valid.copyOf()
            when (rng.nextInt(3)) {
                0 -> {
                    val i = rng.nextInt(mutated.size)
                    mutated[i] = (mutated[i].toInt() xor (1 shl rng.nextInt(8))).toByte()
                }
                1 -> {
                    // clobber a random 2-byte length field region
                    val i = rng.nextInt(mutated.size - 1)
                    mutated[i] = rng.nextInt(256).toByte()
                    mutated[i + 1] = rng.nextInt(256).toByte()
                }
                2 -> {
                    val cut = rng.nextInt(mutated.size + 1)
                    val r = PublishedPreKeyBundleCodec.decode(mutated.copyOf(cut))
                    if (r is PublishedBundleDecodeResult.Success) {
                        assertTrue(r.bundle.kyberPreKeyPublic.size <= PublishedPreKeyBundleCodec.MAX_KYBER_KEY_BYTES)
                    }
                    cases++
                    return@repeat
                }
            }
            val r = PublishedPreKeyBundleCodec.decode(mutated) // must not throw
            if (r is PublishedBundleDecodeResult.Success) {
                assertTrue(r.bundle.signedPreKeyPublic.size <= PublishedPreKeyBundleCodec.MAX_EC_KEY_BYTES)
                assertTrue(r.bundle.kyberPreKeyPublic.size <= PublishedPreKeyBundleCodec.MAX_KYBER_KEY_BYTES)
                assertTrue(r.bundle.signedPreKeySignature.size <= PublishedPreKeyBundleCodec.MAX_SIGNATURE_BYTES)
            }
            cases++
        }
        assertTrue("expected >= 2500 cases, ran $cases", cases >= 2_500)
    }
}

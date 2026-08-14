package com.meshchats.protocol.codec

import com.meshchats.protocol.routing.MeshPacket
import com.meshchats.protocol.routing.PacketId
import com.meshchats.protocol.routing.PacketKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MeshPacketCodecTest {

    // A 16-byte id as 32 lowercase hex chars, the canonical wire form.
    private val idHex = "000102030405060708090a0b0c0d0e0f"
    private val destTag = ByteArray(16) { (0x10 + it).toByte() }
    private val originKeyId = ByteArray(16) { (0x20 + it).toByte() }

    private fun packet(
        id: String = idHex,
        kind: PacketKind = PacketKind.TEXT,
        expiresAtMillis: Long = 256,
        hopsRemaining: Int = 5,
        ciphertext: ByteArray = byteArrayOf(1, 2, 3),
        signature: ByteArray = byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
        destination: ByteArray = destTag,
        keyId: ByteArray = originKeyId,
    ): MeshPacket = MeshPacket.create(
        packetId = PacketId(id),
        kind = kind,
        destinationTag = destination,
        expiresAtMillis = expiresAtMillis,
        hopsRemaining = hopsRemaining,
        ciphertext = ciphertext,
        originSignature = signature,
        originKeyId = keyId,
    )

    private fun decoded(bytes: ByteArray): MeshPacket {
        val result = MeshPacketCodec.decode(bytes)
        assertTrue("expected Success, got $result", result is DecodeResult.Success)
        return (result as DecodeResult.Success).packet
    }

    private fun encoded(packet: MeshPacket): ByteArray {
        val result = MeshPacketCodec.encode(packet)
        assertTrue("expected Success, got $result", result is EncodeResult.Success)
        return (result as EncodeResult.Success).bytes
    }

    @Test
    fun `round trips every packet kind`() {
        for (kind in PacketKind.entries) {
            val original = packet(kind = kind)
            val frame = encoded(original)
            val back = decoded(frame)

            assertEquals(kind, back.kind)
            assertEquals(original.packetId, back.packetId)
            assertEquals(original.expiresAtMillis, back.expiresAtMillis)
            assertEquals(original.hopsRemaining, back.hopsRemaining)
            assertEquals(original.protocolVersion, back.protocolVersion)
            assertArrayEquals(original.ciphertext, back.ciphertext)
            assertArrayEquals(original.originSignature, back.originSignature)
            assertArrayEquals(original.destinationTag, back.destinationTag)
            assertArrayEquals(original.originKeyId, back.originKeyId)
        }
    }

    @Test
    fun `encodes the exact expected frame bytes`() {
        val frame = encoded(packet())

        val expected = buildList {
            addAll(listOf(0x4D, 0x53, 0x48, 0x31)) // magic MSH1
            add(0x01) // version 1
            add(0x01) // kind TEXT
            addAll(listOf(0x00, 0x3C)) // headerLength 60
            addAll(listOf(0x00, 0x00, 0x00, 0x0B)) // payloadLength 11
            // header
            addAll((0x00..0x0f).toList()) // packetId
            addAll((0x10..0x1f).toList()) // destinationTag
            addAll(listOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00)) // expiry 256
            addAll(listOf(0x00, 0x00, 0x00, 0x05)) // hopsRemaining 5
            addAll((0x20..0x2f).toList()) // originKeyId
            // payload
            addAll(listOf(0x00, 0x02)) // sigLength 2
            addAll(listOf(0xAA, 0xBB)) // signature
            addAll(listOf(0x00, 0x00, 0x00, 0x03)) // ctLength 3
            addAll(listOf(0x01, 0x02, 0x03)) // ciphertext
        }.map { it.toByte() }.toByteArray()

        assertArrayEquals(expected, frame)
    }

    @Test
    fun `encoding is deterministic`() {
        val p = packet()
        assertArrayEquals(encoded(p), encoded(p))
    }

    @Test
    fun `round trips an empty ciphertext and empty signature`() {
        val frame = encoded(
            packet(ciphertext = ByteArray(0), signature = ByteArray(0)),
        )
        val back = decoded(frame)
        assertEquals(0, back.ciphertext.size)
        assertEquals(0, back.originSignature.size)
    }

    @Test
    fun `round trips signature at the 256 byte bound`() {
        val sig = ByteArray(256) { (it and 0xFF).toByte() }
        val back = decoded(encoded(packet(signature = sig)))
        assertArrayEquals(sig, back.originSignature)
    }

    @Test
    fun `round trips ciphertext at the 1 MiB bound`() {
        val ct = ByteArray(1 shl 20) { (it and 0xFF).toByte() }
        val back = decoded(encoded(packet(ciphertext = ct)))
        assertArrayEquals(ct, back.ciphertext)
    }

    @Test
    fun `decoded byte arrays are defensive copies`() {
        val back = decoded(encoded(packet()))
        val first = back.ciphertext
        val second = back.ciphertext
        first[0] = 99
        assertNotSame(first, second)
        assertArrayEquals(byteArrayOf(1, 2, 3), second)
    }

    @Test
    fun `rejects unknown magic`() {
        val frame = encoded(packet())
        frame[0] = 0x00
        assertEquals(DecodeResult.Failure(DecodeError.UNKNOWN_MAGIC), MeshPacketCodec.decode(frame))
    }

    @Test
    fun `rejects an unsupported version`() {
        val frame = encoded(packet())
        frame[4] = 0x7F
        assertEquals(
            DecodeResult.Failure(DecodeError.UNSUPPORTED_VERSION),
            MeshPacketCodec.decode(frame),
        )
    }

    @Test
    fun `rejects an unknown kind`() {
        val frame = encoded(packet())
        frame[5] = 0x40
        assertEquals(DecodeResult.Failure(DecodeError.UNKNOWN_KIND), MeshPacketCodec.decode(frame))
    }

    @Test
    fun `rejects a truncated frame`() {
        val frame = encoded(packet())
        val truncated = frame.copyOf(frame.size - 1)
        assertEquals(DecodeResult.Failure(DecodeError.TRUNCATED), MeshPacketCodec.decode(truncated))
    }

    @Test
    fun `rejects trailing bytes`() {
        val frame = encoded(packet())
        val extended = frame + byteArrayOf(0x00)
        assertEquals(
            DecodeResult.Failure(DecodeError.TRAILING_BYTES),
            MeshPacketCodec.decode(extended),
        )
    }

    @Test
    fun `rejects an empty input`() {
        assertEquals(DecodeResult.Failure(DecodeError.TRUNCATED), MeshPacketCodec.decode(ByteArray(0)))
    }

    @Test
    fun `rejects a malformed header length`() {
        val frame = encoded(packet())
        // Corrupt headerLength (offset 6..7) to a wrong value.
        frame[6] = 0x00
        frame[7] = 0x3B // 59 instead of 60
        assertEquals(
            DecodeResult.Failure(DecodeError.MALFORMED_HEADER),
            MeshPacketCodec.decode(frame),
        )
    }

    @Test
    fun `rejects negative hop budget`() {
        val frame = encoded(packet())
        // hopsRemaining is 4 bytes at offset 12 + 16 + 16 + 8 = 52.
        val hopsOffset = 12 + 16 + 16 + 8
        frame[hopsOffset] = 0xFF.toByte()
        frame[hopsOffset + 1] = 0xFF.toByte()
        frame[hopsOffset + 2] = 0xFF.toByte()
        frame[hopsOffset + 3] = 0xFF.toByte()
        assertEquals(DecodeResult.Failure(DecodeError.NEGATIVE_VALUE), MeshPacketCodec.decode(frame))
    }

    @Test
    fun `rejects a signature above the 256 byte bound without allocating it`() {
        // payloadLength is honest (matches buffer) but sigLength claims 257.
        val frame = frameWith(sigLen = 257, sig = ByteArray(257), ctLen = 0, ct = ByteArray(0))
        assertEquals(
            DecodeResult.Failure(DecodeError.SIGNATURE_TOO_LARGE),
            MeshPacketCodec.decode(frame),
        )
    }

    @Test
    fun `rejects a ciphertext above 1 MiB without allocating it`() {
        // A tiny buffer that declares a 2 GiB ciphertext must fail fast.
        val frame = frameHeader(payloadLength = 10L) +
            byteArrayOf(0x00, 0x00) + // sigLen 0
            byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) + // ctLen ~2GiB
            ByteArray(4) // 4 filler bytes so payload region is 10 bytes
        val result = MeshPacketCodec.decode(frame)
        assertEquals(DecodeResult.Failure(DecodeError.CIPHERTEXT_TOO_LARGE), result)
    }

    @Test
    fun `rejects a frame whose declared payload exceeds the maximum`() {
        val frame = frameHeader(payloadLength = MeshPacketCodec.MAX_PAYLOAD_BYTES + 1)
        assertEquals(DecodeResult.Failure(DecodeError.FRAME_TOO_LARGE), MeshPacketCodec.decode(frame))
    }

    @Test
    fun `rejects an internally inconsistent payload length`() {
        // sigLen + ctLen do not add up to the declared payloadLength.
        val frame = frameWith(sigLen = 1, sig = byteArrayOf(9), ctLen = 1, ct = byteArrayOf(8), padPayload = 5)
        assertEquals(
            DecodeResult.Failure(DecodeError.PAYLOAD_LENGTH_MISMATCH),
            MeshPacketCodec.decode(frame),
        )
    }

    @Test
    fun `encodes an uppercase hex packet id and decodes to canonical lowercase`() {
        val upper = "000102030405060708090A0B0C0D0E0F"
        val back = decoded(encoded(packet(id = upper)))
        assertEquals(idHex, back.packetId.value)
    }

    @Test
    fun `rejects an opaque non-hex packet id without throwing`() {
        // Routing/tracer PacketIds are opaque and need not be 32 hex chars.
        val result = MeshPacketCodec.encode(packet(id = "route-tracer-opaque-id"))
        assertEquals(EncodeResult.Failure(EncodeError.INVALID_PACKET_ID), result)
    }

    @Test
    fun `rejects a dashed uuid style packet id`() {
        val result = MeshPacketCodec.encode(packet(id = "00010203-0405-0607-0809-0a0b0c0d0e0f"))
        assertEquals(EncodeResult.Failure(EncodeError.INVALID_PACKET_ID), result)
    }

    @Test
    fun `rejects a short packet id`() {
        val result = MeshPacketCodec.encode(packet(id = "00010203"))
        assertEquals(EncodeResult.Failure(EncodeError.INVALID_PACKET_ID), result)
    }

    @Test
    fun `rejects an odd length packet id`() {
        val result = MeshPacketCodec.encode(packet(id = "abc"))
        assertEquals(EncodeResult.Failure(EncodeError.INVALID_PACKET_ID), result)
    }

    @Test
    fun `rejects an empty packet id`() {
        val result = MeshPacketCodec.encode(packet(id = ""))
        assertEquals(EncodeResult.Failure(EncodeError.INVALID_PACKET_ID), result)
    }

    @Test
    fun `rejects a wrong width destination tag`() {
        val result = MeshPacketCodec.encode(packet(destination = ByteArray(15)))
        assertEquals(EncodeResult.Failure(EncodeError.INVALID_DESTINATION_TAG), result)
    }

    @Test
    fun `rejects a wrong width origin key id`() {
        val result = MeshPacketCodec.encode(packet(keyId = ByteArray(17)))
        assertEquals(EncodeResult.Failure(EncodeError.INVALID_ORIGIN_KEY_ID), result)
    }

    @Test
    fun `encode success bytes are a defensive copy independent of later mutation`() {
        val result = MeshPacketCodec.encode(packet())
        assertTrue(result is EncodeResult.Success)
        val bytes = (result as EncodeResult.Success).bytes
        bytes[0] = 0x00
        // Re-encoding the same packet still yields a valid, unmutated frame.
        assertEquals(0x4D.toByte(), encoded(packet())[0])
    }

    @Test
    fun `deterministic mutation fuzz over a valid frame never throws and respects bounds`() {
        val valid = encoded(packet(ciphertext = byteArrayOf(1, 2, 3, 4, 5), signature = byteArrayOf(7, 8)))
        val rng = Random(424242)
        var cases = 0
        repeat(1_200) {
            val mutated = valid.copyOf()
            when (rng.nextInt(3)) {
                0 -> {
                    // Flip a random byte.
                    val i = rng.nextInt(mutated.size)
                    mutated[i] = (mutated[i].toInt() xor (1 shl rng.nextInt(8))).toByte()
                }
                1 -> {
                    // Overwrite the 4-byte payloadLength field (offset 8..11).
                    for (i in 8..11) mutated[i] = rng.nextInt(256).toByte()
                }
                2 -> {
                    // Truncate to a random shorter length.
                    val cut = rng.nextInt(mutated.size + 1)
                    val trunc = mutated.copyOf(cut)
                    val r = MeshPacketCodec.decode(trunc) // must not throw
                    if (r is DecodeResult.Success) {
                        assertTrue(r.packet.originSignature.size <= MeshPacketCodec.MAX_SIGNATURE_BYTES)
                        assertTrue(r.packet.ciphertext.size <= MeshPacketCodec.MAX_CIPHERTEXT_BYTES)
                        assertTrue(r.packet.ciphertext.size <= cut)
                    }
                    cases++
                    return@repeat
                }
            }
            val result = MeshPacketCodec.decode(mutated) // must not throw
            if (result is DecodeResult.Success) {
                assertTrue(result.packet.originSignature.size <= MeshPacketCodec.MAX_SIGNATURE_BYTES)
                assertTrue(result.packet.ciphertext.size <= MeshPacketCodec.MAX_CIPHERTEXT_BYTES)
                assertTrue(result.packet.ciphertext.size <= mutated.size)
            }
            cases++
        }
        assertTrue("expected >= 1000 fuzz cases, ran $cases", cases >= 1_000)
    }

    @Test
    fun `fuzzing random bytes never throws and never returns an oversized packet`() {
        val rng = Random(20260814)
        repeat(5_000) {
            val len = rng.nextInt(0, 2_048)
            val bytes = ByteArray(len).also { rng.nextBytes(it) }
            val result = MeshPacketCodec.decode(bytes) // must not throw
            if (result is DecodeResult.Success) {
                // Any packet decoded from a <2KiB buffer cannot exceed it.
                assertTrue(result.packet.ciphertext.size <= len)
                assertTrue(result.packet.originSignature.size <= len)
            }
        }
    }

    // --- frame construction helpers for malformed cases ---

    /** A valid 12-byte prefix + 60-byte header, with a chosen payloadLength. */
    private fun frameHeader(payloadLength: Long): ByteArray {
        val out = ArrayList<Byte>()
        out.addAll(listOf(0x4D, 0x53, 0x48, 0x31).map { it.toByte() }) // magic
        out.add(0x01) // version
        out.add(0x01) // kind TEXT
        out.addAll(listOf(0x00, 0x3C).map { it.toByte() }) // headerLength 60
        out.addAll(uint32(payloadLength))
        out.addAll((0x00..0x0f).map { it.toByte() }) // packetId
        out.addAll((0x10..0x1f).map { it.toByte() }) // destTag
        out.addAll(listOf(0, 0, 0, 0, 0, 0, 1, 0).map { it.toByte() }) // expiry 256
        out.addAll(listOf(0, 0, 0, 5).map { it.toByte() }) // hops 5
        out.addAll((0x20..0x2f).map { it.toByte() }) // originKeyId
        return out.toByteArray()
    }

    /**
     * Builds a full frame with an explicit sigLength/ctLength that may disagree
     * with the actual bytes, plus optional padding, so payload consistency can be
     * probed. payloadLength is set to the real number of payload bytes emitted.
     */
    private fun frameWith(
        sigLen: Int,
        sig: ByteArray,
        ctLen: Long,
        ct: ByteArray,
        padPayload: Int = 0,
    ): ByteArray {
        val payload = ArrayList<Byte>()
        payload.addAll(uint16(sigLen))
        payload.addAll(sig.toList())
        payload.addAll(uint32(ctLen))
        payload.addAll(ct.toList())
        repeat(padPayload) { payload.add(0) }
        val header = frameHeader(payloadLength = payload.size.toLong())
        return header + payload.toByteArray()
    }

    private fun uint16(v: Int): List<Byte> =
        listOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun uint32(v: Long): List<Byte> = listOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )
}

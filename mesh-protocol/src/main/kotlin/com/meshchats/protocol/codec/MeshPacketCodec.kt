package com.meshchats.protocol.codec

import com.meshchats.protocol.routing.MeshPacket
import com.meshchats.protocol.routing.PacketId
import com.meshchats.protocol.routing.PacketKind

/**
 * A bounded reason a frame could not be decoded. Decoding never throws for
 * malformed input; every rejection is one of these closed values so callers can
 * branch exhaustively and a hostile peer cannot trigger an exception path.
 */
enum class DecodeError {
    /** Fewer bytes than the fixed prefix, header, or declared payload require. */
    TRUNCATED,

    /** Extra bytes beyond the single declared frame. */
    TRAILING_BYTES,

    /** Leading magic did not match the protocol. */
    UNKNOWN_MAGIC,

    /** Wire version is not one this build understands. */
    UNSUPPORTED_VERSION,

    /** Packet kind wire code is not recognized. */
    UNKNOWN_KIND,

    /** Header length field is not the fixed expected width. */
    MALFORMED_HEADER,

    /** A length or count field decoded to a negative value. */
    NEGATIVE_VALUE,

    /** Declared payload length exceeds the hard frame bound. */
    FRAME_TOO_LARGE,

    /** Declared signature length exceeds the signature bound. */
    SIGNATURE_TOO_LARGE,

    /** Declared ciphertext length exceeds the ciphertext bound. */
    CIPHERTEXT_TOO_LARGE,

    /** Signature and ciphertext lengths do not add up to the declared payload. */
    PAYLOAD_LENGTH_MISMATCH,
}

/**
 * Result of decoding a frame. [Success] carries the reconstructed packet;
 * [Failure] carries a bounded [DecodeError]. No malformed input throws.
 */
sealed interface DecodeResult {
    data class Success(val packet: MeshPacket) : DecodeResult
    data class Failure(val error: DecodeError) : DecodeResult
}

/**
 * A bounded reason a packet could not be encoded. Encoding never throws; every
 * rejection is one of these closed values so a caller holding an in-memory
 * [MeshPacket] whose [PacketId] is opaque (used for routing/tracing rather than
 * the wire form) gets an explicit [EncodeResult.Failure] instead of an
 * exception.
 */
enum class EncodeError {
    /** packetId is not exactly 32 hex chars decoding to 16 bytes. */
    INVALID_PACKET_ID,

    /** destinationTag is not the fixed 16-byte width. */
    INVALID_DESTINATION_TAG,

    /** originKeyId is not the fixed 16-byte width. */
    INVALID_ORIGIN_KEY_ID,

    /** Signature exceeds the signature bound. */
    SIGNATURE_TOO_LARGE,

    /** Ciphertext exceeds the ciphertext bound. */
    CIPHERTEXT_TOO_LARGE,

    /** protocolVersion does not fit in a single wire byte. */
    UNSUPPORTED_VERSION,
}

/**
 * Result of encoding a packet. [Success] carries the frame bytes; [Failure]
 * carries a bounded [EncodeError]. Encoding never throws.
 */
sealed interface EncodeResult {
    data class Success(val bytes: ByteArray) : EncodeResult {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Success && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data class Failure(val error: EncodeError) : EncodeResult
}

/**
 * Deterministic, big-endian codec for the opaque mesh packet frame.
 *
 * Frame layout (all integers big-endian):
 * ```
 * magic          4   'M' 'S' 'H' '1'
 * version        1
 * kind           1   stable PacketKind.wireCode, not ordinal
 * headerLength   2   fixed 60
 * payloadLength  4   uint32, bounded before any allocation
 * --- fixed 60-byte header ---
 * packetId      16
 * destinationTag 16
 * expiresAtMillis 8  signed long
 * hopsRemaining  4   signed int, must be >= 0
 * originKeyId   16
 * --- payload (payloadLength bytes) ---
 * sigLength      2   uint16, <= 256
 * signature    sigLength
 * ctLength       4   uint32, <= 1 MiB
 * ciphertext   ctLength
 * ```
 *
 * The decoder validates magic, version, kind, header width, and every length
 * against the remaining buffer and against hard bounds **before** allocating any
 * attacker-declared array, so a hostile frame can neither allocate gigabytes nor
 * read out of bounds. It fails closed on unknown or truncated input.
 */
object MeshPacketCodec {

    private val MAGIC = byteArrayOf(0x4D, 0x53, 0x48, 0x31) // "MSH1"

    private const val VERSION = 1
    private const val PREFIX_BYTES = 12 // magic(4)+version(1)+kind(1)+headerLen(2)+payloadLen(4)
    private const val HEADER_BYTES = 60 // packetId(16)+destTag(16)+expiry(8)+hops(4)+originKeyId(16)
    private const val PACKET_ID_BYTES = 16
    private const val DESTINATION_TAG_BYTES = 16
    private const val ORIGIN_KEY_ID_BYTES = 16

    /** Maximum signature size, in bytes. */
    const val MAX_SIGNATURE_BYTES: Int = 256

    /** Maximum ciphertext size, in bytes (1 MiB). */
    const val MAX_CIPHERTEXT_BYTES: Int = 1 shl 20

    /**
     * Hard ceiling on the declared payload region: the largest legitimate
     * `sigLength(2) + signature + ctLength(4) + ciphertext`. A frame declaring
     * more is rejected before allocation.
     */
    const val MAX_PAYLOAD_BYTES: Long =
        (2 + MAX_SIGNATURE_BYTES + 4 + MAX_CIPHERTEXT_BYTES).toLong()

    /**
     * Serializes [packet] to a canonical, deterministic frame. Never throws:
     * every invariant violation (opaque/non-hex packetId, wrong fixed-width
     * fields, oversized signature/ciphertext, out-of-range version) is reported
     * as a bounded [EncodeResult.Failure]. On success the returned bytes are a
     * fresh array owned by the caller.
     */
    fun encode(packet: MeshPacket): EncodeResult {
        val packetIdBytes = hexToBytesOrNull(packet.packetId.value)
            ?: return failEncode(EncodeError.INVALID_PACKET_ID)
        if (packetIdBytes.size != PACKET_ID_BYTES) {
            return failEncode(EncodeError.INVALID_PACKET_ID)
        }
        val destinationTag = packet.destinationTag
        if (destinationTag.size != DESTINATION_TAG_BYTES) {
            return failEncode(EncodeError.INVALID_DESTINATION_TAG)
        }
        val originKeyId = packet.originKeyId
        if (originKeyId.size != ORIGIN_KEY_ID_BYTES) {
            return failEncode(EncodeError.INVALID_ORIGIN_KEY_ID)
        }
        val signature = packet.originSignature
        if (signature.size > MAX_SIGNATURE_BYTES) {
            return failEncode(EncodeError.SIGNATURE_TOO_LARGE)
        }
        val ciphertext = packet.ciphertext
        if (ciphertext.size > MAX_CIPHERTEXT_BYTES) {
            return failEncode(EncodeError.CIPHERTEXT_TOO_LARGE)
        }
        if (packet.protocolVersion !in 0..0xFF) {
            return failEncode(EncodeError.UNSUPPORTED_VERSION)
        }

        val payloadLength = 2 + signature.size + 4 + ciphertext.size
        val frame = ByteArray(PREFIX_BYTES + HEADER_BYTES + payloadLength)
        var off = 0

        // prefix
        MAGIC.copyInto(frame, off); off += MAGIC.size
        frame[off++] = packet.protocolVersion.toByte()
        frame[off++] = packet.kind.wireCode.toByte()
        off = putU16(frame, off, HEADER_BYTES)
        off = putU32(frame, off, payloadLength.toLong())

        // header
        packetIdBytes.copyInto(frame, off); off += PACKET_ID_BYTES
        destinationTag.copyInto(frame, off); off += DESTINATION_TAG_BYTES
        off = putI64(frame, off, packet.expiresAtMillis)
        off = putI32(frame, off, packet.hopsRemaining)
        originKeyId.copyInto(frame, off); off += ORIGIN_KEY_ID_BYTES

        // payload
        off = putU16(frame, off, signature.size)
        signature.copyInto(frame, off); off += signature.size
        off = putU32(frame, off, ciphertext.size.toLong())
        ciphertext.copyInto(frame, off); off += ciphertext.size

        return EncodeResult.Success(frame)
    }

    /** Parses a frame. Never throws on malformed input; returns [DecodeResult.Failure]. */
    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size < PREFIX_BYTES) return fail(DecodeError.TRUNCATED)

        // magic
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) return fail(DecodeError.UNKNOWN_MAGIC)
        }

        val version = bytes[4].toInt() and 0xFF
        if (version != VERSION) return fail(DecodeError.UNSUPPORTED_VERSION)

        val kind = PacketKind.fromWireCode(bytes[5].toInt() and 0xFF)
            ?: return fail(DecodeError.UNKNOWN_KIND)

        val headerLength = getU16(bytes, 6)
        if (headerLength != HEADER_BYTES) return fail(DecodeError.MALFORMED_HEADER)

        val payloadLength = getU32(bytes, 8)
        // Bound the declared payload before trusting the buffer size.
        if (payloadLength > MAX_PAYLOAD_BYTES) return fail(DecodeError.FRAME_TOO_LARGE)

        val total = PREFIX_BYTES.toLong() + HEADER_BYTES + payloadLength
        if (bytes.size < total) return fail(DecodeError.TRUNCATED)
        if (bytes.size.toLong() > total) return fail(DecodeError.TRAILING_BYTES)

        // header fields (payloadLength <= MAX_PAYLOAD_BYTES so total fits in Int here)
        var off = PREFIX_BYTES
        val packetIdBytes = bytes.copyOfRange(off, off + PACKET_ID_BYTES); off += PACKET_ID_BYTES
        val destinationTag = bytes.copyOfRange(off, off + DESTINATION_TAG_BYTES); off += DESTINATION_TAG_BYTES
        val expiresAtMillis = getI64(bytes, off); off += 8
        val hopsRemaining = getI32(bytes, off); off += 4
        if (hopsRemaining < 0) return fail(DecodeError.NEGATIVE_VALUE)
        val originKeyId = bytes.copyOfRange(off, off + ORIGIN_KEY_ID_BYTES); off += ORIGIN_KEY_ID_BYTES

        // payload: validate every declared length before allocation
        val payloadLen = payloadLength.toInt()

        if (payloadLen < 2) return fail(DecodeError.PAYLOAD_LENGTH_MISMATCH)
        val sigLength = getU16(bytes, off); off += 2
        if (sigLength > MAX_SIGNATURE_BYTES) return fail(DecodeError.SIGNATURE_TOO_LARGE)

        // Need room within the payload for the signature and the 4-byte ctLength.
        if (2 + sigLength + 4 > payloadLen) return fail(DecodeError.PAYLOAD_LENGTH_MISMATCH)
        val sigStart = off
        off += sigLength

        val ctLength = getU32(bytes, off); off += 4
        if (ctLength > MAX_CIPHERTEXT_BYTES) return fail(DecodeError.CIPHERTEXT_TOO_LARGE)

        // Full payload must add up exactly, no slack.
        if (2L + sigLength + 4 + ctLength != payloadLen.toLong()) {
            return fail(DecodeError.PAYLOAD_LENGTH_MISMATCH)
        }

        val signature = bytes.copyOfRange(sigStart, sigStart + sigLength)
        val ciphertext = bytes.copyOfRange(off, off + ctLength.toInt())

        val packet = MeshPacket.create(
            packetId = PacketId(bytesToHex(packetIdBytes)),
            kind = kind,
            destinationTag = destinationTag,
            expiresAtMillis = expiresAtMillis,
            hopsRemaining = hopsRemaining,
            ciphertext = ciphertext,
            originSignature = signature,
            originKeyId = originKeyId,
            protocolVersion = version,
        )
        return DecodeResult.Success(packet)
    }

    private fun fail(error: DecodeError): DecodeResult = DecodeResult.Failure(error)

    private fun failEncode(error: EncodeError): EncodeResult = EncodeResult.Failure(error)

    // --- big-endian primitive helpers ---

    private fun putU16(buf: ByteArray, off: Int, v: Int): Int {
        buf[off] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 1] = (v and 0xFF).toByte()
        return off + 2
    }

    private fun putI32(buf: ByteArray, off: Int, v: Int): Int {
        buf[off] = ((v ushr 24) and 0xFF).toByte()
        buf[off + 1] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 3] = (v and 0xFF).toByte()
        return off + 4
    }

    private fun putU32(buf: ByteArray, off: Int, v: Long): Int {
        buf[off] = ((v ushr 24) and 0xFF).toByte()
        buf[off + 1] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 3] = (v and 0xFF).toByte()
        return off + 4
    }

    private fun putI64(buf: ByteArray, off: Int, v: Long): Int {
        for (i in 0 until 8) {
            buf[off + i] = ((v ushr (56 - i * 8)) and 0xFF).toByte()
        }
        return off + 8
    }

    private fun getU16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    private fun getI32(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)

    private fun getU32(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xFF) shl 24) or
            ((buf[off + 1].toLong() and 0xFF) shl 16) or
            ((buf[off + 2].toLong() and 0xFF) shl 8) or
            (buf[off + 3].toLong() and 0xFF)

    private fun getI64(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        }
        return v
    }

    private const val HEX = "0123456789abcdef"

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * Parses an even-length hex string to bytes, accepting upper or lower case,
     * or returns null for odd length, non-hex, or empty rejection. Never throws
     * so an opaque or malformed [PacketId] becomes a bounded encode failure.
     */
    private fun hexToBytesOrNull(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

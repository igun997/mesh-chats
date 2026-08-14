package com.meshchats.app.crypto

/**
 * A bounded reason a wrapped-secret blob could not be decoded. Decoding never
 * throws for malformed input; every rejection is one of these closed values so a
 * caller can branch exhaustively and a corrupt or hostile file can never trigger
 * an exception path or an attacker-declared allocation.
 */
enum class WrappedSecretDecodeError {
    /** Fewer bytes than the fixed prefix or the declared nonce/ciphertext require. */
    TRUNCATED,

    /** Extra bytes beyond the single declared record. */
    TRAILING_BYTES,

    /** Leading magic did not match the wrapped-secret format. */
    UNKNOWN_MAGIC,

    /** Format version is not one this build understands. */
    UNSUPPORTED_VERSION,

    /** Nonce length is outside the accepted AEAD nonce range. */
    NONCE_LENGTH_INVALID,

    /** Declared ciphertext length is zero (a valid AEAD output is never empty). */
    EMPTY_CIPHERTEXT,

    /** Declared ciphertext length exceeds the hard bound. */
    CIPHERTEXT_TOO_LARGE,
}

/**
 * Result of decoding a wrapped-secret blob. [Success] carries the nonce and
 * ciphertext; [Failure] carries a bounded [WrappedSecretDecodeError]. No
 * malformed input throws.
 */
sealed interface WrappedSecretDecodeResult {
    data class Success(val nonce: ByteArray, val ciphertext: ByteArray) : WrappedSecretDecodeResult {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Success && nonce.contentEquals(other.nonce) && ciphertext.contentEquals(other.ciphertext))

        override fun hashCode(): Int = 31 * nonce.contentHashCode() + ciphertext.contentHashCode()
    }

    data class Failure(val error: WrappedSecretDecodeError) : WrappedSecretDecodeResult
}

/** A bounded reason a wrapped-secret blob could not be encoded. Encoding never throws. */
enum class WrappedSecretEncodeError {
    /** Nonce length is outside the accepted AEAD nonce range. */
    NONCE_LENGTH_INVALID,

    /** Ciphertext is empty (a valid AEAD output is never empty). */
    EMPTY_CIPHERTEXT,

    /** Ciphertext exceeds the hard bound. */
    CIPHERTEXT_TOO_LARGE,
}

/** Result of encoding a wrapped-secret blob. [Success] carries the record bytes. */
sealed interface WrappedSecretEncodeResult {
    data class Success(val bytes: ByteArray) : WrappedSecretEncodeResult {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Success && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data class Failure(val error: WrappedSecretEncodeError) : WrappedSecretEncodeResult
}

/**
 * Platform-free, deterministic codec for a versioned wrapped-secret record.
 *
 * The record carries an AEAD nonce and the AEAD ciphertext (which already
 * includes its authentication tag). It performs no cryptography itself; it only
 * frames the two opaque byte fields a [SecretWrapper] produces so they can be
 * stored on disk and re-read across process restarts and app versions.
 *
 * Record layout (all integers big-endian):
 * ```
 * magic          4   'M' 'S' 'K' '1'
 * version        1
 * nonceLength    1   uint8, bounded to [MIN_NONCE_BYTES, MAX_NONCE_BYTES]
 * ciphertextLen  4   uint32, bounded to (0, MAX_CIPHERTEXT_BYTES]
 * nonce          nonceLength
 * ciphertext     ciphertextLen
 * ```
 *
 * The decoder validates magic, version, and both declared lengths against hard
 * bounds and against the remaining buffer **before** allocating any array, so a
 * corrupt or hostile file can neither over-allocate nor read out of bounds. It
 * fails closed on unknown or truncated input. This codec never authenticates the
 * ciphertext — tamper detection is the [SecretWrapper]'s AEAD tag, which fails on
 * unwrap. Structural corruption (truncation, bad magic, impossible lengths) is
 * caught here.
 */
object WrappedSecretCodec {

    private val MAGIC = byteArrayOf(0x4D, 0x53, 0x4B, 0x31) // "MSK1"

    /** Current wire version of the wrapped-secret record. */
    const val VERSION: Int = 1

    // magic(4) + version(1) + nonceLength(1) + ciphertextLength(4)
    private const val PREFIX_BYTES = 10

    /** Minimum accepted nonce length, in bytes (AES-GCM standard is 12). */
    const val MIN_NONCE_BYTES: Int = 12

    /** Maximum accepted nonce length, in bytes. */
    const val MAX_NONCE_BYTES: Int = 16

    /**
     * Maximum accepted ciphertext length, in bytes. Wrapped secrets are small
     * (a 32-byte database key or a short PKCS#8 private key plus a 16-byte GCM
     * tag), so a few KiB is a generous, hostile-input-safe ceiling.
     */
    const val MAX_CIPHERTEXT_BYTES: Int = 4096

    /**
     * Serializes [nonce] and [ciphertext] into a canonical record. Never throws:
     * every invariant violation (out-of-range nonce, empty or oversized
     * ciphertext) is reported as a bounded [WrappedSecretEncodeResult.Failure].
     * On success the returned bytes are a fresh array owned by the caller.
     */
    fun encode(nonce: ByteArray, ciphertext: ByteArray): WrappedSecretEncodeResult {
        if (nonce.size < MIN_NONCE_BYTES || nonce.size > MAX_NONCE_BYTES) {
            return WrappedSecretEncodeResult.Failure(WrappedSecretEncodeError.NONCE_LENGTH_INVALID)
        }
        if (ciphertext.isEmpty()) {
            return WrappedSecretEncodeResult.Failure(WrappedSecretEncodeError.EMPTY_CIPHERTEXT)
        }
        if (ciphertext.size > MAX_CIPHERTEXT_BYTES) {
            return WrappedSecretEncodeResult.Failure(WrappedSecretEncodeError.CIPHERTEXT_TOO_LARGE)
        }

        val record = ByteArray(PREFIX_BYTES + nonce.size + ciphertext.size)
        var off = 0
        MAGIC.copyInto(record, off); off += MAGIC.size
        record[off++] = VERSION.toByte()
        record[off++] = nonce.size.toByte()
        off = putU32(record, off, ciphertext.size.toLong())
        nonce.copyInto(record, off); off += nonce.size
        ciphertext.copyInto(record, off)

        return WrappedSecretEncodeResult.Success(record)
    }

    /** Parses a record. Never throws on malformed input; returns [WrappedSecretDecodeResult.Failure]. */
    fun decode(bytes: ByteArray): WrappedSecretDecodeResult {
        if (bytes.size < PREFIX_BYTES) return fail(WrappedSecretDecodeError.TRUNCATED)

        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) return fail(WrappedSecretDecodeError.UNKNOWN_MAGIC)
        }

        val version = bytes[4].toInt() and 0xFF
        if (version != VERSION) return fail(WrappedSecretDecodeError.UNSUPPORTED_VERSION)

        val nonceLength = bytes[5].toInt() and 0xFF
        if (nonceLength < MIN_NONCE_BYTES || nonceLength > MAX_NONCE_BYTES) {
            return fail(WrappedSecretDecodeError.NONCE_LENGTH_INVALID)
        }

        val ciphertextLength = getU32(bytes, 6)
        if (ciphertextLength == 0L) return fail(WrappedSecretDecodeError.EMPTY_CIPHERTEXT)
        if (ciphertextLength > MAX_CIPHERTEXT_BYTES) return fail(WrappedSecretDecodeError.CIPHERTEXT_TOO_LARGE)

        val total = PREFIX_BYTES.toLong() + nonceLength + ciphertextLength
        if (bytes.size < total) return fail(WrappedSecretDecodeError.TRUNCATED)
        if (bytes.size.toLong() > total) return fail(WrappedSecretDecodeError.TRAILING_BYTES)

        var off = PREFIX_BYTES
        val nonce = bytes.copyOfRange(off, off + nonceLength); off += nonceLength
        val ciphertext = bytes.copyOfRange(off, off + ciphertextLength.toInt())

        return WrappedSecretDecodeResult.Success(nonce = nonce, ciphertext = ciphertext)
    }

    private fun fail(error: WrappedSecretDecodeError): WrappedSecretDecodeResult =
        WrappedSecretDecodeResult.Failure(error)

    private fun putU32(buf: ByteArray, off: Int, v: Long): Int {
        buf[off] = ((v ushr 24) and 0xFF).toByte()
        buf[off + 1] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 3] = (v and 0xFF).toByte()
        return off + 4
    }

    private fun getU32(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xFF) shl 24) or
            ((buf[off + 1].toLong() and 0xFF) shl 16) or
            ((buf[off + 2].toLong() and 0xFF) shl 8) or
            (buf[off + 3].toLong() and 0xFF)
}

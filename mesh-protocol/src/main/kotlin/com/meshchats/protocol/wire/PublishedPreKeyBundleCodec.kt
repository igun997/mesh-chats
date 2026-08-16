package com.meshchats.protocol.wire

/**
 * A bounded reason a [PublishedPreKeyBundle] could not be encoded. Encoding
 * never throws; every rejection is one of these closed values.
 */
enum class PublishedBundleEncodeError {
    INVALID_REGISTRATION_ID,
    INVALID_DEVICE_ID,
    INVALID_ONE_TIME_PREKEY_ID,
    INVALID_ONE_TIME_PREKEY_PUBLIC,
    INVALID_SIGNED_PREKEY_ID,
    INVALID_SIGNED_PREKEY_PUBLIC,
    INVALID_SIGNED_PREKEY_SIGNATURE,
    INVALID_IDENTITY_KEY,
    INVALID_KYBER_PREKEY_ID,
    INVALID_KYBER_PREKEY_PUBLIC,
    INVALID_KYBER_PREKEY_SIGNATURE,
    INVALID_TIMESTAMP,
    FRAME_TOO_LARGE,
}

/**
 * A bounded reason a frame could not be decoded into a [PublishedPreKeyBundle].
 * Decoding never throws for malformed input; every rejection is one of these
 * closed values so a hostile peer cannot trigger an exception path.
 */
enum class PublishedBundleDecodeError {
    TRUNCATED,
    TRAILING_BYTES,
    UNKNOWN_MAGIC,
    UNSUPPORTED_VERSION,
    INVALID_FLAGS,
    FRAME_TOO_LARGE,
    INVALID_REGISTRATION_ID,
    INVALID_DEVICE_ID,
    INVALID_ONE_TIME_PREKEY_ID,
    INVALID_ONE_TIME_PREKEY_PUBLIC,
    INVALID_SIGNED_PREKEY_ID,
    INVALID_SIGNED_PREKEY_PUBLIC,
    INVALID_SIGNED_PREKEY_SIGNATURE,
    INVALID_IDENTITY_KEY,
    INVALID_KYBER_PREKEY_ID,
    INVALID_KYBER_PREKEY_PUBLIC,
    INVALID_KYBER_PREKEY_SIGNATURE,
    INVALID_TIMESTAMP,
}

/** Result of encoding a bundle. [Success] carries fresh frame bytes owned by the caller. */
sealed interface PublishedBundleEncodeResult {
    class Success(val bytes: ByteArray) : PublishedBundleEncodeResult {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Success && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data class Failure(val error: PublishedBundleEncodeError) : PublishedBundleEncodeResult
}

/** Result of decoding a frame. [Success] carries the reconstructed bundle. */
sealed interface PublishedBundleDecodeResult {
    data class Success(val bundle: PublishedPreKeyBundle) : PublishedBundleDecodeResult
    data class Failure(val error: PublishedBundleDecodeError) : PublishedBundleDecodeResult
}

/**
 * Deterministic, big-endian codec for [PublishedPreKeyBundle].
 *
 * Frame layout (all integers big-endian):
 * ```
 * magic            4   'M' 'B' 'K' '1'
 * version          1
 * flags            1   bit0 = one-time EC prekey present; all other bits reserved (must be 0)
 * registrationId   4   uint32, 1..MAX_REGISTRATION_ID
 * deviceId         4   uint32, 1..Int.MAX
 * issuedAtEpoch    8   int64, >= 0
 * --- present only when flags bit0 set ---
 * oneTimePreKeyId  4   uint32, 1..Int.MAX
 * oneTimePreKey    2+n uint16 length + public bytes (1..MAX_EC_KEY_BYTES)
 * ---
 * signedPreKeyId   4   uint32, 1..Int.MAX
 * signedPreKey     2+n uint16 length + public bytes (1..MAX_EC_KEY_BYTES)
 * signedSignature  2+n uint16 length + signature bytes (1..MAX_SIGNATURE_BYTES)
 * identityKey      2+n uint16 length + identity bytes (1..MAX_EC_KEY_BYTES)
 * kyberPreKeyId    4   uint32, 1..Int.MAX
 * kyberPreKey      2+n uint16 length + public bytes (1..MAX_KYBER_KEY_BYTES)
 * kyberSignature   2+n uint16 length + signature bytes (1..MAX_SIGNATURE_BYTES)
 * ```
 *
 * The optional one-time EC prekey is canonical: the flag and both its fields are
 * present together or all absent, so there is exactly one encoding of a bundle.
 * The decoder validates magic, version, flags, and every declared length against
 * the remaining buffer and against hard bounds **before** slicing any
 * attacker-declared array, and rejects any trailing bytes so no alternate
 * encoding is accepted. It fails closed on unknown or truncated input.
 */
object PublishedPreKeyBundleCodec {

    private val MAGIC = byteArrayOf(0x4D, 0x42, 0x4B, 0x31) // "MBK1"
    private const val VERSION = 1
    private const val PREFIX_BYTES = 6 // magic(4) + version(1) + flags(1)
    private const val FLAG_HAS_ONE_TIME = 0x01
    private const val FLAG_MASK = FLAG_HAS_ONE_TIME // every other bit reserved

    /**
     * Maximum registration id. Signal's standard registration id range is
     * `1..16380` (14-bit); this codec adopts that standard bound. Extended
     * ranges are not supported and are rejected.
     */
    const val MAX_REGISTRATION_ID: Int = 16380

    /** Maximum EC / identity public key size, in bytes. */
    const val MAX_EC_KEY_BYTES: Int = 128

    /** Maximum signature size, in bytes. */
    const val MAX_SIGNATURE_BYTES: Int = 256

    /** Maximum Kyber-1024 public key size, in bytes. */
    const val MAX_KYBER_KEY_BYTES: Int = 4096

    /** Hard ceiling on the total frame size, in bytes (16 KiB). */
    const val MAX_FRAME_BYTES: Int = 16 * 1024

    // --- encode -----------------------------------------------------------------

    /**
     * Serializes [bundle] to a canonical, deterministic frame. Never throws;
     * every invariant violation is reported as a bounded [PublishedBundleEncodeResult.Failure].
     * On success the returned bytes are a fresh array owned by the caller.
     */
    fun encode(bundle: PublishedPreKeyBundle): PublishedBundleEncodeResult {
        if (bundle.registrationId !in 1..MAX_REGISTRATION_ID) {
            return fe(PublishedBundleEncodeError.INVALID_REGISTRATION_ID)
        }
        if (bundle.deviceId < 1) return fe(PublishedBundleEncodeError.INVALID_DEVICE_ID)

        val hasOneTime = bundle.hasOneTimePreKey
        val oneTimePublic = bundle.oneTimePreKeyPublic
        if (hasOneTime) {
            val id = bundle.oneTimePreKeyId
            if (id == null || id < 1) return fe(PublishedBundleEncodeError.INVALID_ONE_TIME_PREKEY_ID)
            if (oneTimePublic == null || oneTimePublic.isEmpty() ||
                oneTimePublic.size > MAX_EC_KEY_BYTES
            ) {
                return fe(PublishedBundleEncodeError.INVALID_ONE_TIME_PREKEY_PUBLIC)
            }
        }

        if (bundle.signedPreKeyId < 1) return fe(PublishedBundleEncodeError.INVALID_SIGNED_PREKEY_ID)
        val signedPublic = bundle.signedPreKeyPublic
        if (signedPublic.isEmpty() || signedPublic.size > MAX_EC_KEY_BYTES) {
            return fe(PublishedBundleEncodeError.INVALID_SIGNED_PREKEY_PUBLIC)
        }
        val signedSignature = bundle.signedPreKeySignature
        if (signedSignature.isEmpty() || signedSignature.size > MAX_SIGNATURE_BYTES) {
            return fe(PublishedBundleEncodeError.INVALID_SIGNED_PREKEY_SIGNATURE)
        }
        val identity = bundle.identityKey
        if (identity.isEmpty() || identity.size > MAX_EC_KEY_BYTES) {
            return fe(PublishedBundleEncodeError.INVALID_IDENTITY_KEY)
        }

        if (bundle.kyberPreKeyId < 1) return fe(PublishedBundleEncodeError.INVALID_KYBER_PREKEY_ID)
        val kyberPublic = bundle.kyberPreKeyPublic
        if (kyberPublic.isEmpty() || kyberPublic.size > MAX_KYBER_KEY_BYTES) {
            return fe(PublishedBundleEncodeError.INVALID_KYBER_PREKEY_PUBLIC)
        }
        val kyberSignature = bundle.kyberPreKeySignature
        if (kyberSignature.isEmpty() || kyberSignature.size > MAX_SIGNATURE_BYTES) {
            return fe(PublishedBundleEncodeError.INVALID_KYBER_PREKEY_SIGNATURE)
        }

        if (bundle.issuedAtEpochMillis < 0) return fe(PublishedBundleEncodeError.INVALID_TIMESTAMP)

        var size = PREFIX_BYTES + 4 + 4 + 8 // prefix + reg + device + issuedAt
        if (hasOneTime) size += 4 + 2 + oneTimePublic!!.size
        size += 4 + 2 + signedPublic.size
        size += 2 + signedSignature.size
        size += 2 + identity.size
        size += 4 + 2 + kyberPublic.size
        size += 2 + kyberSignature.size

        // Future-proof backstop: with current per-field maxima the largest
        // possible frame is well under MAX_FRAME_BYTES, so this branch is
        // unreachable today. It stays as a guard against future field-limit
        // increases that could otherwise let a frame exceed the ceiling.
        if (size > MAX_FRAME_BYTES) return fe(PublishedBundleEncodeError.FRAME_TOO_LARGE)

        val frame = ByteArray(size)
        var off = 0
        MAGIC.copyInto(frame, off); off += MAGIC.size
        frame[off++] = VERSION.toByte()
        frame[off++] = (if (hasOneTime) FLAG_HAS_ONE_TIME else 0).toByte()
        off = putU32(frame, off, bundle.registrationId.toLong())
        off = putU32(frame, off, bundle.deviceId.toLong())
        off = putI64(frame, off, bundle.issuedAtEpochMillis)
        if (hasOneTime) {
            off = putU32(frame, off, bundle.oneTimePreKeyId!!.toLong())
            off = putField(frame, off, oneTimePublic!!)
        }
        off = putU32(frame, off, bundle.signedPreKeyId.toLong())
        off = putField(frame, off, signedPublic)
        off = putField(frame, off, signedSignature)
        off = putField(frame, off, identity)
        off = putU32(frame, off, bundle.kyberPreKeyId.toLong())
        off = putField(frame, off, kyberPublic)
        off = putField(frame, off, kyberSignature)

        return PublishedBundleEncodeResult.Success(frame)
    }

    // --- decode -----------------------------------------------------------------

    /** Parses a frame. Never throws on malformed input; returns [PublishedBundleDecodeResult.Failure]. */
    fun decode(bytes: ByteArray): PublishedBundleDecodeResult {
        if (bytes.size > MAX_FRAME_BYTES) return fd(PublishedBundleDecodeError.FRAME_TOO_LARGE)
        if (bytes.size < PREFIX_BYTES) return fd(PublishedBundleDecodeError.TRUNCATED)

        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) return fd(PublishedBundleDecodeError.UNKNOWN_MAGIC)
        }
        val version = bytes[4].toInt() and 0xFF
        if (version != VERSION) return fd(PublishedBundleDecodeError.UNSUPPORTED_VERSION)

        val flags = bytes[5].toInt() and 0xFF
        if (flags and FLAG_MASK.inv() != 0) return fd(PublishedBundleDecodeError.INVALID_FLAGS)
        val hasOneTime = flags and FLAG_HAS_ONE_TIME != 0

        val c = Cursor(bytes, PREFIX_BYTES)

        val registrationId = c.readU32() ?: return fd(PublishedBundleDecodeError.TRUNCATED)
        if (registrationId < 1 || registrationId > MAX_REGISTRATION_ID) {
            return fd(PublishedBundleDecodeError.INVALID_REGISTRATION_ID)
        }
        val deviceId = c.readU32() ?: return fd(PublishedBundleDecodeError.TRUNCATED)
        if (deviceId < 1 || deviceId > Int.MAX_VALUE.toLong()) return fd(PublishedBundleDecodeError.INVALID_DEVICE_ID)

        val issuedAt = c.readI64() ?: return fd(PublishedBundleDecodeError.TRUNCATED)
        if (issuedAt < 0) return fd(PublishedBundleDecodeError.INVALID_TIMESTAMP)

        var oneTimeId: Int? = null
        var oneTimePublic: ByteArray? = null
        if (hasOneTime) {
            val id = c.readU32() ?: return fd(PublishedBundleDecodeError.TRUNCATED)
            if (id < 1 || id > Int.MAX_VALUE.toLong()) return fd(PublishedBundleDecodeError.INVALID_ONE_TIME_PREKEY_ID)
            oneTimeId = id.toInt()
            oneTimePublic = c.readField(MAX_EC_KEY_BYTES)
                ?: return fd(mapFieldError(c, PublishedBundleDecodeError.INVALID_ONE_TIME_PREKEY_PUBLIC))
        }

        val signedId = c.readU32() ?: return fd(PublishedBundleDecodeError.TRUNCATED)
        if (signedId < 1 || signedId > Int.MAX_VALUE.toLong()) return fd(PublishedBundleDecodeError.INVALID_SIGNED_PREKEY_ID)
        val signedPublic = c.readField(MAX_EC_KEY_BYTES)
            ?: return fd(mapFieldError(c, PublishedBundleDecodeError.INVALID_SIGNED_PREKEY_PUBLIC))
        val signedSignature = c.readField(MAX_SIGNATURE_BYTES)
            ?: return fd(mapFieldError(c, PublishedBundleDecodeError.INVALID_SIGNED_PREKEY_SIGNATURE))
        val identity = c.readField(MAX_EC_KEY_BYTES)
            ?: return fd(mapFieldError(c, PublishedBundleDecodeError.INVALID_IDENTITY_KEY))

        val kyberId = c.readU32() ?: return fd(PublishedBundleDecodeError.TRUNCATED)
        if (kyberId < 1 || kyberId > Int.MAX_VALUE.toLong()) return fd(PublishedBundleDecodeError.INVALID_KYBER_PREKEY_ID)
        val kyberPublic = c.readField(MAX_KYBER_KEY_BYTES)
            ?: return fd(mapFieldError(c, PublishedBundleDecodeError.INVALID_KYBER_PREKEY_PUBLIC))
        val kyberSignature = c.readField(MAX_SIGNATURE_BYTES)
            ?: return fd(mapFieldError(c, PublishedBundleDecodeError.INVALID_KYBER_PREKEY_SIGNATURE))

        if (!c.atEnd()) return fd(PublishedBundleDecodeError.TRAILING_BYTES)

        val bundle = PublishedPreKeyBundle(
            registrationId = registrationId.toInt(),
            deviceId = deviceId.toInt(),
            oneTimePreKeyId = oneTimeId,
            oneTimePreKeyPublic = oneTimePublic,
            signedPreKeyId = signedId.toInt(),
            signedPreKeyPublic = signedPublic,
            signedPreKeySignature = signedSignature,
            identityKey = identity,
            kyberPreKeyId = kyberId.toInt(),
            kyberPreKeyPublic = kyberPublic,
            kyberPreKeySignature = kyberSignature,
            issuedAtEpochMillis = issuedAt,
        )
        return PublishedBundleDecodeResult.Success(bundle)
    }

    /**
     * A field read failed. If the cursor ran out of buffer it is structural
     * truncation; otherwise the declared length violated a semantic bound, which
     * maps to the field-specific error.
     */
    private fun mapFieldError(c: Cursor, boundError: PublishedBundleDecodeError): PublishedBundleDecodeError =
        if (c.truncated) PublishedBundleDecodeError.TRUNCATED else boundError

    private fun fe(error: PublishedBundleEncodeError): PublishedBundleEncodeResult = PublishedBundleEncodeResult.Failure(error)
    private fun fd(error: PublishedBundleDecodeError): PublishedBundleDecodeResult = PublishedBundleDecodeResult.Failure(error)

    // --- big-endian helpers -----------------------------------------------------

    private fun putField(buf: ByteArray, off: Int, field: ByteArray): Int {
        var o = putU16(buf, off, field.size)
        field.copyInto(buf, o); o += field.size
        return o
    }

    private fun putU16(buf: ByteArray, off: Int, v: Int): Int {
        buf[off] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 1] = (v and 0xFF).toByte()
        return off + 2
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

    /**
     * A forward-only reader over the frame. Reads return null when the buffer is
     * exhausted (setting [truncated]) or a declared length exceeds its bound, so
     * no read ever throws or allocates an attacker-sized array without first
     * confirming the bytes are present and within bounds.
     */
    private class Cursor(private val buf: ByteArray, private var pos: Int) {
        var truncated: Boolean = false
            private set

        fun atEnd(): Boolean = pos == buf.size

        fun readU16(): Int? {
            if (pos + 2 > buf.size) {
                truncated = true
                return null
            }
            val v = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
            pos += 2
            return v
        }

        fun readU32(): Long? {
            if (pos + 4 > buf.size) {
                truncated = true
                return null
            }
            val v = ((buf[pos].toLong() and 0xFF) shl 24) or
                ((buf[pos + 1].toLong() and 0xFF) shl 16) or
                ((buf[pos + 2].toLong() and 0xFF) shl 8) or
                (buf[pos + 3].toLong() and 0xFF)
            pos += 4
            return v
        }

        fun readI64(): Long? {
            if (pos + 8 > buf.size) {
                truncated = true
                return null
            }
            var v = 0L
            for (i in 0 until 8) {
                v = (v shl 8) or (buf[pos + i].toLong() and 0xFF)
            }
            pos += 8
            return v
        }

        /**
         * Reads a uint16 length-prefixed field. Returns null on truncation
         * (buffer too short for the length prefix or the declared bytes) or when
         * the declared length is not in `1..maxBytes`. The array is sliced only
         * after both checks pass.
         */
        fun readField(maxBytes: Int): ByteArray? {
            val len = readU16() ?: return null
            // Confirm the bytes are present before judging the bound, so an
            // overflowing length reports truncation rather than allocating.
            if (pos + len > buf.size) {
                truncated = true
                return null
            }
            if (len < 1 || len > maxBytes) return null
            val out = buf.copyOfRange(pos, pos + len)
            pos += len
            return out
        }
    }
}

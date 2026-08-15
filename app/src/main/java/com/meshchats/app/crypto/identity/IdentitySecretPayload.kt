package com.meshchats.app.crypto.identity

/**
 * The plaintext that is wrapped (AEAD) and stored in the versioned identity-secret
 * file, independently of the SQLCipher database.
 *
 * ## What it carries and why
 *
 * It is the single source of truth for the device identity, so the create
 * protocol is crash-recoverable (see [DefaultDeviceIdentityRepository]). It holds:
 *
 * - the Ed25519 **private** key (PKCS#8) — the one secret that must never live in
 *   the database or logs;
 * - the Ed public key, its fingerprint, the bound Signal public bytes, and the
 *   Ed25519 binding signature — enough to rebuild the `DeviceIdentityEntity` row;
 * - the Signal **registration id** and **serialized key pair** — enough to rebuild
 *   the `SignalIdentityEntity` row.
 *
 * The Signal serialized key pair is a secret too (it contains the Signal private
 * key). It is stored here **in addition** to the SQLCipher database so that a
 * crash landing the file before the DB transaction commits can be finalized: on
 * reopen the whole identity — both rows — is reconstructed from this one
 * authenticated blob rather than being lost. Both copies are device-key protected
 * (this file by the dedicated identity Keystore alias; the DB by the distinct
 * database alias), so embedding it here does not weaken the domain separation of
 * the wrapping keys.
 *
 * Because the whole payload is AEAD-wrapped, every field — public and private — is
 * tamper-evident: unwrap fails closed if any byte changed.
 */
class IdentitySecretPayload(
    val version: Int,
    val privatePkcs8: ByteArray,
    val edPublicX509: ByteArray,
    val fingerprintSha256: ByteArray,
    val signalPublicBinding: ByteArray,
    val bindingSignature: ByteArray,
    val bindingVersion: Int,
    val signalRegistrationId: Int,
    val signalSerializedKeyPair: ByteArray,
    val signalSchemaVersion: Int,
    val createdAt: Long,
) {
    /** Zeroes both private secrets. Call once the payload is no longer needed. */
    fun zeroPrivate() {
        privatePkcs8.fill(0)
        signalSerializedKeyPair.fill(0)
    }
}

/** A bounded reason the identity-secret payload could not be encoded. */
enum class IdentitySecretEncodeError {
    /** A field is empty or exceeds its hard bound. */
    FIELD_SIZE_INVALID,

    /**
     * [IdentitySecretPayload.bindingVersion] or
     * [IdentitySecretPayload.signalSchemaVersion] is outside the single-byte range
     * 0..255 the wire format can carry. Refused up front so a version is never
     * silently truncated (`and 0xFF`) into a different, valid-looking version.
     */
    VERSION_OUT_OF_RANGE,
}

/** Result of encoding the identity-secret payload. */
sealed interface IdentitySecretEncodeResult {
    data class Success(val bytes: ByteArray) : IdentitySecretEncodeResult
    data class Failure(val error: IdentitySecretEncodeError) : IdentitySecretEncodeResult
}

/** A bounded reason the identity-secret payload could not be decoded. Total; never throws. */
enum class IdentitySecretDecodeError {
    TRUNCATED,
    TRAILING_BYTES,
    UNKNOWN_MAGIC,
    UNSUPPORTED_VERSION,
    FIELD_SIZE_INVALID,
    OVERSIZE,
}

/** Result of decoding the identity-secret payload. */
sealed interface IdentitySecretDecodeResult {
    data class Success(val payload: IdentitySecretPayload) : IdentitySecretDecodeResult
    data class Failure(val error: IdentitySecretDecodeError) : IdentitySecretDecodeResult
}

/**
 * Deterministic codec for the wrapped identity-secret payload.
 *
 * This is the *inner* plaintext framing; the AEAD wrapping (nonce + ciphertext)
 * and its own file framing are handled separately by
 * [com.meshchats.app.crypto.WrappedSecretCodec] and
 * [com.meshchats.app.crypto.AtomicSecretFile]. Keeping this layer explicit means
 * the whole identity — private keys plus the public recovery metadata — travels
 * together inside a single authenticated blob.
 *
 * Layout (all integers big-endian):
 * ```
 * magic                4   'M' 'I' 'D' '1'
 * version              1
 * bindingVersion       1
 * signalSchemaVersion  1
 * signalRegistrationId 4   (int32)
 * createdAt            8   (int64, ms)
 * len(privatePkcs8)          2 || privatePkcs8
 * len(edPublicX509)          2 || edPublicX509
 * len(fingerprintSha256)     2 || fingerprintSha256
 * len(signalPublicBinding)   2 || signalPublicBinding
 * len(bindingSignature)      2 || bindingSignature
 * len(signalSerializedPair)  2 || signalSerializedKeyPair
 * ```
 * Every declared length is validated against a hard bound and the remaining
 * buffer before allocation, so a corrupt blob fails closed rather than
 * over-allocating.
 */
object IdentitySecretCodec {

    private val MAGIC = byteArrayOf(0x4D, 0x49, 0x44, 0x31) // "MID1"

    /** Current wire version of the identity-secret payload. */
    const val VERSION: Int = 1

    // magic(4)+version(1)+bindingVersion(1)+signalSchemaVersion(1)+regId(4)+createdAt(8)+six 2-byte prefixes
    private const val HEADER_AND_PREFIXES = 4 + 1 + 1 + 1 + 4 + 8 + (6 * 2)

    /** Per-field hard bound (a PKCS#8 Ed25519 key is ~83 bytes; keys/sigs are small). */
    const val MAX_FIELD_BYTES: Int = 1024

    /** Hard total bound. */
    const val MAX_TOTAL_BYTES: Int = 8 * 1024

    fun encode(payload: IdentitySecretPayload): IdentitySecretEncodeResult {
        // Both version fields are carried in a single byte each. Reject an
        // out-of-range value rather than truncating it into a different version.
        if (payload.bindingVersion !in 0..0xFF || payload.signalSchemaVersion !in 0..0xFF) {
            return IdentitySecretEncodeResult.Failure(IdentitySecretEncodeError.VERSION_OUT_OF_RANGE)
        }
        val fields = listOf(
            payload.privatePkcs8,
            payload.edPublicX509,
            payload.fingerprintSha256,
            payload.signalPublicBinding,
            payload.bindingSignature,
            payload.signalSerializedKeyPair,
        )
        for (f in fields) {
            if (f.isEmpty() || f.size > MAX_FIELD_BYTES) {
                return IdentitySecretEncodeResult.Failure(IdentitySecretEncodeError.FIELD_SIZE_INVALID)
            }
        }
        val total = HEADER_AND_PREFIXES + fields.sumOf { it.size }
        val record = ByteArray(total)
        var off = 0
        MAGIC.copyInto(record, off); off += MAGIC.size
        record[off++] = VERSION.toByte()
        record[off++] = (payload.bindingVersion and 0xFF).toByte()
        record[off++] = (payload.signalSchemaVersion and 0xFF).toByte()
        off = putI32(record, off, payload.signalRegistrationId)
        off = putI64(record, off, payload.createdAt)
        for (f in fields) {
            record[off++] = ((f.size ushr 8) and 0xFF).toByte()
            record[off++] = (f.size and 0xFF).toByte()
            f.copyInto(record, off); off += f.size
        }
        return IdentitySecretEncodeResult.Success(record)
    }

    fun decode(bytes: ByteArray): IdentitySecretDecodeResult {
        if (bytes.size > MAX_TOTAL_BYTES) return fail(IdentitySecretDecodeError.OVERSIZE)
        if (bytes.size < HEADER_AND_PREFIXES) return fail(IdentitySecretDecodeError.TRUNCATED)
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) return fail(IdentitySecretDecodeError.UNKNOWN_MAGIC)
        }
        val version = bytes[4].toInt() and 0xFF
        if (version != VERSION) return fail(IdentitySecretDecodeError.UNSUPPORTED_VERSION)
        val bindingVersion = bytes[5].toInt() and 0xFF
        val signalSchemaVersion = bytes[6].toInt() and 0xFF
        val signalRegistrationId = getI32(bytes, 7)
        val createdAt = getI64(bytes, 11)

        var off = 19
        val parsed = arrayOfNulls<ByteArray>(6)
        for (i in 0 until 6) {
            if (off + 2 > bytes.size) return fail(IdentitySecretDecodeError.TRUNCATED)
            val len = ((bytes[off].toInt() and 0xFF) shl 8) or (bytes[off + 1].toInt() and 0xFF)
            off += 2
            if (len == 0 || len > MAX_FIELD_BYTES) return fail(IdentitySecretDecodeError.FIELD_SIZE_INVALID)
            if (off + len > bytes.size) return fail(IdentitySecretDecodeError.TRUNCATED)
            parsed[i] = bytes.copyOfRange(off, off + len)
            off += len
        }
        if (off != bytes.size) return fail(IdentitySecretDecodeError.TRAILING_BYTES)

        return IdentitySecretDecodeResult.Success(
            IdentitySecretPayload(
                version = version,
                privatePkcs8 = parsed[0]!!,
                edPublicX509 = parsed[1]!!,
                fingerprintSha256 = parsed[2]!!,
                signalPublicBinding = parsed[3]!!,
                bindingSignature = parsed[4]!!,
                signalSerializedKeyPair = parsed[5]!!,
                signalRegistrationId = signalRegistrationId,
                signalSchemaVersion = signalSchemaVersion,
                bindingVersion = bindingVersion,
                createdAt = createdAt,
            ),
        )
    }

    private fun fail(error: IdentitySecretDecodeError) = IdentitySecretDecodeResult.Failure(error)

    private fun putI32(buf: ByteArray, off: Int, v: Int): Int {
        for (i in 0 until 4) buf[off + i] = ((v ushr (24 - i * 8)) and 0xFF).toByte()
        return off + 4
    }

    private fun getI32(buf: ByteArray, off: Int): Int {
        var v = 0
        for (i in 0 until 4) v = (v shl 8) or (buf[off + i].toInt() and 0xFF)
        return v
    }

    private fun putI64(buf: ByteArray, off: Int, v: Long): Int {
        for (i in 0 until 8) buf[off + i] = ((v ushr (56 - i * 8)) and 0xFF).toByte()
        return off + 8
    }

    private fun getI64(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        return v
    }
}

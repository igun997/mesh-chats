package com.meshchats.app.crypto.identity

import java.util.Base64

/**
 * A device identity as carried in a QR / share payload: only public material.
 * Never contains the Ed25519 private key nor any rotating BLE ephemeral id.
 */
class IdentityQrPayload(
    val edPublicX509: ByteArray,
    val fingerprintSha256: ByteArray,
    val signalPublicBinding: ByteArray,
    val bindingSignature: ByteArray,
    val bindingVersion: Int,
)

/** A bounded reason a QR payload could not be encoded. Encoding never throws. */
enum class IdentityQrEncodeError {
    /** A field is empty or exceeds its hard per-field bound. */
    FIELD_SIZE_INVALID,

    /**
     * [IdentityQrPayload.bindingVersion] is outside the single-byte range 0..255
     * the wire format can carry. Refused up front so a version is never silently
     * truncated (`and 0xFF`) into a different, valid-looking version.
     */
    BINDING_VERSION_INVALID,
}

/** Result of encoding a QR payload. [Success] carries the base64url string. */
sealed interface IdentityQrEncodeResult {
    data class Success(val text: String) : IdentityQrEncodeResult
    data class Failure(val error: IdentityQrEncodeError) : IdentityQrEncodeResult
}

/**
 * A bounded reason a QR payload could not be decoded. Decoding is total: every
 * rejection of hostile or malformed input is one of these closed values, never an
 * exception, and never an attacker-controlled allocation.
 */
enum class IdentityQrDecodeError {
    /** The base64url text was not valid base64url. */
    BASE64_INVALID,

    /** Fewer bytes than the header or a declared field requires. */
    TRUNCATED,

    /** Extra bytes beyond the single declared record. */
    TRAILING_BYTES,

    /** Leading magic did not match the identity-QR format. */
    UNKNOWN_MAGIC,

    /** Format version is not one this build understands. */
    UNSUPPORTED_VERSION,

    /** A declared field length is zero or exceeds its hard bound. */
    FIELD_SIZE_INVALID,

    /** The whole payload exceeds the hard total bound. */
    OVERSIZE,
}

/** Result of decoding a QR payload. [Success] carries the parsed public fields. */
sealed interface IdentityQrDecodeResult {
    data class Success(val payload: IdentityQrPayload) : IdentityQrDecodeResult
    data class Failure(val error: IdentityQrDecodeError) : IdentityQrDecodeResult
}

/**
 * Canonical, versioned, self-describing codec for the identity QR / share
 * payload.
 *
 * The payload carries the full Ed25519 X.509 public key, its SHA-256 fingerprint,
 * the bound Signal public identity bytes, and the Ed25519 signature over the
 * canonical binding ([IdentityBinding.bindingPayload]). It deliberately carries
 * **no** private key and **no** rotating BLE ephemeral id — only long-term public
 * identity material.
 *
 * Binary layout (all integers big-endian), then the whole thing base64url-encoded
 * without padding:
 * ```
 * magic            4   'M' 'Q' 'R' '1'
 * version          1
 * bindingVersion   1
 * len(edPublicX509)      2 || edPublicX509
 * len(fingerprint)       2 || fingerprint
 * len(signalBinding)     2 || signalBinding
 * len(bindingSignature)  2 || bindingSignature
 * ```
 *
 * The decoder validates magic, version, and every declared length against hard
 * per-field bounds, against a total-size bound, and against the remaining buffer
 * **before** allocating, so a truncated, oversized, or hostile blob fails closed
 * with a bounded error and never over-allocates or reads out of bounds.
 *
 * ## Authenticity is the caller's job — decode is only structural
 *
 * A successful [decode] means the bytes were well-formed, NOT that the identity is
 * authentic. The caller (or [DeviceIdentityRepository.verifyScannedPayload]) must
 * still recompute the fingerprint over [IdentityQrPayload.edPublicX509] and check
 * it equals the carried fingerprint, and verify [IdentityQrPayload.bindingSignature]
 * over the canonical binding of the Ed and Signal public keys, before trusting it.
 */
object IdentityQrCodec {

    private val MAGIC = byteArrayOf(0x4D, 0x51, 0x52, 0x31) // "MQR1"

    /** Current wire version of the QR payload. */
    const val VERSION: Int = 1

    // magic(4) + version(1) + bindingVersion(1) + four 2-byte length prefixes
    private const val HEADER_AND_PREFIXES = 4 + 1 + 1 + (4 * 2)

    /** Per-field hard bounds. Public keys/fingerprints/signatures are all small. */
    const val MAX_FIELD_BYTES: Int = 1024

    /** Hard bound on the whole decoded record, before base64. */
    const val MAX_TOTAL_BYTES: Int = 8 * 1024

    fun encode(payload: IdentityQrPayload): IdentityQrEncodeResult {
        // The wire format carries bindingVersion in a single byte. Reject an
        // out-of-range value rather than truncating it into a different version.
        if (payload.bindingVersion !in 0..0xFF) {
            return IdentityQrEncodeResult.Failure(IdentityQrEncodeError.BINDING_VERSION_INVALID)
        }
        val fields = listOf(
            payload.edPublicX509,
            payload.fingerprintSha256,
            payload.signalPublicBinding,
            payload.bindingSignature,
        )
        for (f in fields) {
            if (f.isEmpty() || f.size > MAX_FIELD_BYTES) {
                return IdentityQrEncodeResult.Failure(IdentityQrEncodeError.FIELD_SIZE_INVALID)
            }
        }

        val total = HEADER_AND_PREFIXES + fields.sumOf { it.size }
        val record = ByteArray(total)
        var off = 0
        MAGIC.copyInto(record, off); off += MAGIC.size
        record[off++] = VERSION.toByte()
        record[off++] = (payload.bindingVersion and 0xFF).toByte()
        for (f in fields) {
            record[off++] = ((f.size ushr 8) and 0xFF).toByte()
            record[off++] = (f.size and 0xFF).toByte()
            f.copyInto(record, off); off += f.size
        }

        val text = Base64.getUrlEncoder().withoutPadding().encodeToString(record)
        return IdentityQrEncodeResult.Success(text)
    }

    fun decode(text: String): IdentityQrDecodeResult {
        // Bound the base64 length up front so a hostile giant string cannot force a
        // huge decode allocation. base64 expands ~4/3, so cap generously.
        if (text.length > MAX_TOTAL_BYTES * 2) return fail(IdentityQrDecodeError.OVERSIZE)

        val bytes = try {
            Base64.getUrlDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            return fail(IdentityQrDecodeError.BASE64_INVALID)
        }

        if (bytes.size > MAX_TOTAL_BYTES) return fail(IdentityQrDecodeError.OVERSIZE)
        if (bytes.size < HEADER_AND_PREFIXES) return fail(IdentityQrDecodeError.TRUNCATED)

        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) return fail(IdentityQrDecodeError.UNKNOWN_MAGIC)
        }
        val version = bytes[4].toInt() and 0xFF
        if (version != VERSION) return fail(IdentityQrDecodeError.UNSUPPORTED_VERSION)
        val bindingVersion = bytes[5].toInt() and 0xFF

        var off = 6
        val parsed = arrayOfNulls<ByteArray>(4)
        for (i in 0 until 4) {
            // Need two length bytes.
            if (off + 2 > bytes.size) return fail(IdentityQrDecodeError.TRUNCATED)
            val len = ((bytes[off].toInt() and 0xFF) shl 8) or (bytes[off + 1].toInt() and 0xFF)
            off += 2
            if (len == 0 || len > MAX_FIELD_BYTES) return fail(IdentityQrDecodeError.FIELD_SIZE_INVALID)
            if (off + len > bytes.size) return fail(IdentityQrDecodeError.TRUNCATED)
            parsed[i] = bytes.copyOfRange(off, off + len)
            off += len
        }
        if (off != bytes.size) return fail(IdentityQrDecodeError.TRAILING_BYTES)

        return IdentityQrDecodeResult.Success(
            IdentityQrPayload(
                edPublicX509 = parsed[0]!!,
                fingerprintSha256 = parsed[1]!!,
                signalPublicBinding = parsed[2]!!,
                bindingSignature = parsed[3]!!,
                bindingVersion = bindingVersion,
            ),
        )
    }

    private fun fail(error: IdentityQrDecodeError): IdentityQrDecodeResult =
        IdentityQrDecodeResult.Failure(error)
}

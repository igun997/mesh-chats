package com.meshchats.app.crypto.identity

import java.io.ByteArrayOutputStream

/**
 * Canonical, domain-separated payloads that the device signs with its Ed25519
 * key. Centralized here so the signer, the verifier, and every test build the
 * exact same bytes — a mismatch would silently break binding verification.
 *
 * ## Domain separation
 *
 * Every signed payload begins with a fixed ASCII context string unique to its
 * purpose and a version byte, then length-prefixed fields. Prefixing the context
 * means a signature produced for one purpose (e.g. the Signal binding) can never
 * be replayed as a valid signature for another (e.g. an app-level challenge),
 * even if the remaining bytes coincide. Length prefixes make the encoding
 * unambiguous: no two distinct field tuples produce the same byte string, so an
 * attacker cannot shift bytes between fields.
 */
object IdentityBinding {

    /** Current version of the Signal-binding payload scheme. */
    const val BINDING_VERSION: Int = 1

    /** Context string for the Ed25519 → Signal identity binding. */
    private val BINDING_CONTEXT: ByteArray =
        "mesh-chats/identity-binding/v1".toByteArray(Charsets.US_ASCII)

    /** Context string for the generic app-level sign/verify API. */
    private val MESSAGE_CONTEXT: ByteArray =
        "mesh-chats/identity-message/v1".toByteArray(Charsets.US_ASCII)

    /**
     * Bytes [messagePayload] prepends to a message: the message context plus the
     * 2-byte length prefix. The signer's hard bound applies to the *framed*
     * payload, so the largest raw message is reduced by this overhead.
     */
    val MESSAGE_FRAMING_OVERHEAD: Int = MESSAGE_CONTEXT.size + 2

    /**
     * The largest raw application message [messagePayload] will frame. It is the
     * Ed25519 signer's hard message bound ([Ed25519Crypto.MAX_MESSAGE_BYTES])
     * minus this scheme's framing overhead, and never above the 16-bit
     * length-prefix ceiling. A message of exactly this size frames to a payload of
     * exactly [Ed25519Crypto.MAX_MESSAGE_BYTES] and both signs and verifies; one
     * byte more is refused with [MessagePayloadResult.TooLarge] rather than being
     * silently truncated or overflowing the signer's bound.
     */
    val MAX_MESSAGE_BYTES: Int =
        minOf(Ed25519Crypto.MAX_MESSAGE_BYTES - MESSAGE_FRAMING_OVERHEAD, 0xFFFF)

    /**
     * Result of framing an application message. Framing is total: an over-bound
     * message yields [TooLarge] instead of throwing, so a hostile caller can never
     * force an exception or an out-of-bound signer input.
     */
    sealed interface MessagePayloadResult {
        data class Success(val bytes: ByteArray) : MessagePayloadResult
        data object TooLarge : MessagePayloadResult
    }

    /**
     * Builds the canonical binding payload the device signs to attest that
     * [signalPublicBytes] belongs to the identity whose Ed25519 public key is
     * [edPublicX509]. Fields are internally generated identity material bounded to
     * a few hundred bytes, well within the length-prefix limit.
     *
     * Layout (all lengths are unsigned 16-bit big-endian):
     * ```
     * BINDING_CONTEXT bytes
     * version                (1 byte)
     * len(edPublicX509)      (2 bytes) || edPublicX509
     * len(signalPublicBytes) (2 bytes) || signalPublicBytes
     * ```
     */
    fun bindingPayload(
        version: Int,
        edPublicX509: ByteArray,
        signalPublicBytes: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(BINDING_CONTEXT)
        out.write(version and 0xFF)
        writeField(out, edPublicX509)
        writeField(out, signalPublicBytes)
        return out.toByteArray()
    }

    /**
     * Wraps an application [message] for the generic sign/verify API with the
     * message context and a length prefix, so app signatures live in a separate
     * domain from the binding signature and cannot be cross-replayed.
     *
     * Total: a message larger than [MAX_MESSAGE_BYTES] returns
     * [MessagePayloadResult.TooLarge] instead of throwing, so a hostile caller
     * cannot trip the internal length-prefix invariant or exceed the signer bound.
     */
    fun messagePayload(message: ByteArray): MessagePayloadResult {
        if (message.size > MAX_MESSAGE_BYTES) return MessagePayloadResult.TooLarge
        val out = ByteArrayOutputStream()
        out.write(MESSAGE_CONTEXT)
        writeField(out, message)
        return MessagePayloadResult.Success(out.toByteArray())
    }

    private fun writeField(out: ByteArrayOutputStream, field: ByteArray) {
        require(field.size <= 0xFFFF) { "field too large to length-prefix" }
        out.write((field.size ushr 8) and 0xFF)
        out.write(field.size and 0xFF)
        out.write(field)
    }
}

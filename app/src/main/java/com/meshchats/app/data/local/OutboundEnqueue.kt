package com.meshchats.app.data.local

import com.meshchats.protocol.routing.MeshPacket

/**
 * Trust states for a contact device identity. Stored as the enum [name] in the
 * `contact_identities.trust_state` column.
 */
enum class TrustState { UNVERIFIED, VERIFIED, REVOKED }

/**
 * Lifecycle of a ciphertext packet in the encrypted outbox and, mirrored, of the
 * visible message row. Stored as the enum [name] in `*.delivery_state` columns.
 */
enum class OutboxDeliveryState { QUEUED, SENDING, SENT, DELIVERED, FAILED, EXPIRED }

/**
 * Why an atomic outbound-enqueue was refused. A rejection means neither the
 * visible message row nor the ciphertext outbox row was written.
 */
enum class EnqueueRejection {
    /** The message is not outbound; only outbound messages own an outbox packet. */
    NOT_OUTBOUND,

    /** The message id / packet id linking the two rows is missing or inconsistent. */
    ID_MISMATCH,

    /** The ciphertext blob is empty; there is nothing to send. */
    EMPTY_CIPHERTEXT,

    /** The ciphertext exceeds the protocol ceiling ([MeshPacket.MAX_CIPHERTEXT_BYTES]). */
    CIPHERTEXT_TOO_LARGE,

    /** The destination address is blank. */
    MISSING_DESTINATION,

    /** The expiry, if present, is not strictly after creation time. */
    INVALID_EXPIRY,
}

/**
 * Bounded, typed outcome of [OutboxDao.enqueueOutbound]. The transaction never
 * throws a raw SQL exception for an invariant violation; it returns [Rejected]
 * instead, and callers decide how to surface it.
 */
sealed interface EnqueueResult {
    /** Both the visible message and its ciphertext outbox row were committed. */
    data class Success(val packetId: String) : EnqueueResult

    /** Nothing was written; [reason] explains which invariant failed. */
    data class Rejected(val reason: EnqueueRejection) : EnqueueResult
}

/**
 * Pure, side-effect-free validation of the message + outbox pair that
 * [OutboxDao.enqueueOutbound] would persist. Kept independent of Room so the
 * invariants can be proven in fast JVM tests, then reused verbatim inside the
 * transaction.
 */
object OutboundMessageValidator {

    /**
     * @return the first violated invariant, or `null` when the pair is valid and
     *   safe to insert atomically.
     */
    fun validate(
        message: MessageEntity,
        outbox: CiphertextOutboxEntity,
        maxCiphertextBytes: Int = MeshPacket.MAX_CIPHERTEXT_BYTES,
    ): EnqueueRejection? {
        if (!message.isOutgoing) return EnqueueRejection.NOT_OUTBOUND

        // The two rows must reference each other consistently: the message points at
        // the packet (packet_id) and the outbox points back at the message (message_id).
        if (message.packetId.isNullOrBlank()) return EnqueueRejection.ID_MISMATCH
        if (message.packetId != outbox.packetId) return EnqueueRejection.ID_MISMATCH
        if (outbox.messageId != message.id) return EnqueueRejection.ID_MISMATCH

        if (outbox.ciphertext.isEmpty()) return EnqueueRejection.EMPTY_CIPHERTEXT
        if (outbox.ciphertext.size > maxCiphertextBytes) return EnqueueRejection.CIPHERTEXT_TOO_LARGE

        if (outbox.destinationAddress.isBlank()) return EnqueueRejection.MISSING_DESTINATION

        val expiry = outbox.expiresAt
        if (expiry != null && expiry <= outbox.createdAt) return EnqueueRejection.INVALID_EXPIRY

        return null
    }
}

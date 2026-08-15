package com.meshchats.app.data.local

import com.meshchats.protocol.routing.MeshPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fast, Room-free proof of the atomic-enqueue invariants. These exact rules run
 * inside [OutboxDao.enqueueOutbound]'s transaction, so proving them here covers
 * the rejection paths without a device.
 */
class OutboundMessageValidatorTest {

    private val now = 1_000L

    private fun message(
        id: String = "m1",
        outgoing: Boolean = true,
        packetId: String? = "p1",
    ) = MessageEntity(
        id = id,
        conversationId = "c1",
        authorId = "me",
        body = "hi",
        sentAt = now,
        isOutgoing = outgoing,
        deliveryState = OutboxDeliveryState.QUEUED.name,
        packetId = packetId,
    )

    private fun outbox(
        packetId: String = "p1",
        messageId: String = "m1",
        ciphertext: ByteArray = byteArrayOf(1, 2, 3),
        destination: String = "peer",
        createdAt: Long = now,
        expiresAt: Long? = null,
    ) = CiphertextOutboxEntity(
        packetId = packetId,
        messageId = messageId,
        destinationAddress = destination,
        destinationDeviceId = 1,
        ciphertext = ciphertext,
        createdAt = createdAt,
        expiresAt = expiresAt,
    )

    @Test
    fun validPairPasses() {
        assertNull(OutboundMessageValidator.validate(message(), outbox()))
    }

    @Test
    fun validPairWithFutureExpiryPasses() {
        assertNull(
            OutboundMessageValidator.validate(
                message(),
                outbox(expiresAt = now + 1),
            ),
        )
    }

    @Test
    fun incomingMessageRejected() {
        assertEquals(
            EnqueueRejection.NOT_OUTBOUND,
            OutboundMessageValidator.validate(message(outgoing = false), outbox()),
        )
    }

    @Test
    fun missingPacketIdOnMessageRejected() {
        assertEquals(
            EnqueueRejection.ID_MISMATCH,
            OutboundMessageValidator.validate(message(packetId = null), outbox()),
        )
    }

    @Test
    fun mismatchedPacketIdRejected() {
        assertEquals(
            EnqueueRejection.ID_MISMATCH,
            OutboundMessageValidator.validate(message(packetId = "pX"), outbox(packetId = "pY")),
        )
    }

    @Test
    fun mismatchedMessageIdRejected() {
        assertEquals(
            EnqueueRejection.ID_MISMATCH,
            OutboundMessageValidator.validate(message(id = "mA"), outbox(messageId = "mB")),
        )
    }

    @Test
    fun emptyCiphertextRejected() {
        assertEquals(
            EnqueueRejection.EMPTY_CIPHERTEXT,
            OutboundMessageValidator.validate(message(), outbox(ciphertext = ByteArray(0))),
        )
    }

    @Test
    fun oversizeCiphertextRejected() {
        val tooBig = ByteArray(MeshPacket.MAX_CIPHERTEXT_BYTES + 1)
        assertEquals(
            EnqueueRejection.CIPHERTEXT_TOO_LARGE,
            OutboundMessageValidator.validate(message(), outbox(ciphertext = tooBig)),
        )
    }

    @Test
    fun maxSizeCiphertextAccepted() {
        val atLimit = ByteArray(MeshPacket.MAX_CIPHERTEXT_BYTES) { 1 }
        assertNull(OutboundMessageValidator.validate(message(), outbox(ciphertext = atLimit)))
    }

    @Test
    fun blankDestinationRejected() {
        assertEquals(
            EnqueueRejection.MISSING_DESTINATION,
            OutboundMessageValidator.validate(message(), outbox(destination = "  ")),
        )
    }

    @Test
    fun expiryNotAfterCreationRejected() {
        assertEquals(
            EnqueueRejection.INVALID_EXPIRY,
            OutboundMessageValidator.validate(message(), outbox(createdAt = now, expiresAt = now)),
        )
    }
}

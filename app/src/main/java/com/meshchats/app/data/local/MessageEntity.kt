package com.meshchats.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversation_id", "sent_at"]),
        // packetId links a visible message to its ciphertext outbox row; unique so
        // one message maps to at most one queued packet. Nullable values are exempt
        // from the SQLite unique constraint, so un-queued rows never collide.
        Index(value = ["packet_id"], unique = true),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "author_id") val authorId: String,
    val body: String,
    @ColumnInfo(name = "sent_at") val sentAt: Long,
    @ColumnInfo(name = "is_outgoing") val isOutgoing: Boolean,

    // --- v2 additive columns ---
    // Defaults are chosen so pre-existing v1 rows migrate to values the current UI
    // already renders: a message with no packet is DELIVERED (its historical
    // steady state), no expiry, no route path, no failure.

    /** Delivery lifecycle; one of [OutboxDeliveryState] names. */
    @ColumnInfo(name = "delivery_state", defaultValue = "DELIVERED")
    val deliveryState: String = OutboxDeliveryState.DELIVERED.name,

    /** Links to the ciphertext outbox packet, or null for messages with no queued send. */
    @ColumnInfo(name = "packet_id")
    val packetId: String? = null,

    /** When the message expires, or null for no expiry. */
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long? = null,

    /** Opaque route path the message took/should take, or null. */
    @ColumnInfo(name = "route_path")
    val routePath: String? = null,

    /** Human/machine reason for a failed delivery, or null. */
    @ColumnInfo(name = "failure_reason")
    val failureReason: String? = null,
)

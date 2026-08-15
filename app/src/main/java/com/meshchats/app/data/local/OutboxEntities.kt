package com.meshchats.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single ciphertext packet queued for delivery over the mesh. This table stores
 * ONLY ciphertext: there is deliberately no plaintext payload column, so the
 * queued send path never persists cleartext. The plaintext lives only in the
 * visible [MessageEntity.body] (itself inside the SQLCipher-encrypted database),
 * and the two are linked by [messageId] / [packetId].
 */
@Entity(
    tableName = "ciphertext_outbox",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["message_id"],
            // Deleting the visible message removes its queued ciphertext: a message
            // the user erased must not linger as sendable ciphertext.
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["packet_id"], unique = true),
        Index(value = ["message_id"]),
        Index(value = ["delivery_state"]),
        // Backs the scheduler scan (dueForDelivery): equality-filter on
        // delivery_state, then the exact ORDER BY (priority DESC, created_at ASC)
        // so the query is served by an ordered index walk rather than a sort.
        Index(
            value = ["delivery_state", "priority", "created_at"],
            orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.ASC],
        ),
    ],
)
data class CiphertextOutboxEntity(
    /** Stable internal row id (the outbox packet id). */
    @PrimaryKey @ColumnInfo(name = "packet_id") val packetId: String,

    /** The visible message this packet delivers. FK → messages.id, cascade delete. */
    @ColumnInfo(name = "message_id") val messageId: String,

    /** Destination contact/device address. */
    @ColumnInfo(name = "destination_address") val destinationAddress: String,

    /** Destination libsignal device id. */
    @ColumnInfo(name = "destination_device_id") val destinationDeviceId: Int,

    /** The encrypted payload. Never plaintext; bounded by protocol max at enqueue. */
    @ColumnInfo(name = "ciphertext", typeAffinity = ColumnInfo.BLOB)
    val ciphertext: ByteArray,

    @ColumnInfo(name = "created_at") val createdAt: Long,

    /** When the packet becomes undeliverable, or null for no expiry. */
    @ColumnInfo(name = "expires_at") val expiresAt: Long? = null,

    /** Higher runs first. */
    @ColumnInfo(name = "priority") val priority: Int = 0,

    /** Protocol content type / PacketKind wire code. */
    @ColumnInfo(name = "content_type") val contentType: Int = 0,

    /** One of [OutboxDeliveryState] names. */
    @ColumnInfo(name = "delivery_state") val deliveryState: String = OutboxDeliveryState.QUEUED.name,

    /** How many send attempts have been made. */
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,

    /** Earliest time the next attempt may run (backoff), or null for immediate. */
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long? = null,

    /** Opaque route metadata (e.g. last chosen transport / path), or null. */
    @ColumnInfo(name = "route_metadata") val routeMetadata: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CiphertextOutboxEntity) return false
        return packetId == other.packetId &&
            messageId == other.messageId &&
            destinationAddress == other.destinationAddress &&
            destinationDeviceId == other.destinationDeviceId &&
            ciphertext.contentEquals(other.ciphertext) &&
            createdAt == other.createdAt &&
            expiresAt == other.expiresAt &&
            priority == other.priority &&
            contentType == other.contentType &&
            deliveryState == other.deliveryState &&
            attemptCount == other.attemptCount &&
            nextAttemptAt == other.nextAttemptAt &&
            routeMetadata == other.routeMetadata
    }

    override fun hashCode(): Int {
        var result = packetId.hashCode()
        result = 31 * result + messageId.hashCode()
        result = 31 * result + destinationAddress.hashCode()
        result = 31 * result + destinationDeviceId
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (expiresAt?.hashCode() ?: 0)
        result = 31 * result + priority
        result = 31 * result + contentType
        result = 31 * result + deliveryState.hashCode()
        result = 31 * result + attemptCount
        result = 31 * result + (nextAttemptAt?.hashCode() ?: 0)
        result = 31 * result + (routeMetadata?.hashCode() ?: 0)
        return result
    }
}

/**
 * An audit record of one delivery attempt for an outbox packet. Cascades with its
 * parent packet so attempts never outlive the packet they describe.
 */
@Entity(
    tableName = "delivery_attempts",
    foreignKeys = [
        ForeignKey(
            entity = CiphertextOutboxEntity::class,
            parentColumns = ["packet_id"],
            childColumns = ["packet_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["packet_id"]),
        Index(value = ["attempted_at"]),
    ],
)
data class DeliveryAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    @ColumnInfo(name = "packet_id") val packetId: String,

    /** Transport/route this attempt used (e.g. transport id / route path). */
    @ColumnInfo(name = "transport") val transport: String,

    @ColumnInfo(name = "route") val route: String? = null,

    @ColumnInfo(name = "attempted_at") val attemptedAt: Long,

    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,

    /** Terminal outcome of this attempt: one of [OutboxDeliveryState] names. */
    @ColumnInfo(name = "outcome") val outcome: String,

    /** Machine-readable failure code, or null on success. */
    @ColumnInfo(name = "failure_code") val failureCode: String? = null,
)

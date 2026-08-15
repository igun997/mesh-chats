package com.meshchats.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.meshchats.protocol.routing.MeshPacket
import kotlinx.coroutines.flow.Flow

@Dao
abstract class OutboxDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOutbox(outbox: CiphertextOutboxEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertAttempt(attempt: DeliveryAttemptEntity): Long

    /**
     * Atomically insert a visible queued [message] and its ciphertext [outbox]
     * packet. Either both rows are committed or neither is.
     *
     * Two distinct failure modes:
     *  - **Validated invariants** (message must be outbound, ids must match,
     *    ciphertext must be non-empty and within [MeshPacket.MAX_CIPHERTEXT_BYTES],
     *    destination present, expiry valid) are checked up front by
     *    [OutboundMessageValidator]. A violation returns
     *    [EnqueueResult.Rejected] and writes nothing — it never throws.
     *  - **Database conflicts** that validation cannot foresee (a duplicate
     *    `packet_id` unique conflict, or a `message_id` foreign-key violation)
     *    still surface as a thrown `SQLiteException` from the offending insert.
     *    Because both inserts run inside this [Transaction], the throw rolls the
     *    whole transaction back: the first insert is undone and neither row
     *    survives. Callers that enqueue with caller-supplied ids should be ready
     *    to catch that exception.
     *
     * No encryption happens here; the caller supplies ready ciphertext.
     */
    @Transaction
    open suspend fun enqueueOutbound(
        message: MessageEntity,
        outbox: CiphertextOutboxEntity,
    ): EnqueueResult {
        OutboundMessageValidator.validate(message, outbox)?.let { rejection ->
            return EnqueueResult.Rejected(rejection)
        }
        // Both inserts run inside the @Transaction. If the second fails (e.g. a
        // unique/foreign-key conflict), Room rolls the whole transaction back, so
        // the first insert is undone and neither row survives.
        insertMessage(message)
        insertOutbox(outbox)
        return EnqueueResult.Success(outbox.packetId)
    }

    @Query("SELECT * FROM ciphertext_outbox WHERE packet_id = :packetId LIMIT 1")
    abstract suspend fun getPacket(packetId: String): CiphertextOutboxEntity?

    /**
     * Packets due for a send attempt now, most urgent first: queued/failed state,
     * not yet expired, next-attempt time reached. Deterministic ordering:
     * priority desc, then creation time asc, then packet id.
     *
     * [limit] bounds how many rows are materialised in one scan. Each row carries
     * a ciphertext blob up to [MeshPacket.MAX_CIPHERTEXT_BYTES], so an unbounded
     * query could load the entire backlog into memory at once. Callers pass a
     * batch size; the scheduler drains the queue across successive calls rather
     * than in a single unbounded read. [limit] must be positive — see
     * [dueForDelivery].
     */
    @Query(
        """
        SELECT * FROM ciphertext_outbox
        WHERE delivery_state IN ('QUEUED', 'FAILED')
          AND (expires_at IS NULL OR expires_at > :now)
          AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
        ORDER BY priority DESC, created_at ASC, packet_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun dueForDeliveryLimited(now: Long, limit: Int): List<CiphertextOutboxEntity>

    /**
     * Bounded convenience wrapper over [dueForDeliveryLimited]. Rejects a
     * non-positive [limit] up front (a caller bug) and clamps to
     * [MAX_DUE_BATCH] so a single scan can never load the whole backlog — and
     * its ciphertext blobs — into memory.
     */
    suspend fun dueForDelivery(now: Long, limit: Int = DEFAULT_DUE_BATCH): List<CiphertextOutboxEntity> {
        require(limit > 0) { "limit must be positive, was $limit" }
        return dueForDeliveryLimited(now, minOf(limit, MAX_DUE_BATCH))
    }

    @Query("SELECT * FROM ciphertext_outbox ORDER BY created_at ASC, packet_id ASC")
    abstract fun observeAll(): Flow<List<CiphertextOutboxEntity>>

    @Query(
        """
        UPDATE ciphertext_outbox
        SET delivery_state = :state,
            attempt_count = :attemptCount,
            next_attempt_at = :nextAttemptAt
        WHERE packet_id = :packetId
        """,
    )
    abstract suspend fun updateDeliveryState(
        packetId: String,
        state: String,
        attemptCount: Int,
        nextAttemptAt: Long?,
    )

    /**
     * Update only the delivery lifecycle fields of an existing packet, leaving its
     * ciphertext, destination, and creation metadata untouched. Targeted so a
     * status change (e.g. QUEUED → SENT) never rewrites the ciphertext blob and
     * never risks disturbing the row's foreign-key relationships.
     */
    @Query(
        """
        UPDATE ciphertext_outbox
        SET delivery_state = :state,
            attempt_count = :attemptCount,
            next_attempt_at = :nextAttemptAt,
            route_metadata = :routeMetadata
        WHERE packet_id = :packetId
        """,
    )
    abstract suspend fun updateDelivery(
        packetId: String,
        state: String,
        attemptCount: Int,
        nextAttemptAt: Long?,
        routeMetadata: String?,
    )

    @Query("DELETE FROM ciphertext_outbox WHERE packet_id = :packetId")
    abstract suspend fun delete(packetId: String)

    @Query("SELECT * FROM delivery_attempts WHERE packet_id = :packetId ORDER BY attempted_at ASC, id ASC")
    abstract suspend fun attemptsFor(packetId: String): List<DeliveryAttemptEntity>

    companion object {
        /** Default batch size for a single [dueForDelivery] scan. */
        const val DEFAULT_DUE_BATCH: Int = 64

        /**
         * Hard ceiling on rows returned by one [dueForDelivery] scan. Bounds the
         * memory a single read can consume even if a caller asks for more; the
         * scheduler drains the queue across successive calls.
         */
        const val MAX_DUE_BATCH: Int = 256
    }
}

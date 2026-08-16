package com.meshchats.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Backing store for PQXDH Kyber base-key replay detection. Mirrors the base-key
 * tracking libsignal's official `InMemoryKyberPreKeyStore` performs when a Kyber
 * prekey is consumed: record the initiator's base key against the Kyber+signed
 * pair and reject a second consumption that reuses the exact same base key.
 *
 * The core operation, [markKyberUsedWithBaseKey], is transactional and relies on
 * the DB-level UNIQUE constraint over the exact `(kyber_prekey_id,
 * signed_prekey_id, base_key)` triple for concurrency-safe duplicate detection —
 * never a check-then-insert. It returns a bounded [MarkKyberUsedResult]; it does
 * not depend on libsignal (Task 2 maps [MarkKyberUsedResult.REUSED] to
 * `ReusedBaseKeyException`).
 */
@Dao
abstract class SignalKyberBaseKeyDao {

    /**
     * Insert a seen base key. Uses [OnConflictStrategy.IGNORE] so a duplicate exact
     * triple is a no-op that returns a non-positive rowid rather than throwing —
     * the caller distinguishes fresh vs. replay from the returned rowid without a
     * prior read.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertBaseKey(row: SignalKyberBaseKeyEntity): Long

    @Query("SELECT COUNT(*) FROM signal_kyber_prekeys WHERE kyber_prekey_id = :id")
    protected abstract suspend fun kyberCount(id: Int): Int

    @Query("UPDATE signal_kyber_prekeys SET used = 1 WHERE kyber_prekey_id = :id")
    protected abstract suspend fun markKyberUsed(id: Int)

    @Query(
        "SELECT * FROM signal_kyber_base_keys " +
            "WHERE kyber_prekey_id = :kyberPreKeyId " +
            "ORDER BY signed_prekey_id ASC, id ASC",
    )
    abstract suspend fun baseKeysFor(kyberPreKeyId: Int): List<SignalKyberBaseKeyEntity>

    @Query("SELECT COUNT(*) FROM signal_kyber_base_keys")
    abstract suspend fun baseKeyCount(): Int

    /**
     * Atomically record consumption of Kyber prekey [kyberId] by the initiator base
     * key [baseKey] under signed prekey [signedPreKeyId], at time [now].
     *
     * Semantics, matching the official in-memory store:
     *  - **Missing Kyber id** → [MarkKyberUsedResult.MISSING]; nothing is written.
     *  - **First time for this exact triple** → the replay row is inserted and the
     *    Kyber prekey is marked used; returns [MarkKyberUsedResult.MARKED]. The
     *    Kyber key is retained (marked used, not deleted) for decrypt/audit — both
     *    one-time and last-resort keys — exactly as libsignal's in-memory store
     *    keeps the record and tracks base keys rather than deleting.
     *  - **Exact triple already seen** → a replay; nothing changes and returns
     *    [MarkKyberUsedResult.REUSED]. Detected by the UNIQUE constraint via the
     *    IGNORE insert's non-positive rowid, so this is safe under concurrent
     *    callers: exactly one wins the insert, every other observes REUSED.
     *
     * [baseKey] is bounded up front via [SignalKyberBaseKeyBounds]; an out-of-bound
     * blob is a caller bug and fails fast before any row is touched.
     *
     * [BlockingSignalStoreDao.markKyberUsedWithBaseKeyBlocking] is the synchronous
     * mirror of this method for libsignal's native `markKyberPreKeyUsed` callback.
     * Both share the exact-triple UNIQUE-constraint semantics, up-front bounding,
     * bounded [MarkKyberUsedResult] contract, and `signal_kyber_base_keys` schema;
     * they differ only in `suspend` vs. blocking call shape. Any change to the
     * replay semantics or schema here must be mirrored there, and both are covered
     * by their own tests.
     */
    @Transaction
    open suspend fun markKyberUsedWithBaseKey(
        kyberId: Int,
        signedPreKeyId: Int,
        baseKey: ByteArray,
        now: Long,
    ): MarkKyberUsedResult {
        // Bound the caller-supplied base key before it can reach the table.
        SignalKyberBaseKeyBounds.requireValid(baseKey)

        // Missing Kyber prekey: insert nothing, change nothing.
        if (kyberCount(kyberId) == 0) return MarkKyberUsedResult.MISSING

        val rowId = insertBaseKey(
            SignalKyberBaseKeyEntity(
                kyberPreKeyId = kyberId,
                signedPreKeyId = signedPreKeyId,
                baseKey = baseKey,
                firstSeenAt = now,
            ),
        )

        // A non-positive rowid means the IGNORE insert hit the UNIQUE constraint:
        // this exact triple was already seen → replay. Leave all state unchanged.
        if (rowId <= 0L) return MarkKyberUsedResult.REUSED

        // Fresh consumption: mark the Kyber prekey used (retained, not deleted).
        markKyberUsed(kyberId)
        return MarkKyberUsedResult.MARKED
    }
}

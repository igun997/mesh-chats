package com.meshchats.app.crypto.prekey

/** A bounded reason id generation failed. */
enum class PreKeyIdError {
    /**
     * A free, positive, collision-free id could not be found within the bounded
     * attempt budget. Surfaced rather than looping forever; the caller treats it
     * as a typed provisioning failure and writes nothing.
     */
    EXHAUSTED,
}

/** Result of drawing one or a batch of prekey ids. */
sealed interface PreKeyIdResult {
    /** A single freshly drawn positive id. */
    data class Success(val id: Int) : PreKeyIdResult

    /** A batch of distinct positive ids, in draw order. */
    data class Batch(val ids: List<Int>) : PreKeyIdResult

    /** Generation failed with a bounded reason. */
    data class Failure(val error: PreKeyIdError) : PreKeyIdResult
}

/**
 * Draws positive (`1..Int.MAX_VALUE`), collision-checked prekey ids.
 *
 * Every id libsignal and this app's store accept must be strictly positive (see
 * `SignalStoreValidation`: the id is each prekey table's `INTEGER PRIMARY KEY`,
 * SQLite-aliased to `rowid`), so a raw random draw is folded into the positive
 * range and a zero result is remapped to `1` — this generator can never emit 0 or
 * a negative id.
 *
 * Collisions are checked against a caller-supplied predicate (existence in the
 * relevant table plus, within a batch, ids already picked) and retried up to a
 * bounded [maxAttempts]. Exhausting that budget is a typed [PreKeyIdError.EXHAUSTED]
 * failure, never an unbounded loop.
 *
 * [randomInt] is injected so tests are deterministic; production supplies a
 * `SecureRandom`-backed source.
 */
class PreKeyIdGenerator(
    private val randomInt: () -> Int,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, was $maxAttempts" }
    }

    /**
     * Draws one positive id not already present per [exists]. Retries on collision
     * up to [maxAttempts]; returns [PreKeyIdResult.Failure] on exhaustion.
     */
    fun next(exists: (Int) -> Boolean): PreKeyIdResult {
        var attempts = 0
        while (attempts < maxAttempts) {
            attempts++
            val id = drawPositive()
            if (!exists(id)) return PreKeyIdResult.Success(id)
        }
        return PreKeyIdResult.Failure(PreKeyIdError.EXHAUSTED)
    }

    /**
     * Draws [count] distinct positive ids, none present per [exists] and none
     * duplicated within the batch. Returns [PreKeyIdResult.Batch] on success (empty
     * for `count == 0`) or a bounded failure if any single draw exhausts its budget.
     */
    fun batch(count: Int, exists: (Int) -> Boolean): PreKeyIdResult {
        require(count >= 0) { "count must be >= 0, was $count" }
        val picked = LinkedHashSet<Int>(count.coerceAtLeast(0))
        repeat(count) {
            when (val one = next { candidate -> candidate in picked || exists(candidate) }) {
                is PreKeyIdResult.Success -> picked.add(one.id)
                is PreKeyIdResult.Failure -> return one
                is PreKeyIdResult.Batch -> error("unreachable: next() never returns Batch")
            }
        }
        return PreKeyIdResult.Batch(picked.toList())
    }

    /** Folds a raw random int into `1..Int.MAX_VALUE`; a zero fold becomes 1. */
    private fun drawPositive(): Int {
        val folded = randomInt() and Int.MAX_VALUE // clears sign bit → 0..Int.MAX_VALUE
        return if (folded == 0) 1 else folded
    }

    companion object {
        /**
         * Generous default attempt budget. With a 31-bit id space and pools of a
         * few dozen keys, a collision is astronomically unlikely; this budget only
         * guards against a degenerate random source, never normal operation.
         */
        const val DEFAULT_MAX_ATTEMPTS: Int = 64
    }
}

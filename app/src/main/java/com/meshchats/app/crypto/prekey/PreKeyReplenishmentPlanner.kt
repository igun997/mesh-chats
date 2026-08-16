package com.meshchats.app.crypto.prekey

/**
 * A snapshot of the device's current prekey inventory, counting only what is
 * still usable. Used one-time keys and rotated-out signed keys are excluded by
 * the caller before this is built, so the planner reasons purely over the live
 * pool.
 */
data class PreKeyInventoryCounts(
    /** Unused one-time EC prekeys currently in the store. */
    val unusedEcOneTime: Int,
    /** Unused (non-last-resort) one-time Kyber prekeys currently in the store. */
    val unusedKyberOneTime: Int,
    /** Last-resort Kyber prekeys currently in the store (used or not; reusable). */
    val lastResortKyber: Int,
    /** Active signed EC prekeys currently in the store. */
    val activeSigned: Int,
) {
    init {
        require(unusedEcOneTime >= 0)
        require(unusedKyberOneTime >= 0)
        require(lastResortKyber >= 0)
        require(activeSigned >= 0)
    }
}

/**
 * What the current [PreKeyInventoryCounts] require to be generated to bring the
 * inventory back to target. All counts are non-negative; a healthy inventory
 * yields an all-zero / all-false plan.
 */
data class PreKeyReplenishmentPlan(
    val ecOneTimeToGenerate: Int,
    val kyberOneTimeToGenerate: Int,
    val generateLastResort: Boolean,
    val generateSigned: Boolean,
) {
    /** True when nothing needs generating: the inventory already meets targets. */
    val isNoOp: Boolean
        get() = ecOneTimeToGenerate == 0 &&
            kyberOneTimeToGenerate == 0 &&
            !generateLastResort &&
            !generateSigned
}

/**
 * Pure planner deciding how many prekeys to mint, given the live inventory.
 *
 * The rule is deliberately simple and hysteretic (see [PreKeyInventoryTargets]):
 *  - one-time pools refill back to target **only** when they fall strictly below
 *    their threshold, so a device does not churn keys after a single consumption;
 *  - the last-resort Kyber and signed EC keys are singletons: generate one iff
 *    none is present.
 *
 * It never returns a negative count (an over-full pool plans zero) and holds no
 * state, so it is exercised entirely on the host JVM.
 */
object PreKeyReplenishmentPlanner {

    fun plan(counts: PreKeyInventoryCounts): PreKeyReplenishmentPlan {
        val ec = refillCount(
            unused = counts.unusedEcOneTime,
            threshold = PreKeyInventoryTargets.EC_ONE_TIME_THRESHOLD,
            target = PreKeyInventoryTargets.EC_ONE_TIME_TARGET,
        )
        val kyber = refillCount(
            unused = counts.unusedKyberOneTime,
            threshold = PreKeyInventoryTargets.KYBER_ONE_TIME_THRESHOLD,
            target = PreKeyInventoryTargets.KYBER_ONE_TIME_TARGET,
        )
        return PreKeyReplenishmentPlan(
            ecOneTimeToGenerate = ec,
            kyberOneTimeToGenerate = kyber,
            generateLastResort = counts.lastResortKyber < PreKeyInventoryTargets.LAST_RESORT_KYBER_TARGET,
            generateSigned = counts.activeSigned < PreKeyInventoryTargets.SIGNED_TARGET,
        )
    }

    /**
     * Refill to [target] only when [unused] is strictly below [threshold];
     * otherwise 0. Never negative even when the pool is over target.
     */
    private fun refillCount(unused: Int, threshold: Int, target: Int): Int =
        if (unused < threshold) (target - unused).coerceAtLeast(0) else 0
}

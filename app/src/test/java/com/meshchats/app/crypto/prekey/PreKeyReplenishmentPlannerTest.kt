package com.meshchats.app.crypto.prekey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure, Room-free spec for [PreKeyReplenishmentPlanner]. Proves the single
 * hysteresis rule that governs empty→target provisioning, idempotence, and
 * below-threshold refill without any database or libsignal dependency.
 */
class PreKeyReplenishmentPlannerTest {

    @Test
    fun emptyInventoryPlansFullTargets() {
        val plan = PreKeyReplenishmentPlanner.plan(
            PreKeyInventoryCounts(
                unusedEcOneTime = 0,
                unusedKyberOneTime = 0,
                lastResortKyber = 0,
                activeSigned = 0,
            ),
        )
        assertEquals(PreKeyInventoryTargets.EC_ONE_TIME_TARGET, plan.ecOneTimeToGenerate)
        assertEquals(PreKeyInventoryTargets.KYBER_ONE_TIME_TARGET, plan.kyberOneTimeToGenerate)
        assertTrue(plan.generateLastResort)
        assertTrue(plan.generateSigned)
    }

    @Test
    fun fullInventoryPlansNothing() {
        val plan = PreKeyReplenishmentPlanner.plan(
            PreKeyInventoryCounts(
                unusedEcOneTime = PreKeyInventoryTargets.EC_ONE_TIME_TARGET,
                unusedKyberOneTime = PreKeyInventoryTargets.KYBER_ONE_TIME_TARGET,
                lastResortKyber = PreKeyInventoryTargets.LAST_RESORT_KYBER_TARGET,
                activeSigned = PreKeyInventoryTargets.SIGNED_TARGET,
            ),
        )
        assertEquals(0, plan.ecOneTimeToGenerate)
        assertEquals(0, plan.kyberOneTimeToGenerate)
        assertFalse(plan.generateLastResort)
        assertFalse(plan.generateSigned)
    }

    @Test
    fun betweenThresholdAndTargetDoesNotRefill() {
        // Unused sits above the threshold but below the target: hysteresis means
        // we do NOT top back up, avoiding churn on every ensure.
        val ecMid = PreKeyInventoryTargets.EC_ONE_TIME_THRESHOLD + 1
        val kyberMid = PreKeyInventoryTargets.KYBER_ONE_TIME_THRESHOLD + 1
        val plan = PreKeyReplenishmentPlanner.plan(
            PreKeyInventoryCounts(
                unusedEcOneTime = ecMid,
                unusedKyberOneTime = kyberMid,
                lastResortKyber = 1,
                activeSigned = 1,
            ),
        )
        assertEquals(0, plan.ecOneTimeToGenerate)
        assertEquals(0, plan.kyberOneTimeToGenerate)
    }

    @Test
    fun belowThresholdRefillsBackToTarget() {
        val ecLow = PreKeyInventoryTargets.EC_ONE_TIME_THRESHOLD - 1
        val kyberLow = PreKeyInventoryTargets.KYBER_ONE_TIME_THRESHOLD - 1
        val plan = PreKeyReplenishmentPlanner.plan(
            PreKeyInventoryCounts(
                unusedEcOneTime = ecLow,
                unusedKyberOneTime = kyberLow,
                lastResortKyber = 1,
                activeSigned = 1,
            ),
        )
        assertEquals(PreKeyInventoryTargets.EC_ONE_TIME_TARGET - ecLow, plan.ecOneTimeToGenerate)
        assertEquals(PreKeyInventoryTargets.KYBER_ONE_TIME_TARGET - kyberLow, plan.kyberOneTimeToGenerate)
    }

    @Test
    fun exactlyAtThresholdStillRefills() {
        // The rule is strict "below": at the threshold we are not yet below it, so
        // no refill. This pins the boundary.
        val plan = PreKeyReplenishmentPlanner.plan(
            PreKeyInventoryCounts(
                unusedEcOneTime = PreKeyInventoryTargets.EC_ONE_TIME_THRESHOLD,
                unusedKyberOneTime = PreKeyInventoryTargets.KYBER_ONE_TIME_THRESHOLD,
                lastResortKyber = 1,
                activeSigned = 1,
            ),
        )
        assertEquals(0, plan.ecOneTimeToGenerate)
        assertEquals(0, plan.kyberOneTimeToGenerate)
    }

    @Test
    fun overfullInventoryNeverPlansNegative() {
        val plan = PreKeyReplenishmentPlanner.plan(
            PreKeyInventoryCounts(
                unusedEcOneTime = PreKeyInventoryTargets.EC_ONE_TIME_TARGET + 100,
                unusedKyberOneTime = PreKeyInventoryTargets.KYBER_ONE_TIME_TARGET + 100,
                lastResortKyber = 5,
                activeSigned = 3,
            ),
        )
        assertEquals(0, plan.ecOneTimeToGenerate)
        assertEquals(0, plan.kyberOneTimeToGenerate)
        assertFalse(plan.generateLastResort)
        assertFalse(plan.generateSigned)
    }

    @Test
    fun signedAndLastResortAreIndependentOfOneTimeCounts() {
        // Missing signed / last-resort must be planned even when one-time pools are full.
        val plan = PreKeyReplenishmentPlanner.plan(
            PreKeyInventoryCounts(
                unusedEcOneTime = PreKeyInventoryTargets.EC_ONE_TIME_TARGET,
                unusedKyberOneTime = PreKeyInventoryTargets.KYBER_ONE_TIME_TARGET,
                lastResortKyber = 0,
                activeSigned = 0,
            ),
        )
        assertEquals(0, plan.ecOneTimeToGenerate)
        assertEquals(0, plan.kyberOneTimeToGenerate)
        assertTrue(plan.generateLastResort)
        assertTrue(plan.generateSigned)
    }
}

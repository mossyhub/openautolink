package com.openautolink.app.data

import org.junit.Assert.*
import org.junit.Test

class EvLearnQualificationTest {
    private val key = EvLearnQualification.Scope(1L, 80_000, EvLearnQualification.Basis.USABLE, 1L)
    private val clean = EvLearnQualification.Closure.BOUNDARY
    private val baseline = EvLearnQualification.Decision.BASELINE
    private val shadow = EvLearnQualification.Decision.QUALIFIED_SHADOW

    private class Drive(val policy: EvLearnQualification) {
        var next = 1L
        fun start(key: EvLearnQualification.Scope, prediction: Double = 200.0, learned: Double = 150.0) =
            policy.begin(EvLearnQualification.Start(key, next, 50_000, prediction, 0,
                EvLearnQualification.Prior(key, next - 1, learned)))

        fun end(
            ticket: EvLearnQualification.Ticket,
            key: EvLearnQualification.Scope,
            actual: Int = 1500,
            km: Double = 10.0,
            coverage: Double = 0.99,
            gap: Long = 5_000,
            age: Long = 0,
            closure: EvLearnQualification.Closure = EvLearnQualification.Closure.BOUNDARY,
        ): Boolean {
            val result = policy.finish(ticket, EvLearnQualification.End(
                key, next + 600_000, 50_000 - actual, age, km, coverage, gap, closure,
            ))
            next += 700_000
            return result
        }
    }

    private fun drive() = Drive(EvLearnQualification(key, enabled = true))
    private fun Drive.window(actual: Int = 1500, prediction: Double = 200.0) =
        assertTrue(end(requireNotNull(start(key, prediction)), key, actual))

    @Test fun defaultsToBaselineWithoutCollecting() {
        val policy = EvLearnQualification(key)
        assertNull(Drive(policy).start(key))
        assertEquals(baseline, policy.status.decision)
        assertEquals(0, policy.status.trainingWindows)
        assertEquals(EvLearnQualification.Reason.OFF, policy.status.reason)
    }

    @Test fun chronologicalWarmupThenPositiveMatchedComparisonOnlyQualifiesShadow() {
        val d = drive()
        repeat(3) { d.window() }
        assertEquals(0, d.policy.status.pairs)
        assertEquals(baseline, d.policy.status.decision)
        repeat(4) { d.window() }
        assertEquals(4, d.policy.status.pairs)
        assertEquals(baseline, d.policy.status.decision)
        d.window()
        assertEquals(5, d.policy.status.pairs)
        assertEquals(shadow, d.policy.status.decision)
        assertEquals(0.0, d.policy.status.learnedMaeWh!!, 0.001)
        assertEquals(500.0, d.policy.status.baselineMaeWh!!, 0.001)
    }

    @Test fun currentOutcomeCannotImproveItsOwnFrozenPrediction() {
        val d = drive()
        repeat(3) { d.window() }
        d.window(actual = 1700, prediction = 190.0)
        assertEquals(200.0, d.policy.status.learnedMaeWh!!, 0.001)
        assertEquals(200.0, d.policy.status.baselineMaeWh!!, 0.001)
        assertEquals(baseline, d.policy.status.decision)
    }

    @Test fun replayLossDoesNotPromoteAndSevereRegressionRevokesAndLatches() {
        val cold = drive()
        cold.window()
        cold.window(actual = 3010, prediction = 234.7)
        assertEquals(0, cold.policy.status.pairs)
        assertEquals(baseline, cold.policy.status.decision)

        val d = drive()
        repeat(8) { d.window() }
        assertEquals(shadow, d.policy.status.decision)
        // Construct a warm held-out pair with the replay's approximate Wh errors.
        val t = requireNotNull(d.start(key, prediction = 234.7, learned = 146.9))
        assertTrue(d.end(t, key, actual = 3010))
        assertEquals(baseline, d.policy.status.decision)
        assertEquals(EvLearnQualification.Reason.REGRESSED, d.policy.status.reason)
        repeat(12) { assertNull(d.start(key)) }
    }

    @Test fun lowerMaeCannotExcuseMoreOptimisticConsumptionErrors() {
        val d = drive()
        repeat(3) { d.window() }
        repeat(5) { d.window(actual = 1550, prediction = 170.0) }
        assertTrue(d.policy.status.learnedMaeWh!! < d.policy.status.baselineMaeWh!!)
        assertEquals(EvLearnQualification.Reason.NO_GAIN, d.policy.status.reason)
        assertEquals(baseline, d.policy.status.decision)
    }

    @Test fun classExactCapacityBasisAndSourceEpochMustAllMatch() {
        val wrongKeys = listOf(key.copy(classId = 2L), key.copy(capacityWh = 80_001),
            key.copy(basis = EvLearnQualification.Basis.REPORTED), key.copy(sourceEpoch = 2L))
        for (wrong in wrongKeys) {
            val d = drive()
            assertNull(d.start(wrong))
            assertFalse(d.end(requireNotNull(d.start(key)), wrong))
            assertEquals(0, d.policy.status.trainingWindows)
            d.window()
        }
    }

    @Test fun interruptedChargingGappyStaleLowCoverageAndImplausibleWindowsDoNotTeach() {
        val d = drive()
        fun ticket() = requireNotNull(d.start(key))
        assertFalse(d.end(ticket(), key, closure = EvLearnQualification.Closure.CHARGING))
        assertFalse(d.end(ticket(), key, closure = EvLearnQualification.Closure.INTERRUPTED))
        assertFalse(d.end(ticket(), key, gap = 15_001))
        assertFalse(d.end(ticket(), key, coverage = 0.899))
        assertFalse(d.end(ticket(), key, coverage = Double.NaN))
        assertFalse(d.end(ticket(), key, age = 10_001))
        assertFalse(d.end(ticket(), key, actual = -100))
        assertFalse(d.end(ticket(), key, km = Double.POSITIVE_INFINITY))
        assertFalse(d.end(ticket(), key, km = 0.99))
        assertNull(d.policy.begin(EvLearnQualification.Start(key, d.next, 50_000, 200.0, -1)))
        assertNull(d.start(key, prediction = Double.NaN))
        assertEquals(0, d.policy.status.trainingWindows)
        assertEquals(0, d.policy.status.pairs)
        d.window()
    }

    @Test fun oneExactTicketRejectsReplayAndOverlapWithoutConsumingSuccessor() {
        val d = drive()
        val a = requireNotNull(d.start(key))
        assertNull(d.start(key))
        assertTrue(d.end(a, key))
        assertNull(d.policy.begin(EvLearnQualification.Start(key, 599_999, 50_000, 200.0, 0)))
        val b = requireNotNull(d.start(key))
        assertFalse(d.end(a, key))
        assertFalse(d.policy.abort(a))
        assertTrue(d.policy.abort(b))
        assertFalse(d.end(b, key))
        d.window()
        assertEquals(2, d.policy.status.trainingWindows)
    }

    @Test fun evidenceIsBoundedAndWarmupRequiresDistanceAsWellAsCount() {
        val d = drive()
        repeat(28) { assertTrue(d.end(requireNotNull(d.start(key)), key, actual = 150, km = 1.0)) }
        assertEquals(0, d.policy.status.pairs)
        assertEquals(28, d.policy.status.trainingWindows)
        repeat(46) { d.window() }
        assertEquals(EvLearnQualification.MAX_PAIRS, d.policy.status.pairs)
        assertEquals(EvLearnQualification.MAX_TRAINING_WINDOWS, d.policy.status.trainingWindows)
        assertEquals(shadow, d.policy.status.decision)
    }

    @Test fun laterComparableNonSevereLossCanWithdrawRollingQualification() {
        val d = drive()
        repeat(8) { d.window() }
        assertEquals(shadow, d.policy.status.decision)
        repeat(16) { d.window(actual = 1550, prediction = 175.0) }
        assertEquals(baseline, d.policy.status.decision)
        assertEquals(EvLearnQualification.Reason.NO_GAIN, d.policy.status.reason)
    }

    @Test fun enoughPairsWithoutEnoughHeldOutDistanceStillCannotQualify() {
        val d = drive()
        repeat(3) { d.window() }
        repeat(5) { assertTrue(d.end(requireNotNull(d.start(key)), key, actual = 150, km = 1.0)) }
        assertEquals(5, d.policy.status.pairs)
        assertEquals(EvLearnQualification.Reason.EVALUATING, d.policy.status.reason)
        assertEquals(baseline, d.policy.status.decision)
    }

    @Test fun oneSeverelyOptimisticCandidateRevokesEvenIfBaselineWasWorse() {
        val d = drive()
        repeat(20) { d.window() }
        assertEquals(shadow, d.policy.status.decision)
        d.window(actual = 2000, prediction = 100.0)
        assertEquals(baseline, d.policy.status.decision)
        assertEquals(EvLearnQualification.Reason.REGRESSED, d.policy.status.reason)
    }

    @Test fun missingForeignUnboundedOrNonPriorSnapshotNeverSuppliesAPair() {
        val d = drive()
        repeat(8) { d.window() }
        assertEquals(shadow, d.policy.status.decision)
        val invalidPriors: List<(Long) -> EvLearnQualification.Prior?> = listOf(
            { null },
            { EvLearnQualification.Prior(key.copy(sourceEpoch = 2L), it - 1, 150.0) },
            { EvLearnQualification.Prior(key, it, 150.0) },
            { EvLearnQualification.Prior(key, it + 1, 150.0) },
            { EvLearnQualification.Prior(key, -1, 150.0) },
            { EvLearnQualification.Prior(key, it - 1, Double.POSITIVE_INFINITY) },
        )
        for (invalidPrior in invalidPriors) {
            val prior = invalidPrior(d.next)
            val t = requireNotNull(d.policy.begin(EvLearnQualification.Start(key, d.next, 50_000, 200.0, 0, prior)))
            assertEquals(baseline, d.policy.status.decision)
            assertTrue(d.end(t, key))
            assertEquals(EvLearnQualification.Reason.NO_PRIOR_CANDIDATE, d.policy.status.reason)
            assertEquals(5, d.policy.status.pairs)
        }
        d.window()
        assertEquals(6, d.policy.status.pairs)
        assertEquals(shadow, d.policy.status.decision)
    }

    @Test fun retireClearsEvidenceAndFencesOpenAndFutureTickets() {
        val d = drive()
        repeat(8) { d.window() }
        assertEquals(shadow, d.policy.status.decision)
        val t = requireNotNull(d.start(key))
        d.policy.retire()
        d.policy.retire()
        assertFalse(d.end(t, key))
        assertNull(d.start(key))
        assertEquals(baseline, d.policy.status.decision)
        assertEquals(EvLearnQualification.Reason.RETIRED, d.policy.status.reason)
        assertEquals(0, d.policy.status.trainingWindows)
        assertEquals(0, d.policy.status.pairs)
        assertNull(d.policy.status.learnedMaeWh)
    }
}

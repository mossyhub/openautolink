package com.openautolink.app.data

import kotlin.math.abs
import kotlin.math.max

/**
 * In-memory, single-owner EV drive-energy qualification. Call on one serialized lane.
 * Scores frozen baseline and shadow-learner inputs using matched physical windows, not Maps forecasts.
 * Status is evidence for review only; the candidate rate never leaves this class.
 */
internal class EvLearnQualification(private val scope: Scope, private val enabled: Boolean = false) {
    enum class Basis { USABLE, REPORTED }

    /** Exact upstream-qualified local class and source epoch, not VIN or a capacity band. */
    data class Scope(val classId: Long, val capacityWh: Int, val basis: Basis, val sourceEpoch: Long) {
        init {
            require(classId > 0 && sourceEpoch > 0 && capacityWh in 1_000..300_000)
        }
    }

    enum class Closure { BOUNDARY, INTERRUPTED, CHARGING }
    enum class Decision { BASELINE, QUALIFIED_SHADOW }
    enum class Reason { OFF, RETIRED, WARMING_UP, NO_PRIOR_CANDIDATE, EVALUATING, NO_GAIN, QUALIFIED, REGRESSED }

    /** Latest contributing event in a trustworthy shadow-learner snapshot, in this source epoch. */
    data class Prior(val scope: Scope, val trainedThroughElapsedMs: Long, val whPerKm: Double)

    /** Capture only at the physical boundary, before any drive outcome is observed. */
    data class Start(
        val scope: Scope,
        val elapsedMs: Long,
        val packWh: Int,
        val baselineWhPerKm: Double,
        val packAgeMs: Long,
        val prior: Prior? = null,
    )

    /**
     * Vehicle-only summary for this exact start. Ages are the worse of qualified event/read and
     * receipt ages. Coverage is covered moving time / observed moving time, and largest gap
     * includes the boundaries. BOUNDARY asserts fresh motion/gear/ignition, valid pack endpoints,
     * no charging, and uninterrupted source authority throughout. Otherwise interrupt or abort.
     */
    data class End(
        val scope: Scope,
        val elapsedMs: Long,
        val packWh: Int,
        val packAgeMs: Long,
        val coveredKm: Double,
        val coverage: Double,
        val largestGapMs: Long,
        val closure: Closure,
    )

    data class Status(
        val decision: Decision,
        val reason: Reason,
        val trainingWindows: Int,
        val pairs: Int,
        val baselineMaeWh: Double?,
        val learnedMaeWh: Double?,
    )

    /** Single-use, exact reference identity, not a replayable sequence number. */
    class Ticket internal constructor()

    private data class Open(val ticket: Ticket, val start: Start, val learnedRate: Double?)

    private data class PairScore(val km: Double, val baselineErrorWh: Double, val learnedErrorWh: Double, val wh: Int) {
        val baselineOptimism get() = max(0.0, -baselineErrorWh / wh)
        val learnedOptimism get() = max(0.0, -learnedErrorWh / wh)
    }

    companion object {
        const val MAX_TRAINING_WINDOWS = 32
        const val MAX_PAIRS = 16
        private const val MIN_TRAINING_WINDOWS = 3
        private const val MIN_TRAINING_KM = 30.0
        private const val MIN_PAIRS = 5
        private const val MIN_PAIRED_KM = 50.0
        private const val MAX_WINDOW_MS = 7_200_000L
        private const val MAX_GAP_MS = 15_000L
        private const val MAX_PACK_AGE_MS = 10_000L
    }

    private val trainingKm = ArrayDeque<Double>(MAX_TRAINING_WINDOWS)
    private val scores = ArrayDeque<PairScore>(MAX_PAIRS)
    private var active: Open? = null
    private var lastBoundaryMs = -1L
    private var regressed = false
    private var retired = false
    private var candidateAvailable = false

    val status: Status
        get() {
            val baselineMae = scores.takeIf { it.isNotEmpty() }?.let { it.sumOf { s -> abs(s.baselineErrorWh) } / it.size }
            val learnedMae = scores.takeIf { it.isNotEmpty() }?.let { it.sumOf { s -> abs(s.learnedErrorWh) } / it.size }
            val reason = when {
                retired -> Reason.RETIRED
                !enabled -> Reason.OFF
                regressed -> Reason.REGRESSED
                !warmedUp() -> Reason.WARMING_UP
                !candidateAvailable -> Reason.NO_PRIOR_CANDIDATE
                scores.size < MIN_PAIRS || scores.sumOf { it.km } < MIN_PAIRED_KM -> Reason.EVALUATING
                baselineMae == null || learnedMae == null || baselineMae == 0.0 || learnedMae > 0.9 * baselineMae -> Reason.NO_GAIN
                tail { it.learnedOptimism } > minOf(0.10, tail { it.baselineOptimism }) -> Reason.NO_GAIN
                else -> Reason.QUALIFIED
            }
            val decision = if (reason == Reason.QUALIFIED) Decision.QUALIFIED_SHADOW else Decision.BASELINE
            return Status(decision, reason, trainingKm.size, scores.size, baselineMae, learnedMae)
        }

    fun begin(start: Start): Ticket? {
        if (!enabled || regressed || retired || active != null || start.scope != scope ||
            start.elapsedMs <= lastBoundaryMs
        ) return null
        if (start.packWh !in 1..scope.capacityWh ||
            start.packAgeMs !in 0..MAX_PACK_AGE_MS || !validRate(start.baselineWhPerKm)
        ) {
            candidateAvailable = false
            return null
        }

        val learned = start.prior?.takeIf {
            it.scope == scope && it.trainedThroughElapsedMs in 0 until start.elapsedMs && validRate(it.whPerKm)
        }?.whPerKm
        if (learned == null) candidateAvailable = false
        val ticket = Ticket()
        active = Open(ticket, start.copy(prior = null), learned)
        lastBoundaryMs = start.elapsedMs
        return ticket
    }

    /** A stale or foreign ticket cannot cancel or consume the current window. */
    fun abort(ticket: Ticket): Boolean {
        if (active?.ticket !== ticket) return false
        active = null
        candidateAvailable = false
        return true
    }

    /** Off, exact source loss/replacement, or reset: invalidate this owner before dropping it. */
    fun retire() {
        retired = true
        active = null
        trainingKm.clear()
        scores.clear()
        candidateAvailable = false
    }

    /** True means a vehicle-only outcome passed all gates, even when never paired or a loss. */
    fun finish(ticket: Ticket, end: End): Boolean {
        val open = active ?: return false
        if (!abort(ticket)) return false
        val start = open.start
        if (end.scope != scope || end.elapsedMs <= start.elapsedMs) return false
        val duration = end.elapsedMs - start.elapsedMs
        if (duration > MAX_WINDOW_MS) return false
        lastBoundaryMs = end.elapsedMs
        if (end.closure != Closure.BOUNDARY || end.packWh !in 0 until start.packWh ||
            end.packAgeMs !in 0..MAX_PACK_AGE_MS || end.largestGapMs !in 1..minOf(MAX_GAP_MS, duration) ||
            end.coveredKm !in 1.0..300.0 || end.coverage !in 0.90..1.0
        ) return false
        val measuredWh = start.packWh - end.packWh
        if (!validRate(measuredWh / end.coveredKm)) return false

        // Compare frozen rates against the same endpoint before crediting this window to warm-up.
        open.learnedRate?.takeIf { warmedUp() }?.let { rate ->
            val pair = PairScore(end.coveredKm, start.baselineWhPerKm * end.coveredKm - measuredWh,
                rate * end.coveredKm - measuredWh, measuredWh)
            if (scores.size == MAX_PAIRS) scores.removeFirst()
            scores.addLast(pair)
            val severelyWorse = abs(pair.learnedErrorWh) - abs(pair.baselineErrorWh) >= max(200.0, 0.10 * measuredWh)
            val severelyOptimistic = -pair.learnedErrorWh >= max(200.0, 0.20 * measuredWh)
            regressed = severelyWorse || severelyOptimistic
        }
        if (trainingKm.size == MAX_TRAINING_WINDOWS) trainingKm.removeFirst()
        trainingKm.addLast(end.coveredKm)
        candidateAvailable = open.learnedRate != null
        return true
    }

    private fun warmedUp() = trainingKm.size >= MIN_TRAINING_WINDOWS && trainingKm.sum() >= MIN_TRAINING_KM
    private fun validRate(whPerKm: Double) = whPerKm in 50.0..800.0

    // Nearest rank p90 on the bounded paired cohort, with zero for non-optimistic predictions.
    private fun tail(metric: (PairScore) -> Double): Double {
        val rank = (scores.size * 9 + 9) / 10
        return scores.map(metric).sorted()[rank - 1]
    }
}

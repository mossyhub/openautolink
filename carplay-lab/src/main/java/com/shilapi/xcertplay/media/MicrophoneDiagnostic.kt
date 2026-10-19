// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit, memory-only comparisons. One worker owns every platform call and release. */
class MicrophoneDiagnostic internal constructor(
    private val platform: MicrophoneDiagnosticPlatform,
    private val eligibility: () -> Boolean,
    private val onState: (MicrophoneDiagnosticState) -> Unit,
    private val timing: MicrophoneDiagnosticTiming
) {
    constructor(context: android.content.Context, eligibility: () -> Boolean,
        onState: (MicrophoneDiagnosticState) -> Unit) :
        this(MicrophoneDiagnosticAndroid(context), eligibility, onState, MicrophoneDiagnosticTiming())

    private val lock = Any()
    @Volatile var state = MicrophoneDiagnosticState()
        private set
    private class Run(val comparison: MicrophoneDiagnosticComparison?, val lease: MicrophoneCaptureFence.Lease) {
        val reason = AtomicReference(MicrophoneDiagnosticReason.NONE)
        var arm: Int? = null // accessed under lock, including deadline callbacks
    }
    private var active: Run? = null
    // Test barrier at the publication/delivery boundary; never invoked under the owner lock.
    internal var beforeTerminalNotification: (() -> Unit)? = null
    private class Abort(val reason: MicrophoneDiagnosticReason) : Exception()

    fun inspect(): Boolean = begin(null)
    fun start(comparison: MicrophoneDiagnosticComparison): Boolean = begin(comparison)
    fun cancel() { synchronized(lock) { active }?.let { stop(it, MicrophoneDiagnosticReason.CANCELLED) } }

    private fun begin(comparison: MicrophoneDiagnosticComparison?): Boolean {
        val run = synchronized(lock) {
            if (active != null) return false
            val lease = MicrophoneCaptureFence.tryDiagnostic() ?: return false
            Run(comparison, lease).also {
                active = it
                state = MicrophoneDiagnosticState(MicrophoneDiagnosticPhase.INSPECTING, comparison)
            }
        }
        notifyState()
        try {
            Thread({ execute(run) }, "oal-mic-diagnostic").apply { isDaemon = true }.start()
        } catch (_: Throwable) {
            finish(run, MicrophoneDiagnosticResult(comparison, unavailableInventory(), emptyList(),
                MicrophoneDiagnosticReason.PLATFORM_FAILURE))
        }
        return true
    }

    private fun notifyState() { runCatching { onState(state) } }
    private fun stop(run: Run, reason: MicrophoneDiagnosticReason, arm: Int? = null) {
        val changed = synchronized(lock) {
            if (active !== run || (arm != null && run.arm != arm)) false else {
                run.reason.compareAndSet(MicrophoneDiagnosticReason.NONE, reason)
                state = MicrophoneDiagnosticState(MicrophoneDiagnosticPhase.STOPPING, run.comparison,
                    run.arm, run.reason.get())
                true
            }
        }
        if (changed) notifyState()
        // Never release/stop an AudioRecord here: interrupt is not platform retirement.
    }
    private fun check(run: Run, capturePolicy: Boolean = true) {
        run.reason.get().takeUnless { it == MicrophoneDiagnosticReason.NONE }?.let { throw Abort(it) }
        if (!runCatching(eligibility).getOrDefault(false)) throw Abort(MicrophoneDiagnosticReason.INELIGIBLE)
        if (capturePolicy) platform.policy().takeUnless { it == MicrophoneDiagnosticReason.NONE }?.let { throw Abort(it) }
        run.reason.get().takeUnless { it == MicrophoneDiagnosticReason.NONE }?.let { throw Abort(it) }
    }
    private fun execute(run: Run) {
        var inventory = unavailableInventory()
        val arms = mutableListOf<MicrophoneDiagnosticArm>()
        var reason = MicrophoneDiagnosticReason.NONE
        var acknowledged = true
        var firstRoute: Any? = null // never copied into a public DTO
        val whole = deadlines.schedule({ stop(run, MicrophoneDiagnosticReason.DEADLINE) },
            timing.wholeLimitMillis, TimeUnit.MILLISECONDS)
        try {
            check(run, false)
            inventory = platform.inventory()
            check(run, false)
            if (inventory.match == MicrophoneDiagnosticMatch.UNAVAILABLE) throw Abort(MicrophoneDiagnosticReason.INVENTORY_UNAVAILABLE)
            val comparison = run.comparison
            if (comparison != null) {
                check(run)
                if (comparison == MicrophoneDiagnosticComparison.PREFERRED && inventory.match != MicrophoneDiagnosticMatch.UNIQUE)
                    throw Abort(MicrophoneDiagnosticReason.PREFERRED_UNAVAILABLE)
                for (ordinal in 0..2) {
                    check(run)
                    val requested = shape(comparison, ordinal)
                    val preferred = comparison == MicrophoneDiagnosticComparison.PREFERRED && ordinal == 1
                    val stats = MicrophoneDiagnosticAccumulator(requested.channelCount)
                    var record: MicrophoneDiagnosticRecord? = null
                    var observed: MicrophoneDiagnosticObservation? = null
                    var initialRoute: Any? = null
                    var routeStable: Boolean? = null
                    var accepted: Boolean? = null
                    var timer: ScheduledFuture<*>? = null
                    val buffer = ShortArray(960 * requested.channelCount)
                    try {
                        synchronized(lock) {
                            run.arm = ordinal
                            if (run.reason.get() == MicrophoneDiagnosticReason.NONE)
                                state = MicrophoneDiagnosticState(MicrophoneDiagnosticPhase.RUNNING, comparison, ordinal)
                        }
                        notifyState()
                        timer = deadlines.schedule({ stop(run, MicrophoneDiagnosticReason.DEADLINE, ordinal) },
                            timing.armLimitMillis, TimeUnit.MILLISECONDS)
                        val startedAt = System.nanoTime()
                        check(run)
                        val current = platform.open(requested)
                        record = current
                        acknowledged = false
                        check(run)
                        if (preferred) {
                            accepted = record.preferNamedInput()
                            if (accepted != true) throw Abort(MicrophoneDiagnosticReason.PREFERRED_REJECTED)
                        }
                        check(run)
                        record.start()
                        var firstObservation = true
                        var knownPolicy = false
                        fun sample(): Boolean {
                            check(run)
                            val receipt = current.observe()
                            check(run)
                            observed = receipt
                            if (receipt.actual != requested || (receipt.client != null && receipt.client != requested))
                                throw Abort(MicrophoneDiagnosticReason.SHAPE_MISMATCH)
                            val route = receipt.routeToken
                            if (firstObservation) {
                                firstObservation = false
                                initialRoute = route
                                if (ordinal == 0) firstRoute = route
                                routeStable = if (route == null) null else true
                            } else {
                                if (route == null || initialRoute == null) routeStable = null
                                else if (route != initialRoute) {
                                    routeStable = false
                                    throw Abort(MicrophoneDiagnosticReason.ROUTE_CHANGED)
                                }
                            }
                            if (receipt.silenced == true) throw Abort(MicrophoneDiagnosticReason.SILENCED)
                            if (receipt.silenced == null) {
                                if (knownPolicy || elapsedMillis(startedAt) >= timing.graceMillis)
                                    throw Abort(MicrophoneDiagnosticReason.POLICY_UNKNOWN)
                                return false
                            }
                            knownPolicy = true
                            return true
                        }
                        while (elapsedMillis(startedAt) < timing.captureMillis) {
                            val safe = sample()
                            val count = record.read(buffer)
                            check(run)
                            if (count !in 0..buffer.size) throw Abort(MicrophoneDiagnosticReason.PLATFORM_FAILURE)
                            // Revalidate the policy/route after every public read before accepting its aggregates.
                            if (sample() && safe) stats.add(buffer, count)
                            buffer.fill(0)
                            Thread.sleep(5)
                        }
                        if (!sample()) throw Abort(MicrophoneDiagnosticReason.POLICY_UNKNOWN)
                    } finally {
                        buffer.fill(0);stats.clearPartial()
                        // Stop may fail, but release must still be attempted. Only its return acknowledges ownership.
                        if (record != null) {
                            runCatching { record.stop() }
                            try { record.release(); acknowledged = true }
                            catch (_: Throwable) { stop(run, MicrophoneDiagnosticReason.PLATFORM_FAILURE) }
                        }
                        synchronized(lock) { run.arm = null }
                        timer?.cancel(false)
                        val receipt = observed
                        arms.add(MicrophoneDiagnosticArm(ordinal, requested, receipt?.actual, preferred, accepted,
                            receipt?.routeMatchesNamedInput,
                            if (firstRoute == null || receipt?.routeToken == null) null else firstRoute == receipt.routeToken,
                            stats.snapshot(), receipt?.client, receipt?.device, routeStable))
                    }
                    if (!acknowledged) throw Abort(MicrophoneDiagnosticReason.PLATFORM_FAILURE)
                    check(run)
                    if (comparison != MicrophoneDiagnosticComparison.PREFERRED && arms.last().routeSameAsFirst == false)
                        throw Abort(MicrophoneDiagnosticReason.ROUTE_CHANGED)
                }
            }
        } catch (abort: Abort) { reason = abort.reason }
        catch (_: Throwable) { reason = MicrophoneDiagnosticReason.PLATFORM_FAILURE }
        finally {
            whole.cancel(false)
            run.reason.get().takeUnless { it == MicrophoneDiagnosticReason.NONE }?.let { reason = it }
            if (acknowledged) finish(run, MicrophoneDiagnosticResult(run.comparison, inventory, arms.toList(), reason))
            else stop(run, MicrophoneDiagnosticReason.PLATFORM_FAILURE)
        }
    }
    private fun finish(run: Run, result: MicrophoneDiagnosticResult) {
        val terminalState = synchronized(lock) {
            if (active !== run) return
            val terminalReason = run.reason.get()
            val completed = if (terminalReason == MicrophoneDiagnosticReason.NONE) result
                else result.copy(reason = terminalReason)
            run.lease.release()
            active = null
            state = MicrophoneDiagnosticState(MicrophoneDiagnosticPhase.COMPLETE, run.comparison,
                reason = completed.reason, result = completed)
            state
        }
        runCatching { beforeTerminalNotification?.invoke() }
        // A successor may already have replaced state; deliver this exact completion outside the lock.
        runCatching { onState(terminalState) }
    }
    private companion object {
        val deadlines = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "oal-mic-diagnostic-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        fun unavailableInventory() = MicrophoneDiagnosticInventory(MicrophoneDiagnosticMatch.UNAVAILABLE, emptyList())
        fun elapsedMillis(start: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        fun shape(comparison: MicrophoneDiagnosticComparison, ordinal: Int): MicrophoneDiagnosticShape {
            val channels = if (comparison == MicrophoneDiagnosticComparison.CHANNELS && ordinal == 1) 2 else 1
            return MicrophoneDiagnosticShape(
                if (comparison == MicrophoneDiagnosticComparison.SOURCES && ordinal == 1) 7 else 6,
                if (comparison == MicrophoneDiagnosticComparison.RATE && ordinal == 1) 48000 else 24000,
                channels, if (channels == 2) 12 else 16, 0)
        }
    }
}

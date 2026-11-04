package com.openautolink.app.cluster

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.Surface
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Process admission for the ONE existing ClusterManager. Parent must share this instance with
 * every projection admission, acquiring before beginMapOutput and retaining through true retirement.
 * Constructing this does not create/open another manager or detach an existing phone stream.
 */
class ClusterOutputOwnership(private val manager: ClusterManager) {
    private var current: Claim? = null
    fun tryAcquire(owner: Any): Claim? {
        val claim = synchronized(this) {
            if (current != null) return null
            Claim(owner).also { current = it }
        }
        val admitted = try { manager.reservePreviewAdmission(claim) } catch (failure: Exception) {
            synchronized(this) { if (current === claim) current = null }; throw failure
        }
        if (!admitted) { synchronized(this) { if (current === claim) current = null }; return null }
        return claim
    }
    inner class Claim internal constructor(val owner: Any) {
        private var lease: ClusterOutputLease? = null
        private var selection: ClusterOutputSelection? = null
        private var ending: CompletableFuture<Boolean>? = null
        private var beginning = false
        fun begin(epoch: Long, selection: ClusterOutputSelection, protocol: ClusterProjectionProtocol,
                  display: QualifiedClusterDisplay, bridge: ClusterHostOutputBridge): ClusterOutputLease? {
            synchronized(this@ClusterOutputOwnership) {
                if (current !== this || lease != null || ending != null || beginning) return null
                beginning = true
            }
            try {
                val admitted = manager.beginMapOutput(epoch, selection, protocol, display, bridge, admissionOwner = this)
                synchronized(this@ClusterOutputOwnership) { lease = admitted; this.selection = selection }
                return admitted
            } finally { synchronized(this@ClusterOutputOwnership) { beginning = false } }
        }
        /** No caller holds the ownership monitor across a manager operation or public completion. */
        fun abandon(): Boolean {
            synchronized(this@ClusterOutputOwnership) {
                if (current !== this || lease != null || beginning || ending != null) return false
                beginning = true
            }
            val success = try { manager.abandonAdmission(this) }
                finally { synchronized(this@ClusterOutputOwnership) { beginning = false } }
            synchronized(this@ClusterOutputOwnership) { if (success && current === this) current = null }
            return success
        }
        fun end(reason: String): CompletableFuture<Boolean> {
            val result: CompletableFuture<Boolean>
            val exact: ClusterOutputLease?
            val frozen: ClusterOutputSelection?
            synchronized(this@ClusterOutputOwnership) {
                if (current !== this) return completed(true)
                ending?.let { return it }
                if (beginning) return completed(false)
                exact = lease; frozen = selection
                result = CompletableFuture(); ending = result
            }
            fun finish(success: Boolean) {
                synchronized(this@ClusterOutputOwnership) {
                    if (success && current === this) current = null
                    if (ending === result) ending = null
                }
                result.complete(success)
            }
            try {
                val retirement = if (exact == null) completed(manager.abandonAdmission(this))
                    else manager.endMapOutput(exact, requireNotNull(frozen), reason)
                retirement.whenComplete { success, failure -> finish(failure == null && success == true) }
            } catch (_: Exception) { finish(false) }
            return result
        }
    }
}

/** Explicit host-only grid; never generates phone frames, a Trip, or navigation guidance.
 * Deadline and stop authority outlive UI and use elapsed realtime, not Activity lifecycle timers.
 */
class ClusterCalibrationPreview internal constructor(
    private val backend: Backend,
    private val now: () -> Long,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val beforeOwnerPublication: (Any) -> Unit = {},
) {
    constructor(ownership: ClusterOutputOwnership) : this(ManagerBackend(ownership), SystemClock::elapsedRealtime,
        { delay, action -> watchdog.schedule(action, delay, TimeUnit.MILLISECONDS).let { future -> { future.cancel(false); Unit } } })

    internal interface Session { fun stop(): CompletableFuture<Boolean> }
    internal fun interface Backend {
        fun begin(owner: Any, display: QualifiedClusterDisplay, output: PixelRect, safe: PixelRect, deadline: Long): Session?
    }
    private class Run(val owner: Any, val deadline: Long, val session: Session) {
        var cancel: (() -> Unit)? = null
        var stopping: CompletableFuture<Boolean>? = null
        var stopped = false
    }
    private var run: Run? = null
    // Owner identity alone is not a CAS token: callers may reuse it, and null has an ABA race.
    private class OwnerPublication(val revision: Long, val owner: Any?)
    private var publication = OwnerPublication(0L, null)
    private val active = MutableStateFlow(publication)
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    val owner: StateFlow<Any?> = object : StateFlow<Any?> {
        override val value: Any? get() = active.value.owner
        override val replayCache: List<Any?> get() = listOf(value)
        @OptIn(InternalCoroutinesApi::class)
        override suspend fun collect(collector: FlowCollector<Any?>): Nothing {
            active.map { it.owner }.distinctUntilChanged().collect(collector)
            error("Preview owner StateFlow completed")
        }
    }
    val activeOwner: Any? get() = owner.value

    /** Snapshot the exact run revision under the monitor, but notify observers only outside it.
     * A delayed publisher can help publish the latest revision, never overwrite a newer one.
     */
    private fun publishOwner() {
        while (true) {
            val (observed, desired) = synchronized(this) {
                val observed = active.value
                if (observed.revision >= publication.revision) return
                observed to publication
            }
            active.compareAndSet(observed, desired)
        }
    }

    private var starting = false
    fun start(display: QualifiedClusterDisplay, output: PixelRect, safe: PixelRect,
              seconds: Int, owner: Any = Any()): Any {
        require(seconds > 0)
        val canvas = requireNotNull(display.host.visible.intersect(display.host.stable))
        require(canvas.contains(output) && output.contains(safe))
        synchronized(this) {
            check(run == null && !starting) { "Preview unavailable: prior output retirement pending" }
            starting = true
        }
        val deadline = now() + seconds.coerceAtMost(15) * 1000L
        val next = try {
            val session = backend.begin(owner, display, output, safe, deadline)
                ?: error("Preview unavailable: cluster output is owned by a projection or host is unqualified")
            Run(owner, deadline, session).also { synchronized(this) {
                run = it; starting = false
                publication = OwnerPublication(publication.revision + 1, owner)
            } }
        } catch (failure: Exception) { synchronized(this) { starting = false }; throw failure }
        beforeOwnerPublication(owner)
        publishOwner()
        try { arm(next) } catch (failure: Exception) { stop(owner); throw failure }
        return owner
    }
    @Synchronized fun isActive(owner: Any): Boolean = run?.let { it.owner === owner && !it.stopped && now() < it.deadline } == true
    private fun arm(exact: Run) {
        val remaining = synchronized(this) {
            if (run !== exact || exact.stopped) return
            exact.deadline - now()
        }
        if (remaining <= 0) { stop(exact.owner); return }
        val cancel = schedule(remaining) {
            val expired = synchronized(this) { run === exact && !exact.stopped && now() >= exact.deadline }
            if (expired) stop(exact.owner) else arm(exact)
        }
        val discard = synchronized(this) {
            if (run !== exact || exact.stopped) true else { exact.cancel = cancel; false }
        }
        if (discard) cancel()
    }
    fun stop(owner: Any): CompletableFuture<Boolean> {
        val exact: Run
        val result: CompletableFuture<Boolean>
        val cancel: (() -> Unit)?
        synchronized(this) {
            exact = run?.takeIf { it.owner === owner } ?: return completed(true)
            exact.stopping?.let { return it }
            exact.stopped = true
            publication = OwnerPublication(publication.revision + 1, null)
            cancel = exact.cancel; exact.cancel = null
            result = CompletableFuture()
            exact.stopping = result
        }
        // Both StateFlow observers and immediate CompletableFuture callbacks are external code.
        publishOwner()
        fun finish(success: Boolean) {
            synchronized(this) {
                if (success && run === exact) run = null
                if (exact.stopping === result) exact.stopping = null
            }
            result.complete(success)
        }
        try {
            cancel?.invoke()
            exact.session.stop().whenComplete { success, failure -> finish(success == true && failure == null) }
        } catch (_: Exception) { finish(false) }
        return result
    }
    private class ManagerBackend(private val ownership: ClusterOutputOwnership) : Backend {
        override fun begin(owner: Any, display: QualifiedClusterDisplay, output: PixelRect, safe: PixelRect, deadline: Long): Session? {
            val claim = ownership.tryAcquire(owner) ?: return null
            val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "cluster-grid").apply { isDaemon = true } }
            val bridge = ClusterCalibrationGridBridge(display, output, safe, deadline, SystemClock::elapsedRealtime, worker)
            val lease = try {
                claim.begin(-epochs.incrementAndGet(), ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO,
                    androidAuto = true, carPlay = false, nativeIconsWithMap = false),
                    ClusterProjectionProtocol.ANDROID_AUTO, display, bridge)
            } catch (failure: Exception) { claim.abandon(); worker.shutdown(); throw failure }
            if (lease == null) { claim.abandon(); worker.shutdown(); return null }
            bridge.bind(lease)
            return object : Session {
                override fun stop(): CompletableFuture<Boolean> {
                    bridge.invalidate()
                    return claim.end("calibration preview stopped").thenApply { success ->
                        if (success) worker.shutdown()
                        success
                    }
                }
            }
        }
    }
    companion object {
        /**
         * Advisory projection-idle check plus the very same atomic admission used automatically by
         * every legacy ClusterManager. Construction never opens a binding generation. A real phone
         * arriving during a grid may decline optional cluster output; main projection never waits.
         */
        fun forProduction(context: android.content.Context, projectionIdle: () -> Boolean): ClusterCalibrationPreview {
            val backend by lazy { ManagerBackend(ClusterOutputOwnership(ClusterManager(context.applicationContext))) }
            return ClusterCalibrationPreview(Backend { owner, display, output, safe, deadline ->
                if (!projectionIdle() || display.profile != ClusterDisplayProfiles.AEGEAN_ORANGE_239) null
                else backend.begin(owner, display, output, safe, deadline)
            }, SystemClock::elapsedRealtime, { delay, action ->
                watchdog.schedule(action, delay, TimeUnit.MILLISECONDS).let { future -> { future.cancel(false); Unit } }
            })
        }
        private val epochs = AtomicLong()
        private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "cluster-grid-deadline").apply { isDaemon = true }
        }
    }
}

/** Own preview session's borrowed host surface only; never given a live video surface.
 * CPU Canvas stays exclusively inside this separate host session. Parent must not repurpose the
 * preview Surface for EGL: manager end must retire the borrowed output and invalidate its exact
 * session before the shared claim can be reused; host Surface destruction is separately host-owned.
 * Queue bound excludes ONE reserved terminal clear; no callback thread waits for Canvas.
 */
internal class ClusterCalibrationGridBridge(
    private val display: QualifiedClusterDisplay,
    private val output: PixelRect,
    private val safe: PixelRect,
    private val deadline: Long,
    private val now: () -> Long,
    private val worker: Executor,
    private val paint: (Surface, Boolean) -> Boolean = { surface, clear -> paintSurface(surface, display, output, safe, clear) },
) : ClusterHostOutputBridge {
    private var lease: ClusterOutputLease? = null
    private var valid = true
    private var revision = 0L
    private var borrowed: Surface? = null
    private var queued = 0
    private var cleanup: CompletableFuture<Boolean>? = null
    @Synchronized fun bind(exact: ClusterOutputLease) { check(lease == null); lease = exact }
    @Synchronized fun invalidate() { valid = false; revision++ }
    override fun attach(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry): CompletableFuture<Boolean> = synchronized(this) {
        if (!valid || this.lease != lease || now() >= deadline || borrowed != null || !matches(geometry) || queued >= 8) return@synchronized completed(false)
        borrowed = surface // retain even after partial paint/failure until explicit clear ack
        val generation = ++revision
        enqueue {
            val accepted = synchronized(this) { valid && revision == generation && now() < deadline && borrowed === surface }
            accepted && paint(surface, false)
        }
    }
    override fun updateBounds(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry): CompletableFuture<Boolean> = synchronized(this) {
        if (!valid || this.lease != lease || borrowed !== surface || now() >= deadline || !matches(geometry) || queued >= 8 || cleanup != null) return@synchronized completed(false)
        val generation = ++revision
        enqueue {
            synchronized(this) { valid && revision == generation && now() < deadline && borrowed === surface } && paint(surface, false)
        }
    }
    override fun detach(lease: ClusterOutputLease, surface: Surface): CompletableFuture<Boolean> = synchronized(this) {
        if (this.lease != lease || (borrowed != null && borrowed !== surface)) return@synchronized completed(false)
        cleanup?.let { return@synchronized it }
        revision++
        if (borrowed == null) return@synchronized completed(true)
        val result = enqueue {
            val success = paint(surface, true)
            if (success) synchronized(this) { if (borrowed === surface) borrowed = null }
            success
        }
        cleanup = result
        result.whenComplete { _, _ -> synchronized(this) { if (cleanup === result) cleanup = null } }
        result
    }
    private fun matches(host: ClusterHostGeometry): Boolean = host.displayId == display.displayId &&
        host.surfaceSize == display.profile.panel && host.visible.contains(output) && host.stable.contains(output) &&
        output.contains(safe)
    private fun enqueue(action: () -> Boolean): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>(); queued++
        try { worker.execute {
            val success = try { action() } catch (_: Exception) { false }
            synchronized(this) { queued-- }
            result.complete(success)
        } } catch (_: Exception) { queued--; result.complete(false) }
        return result
    }
}

private fun paintSurface(surface: Surface, display: QualifiedClusterDisplay, output: PixelRect, safe: PixelRect, clear: Boolean): Boolean {
    if (!surface.isValid) return false
    val canvas = surface.lockCanvas(null) ?: return false
    var valid = false
    try {
        canvas.drawColor(Color.BLACK) // opaque including the unused panel and old map pixels
        if (!clear) {
            if (canvas.width != display.profile.panel.width || canvas.height != display.profile.panel.height) return false
            drawClusterCalibrationGrid(canvas, output, safe)
        }
        valid = true
    } finally { surface.unlockCanvasAndPost(canvas) }
    return valid
}

internal fun drawClusterCalibrationGrid(canvas: Canvas, output: PixelRect, safe: PixelRect) {
    val p = Paint().apply { color = Color.rgb(45, 80, 110); strokeWidth = 2f }
    val save = canvas.save()
    try {
        canvas.clipRect(output.left, output.top, output.right, output.bottom)
        for (i in 0..10) {
            val x = output.left + output.width * i / 10f
            val y = output.top + output.height * i / 10f
            canvas.drawLine(x, output.top.toFloat(), x, output.bottom.toFloat(), p)
            canvas.drawLine(output.left.toFloat(), y, output.right.toFloat(), y, p)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = Color.BLUE
        canvas.drawRect(output.left + 2f, output.top + 2f, output.right - 2f, output.bottom - 2f, p)
        p.color = Color.GREEN
        canvas.drawRect(safe.left + 2f, safe.top + 2f, safe.right - 2f, safe.bottom - 2f, p)
        p.style = Paint.Style.FILL; p.textSize = 24f; p.color = Color.WHITE
        canvas.drawText("OUTPUT ${output.width}x${output.height} @ ${output.left},${output.top}", output.left + 12f, output.top + 30f, p)
        canvas.drawText("SAFE ${safe.width}x${safe.height} @ ${safe.left},${safe.top}", safe.left + 12f, safe.top + 30f, p)
        canvas.drawText("LOCAL CALIBRATION GRID - NOT PHONE NAVIGATION", safe.left + 12f, safe.bottom - 20f, p)
    } finally { canvas.restoreToCount(save) }
}

private fun completed(value: Boolean): CompletableFuture<Boolean> = CompletableFuture.completedFuture(value)

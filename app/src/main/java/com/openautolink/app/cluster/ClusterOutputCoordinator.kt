package com.openautolink.app.cluster

import android.view.Surface
import java.util.concurrent.CompletableFuture

/** Optional stricter identity receipt; the qualified GM predicate does not require EDID/name. */
data class ClusterDriverDisplayReceipt(
    val vehicle: ClusterVehicleIdentity,
    val name: String? = null,
    val manufacturerPnpId: String? = null,
    val productId: String? = null,
    val source: String,
) {
    init { require(source.isNotBlank()) }
    internal fun resolve(context: android.content.Context, qualified: QualifiedClusterDisplay): android.view.Display? {
        if (qualified.profile != ClusterDisplayProfiles.AEGEAN_ORANGE_239 ||
            vehicle.product != qualified.profile.vehicle.product) return null
        return resolveCandidate(context, qualified.displayId, "receipt")
    }

    /** The retained GM default requires ONE compatible physical target, never first-by-size.
     * Firmware and optional builtin/EDID diagnostic representations do not establish that role. */
    @Suppress("DEPRECATION")
    internal fun resolveCandidate(context: android.content.Context, expectedId: Int? = null,
        stage: String = "initial"): android.view.Display? = resolvePhysicalCandidate(context, expectedId, stage, true)

    /** Waiting identity only: OFF never becomes a host/admission receipt. */
    internal fun resolveReadinessCandidate(context: android.content.Context): android.view.Display? =
        resolvePhysicalCandidate(context, null, "readiness_identity", false)

    private fun resolvePhysicalCandidate(context: android.content.Context, expectedId: Int?,
        stage: String, requireOn: Boolean): android.view.Display? {
        val attempt = ClusterAdmissionDiagnostics.nextAttempt()
        fun rejected(reason: String): android.view.Display? {
            ClusterAdmissionDiagnostics.outcome(stage, attempt, expectedId, reason)
            return null
        }
        if (android.os.Build.PRODUCT != vehicle.product) return rejected("unknown_vehicle")
        val manager = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?: return rejected("display_service_unavailable")
        try {
            val displays = manager.displays.filter { it.displayId != android.view.Display.DEFAULT_DISPLAY }
            if (expectedId != null && displays.none { it.displayId == expectedId })
                return rejected("selected_display_unavailable")
            val panel = ClusterDisplayProfiles.AEGEAN_ORANGE_239.panel
            val matches = displays.filter { display ->
                val point = android.graphics.Point().also { display.getRealSize(it) }
                point.x == panel.width && point.y == panel.height
            }
            // Count compatible targets even when one is OFF: never substitute a passenger
            // candidate merely because the previously compatible driver panel is unavailable.
            if (matches.size > 1) return rejected("ambiguous_target")
            val display = matches.singleOrNull() ?: return rejected(
                if (displays.isEmpty()) "no_target" else "invalid_geometry")
            if (expectedId != null && display.displayId != expectedId) return rejected("selected_display_unavailable")
            if (!display.isValid || (requireOn && display.state != android.view.Display.STATE_ON))
                return rejected("selected_display_unavailable")
            if (display.flags and android.view.Display.FLAG_PRIVATE != 0) return rejected("private_display")
            // Optional explicit identity constraints remain strict, but the production GM
            // default has none and does not read EDID, name or Display.toString at all.
            if (name != null && display.name != name) return rejected("explicit_identity_mismatch")
            if (manufacturerPnpId != null && display.deviceProductInfo?.manufacturerPnpId != manufacturerPnpId)
                return rejected("explicit_identity_mismatch")
            if (productId != null && display.deviceProductInfo?.productId != productId)
                return rejected("explicit_identity_mismatch")
            ClusterAdmissionDiagnostics.outcome(stage, attempt, display.displayId, "none")
            return display
        } catch (_: RuntimeException) { return rejected("display_facts_unavailable") }
    }
    companion object {
        val QUALIFIED_GM = ClusterDriverDisplayReceipt(ClusterDisplayProfiles.AEGEAN_ORANGE_239.vehicle,
            source = "Mossy hosted TestA: known vehicle, unique nondefault public driver2000x810; " +
                "firmware identifies geometry provenance, not eligibility")
    }
}

/** Bounded identifiers/reasons only; INFO/WARN reaches the existing persistent diagnostic sink. */
internal object ClusterAdmissionDiagnostics {
    private val attempts = java.util.concurrent.atomic.AtomicLong()
    fun nextAttempt(): Long = attempts.incrementAndGet()
    fun outcome(stage: String, attempt: Long, output: Int?, reason: String, acceptedResult: String = "candidate") {
        val message = "admission stage=$stage attempt=$attempt output=${output ?: "unselected"} " +
            "provenance=known_vehicle_unique_public_panel result=${if (reason == "none") acceptedResult else "rejected"} reason=$reason"
        try {
            if (reason == "none") com.openautolink.app.diagnostics.DiagnosticLog.i("cluster", message)
            else com.openautolink.app.diagnostics.DiagnosticLog.w("cluster", message)
        } catch (_: RuntimeException) { /* Observability cannot change admission or cleanup. */ }
    }
}

data class ClusterOutputLease(val projectionEpoch: Long, val outputEpoch: Long)
data class ClusterMapOutputSnapshot(
    val lease: ClusterOutputLease,
    val selection: ClusterOutputSelection,
    val protocol: ClusterProjectionProtocol,
    val display: QualifiedClusterDisplay,
) {
    init { require(ClusterOutputPolicy.shouldOfferMapVideo(selection, protocol)) }
}

/** Nonblocking admissions on Main. Futures are exact acknowledgements, never wait/get/join on
 * Main. A false/failed/pending detach retains the borrowed Surface and fences all successors.
 * Implementations enqueue renderer work and never release the host-owned Surface themselves. */
interface ClusterHostOutputBridge {
    fun attach(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry): CompletableFuture<Boolean>
    fun updateBounds(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry): CompletableFuture<Boolean>
    fun detach(lease: ClusterOutputLease, surface: Surface): CompletableFuture<Boolean>
}

/** Serial asynchronous output handoff. No GPU wait and no watchdog on the AndroidX callback thread. */
class ClusterOutputCoordinator(snapshot: ClusterMapOutputSnapshot, bridge: ClusterHostOutputBridge) {
    @Volatile var snapshot: ClusterMapOutputSnapshot = snapshot
        private set
    private var bridge: ClusterHostOutputBridge = bridge
    private var reconnectPaused = false
    private val measured = kotlinx.coroutines.flow.MutableStateFlow<ClusterHostGeometry?>(null)
    internal val observedHost: kotlinx.coroutines.flow.StateFlow<ClusterHostGeometry?> = measured
    internal fun observeHost(owner: Any, host: ClusterHostGeometry?) {
        if (synchronized(this) { sessionOwner === owner && !retired }) measured.value = host
    }
    @Synchronized internal fun liveHostSession(): ClusterMapSession? =
        if (!retired && !releaseRequested) sessionOwner as? ClusterMapSession else null

    /** Retire only the old GPU attachment, retaining exact Session/physical host authority. */
    internal fun pauseForReconnect(lease: ClusterOutputLease): CompletableFuture<Boolean> = mutate {
        if (snapshot.lease != lease || retired || reconnectPaused || releaseRequested || !attached)
            return@mutate completed(false)
        reconnectPaused = true; attached = false; revision++
        requestCleanup()
    }
    internal fun rebindRetainedHost(previous: ClusterOutputLease, next: ClusterMapOutputSnapshot,
        replacement: ClusterHostOutputBridge, owner: ClusterMapSession): Boolean = synchronized(this) {
        if (!reconnectPaused || retired || cleanup != null || borrowed != null || sessionOwner !== owner ||
            snapshot.lease != previous || next.lease.outputEpoch != previous.outputEpoch ||
            next.selection != snapshot.selection || next.protocol != snapshot.protocol) return@synchronized false
        snapshot = next; bridge = replacement; desired = null; reconnectPaused = false; revision++
        true
    }
    private var retired = false
    private var borrowed: Surface? = null
    private var desired: Surface? = null
    private var attached = false
    private var sessionOwner: Any? = null
    private var revision = 0L
    private var queuedOperations = 0
    private var tail = completed(true)
    private var cleanup: CompletableFuture<Boolean>? = null
    // Geometry rejection revokes desired before the Session asks to detach that exact host.
    // Retain its real cleanup receipt (including false), even if it completes before detachHost.
    private class RejectedHostCleanup(
        val owner: Any,
        val surface: Surface,
        val acknowledgement: CompletableFuture<Boolean>,
    )
    private var rejectedHostCleanup: RejectedHostCleanup? = null
    private var releaseRequested = false

    @Synchronized internal fun claim(owner: Any): Boolean {
        if (retired || releaseRequested || cleanup != null || (sessionOwner != null && sessionOwner !== owner)) return false
        sessionOwner = owner
        return true
    }
    fun attachHost(lease: ClusterOutputLease, owner: Any, candidate: Surface, host: ClusterHostGeometry): CompletableFuture<Boolean> {
        val attempt = ClusterAdmissionDiagnostics.nextAttempt()
        return mutate { attachHostLocked(lease, owner, candidate, host) }.whenComplete { success, _ ->
            // Observe the actual bridge acknowledgement, outside lifecycle/coordinator monitors.
            // A reserved or rejected attach is never reported as ready.
            val acknowledgement = synchronized(this) {
                when {
                    !current(lease, owner) -> "stale_owner"
                    cleanup != null -> "cleanup_pending"
                    success == true && attached && borrowed === candidate && desired === candidate -> "none"
                    else -> "host_not_attached"
                }
            }
            val reason = when {
                host.displayId != snapshot.display.displayId -> "wrong_physical_display"
                host.surfaceSize != snapshot.display.host.surfaceSize -> "invalid_host_geometry"
                else -> acknowledgement
            }
            ClusterAdmissionDiagnostics.outcome("host_attach", attempt, host.displayId, reason, "attached")
        }
    }
    private fun attachHostLocked(lease: ClusterOutputLease, owner: Any, candidate: Surface, host: ClusterHostGeometry): CompletableFuture<Boolean> {
        if (!current(lease, owner) || reconnectPaused) { traceHost("stale_owner", host); return completed(false) }
        if (cleanup != null) { traceHost("cleanup_pending", host); return completed(false) }
        val qualified = ClusterDisplayProfiles.qualify(snapshot.display.profile.vehicle,
            listOf(ClusterDisplayBinding(snapshot.display.profile.role, snapshot.display.displayId)), host,
            validatedLayout = snapshot.display.validatedLayout,
            frozenAutomatic = snapshot.display.takeIf { it.validatedLayout == null })
        if (qualified == null || qualified.effectiveOutput != snapshot.display.effectiveOutput ||
            qualified.safe != snapshot.display.safe || !candidate.isValid) {
            traceHost(when {
                host.displayId != snapshot.display.displayId -> "wrong_physical_display"
                host.surfaceSize != snapshot.display.host.surfaceSize -> "surface_size_changed"
                snapshot.display.validatedLayout?.let { it.host != host } == true -> "custom_host_changed"
                host.backgroundCanvas(snapshot.display.profile) == null -> "visible_no_background"
                host.essentialCanvas?.contains(snapshot.display.safe) != true -> "essential_outside_stable"
                host.backgroundCanvas(snapshot.display.profile)?.contains(snapshot.display.profile.safe) == false -> "automatic_safe_outside_output"
                qualified == null -> "qualification_rejected"
                qualified.effectiveOutput != snapshot.display.effectiveOutput -> "effective_output_changed"
                qualified.safe != snapshot.display.safe -> "safe_changed"
                else -> "surface_invalid"
            }, host)
            desired = null; attached = false; revision++
            val pending = requestCleanup()
            rejectedHostCleanup = RejectedHostCleanup(owner, candidate, pending)
            return pending.thenApply { false }
        }
        rejectedHostCleanup = null
        desired = candidate
        val request = ++revision
        traceHost("attach_submitted", host)
        attached = false
        return enqueue {
            val updating = synchronized(this) {
                if (!current(lease, owner) || request != revision) {
                    traceHost("superseded_before_submit", host); return@enqueue completed(false)
                }
                borrowed === candidate
            }
            if (updating) {
                return@enqueue call { bridge.updateBounds(lease, candidate, host) }.thenCompose { success ->
                    traceHost(if (success) "bridge_update_ack" else "bridge_update_rejected", host)
                    synchronized(this) { attached = success && current(lease, owner) && request == revision }
                    if (success) completed(true) else detachBorrowed().thenApply { false }
                }
            }
            detachBorrowed().thenCompose { detached ->
                synchronized(this) {
                    if (!detached || !current(lease, owner) || request != revision) {
                        traceHost(if (!detached) "predecessor_detach_failed" else "superseded_after_detach", host)
                        return@thenCompose completed(false)
                    }
                    borrowed = candidate // retain even after a partial/failed attach
                }
                call { bridge.attach(lease, candidate, host) }.thenCompose { success ->
                    traceHost(if (success) "bridge_attach_ack" else "bridge_attach_rejected", host)
                    synchronized(this) { attached = success && current(lease, owner) && request == revision }
                    if (success) completed(true) else detachBorrowed().thenApply { false }
                }
            }
        }
    }
    private fun traceHost(reason: String, host: ClusterHostGeometry) {
        val prefix = "OPTIONAL_HOST_V1 session=${snapshot.lease.projectionEpoch} output=${snapshot.lease.outputEpoch} revision=$revision"
        runCatching { com.openautolink.app.diagnostics.DiagnosticLog.i("cluster", "$prefix reason=$reason physical=${host.displayId} panel=${host.surfaceSize}") }
        runCatching { com.openautolink.app.diagnostics.DiagnosticLog.i("cluster", "$prefix visible=${host.visible} stable=${host.stable} frozenOutput=${snapshot.display.effectiveOutput} frozenSafe=${snapshot.display.safe}") }
    }
    fun updateBounds(lease: ClusterOutputLease, owner: Any, exactSurface: Surface, host: ClusterHostGeometry): CompletableFuture<Boolean> =
        mutate {
            if (desired !== exactSurface) completed(false) else attachHostLocked(lease, owner, exactSurface, host)
        }
    fun detachHost(lease: ClusterOutputLease, owner: Any, exactSurface: Surface): CompletableFuture<Boolean> = mutate {
        if (!current(lease, owner)) return@mutate completed(false)
        if (desired !== exactSurface) {
            val rejected = rejectedHostCleanup
            return@mutate if (desired == null && rejected?.owner === owner && rejected.surface === exactSurface) {
                rejected.acknowledgement
            } else completed(false)
        }
        desired = null; attached = false; revision++
        requestCleanup()
    }
    internal fun releaseHost(owner: Any): CompletableFuture<Boolean> = mutate {
        if (sessionOwner !== owner) return@mutate completed(true)
        desired = null; attached = false; revision++
        releaseRequested = true
        requestCleanup()
    }
    fun retire(lease: ClusterOutputLease, reason: String): CompletableFuture<Boolean> = mutate {
        if (snapshot.lease != lease) return@mutate completed(false)
        retired = true; desired = null; attached = false; revision++
        releaseRequested = true
        requestCleanup()
    }
    @Synchronized fun isHostAttached(): Boolean = !retired && attached
    @Synchronized internal fun isLive(): Boolean = !retired
    @Synchronized internal fun isRetirementAcknowledged(): Boolean = retired && borrowed == null
    private fun current(lease: ClusterOutputLease, owner: Any): Boolean = !retired && !releaseRequested && snapshot.lease == lease && sessionOwner === owner
    /** One reserved terminal slot, shared by every pending stop/release. Ordinary controls
     * cannot consume it or enqueue across it. A negative acknowledgement retains the borrowed
     * Surface and release authority so an explicit retry can discharge the same cleanup debt. */
    @Synchronized private fun requestCleanup(): CompletableFuture<Boolean> {
        cleanup?.let { return it }
        val result = CompletableFuture<Boolean>()
        cleanup = result
        enqueue(terminal = true) { detachBorrowed() }.whenComplete { success, _ ->
            synchronized(this) {
                if (success == true && releaseRequested) {
                    sessionOwner = null
                    releaseRequested = false
                }
                cleanup = null
            }
            result.complete(success == true)
        }
        return result
    }
    private fun detachBorrowed(): CompletableFuture<Boolean> {
        val old = synchronized(this) { borrowed } ?: return completed(true)
        return call { bridge.detach(snapshot.lease, old) }.thenApply { success ->
            synchronized(this) { if (success && borrowed === old) borrowed = null }
            success
        }
    }
    // Reserve queue order under the monitor, but subscribe/start only after leaving it.
    // Even an already-completed or reentrant bridge must not run an external continuation
    // beneath this monitor: lifecycle entrypoints acquire lifecycle -> coordinator.
    private val starts = ArrayDeque<() -> Unit>()
    private fun <T> mutate(action: () -> T): T {
        try { return synchronized(this) { action() } }
        finally {
            while (true) {
                val start = synchronized(this) { starts.removeFirstOrNull() } ?: break
                start()
            }
        }
    }
    /** Invocation is nonblocking. Queue futures rather than blocking any worker or Main. */
    private fun enqueue(terminal: Boolean = false, action: () -> CompletableFuture<Boolean>): CompletableFuture<Boolean> {
        // A stalled GPU cannot turn host bounds chatter into an unbounded Future chain.
        if (!terminal && (cleanup != null || queuedOperations >= 8)) return completed(false)
        queuedOperations++
        val predecessor = tail
        val next = CompletableFuture<Boolean>()
        tail = next
        starts.addLast {
            predecessor.whenComplete { _, _ ->
                call(action).whenComplete { success, _ ->
                    synchronized(this) { queuedOperations-- }
                    next.complete(success == true)
                }
            }
        }
        return next
    }
    private fun call(action: () -> CompletableFuture<Boolean>): CompletableFuture<Boolean> =
        try { action().handle { value, failure -> failure == null && value == true } }
        catch (_: Exception) { completed(false) }
    private fun completed(value: Boolean) = CompletableFuture.completedFuture(value)
}

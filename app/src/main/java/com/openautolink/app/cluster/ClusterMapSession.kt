package com.openautolink.app.cluster

import android.content.Intent
import android.graphics.Rect
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.Screen
import androidx.car.app.SessionInfo
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Template

/** Reproduce the public TestA route: an Activity already on the qualified driver display
 * starts the AndroidX host there. Never launch from an unqualified center Activity. */
class ClusterMapLaunchActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        synchronized(ClusterBindingLifecycle.lock) {
            val binding = ClusterBindingState.frozenMapBinding()
            val generation = intent.getLongExtra(CLUSTER_BINDING_GENERATION_EXTRA, Long.MIN_VALUE)
            if (binding == null || binding.generation != generation || !binding.coordinator.isLive() ||
                display?.displayId != binding.snapshot.display.displayId ||
                binding.receipt?.resolve(this, binding.snapshot.display)?.displayId != display?.displayId) {
                finish()
                return
            }
            val host = Intent().apply {
                setClassName(this@ClusterMapLaunchActivity, "androidx.car.app.activity.CarAppActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                putExtra(CLUSTER_BINDING_GENERATION_EXTRA, generation)
            }
            try {
                val am = getSystemService(android.app.ActivityManager::class.java)
                if (am.isActivityStartAllowedOnDisplay(this, binding.snapshot.display.displayId, host)) {
                    startActivity(host, android.app.ActivityOptions.makeBasic()
                        .setLaunchDisplayId(binding.snapshot.display.displayId).toBundle())
                }
            } catch (_: Exception) {
                com.openautolink.app.diagnostics.DiagnosticLog.w("cluster", "Map host inherited-display launch failed; main continues")
            } finally { finish() }
        }
    }
}

/** MAIN is a logical host role; the actual Android display is checked independently. */
class ClusterMapSession internal constructor(
    initialSnapshot: ClusterMapOutputSnapshot,
    private val coordinator: ClusterOutputCoordinator,
    val logicalDisplayType: Int,
) : ClusterMainSession(
    nativeGuidance = initialSnapshot.selection.nativeIconsWithMap,
    hideBootstrap = false,
) {
    companion object {
        fun invalidateBindingGeneration(generation: Long, reason: String) =
            ClusterMainSession.invalidateBindingGeneration(generation, reason)
    }
    private val snapshot get() = coordinator.snapshot
    internal data class LiveHost(val surface: Surface, val geometry: ClusterHostGeometry)
    private var retainedHost: LiveHost? = null
    private var retainedInvalidation: (() -> Unit)? = null
    internal fun readLiveHost(): LiveHost? = synchronized(ClusterBindingLifecycle.lock) {
        if (!current() || surfaceCleanup != null) return@synchronized null
        val exact = surface?.takeIf { it.isValid } ?: return@synchronized null
        val placement = physicalHost?.takeIf { ClusterPhysicalHostState.isCurrent(it) } ?: return@synchronized null
        LiveHost(exact, ClusterHostGeometry(placement.displayId, size ?: return@synchronized null,
            visible ?: return@synchronized null, stable ?: return@synchronized null))
    }
    internal fun retainLiveHost(exact: LiveHost, invalidate: () -> Unit): Boolean = synchronized(ClusterBindingLifecycle.lock) {
        if (retainedHost != null || readLiveHost() != exact) return@synchronized false
        retainedHost = exact; retainedInvalidation = invalidate; true
    }
    internal fun clearRetainedHost() = synchronized(ClusterBindingLifecycle.lock) {
        retainedHost = null; retainedInvalidation = null
    }
    private fun validateRetainedHost() {
        val old = retainedHost ?: return
        val now = readLiveHost()
        if (now == null || now.surface !== old.surface || now.geometry.displayId != old.geometry.displayId ||
            now.geometry.surfaceSize != old.geometry.surfaceSize || now.geometry.stable != old.geometry.stable ||
            now.geometry.essentialCanvas != old.geometry.essentialCanvas) {
            val invalidate = retainedInvalidation
            retainedHost = null; retainedInvalidation = null
            invalidate?.let { effects.addLast(it) }
        }
    }
    internal fun refreshRetainedHost(exact: LiveHost): java.util.concurrent.CompletableFuture<Boolean> {
        if (readLiveHost() != exact) return java.util.concurrent.CompletableFuture.completedFuture(false)
        return coordinator.attachHost(snapshot.lease, this, exact.surface, exact.geometry).thenApply { attached ->
            synchronized(ClusterBindingLifecycle.lock) {
                if (attached && current() && readLiveHost() == exact) lease?.let { ClusterBindingState.markReady(it) }
            }
            attached
        }
    }
    private var lease: ClusterBindingRegistry.SessionLease? = null
    private var retired = false
    private var physicalHost: ClusterPhysicalHostState.Receipt? = null
    private var surface: Surface? = null
    private var size: PixelSize? = null
    private var visible: PixelRect? = null
    private var stable: PixelRect? = null
    private var hostCallbacks: AppManager? = null
    // Only an effect admitted for coordinator submission owns a cleanup obligation.
    // A reserved publication can be superseded before its effect ever runs.
    private var submittedSurface: Surface? = null
    private var revision = 0L
    private var lastGeometryDiagnostic: String? = null
    private fun traceGeometry(callback: String) {
        val fields = "physical=${physicalHost?.displayId ?: -1} context=${runCatching { carContext.display.displayId }.getOrNull()} surfaceValid=${surface?.isValid == true} size=$size visible=$visible stable=$stable"
        if (fields == lastGeometryDiagnostic) return
        lastGeometryDiagnostic = fields
        runCatching { com.openautolink.app.diagnostics.DiagnosticLog.i("cluster",
            "OPTIONAL_SURFACE_V1 session=${snapshot.lease.projectionEpoch} output=${snapshot.lease.outputEpoch} revision=$revision callback=$callback $fields") }
    }
    // Reserve exact receipts before invoking external code, including immediately completed futures.
    private var surfaceCleanup: java.util.concurrent.CompletableFuture<Boolean>? = null
    private val effects = ArrayDeque<() -> Unit>()
    private var draining = false

    // Observability only: AndroidX lifecycle, exact borrowed Surface and public window
    // visibility are separate boundaries. None establishes OEM-composited visibility.
    private val visibilityHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val visibilitySample = object : Runnable {
        override fun run() = mutate {
            if (!current()) return@mutate
            traceVisibility("sample")
            visibilityHandler.postDelayed(this, 5000L)
        }
    }
    private fun traceVisibility(event: String, callbackSurface: Surface? = null, accepted: Boolean = current()) {
        val activity = (physicalHost ?: ClusterPhysicalHostState.current(snapshot.lease.outputEpoch))?.activity?.get()
        // Never traverse Window/decor on the preparation/attach path. The sampled
        // receipt reads these public OS facts only after startup, on Main.
        val decor = if (event == "sample") runCatching { activity?.window?.decorView }.getOrNull() else null
        val fields = "OPTIONAL_VISIBILITY_V1 session=${snapshot.lease.projectionEpoch} output=${snapshot.lease.outputEpoch} " +
            "revision=$revision event=$event elapsedMs=${android.os.SystemClock.elapsedRealtime()} " +
            "lifecycle=${lifecycle.currentState} accepted=$accepted retired=$retired " +
            "surface=${surface?.let { System.identityHashCode(it) } ?: 0} " +
            "callbackSurface=${callbackSurface?.let { System.identityHashCode(it) } ?: 0} " +
            "surfaceValid=${surface?.isValid == true} cleanupPending=${surfaceCleanup != null} " +
            "gpuAttached=${coordinator.isHostAttached()} physical=${activity?.display?.displayId ?: -1} " +
            "activity=${activity?.let { System.identityHashCode(it) } ?: 0} finishing=${activity?.isFinishing} destroyed=${activity?.isDestroyed} " +
            "decorAttached=${decor?.isAttachedToWindow} windowVisibility=${decor?.windowVisibility} " +
            "shown=${decor?.isShown} focus=${decor?.hasWindowFocus()} oemVisibility=unknown"
        effects.addLast { runCatching { com.openautolink.app.diagnostics.DiagnosticLog.i("cluster", fields) } }
    }

    private inline fun mutate(action: () -> Unit) {
        synchronized(ClusterBindingLifecycle.lock) { action() }
        drainEffects()
    }
    private fun drainEffects() {
        synchronized(ClusterBindingLifecycle.lock) {
            if (draining || effects.isEmpty()) return
            draining = true
        }
        // A manager can enter retirement with an enclosing lifecycle monitor. Only that case
        // dispatches to Main; ordinary host callbacks and inline acknowledgements remain direct.
        clusterLifecycleEffect {
            while (true) {
                val effect = synchronized(ClusterBindingLifecycle.lock) {
                    if (effects.isEmpty()) { draining = false; null } else effects.removeFirst()
                } ?: break
                effect()
            }
        }
    }
    override fun acceptsBinding(generation: Long): Boolean =
        logicalDisplayType == SessionInfo.DISPLAY_TYPE_MAIN &&
            snapshot.lease.outputEpoch == generation && coordinator.isLive()

    override fun onPrimaryCreated(lease: ClusterBindingRegistry.SessionLease) {
        synchronized(ClusterBindingLifecycle.lock) {
            if (retired || !ClusterBindingState.isSessionCurrent(lease) || !coordinator.claim(this)) return
            this.lease = lease
        }
        clusterLifecycleEffect {
            if (!synchronized(ClusterBindingLifecycle.lock) { current() && this.lease === lease }) return@clusterLifecycleEffect
            val manager = carContext.getCarService(AppManager::class.java)
            synchronized(ClusterBindingLifecycle.lock) {
                if (!current() || this.lease !== lease) return@clusterLifecycleEffect
                hostCallbacks = manager
            }
            val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) = mutate { traceVisibility("start") }
                override fun onResume(owner: androidx.lifecycle.LifecycleOwner) = mutate { traceVisibility("resume") }
                override fun onPause(owner: androidx.lifecycle.LifecycleOwner) = mutate { traceVisibility("pause") }
                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) = mutate { traceVisibility("stop") }
            }
            lifecycle.addObserver(observer)
            mutate { traceVisibility("callback_install") }
            visibilityHandler.postDelayed(visibilitySample, 5000L)
            manager.setSurfaceCallback(object : SurfaceCallback {
                override fun onSurfaceAvailable(container: SurfaceContainer) = mutate {
                    traceVisibility("surface_available", container.surface)
                    if (!current()) return@mutate
                    val candidate = container.surface ?: return@mutate
                    val dimensions = try { PixelSize(container.width, container.height) } catch (_: IllegalArgumentException) {
                        detachCurrent(); return@mutate
                    }
                    if (surface !== candidate) {
                        detachCurrent()
                        visible = null
                        stable = null
                    }
                    surface = candidate
                    size = dimensions
                    traceGeometry("available")
                    publish()
                }
                override fun onVisibleAreaChanged(area: Rect) = mutate {
                    traceVisibility("visible")
                    if (!current()) return@mutate
                    visible = rect(area)
                    traceGeometry("visible")
                    if (visible == null) detachCurrent()
                    publish()
                }
                override fun onStableAreaChanged(area: Rect) = mutate {
                    traceVisibility("stable")
                    if (!current()) return@mutate
                    stable = rect(area)
                    traceGeometry("stable")
                    if (stable == null) detachCurrent()
                    publish()
                }
                override fun onSurfaceDestroyed(container: SurfaceContainer) = mutate {
                    traceVisibility("surface_destroyed", container.surface, container.surface === surface && current())
                    if (container.surface !== surface) return@mutate
                    detachCurrent()
                    traceGeometry("destroyed")
                }
            })
        }
    }
    private fun rect(rect: Rect): PixelRect? = try {
        PixelRect(rect.left, rect.top, rect.right, rect.bottom)
    } catch (_: IllegalArgumentException) { null }
    private fun current(): Boolean = !retired && lease?.let { ClusterBindingState.isSessionCurrent(it) } == true &&
        coordinator.isLive() && physicalHost?.let { ClusterPhysicalHostState.isCurrent(it) } != false
    /** Reserve under lifecycle; execute coordinator/bridge and subscribe outside all monitors. */
    private fun publish() {
        validateRetainedHost()
        if (surfaceCleanup != null || !current()) return
        val exact = surface ?: return
        val dimensions = size ?: return
        val actualVisible = visible ?: return
        val actualStable = stable ?: return
        val placement = physicalHost ?: ClusterPhysicalHostState.current(snapshot.lease.outputEpoch)?.also { observed ->
            if (ClusterPhysicalHostState.borrow(observed, this) { mutate { detachCurrent() } }) {
                physicalHost = observed
                com.openautolink.app.diagnostics.DiagnosticLog.i("cluster",
                    "Map host observed generation=${observed.generation} physical=${observed.displayId} context=${runCatching { carContext.display.displayId }.getOrNull()} surface=${dimensions.width}x${dimensions.height} visible=$actualVisible stable=$actualStable")
            }
        } ?: return
        if (physicalHost !== placement || !ClusterPhysicalHostState.isCurrent(placement)) return
        val host = ClusterHostGeometry(placement.displayId, dimensions, actualVisible, actualStable)
        effects.addLast { coordinator.observeHost(this, host) }
        lease?.let { ClusterBindingState.markNotReady(it) }
        val ownedLease = lease
        val outputLease = snapshot.lease
        val request = ++revision
        effects.addLast {
            val submit = synchronized(ClusterBindingLifecycle.lock) {
                if (current() && snapshot.lease == outputLease && revision == request && lease === ownedLease) {
                    submittedSurface = exact
                    true
                } else false
            }
            if (submit) {
                coordinator.attachHost(outputLease, this, exact, host).thenAccept { attached ->
                    synchronized(ClusterBindingLifecycle.lock) {
                        if (attached && revision == request && current() && surface === exact && lease === ownedLease && coordinator.isHostAttached()) {
                            ownedLease?.let { ClusterBindingState.markReady(it) }
                        }
                    }
                }
            }
        }
    }
    private fun detachCurrent() {
        ++revision
        lease?.let { ClusterBindingState.markNotReady(it) }
        val exact = submittedSurface
        submittedSurface = null
        surface = null
        size = null
        validateRetainedHost()
        effects.addLast { coordinator.observeHost(this, null) }
        if (exact == null || surfaceCleanup != null) return
        val ownedLease = lease
        val outputLease = snapshot.lease
        val pending = java.util.concurrent.CompletableFuture<Boolean>()
        surfaceCleanup = pending
        effects.addLast {
            coordinator.detachHost(outputLease, this, exact).whenComplete { detached, failure ->
                mutate {
                    if (failure == null && detached == true && surfaceCleanup === pending && lease === ownedLease && current()) {
                        surfaceCleanup = null
                        publish()
                    }
                    effects.addLast { pending.complete(failure == null && detached == true) }
                }
            }
        }
    }
    override fun onPrimaryRetired(): java.util.concurrent.CompletableFuture<Boolean> {
        val retirement = java.util.concurrent.CompletableFuture<Boolean>()
        mutate {
            visibilityHandler.removeCallbacks(visibilitySample)
            traceVisibility("retire")
            retired = true; ++revision
            validateRetainedHost()
            effects.addLast { coordinator.observeHost(this, null) }
            ClusterPhysicalHostState.release(physicalHost, this)
            physicalHost = null
            lease?.let { ClusterBindingState.markNotReady(it) }
            // Revoke callback authority before a deferred release, including manager-held locks.
            hostCallbacks = null; surface = null; lease = null
            effects.addLast {
                coordinator.releaseHost(this).whenComplete { success, failure ->
                    retirement.complete(failure == null && success == true)
                }
            }
        }
        return retirement
    }
    override fun outputScreen(): Screen = object : Screen(carContext) {
        override fun onGetTemplate(): Template {
            mutate { traceVisibility("template") }
            return ClusterRelayTemplateFactory.buildMap()
        }
    }
}

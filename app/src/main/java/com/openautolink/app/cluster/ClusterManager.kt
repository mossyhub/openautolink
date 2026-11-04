package com.openautolink.app.cluster

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.openautolink.app.diagnostics.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Manages the cluster service lifecycle: enabling/disabling the CarAppService component,
 * launching CarAppActivity to trigger Templates Host binding, and recovering from
 * GM killing the cluster session.
 *
 * GM AAOS may kill third-party CarAppService sessions. This manager uses a generation-owned
 * binding lease to detect stale sessions and re-launch the binding chain.
 */
class ClusterManager(private val context: Context) {

    companion object {
        private const val TAG = "ClusterMgr"
        private const val CLUSTER_SERVICE_CLASS = "com.openautolink.app.cluster.OalClusterService"
        private const val CAR_APP_ACTIVITY_CLASS = "androidx.car.app.activity.CarAppActivity"
        private const val RELAUNCH_DELAY_MS = 2000L
        private const val RETRY_DELAY_MS = 4000L
        private const val PERMISSIONS_RETRY_DELAY_MS = 3000L
        private const val HEALTH_CHECK_DELAY_MS = 5000L
        private const val MAX_HEALTH_RETRIES = 3
        private fun openBindingManager(): ClusterBindingRegistry.ManagerLease =
            synchronized(ClusterBindingLifecycle.lock) { ClusterBindingState.openManager() }

        /** Runtime permissions that must be granted before launching CarAppActivity,
         *  to avoid covering the system permissions dialog. */
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    // Never cancel exact retirement acknowledgements with recovery callbacks.
    private val retirementHandler = Handler(Looper.getMainLooper())
    private var enabled = false
    private var healthRetryCount = 0
    private var relaunching = false
    // Construction is inert: a settings preview or unselected transport cannot replace a phone.
    private var admittedLease: ClusterBindingRegistry.ManagerLease? = null
    private val bindingLease: ClusterBindingRegistry.ManagerLease get() = requireNotNull(admittedLease)
    private var processClaim: ClusterProcessAdmission.Claim? = null
    private var previewReservation: Any? = null
    internal fun reservePreviewAdmission(owner: Any): Boolean = synchronized(ClusterBindingLifecycle.lock) {
        if (processClaim != null || !reserveAdmission()) return@synchronized false
        previewReservation = owner
        true
    }
    private var mapRendererRetirement: (() -> java.util.concurrent.CompletableFuture<Boolean>)? = null

    /** Caller holds the common lifecycle lock, including opening the exact manager generation. */
    internal fun reserveAdmission(guidanceOnly: Boolean = false): Boolean = synchronized(ClusterBindingLifecycle.lock) {
        processClaim?.let { return@synchronized admittedLease != null && ClusterProcessAdmission.owns(it) &&
            (guidanceOnly || ClusterProcessAdmission.exclusive(it)) }
        val claim = (if (guidanceOnly) ClusterProcessAdmission.acquireGuidance(this)
            else ClusterProcessAdmission.acquire(this)) ?: return@synchronized false
        processClaim = claim
        try { admittedLease = openBindingManager() }
        catch (failure: Exception) {
            // An unacknowledged preexisting binding is debt, not permission to clear the slot.
            throw failure
        }
        true
    }

    internal fun abandonAdmission(owner: Any): Boolean = synchronized(ClusterBindingLifecycle.lock) {
        if (previewReservation !== owner || enabled || mapOutput != null) return@synchronized false
        val lease = admittedLease
        if (lease != null && !ClusterBindingState.closeManager(lease)) return@synchronized false
        admittedLease = null
        val claim = processClaim ?: return@synchronized true
        ClusterProcessAdmission.release(claim).also { if (it) { processClaim = null; previewReservation = null } }
    }

    private fun releaseAdmission() {
        admittedLease = null
        processClaim?.let { if (ClusterProcessAdmission.release(it)) { processClaim = null; previewReservation = null } }
    }
    private var demandJob: Job? = null
    private var recoveryExhausted = false
    private var recoveryRoute: Long? = null
    private var routeRecoveryAttempts = 0
    private var callbackEpoch = 0L
    private var mapOutput: ClusterBindingState.MapBinding? = null
    private var mapRecoveryUsed = false
    private var mapLaunchAttempted = false
    private var mapDisplayReceipt: ClusterDriverDisplayReceipt? = null

    /** Called once for the admitted live projection, not from a reactive preference collector.
     * Frozen geometry may precede host attachment. Unknown public role receipts never launch.
     */
    fun beginMapOutput(
        projectionEpoch: Long,
        selection: ClusterOutputSelection,
        protocol: ClusterProjectionProtocol,
        display: QualifiedClusterDisplay,
        bridge: ClusterHostOutputBridge,
        receipt: ClusterDriverDisplayReceipt? = ClusterDriverDisplayReceipt.QUALIFIED_GM,
        admissionOwner: Any? = null,
    ): ClusterOutputLease? = synchronized(ClusterBindingLifecycle.lock) {
        if (previewReservation !== admissionOwner) return@synchronized null
        // Validate before claiming; unknown profiles and disabled protocol paths cannot take the slot.
        if (!ClusterOutputPolicy.shouldOfferMapVideo(selection, protocol) ||
            display.profile != ClusterDisplayProfiles.AEGEAN_ORANGE_239) return@synchronized null
        if (!reserveAdmission() || !ClusterBindingState.isManagerCurrent(bindingLease)) return@synchronized null
        mapOutput?.takeIf { it.snapshot.lease.projectionEpoch == projectionEpoch }?.let {
            val frozen = it.snapshot
            return@synchronized if (it.coordinator.isLive() && frozen.selection == selection && frozen.protocol == protocol &&
                frozen.display.profile == display.profile && frozen.display.host == display.host &&
                frozen.display.effectiveOutput == display.effectiveOutput) frozen.lease else null
        }
        // A different live/frozen epoch requires explicit acknowledged retirement, never replacement.
        if (mapOutput != null) return@synchronized null
        val previous = bindingLease
        // The old renderer must acknowledge retirement before any successor can be admitted.
        if (!ClusterBindingState.closeManager(previous)) return@synchronized null
        ClusterMainSession.invalidateBindingGeneration(previous.generation, "new projection output")
        finishClusterTask(previous, "new projection output")
        callbackEpoch++
        handler.removeCallbacksAndMessages(null)
        demandJob?.cancel(); demandJob = null
        admittedLease = ClusterBindingState.openManager()
        val lease = ClusterOutputLease(projectionEpoch, bindingLease.generation)
        val snapshot = ClusterMapOutputSnapshot(lease, selection, protocol, display)
        val coordinator = ClusterOutputCoordinator(snapshot, bridge)
        val binding = ClusterBindingState.MapBinding(bindingLease.generation, snapshot, coordinator, {
            onMapSessionDestroyed(lease)
        }, receipt)
        check(ClusterBindingState.installMapBinding(bindingLease, binding))
        mapOutput = binding
        mapRendererRetirement = if (bridge is ClusterProjectionOutput) ({ bridge.retire(lease) }) else null
        mapDisplayReceipt = receipt
        mapRecoveryUsed = false
        mapLaunchAttempted = false
        enabled = true
        applyClusterComponentState(true)
        launchMapBinding(binding)
        lease
    }

    /** One bounded explicit handoff. Never substitutes cached geometry or launches a host. */
    class RetainedMapHost internal constructor(
        internal val binding: ClusterBindingState.MapBinding,
        internal val session: ClusterMapSession,
        internal val captured: ClusterMapSession.LiveHost,
        internal val deadline: Long,
        internal val oldRendererRetirement: (() -> java.util.concurrent.CompletableFuture<Boolean>)?,
    ) {
        val ready = java.util.concurrent.CompletableFuture<Boolean>()
        internal val finished = java.util.concurrent.CompletableFuture<Boolean>()
        internal var valid = true
        internal var consumed = false
        internal var expiry: Runnable? = null
    }
    private var retainedMapHost: RetainedMapHost? = null
    fun retainMapOutputForReconnect(lease: ClusterOutputLease, selection: ClusterOutputSelection): RetainedMapHost? {
        val ticket = synchronized(ClusterBindingLifecycle.lock) {
            val binding = mapOutput ?: return null
            if (retainedMapHost != null || mapEnding != null || !enabled || binding.snapshot.lease != lease ||
                binding.snapshot.selection != selection || !binding.coordinator.isHostAttached()) return null
            val session = binding.coordinator.liveHostSession() ?: return null
            val live = session.readLiveHost() ?: return null
            val next = RetainedMapHost(binding, session, live, android.os.SystemClock.elapsedRealtime() + 15000L, mapRendererRetirement)
            if (!session.retainLiveHost(live) { cancelRetainedMapOutput(next) }) return null
            retainedMapHost = next
            // Explicit handoff never uses the unexpected-destruction relaunch path.
            mapRecoveryUsed = true
            next
        }
        val expiry = Runnable { cancelRetainedMapOutput(ticket) }
        synchronized(ClusterBindingLifecycle.lock) { ticket.expiry = expiry }
        retirementHandler.postDelayed(expiry, 15000L)
        try {
            ticket.binding.coordinator.pauseForReconnect(lease).thenCompose { detached ->
                if (detached) ticket.oldRendererRetirement?.invoke() ?: java.util.concurrent.CompletableFuture.completedFuture(true)
                else java.util.concurrent.CompletableFuture.completedFuture(false)
            }.whenComplete { success, failure ->
                val accepted = failure == null && success == true && synchronized(ClusterBindingLifecycle.lock) {
                    retainedMapHost === ticket && ticket.valid && ticket.session.readLiveHost()?.surface === ticket.captured.surface
                }
                ticket.ready.complete(accepted)
                if (!accepted) cancelRetainedMapOutput(ticket)
                runCatching { DiagnosticLog.i("cluster", "OPTIONAL_HANDOFF_V1 stage=old_gpu_retirement generation=${lease.outputEpoch} success=$accepted") }
            }
        } catch (_: Exception) { ticket.ready.complete(false); cancelRetainedMapOutput(ticket) }
        return ticket
    }
    private fun retainedLive(ticket: RetainedMapHost): ClusterMapSession.LiveHost? = synchronized(ClusterBindingLifecycle.lock) {
        if (retainedMapHost !== ticket || !ticket.valid || ticket.consumed || ticket.ready.getNow(false) != true ||
            android.os.SystemClock.elapsedRealtime() >= ticket.deadline || mapOutput !== ticket.binding ||
            admittedLease == null || !ClusterBindingState.isManagerCurrent(bindingLease)) return@synchronized null
        ticket.session.readLiveHost()?.takeIf { now ->
            now.surface === ticket.captured.surface && now.geometry.displayId == ticket.captured.geometry.displayId &&
                now.geometry.surfaceSize == ticket.captured.geometry.surfaceSize &&
                now.geometry.stable == ticket.captured.geometry.stable &&
                now.geometry.essentialCanvas == ticket.captured.geometry.essentialCanvas
        }
    }
    fun readRetainedHost(ticket: RetainedMapHost): QualifiedClusterDisplay? {
        val live = retainedLive(ticket) ?: return null
        val profile = ticket.binding.snapshot.display.profile
        return ClusterDisplayProfiles.qualify(profile.vehicle,
            listOf(ClusterDisplayBinding(profile.role, live.geometry.displayId)), live.geometry)
    }
    internal fun currentLiveDisplay(): QualifiedClusterDisplay? {
        val binding = synchronized(ClusterBindingLifecycle.lock) { mapOutput } ?: return null
        val live = binding.coordinator.liveHostSession()?.readLiveHost() ?: return null
        val profile = binding.snapshot.display.profile
        return ClusterDisplayProfiles.qualify(profile.vehicle,
            listOf(ClusterDisplayBinding(profile.role, live.geometry.displayId)), live.geometry)
    }
    internal fun observedMapHost(lease: ClusterOutputLease): kotlinx.coroutines.flow.StateFlow<ClusterHostGeometry?>? =
        synchronized(ClusterBindingLifecycle.lock) { mapOutput?.takeIf { it.snapshot.lease == lease }?.coordinator?.observedHost }
    internal class RetainedMapInstallation(val lease: ClusterOutputLease,
        val attached: java.util.concurrent.CompletableFuture<Boolean>) {
        @Volatile var failedAttachmentRetirement: java.util.concurrent.CompletableFuture<Boolean>? = null
    }
    fun resumeRetainedMapOutput(ticket: RetainedMapHost, projectionEpoch: Long, selection: ClusterOutputSelection,
        protocol: ClusterProjectionProtocol, display: QualifiedClusterDisplay, bridge: ClusterHostOutputBridge): java.util.concurrent.CompletableFuture<ClusterOutputLease?> {
        val installed = installRetainedMapOutput(ticket, projectionEpoch, selection, protocol, display, bridge)
            ?: return java.util.concurrent.CompletableFuture.completedFuture(null)
        return installed.attached.thenApply { installed.lease.takeIf { _ -> it } }
    }
    /** Return exact installed cleanup ownership synchronously, before any cancellable attach await. */
    internal fun installRetainedMapOutput(ticket: RetainedMapHost, projectionEpoch: Long, selection: ClusterOutputSelection,
        protocol: ClusterProjectionProtocol, display: QualifiedClusterDisplay, bridge: ClusterHostOutputBridge): RetainedMapInstallation? {
        val result = java.util.concurrent.CompletableFuture<Boolean>()
        val live = retainedLive(ticket)
        if (live == null || display.host != live.geometry) { cancelRetainedMapOutput(ticket); return null }
        val nextLease = ClusterOutputLease(projectionEpoch, ticket.binding.generation)
        var bridgeBound = false
        val installed = synchronized(ClusterBindingLifecycle.lock) {
            if (retainedLive(ticket) != live || display.profile != ticket.binding.snapshot.display.profile ||
                selection != ticket.binding.snapshot.selection || protocol != ticket.binding.snapshot.protocol) false
            else {
                val next = ClusterMapOutputSnapshot(nextLease, selection, protocol, display)
                if (!ticket.binding.coordinator.rebindRetainedHost(ticket.binding.snapshot.lease, next, bridge, ticket.session)) false
                else {
                    ticket.binding.snapshot = next
                    ticket.consumed = true; ticket.valid = false; retainedMapHost = null
                    ticket.expiry?.let { retirementHandler.removeCallbacks(it) }
                    ticket.session.clearRetainedHost()
                    mapRendererRetirement = if (bridge is ClusterProjectionOutput) ({ bridge.retire(nextLease) }) else null
                    bridgeBound = try { bridge !is ClusterProjectionOutput || bridge.bindLease(nextLease) } catch (_: Exception) { false }
                    true
                }
            }
        }
        if (!installed) { cancelRetainedMapOutput(ticket); return null }
        val receipt = RetainedMapInstallation(nextLease, result)
        val attachment = if (bridgeBound) try { ticket.session.refreshRetainedHost(live) }
            catch (_: Exception) { java.util.concurrent.CompletableFuture.completedFuture(false) }
            else java.util.concurrent.CompletableFuture.completedFuture(false)
        attachment.whenComplete { attached, failure ->
            val accepted = failure == null && attached == true && synchronized(ClusterBindingLifecycle.lock) {
                mapOutput === ticket.binding && ticket.binding.snapshot.lease == nextLease && ticket.session.readLiveHost() == live
            }
            if (!accepted) receipt.failedAttachmentRetirement =
                endMapOutput(nextLease, selection, "retained host successor rejected")
            result.complete(accepted)
            ticket.finished.complete(accepted)
            runCatching { DiagnosticLog.i("cluster", "OPTIONAL_HANDOFF_V1 stage=same_host_successor generation=${nextLease.outputEpoch} session=$projectionEpoch success=$accepted") }
        }
        return receipt
    }
    fun cancelRetainedMapOutput(ticket: RetainedMapHost): java.util.concurrent.CompletableFuture<Boolean> {
        val current = synchronized(ClusterBindingLifecycle.lock) {
            if (retainedMapHost !== ticket || ticket.consumed) return java.util.concurrent.CompletableFuture.completedFuture(false)
            ticket.valid = false; retainedMapHost = null
            ticket.expiry?.let { retirementHandler.removeCallbacks(it) }
            ticket.session.clearRetainedHost()
            true
        }
        return if (current) endMapOutput(ticket.binding.snapshot.lease, ticket.binding.snapshot.selection, "explicit handoff cancelled")
            .whenComplete { success, failure -> ticket.finished.complete(failure == null && success == true) }
            else java.util.concurrent.CompletableFuture.completedFuture(false)
    }

    /** Read-only exact-owner access for parent status binding; attachment is not phone/video readiness. */
    fun mapOutputCoordinator(lease: ClusterOutputLease, selection: ClusterOutputSelection): ClusterOutputCoordinator? =
        synchronized(ClusterBindingLifecycle.lock) {
            mapOutput?.takeIf { it.snapshot.lease == lease && it.snapshot.selection == selection &&
                ClusterBindingState.isManagerCurrent(bindingLease) }?.coordinator
        }

    /** Exact output AND frozen selection must match; stale projection cleanup cannot stop another. */
    private var mapEnding: java.util.concurrent.CompletableFuture<Boolean>? = null
    fun endMapOutput(lease: ClusterOutputLease, selection: ClusterOutputSelection, reason: String): java.util.concurrent.CompletableFuture<Boolean> {
        val binding: ClusterBindingState.MapBinding
        val result: java.util.concurrent.CompletableFuture<Boolean>
        val rendererRetirement: (() -> java.util.concurrent.CompletableFuture<Boolean>)?
        val endingTicket: RetainedMapHost?
        synchronized(ClusterBindingLifecycle.lock) {
            binding = mapOutput ?: return java.util.concurrent.CompletableFuture.completedFuture(false)
            if (binding.snapshot.lease != lease || binding.snapshot.selection != selection ||
                admittedLease == null || !ClusterBindingState.isManagerCurrent(bindingLease)) {
                return java.util.concurrent.CompletableFuture.completedFuture(false)
            }
            mapEnding?.let { return it }
            endingTicket = retainedMapHost
            retainedMapHost?.let { ticket ->
                ticket.valid = false; ticket.expiry?.let { retirementHandler.removeCallbacks(it) }
                ticket.session.clearRetainedHost(); retainedMapHost = null
            }
            enabled = false; callbackEpoch++
            handler.removeCallbacksAndMessages(null)
            rendererRetirement = mapRendererRetirement
            result = java.util.concurrent.CompletableFuture(); mapEnding = result
        }
        endingTicket?.let { ticket -> result.whenComplete { success, failure ->
            ticket.finished.complete(failure == null && success == true)
        } }
        fun complete(acknowledged: Boolean) {
            // Never inherit a lifecycle monitor from a reentrant caller or immediate bridge future.
            val finish = Runnable {
                val success = synchronized(ClusterBindingLifecycle.lock) {
                    val accepted = acknowledged && mapOutput === binding && admittedLease != null &&
                        ClusterBindingState.isManagerCurrent(bindingLease) && ClusterBindingState.closeManager(bindingLease)
                    if (accepted) {
                        finishClusterTask(bindingLease, reason)
                        mapOutput = null; mapRendererRetirement = null
                        applyClusterComponentState(false)
                        releaseAdmission()
                    }
                    if (mapEnding === result) mapEnding = null
                    accepted
                }
                result.complete(success)
            }
            if (Looper.myLooper() == Looper.getMainLooper() && !Thread.holdsLock(ClusterBindingLifecycle.lock)) finish.run()
            else retirementHandler.post(finish)
        }
        try {
            val hostRetirement = binding.coordinator.retire(lease, reason)
            ClusterMainSession.invalidateBindingGeneration(binding.generation, reason)
            hostRetirement.thenCompose { hostAcknowledged ->
                if (!hostAcknowledged) java.util.concurrent.CompletableFuture.completedFuture(false)
                else rendererRetirement?.invoke() ?: java.util.concurrent.CompletableFuture.completedFuture(true)
            }.whenComplete { acknowledged, failure -> complete(failure == null && acknowledged == true) }
        } catch (_: Exception) { complete(false) }
        return result
    }

    private fun onMapSessionDestroyed(lease: ClusterOutputLease) {
        val binding = mapOutput ?: return
        if (binding.snapshot.lease != lease || !enabled || !binding.coordinator.isLive() || mapRecoveryUsed) return
        mapRecoveryUsed = true // exactly one unexpected-destroy recovery per explicit projection admission
        postOwned(RELAUNCH_DELAY_MS) { launchMapBinding(binding, recovery = true) }
    }

    private fun launchMapBinding(binding: ClusterBindingState.MapBinding, recovery: Boolean = false) {
        if (!enabled || mapOutput !== binding || !ClusterBindingState.isManagerCurrent(bindingLease) ||
            !binding.coordinator.isLive() || ClusterBindingState.hasLiveSession(bindingLease)) return
        if (mapLaunchAttempted && !recovery) return
        mapLaunchAttempted = true
        val receipt = mapDisplayReceipt
        val display = receipt?.resolve(context, binding.snapshot.display)
        if (display == null) {
            DiagnosticLog.w("cluster", "Map host withheld: public driver display identity unknown or ambiguous; no display-ID guess")
            return
        }
        val intent = Intent().apply {
            setClassName(context, "com.openautolink.app.cluster.ClusterMapLaunchActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            putExtra(CLUSTER_BINDING_GENERATION_EXTRA, binding.generation)
        }
        try {
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
            if (!activityManager.isActivityStartAllowedOnDisplay(context, display.displayId, intent)) {
                DiagnosticLog.w("cluster", "Map host public launch denied display=${display.displayId}")
                return
            }
            val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId)
            context.startActivity(intent, options.toBundle())
            DiagnosticLog.i("cluster", "Map host public launch display=${display.displayId} name=${display.name} logical=MAIN generation=${binding.generation}")
        } catch (e: Exception) {
            DiagnosticLog.w("cluster", "Map host public launch failed; main continues error=${e.javaClass.simpleName}")
        }
    }

    /**
     * Enable or disable the cluster CarAppService component.
     * Must be called early in Activity.onCreate() before Templates Host discovers it.
     */
    fun setClusterEnabled(enabled: Boolean) {
        if (!enabled) { release(); return }
        synchronized(ClusterBindingLifecycle.lock) {
            if (mapOutput != null || previewReservation != null) return@synchronized
            if (!reserveAdmission(guidanceOnly = true)) return@synchronized
            if (admittedLease == null) return@synchronized
            if (!ClusterBindingState.isManagerCurrent(bindingLease)) {
                Log.i(TAG, "Ignoring component state from retired generation ${bindingLease.generation}")
                return@synchronized
            }
            if (this.enabled != enabled) {
                callbackEpoch++
                handler.removeCallbacksAndMessages(null)
                relaunching = false
            }
            this.enabled = enabled
            applyClusterComponentState(enabled)
            demandJob?.cancel()
            demandJob = if (enabled) CoroutineScope(Dispatchers.Main).launch {
                ClusterNavigationState.recoveryDemand.collectLatest { route ->
                    synchronized(ClusterBindingLifecycle.lock) {
                        if (recoveryRoute != null && recoveryRoute != route) {
                            callbackEpoch++
                            handler.removeCallbacksAndMessages(null)
                            relaunching = false
                            recoveryRoute = null
                        }
                        recoveryExhausted = false
                        healthRetryCount = 0
                        routeRecoveryAttempts = 0
                    }
                    while (route != null) {
                        ensureAlive()
                        kotlinx.coroutines.delay(HEALTH_CHECK_DELAY_MS)
                    }
                }
            } else null
        }
    }

    /** Caller owns [ClusterBindingLifecycle.lock]. */
    private fun applyClusterComponentState(enabled: Boolean) {
        val newState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        try {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, CLUSTER_SERVICE_CLASS),
                newState,
                PackageManager.DONT_KILL_APP
            )
            Log.i(TAG, "Cluster service ${if (enabled) "enabled" else "disabled"}")
            DiagnosticLog.i("cluster", "Cluster service ${if (enabled) "enabled" else "disabled"}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set cluster component state: ${e.message}")
            DiagnosticLog.w("cluster", "Failed to set cluster component: ${e.message}")
        }
    }

    /**
     * Launch CarAppActivity to trigger Templates Host binding for the cluster.
     * Uses a separate task (taskAffinity) so it doesn't disturb the main activity.
     *
     * Guarded by this manager's generation-owned binding lease. Sessions from an
     * older manager can never block a fresh launch.
     *
     * The process-scope lifecycle protector keeps this required renderer owner
     * transparent and non-interactive for the lifetime of the cluster session.
     */
    fun launchClusterBinding() {
        synchronized(ClusterBindingLifecycle.lock) {
            mapOutput?.let { launchMapBinding(it); return@synchronized }
            if (!isRecoveryDemandCurrent()) return@synchronized
            if (!enabled) {
                Log.d(TAG, "Cluster not enabled — skipping launch")
                return@synchronized
            }
            val launchLease = bindingLease
            if (!ClusterBindingState.isManagerCurrent(launchLease)) {
                Log.i(TAG, "Cluster binding generation ${launchLease.generation} is retired — skipping launch")
                return@synchronized
            }

            // Don't launch CarAppActivity while runtime permissions are pending —
            // it starts a new task that covers the system permission dialog
            val pendingPermissions = REQUIRED_PERMISSIONS.any {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (pendingPermissions) {
                Log.i(TAG, "Runtime permissions pending — deferring cluster launch")
                postOwned(PERMISSIONS_RETRY_DELAY_MS) { launchClusterBinding() }
                return@synchronized
            }

            if (ClusterBindingState.hasLiveSession(launchLease)) {
                Log.i(TAG, "Current-generation session still alive — will retry after teardown")
                postOwned(RETRY_DELAY_MS) {
                    if (ClusterBindingState.isManagerCurrent(launchLease) &&
                        !ClusterBindingState.hasLiveSession(launchLease)
                    ) {
                        Log.i(TAG, "Old session torn down — retrying launch")
                        launchClusterBinding()
                    }
                }
                return@synchronized
            }

            try {
                val intent = Intent().apply {
                    setClassName(context, CAR_APP_ACTIVITY_CLASS)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    putExtra(CLUSTER_BINDING_GENERATION_EXTRA, launchLease.generation)
                }
                context.startActivity(intent)
                relaunching = true
                Log.i(TAG, "Launched CarAppActivity for Templates Host binding")
                DiagnosticLog.i("cluster", "Launched CarAppActivity for Templates Host binding")

                // Schedule health check — if session doesn't become usable, retry.
                scheduleHealthCheck(launchLease)
            } catch (e: Exception) {
                // Cluster service blocked or not available — graceful degradation
                Log.w(TAG, "Failed to launch CarAppActivity: ${e.message}")
                DiagnosticLog.w("cluster", "Failed to launch CarAppActivity: ${e.message}")
                Log.i(TAG, "Cluster navigation will not be available — media metadata still flows via MediaSession")
            }
        }
    }


    /**
     * Schedule a health check after launching CarAppActivity.
     * If the cluster session isn't ready after [HEALTH_CHECK_DELAY_MS],
     * tear down the stale task and retry the full binding chain.
     */
    private fun scheduleHealthCheck(launchLease: ClusterBindingRegistry.ManagerLease) {
        postOwned(HEALTH_CHECK_DELAY_MS) {
            synchronized(ClusterBindingLifecycle.lock) {
                if (!enabled) return@synchronized
                if (!ClusterBindingState.isManagerCurrent(launchLease)) return@synchronized
                if (!isRecoveryDemandCurrent()) return@synchronized
                if (ClusterBindingState.hasReadySession(launchLease)) {
                    Log.i(TAG, "Health check: cluster session ready")
                    healthRetryCount = 0
                    recoveryExhausted = false
                    relaunching = false
                    return@synchronized
                }
                healthRetryCount++
                if (healthRetryCount > MAX_HEALTH_RETRIES) {
                    Log.w(TAG, "Health check: max retries ($MAX_HEALTH_RETRIES) exceeded — giving up")
                    DiagnosticLog.w("cluster", "Cluster binding failed after $MAX_HEALTH_RETRIES retries")
                    recoveryExhausted = true
                    relaunching = false
                    return@synchronized
                }
                Log.w(TAG, "Health check: session not ready — retry $healthRetryCount/$MAX_HEALTH_RETRIES")
                DiagnosticLog.w("cluster", "Cluster session not ready — retrying ($healthRetryCount/$MAX_HEALTH_RETRIES)")
                restartClusterBinding()
            }
        }
    }

    /**
     * Tear down and re-establish the cluster binding chain.
     * Called when the cluster session is detected as dead, or when nav state needs reset.
     */
    fun restartClusterBinding() {
        synchronized(ClusterBindingLifecycle.lock) {
            // Host lifecycle, not semantic route health or page changes, owns map recovery.
            if (mapOutput != null) return@synchronized
            if (!enabled || !isRecoveryDemandCurrent()) return@synchronized
            if (routeRecoveryAttempts >= MAX_HEALTH_RETRIES + 1) {
                recoveryExhausted = true
                relaunching = false
                DiagnosticLog.i("cluster", "Cluster recovery outcome=exhausted reason=route-budget generation=${bindingLease.generation}")
                return@synchronized
            }
            routeRecoveryAttempts++
            DiagnosticLog.i("cluster", "Cluster recovery outcome=attempted reason=binding-loss generation=${bindingLease.generation} attempt=$routeRecoveryAttempts route=$recoveryRoute preserved=${ClusterNavigationState.isActive}")
            val retiredLease = bindingLease
            if (!ClusterBindingState.closeManager(retiredLease)) {
                DiagnosticLog.i(
                    "cluster",
                    "Cluster binding restart ignored: retired generation=${retiredLease.generation}",
                )
                return@synchronized
            }
            Log.w(TAG, "Restarting cluster binding chain")
            DiagnosticLog.w("cluster", "Restarting cluster binding chain")
            ClusterMainSession.invalidateBindingGeneration(retiredLease.generation, "binding restart")
            finishClusterTask(retiredLease, "restart")
            val replacementLease = ClusterBindingState.openManager()
            admittedLease = replacementLease
            DiagnosticLog.i(
                "cluster",
                "Cluster binding generation replaced: old=${retiredLease.generation} new=${replacementLease.generation}",
            )

            postOwned(RELAUNCH_DELAY_MS) { launchClusterBinding() }
        }
    }

    /**
     * Check if the cluster session is alive; if not, restart the binding chain.
     * Called after bridge reconnect (Hello received) and when returning from Settings.
     */
    fun ensureAlive() {
        synchronized(ClusterBindingLifecycle.lock) {
            if (mapOutput != null) return@synchronized
            if (!enabled) return@synchronized
            if (ClusterNavigationState.isRouteSuppressed) return@synchronized
            if (ClusterBindingState.hasReadySession(bindingLease)) return@synchronized
            if (relaunching) return@synchronized
            if (recoveryExhausted) return@synchronized

            Log.w(TAG, "Cluster session not alive — relaunching binding")
            DiagnosticLog.w("cluster", "Cluster session not alive — relaunching binding")
            recoveryRoute = ClusterNavigationState.recoveryDemand.value
            relaunching = true
            restartClusterBinding()
        }
    }

    /**
     * Stop monitoring and release handler callbacks.
     */
    fun release() {
        val pendingMap = synchronized(ClusterBindingLifecycle.lock) {
            if (previewReservation != null) return@synchronized null
            mapOutput?.let { return@synchronized it }
            if (admittedLease == null) return@synchronized null
            enabled = false
            callbackEpoch++
            demandJob?.cancel()
            demandJob = null
            handler.removeCallbacksAndMessages(null)
            healthRetryCount = 0
            relaunching = false
            val retiredLease = bindingLease
            if (!ClusterBindingState.closeManager(retiredLease)) {
                DiagnosticLog.i(
                    "cluster",
                    "Cluster manager release ignored: retired generation=${retiredLease.generation}",
                )
                return@synchronized null
            }
            ClusterMainSession.invalidateBindingGeneration(retiredLease.generation, "manager release")
            applyClusterComponentState(false)
            finishClusterTask(retiredLease, "release")
            releaseAdmission()
            null
        }
        pendingMap?.let { endMapOutput(it.snapshot.lease, it.snapshot.selection, "manager release") }
    }

    /** Capture ownership at scheduling, including cancellation after dequeue. */
    private fun postOwned(delayMs: Long, action: () -> Unit) {
        val lease = bindingLease
        val route = recoveryRoute
        val epoch = callbackEpoch
        handler.postDelayed({
            synchronized(ClusterBindingLifecycle.lock) {
                if (enabled && epoch == callbackEpoch &&
                    ClusterBindingState.isManagerCurrent(lease) &&
                    (route == null || route == ClusterNavigationState.recoveryDemand.value)
                ) action()
            }
        }, delayMs)
    }

    private fun isRecoveryDemandCurrent(): Boolean =
        recoveryRoute == null || recoveryRoute == ClusterNavigationState.recoveryDemand.value

    private fun finishClusterTask(
        lease: ClusterBindingRegistry.ManagerLease,
        reason: String,
    ) {
        fun trace(outcome: BootstrapDiagnostics.TeardownOutcome, task: Int = -1) {
            NativeBootstrapDiagnostics.teardown(lease.generation, System.identityHashCode(this), reason, task, outcome)
        }
        trace(BootstrapDiagnostics.TeardownOutcome.BEFORE_LOOKUP)
        var selectedTask = -1
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val task = am.appTasks.firstOrNull {
                it.taskInfo.baseActivity?.className == CAR_APP_ACTIVITY_CLASS &&
                    it.taskInfo.baseIntent.getLongExtra(CLUSTER_BINDING_GENERATION_EXTRA, Long.MIN_VALUE) == lease.generation
            }
            if (task == null) {
                trace(BootstrapDiagnostics.TeardownOutcome.NOT_FOUND)
                DiagnosticLog.i(
                    "cluster",
                    "Cluster task teardown outcome=not-found reason=$reason generation=${lease.generation}",
                )
                return
            }
            selectedTask = runCatching { task.taskInfo.taskId }.getOrDefault(-1)
            trace(BootstrapDiagnostics.TeardownOutcome.REMOVE_REQUEST, selectedTask)
            task.finishAndRemoveTask()
            trace(BootstrapDiagnostics.TeardownOutcome.REMOVE_RETURNED, selectedTask)
            DiagnosticLog.i(
                "cluster",
                "Cluster task teardown outcome=requested reason=$reason generation=${lease.generation}",
            )
        } catch (e: Exception) {
            trace(BootstrapDiagnostics.TeardownOutcome.FAILED, selectedTask)
            Log.w(TAG, "Failed to finish CarAppActivity task: ${e.message}")
            DiagnosticLog.w(
                "cluster",
                "Cluster task teardown outcome=failed reason=$reason generation=${lease.generation} error=${e.message}",
            )
        }
    }
}

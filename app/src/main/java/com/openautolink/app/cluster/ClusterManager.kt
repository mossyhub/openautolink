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
        private const val BRING_BACK_DELAY_MS = 1000L
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
    private var enabled = false
    private var healthRetryCount = 0
    private var relaunching = false
    private var bindingLease = openBindingManager()

    /**
     * Enable or disable the cluster CarAppService component.
     * Must be called early in Activity.onCreate() before Templates Host discovers it.
     */
    fun setClusterEnabled(enabled: Boolean) {
        synchronized(ClusterBindingLifecycle.lock) {
            if (!ClusterBindingState.isManagerCurrent(bindingLease)) {
                Log.i(TAG, "Ignoring component state from retired generation ${bindingLease.generation}")
                return@synchronized
            }
            this.enabled = enabled
            applyClusterComponentState(enabled)
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
     * Brings the main activity back to front after binding completes (1s delay).
     */
    fun launchClusterBinding() {
        synchronized(ClusterBindingLifecycle.lock) {
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
                handler.postDelayed({ launchClusterBinding() }, PERMISSIONS_RETRY_DELAY_MS)
                return@synchronized
            }

            if (ClusterBindingState.hasLiveSession(launchLease)) {
                Log.i(TAG, "Current-generation session still alive — will retry after teardown")
                handler.postDelayed({
                    if (ClusterBindingState.isManagerCurrent(launchLease) &&
                        !ClusterBindingState.hasLiveSession(launchLease)
                    ) {
                        Log.i(TAG, "Old session torn down — retrying launch")
                        launchClusterBinding()
                    }
                }, RETRY_DELAY_MS)
                return@synchronized
            }

            try {
                val intent = Intent().apply {
                    setClassName(context, CAR_APP_ACTIVITY_CLASS)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    putExtra(CLUSTER_BINDING_GENERATION_EXTRA, launchLease.generation)
                }
                context.startActivity(intent)
                Log.i(TAG, "Launched CarAppActivity for Templates Host binding")
                DiagnosticLog.i("cluster", "Launched CarAppActivity for Templates Host binding")

                // Schedule health check — if session doesn't come alive, retry
                scheduleHealthCheck(launchLease)

                // Bring main activity back — binding is IPC-based, doesn't need foreground
                handler.postDelayed({
                    synchronized(ClusterBindingLifecycle.lock) {
                        if (!enabled || !ClusterBindingState.isManagerCurrent(launchLease)) {
                            return@synchronized
                        }
                        try {
                            val mainActivity = Class.forName("${context.packageName}.MainActivity")
                            val bringBack = Intent(context, mainActivity).apply {
                                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                            }
                            context.startActivity(bringBack)
                            Log.i(TAG, "Brought main activity back to foreground")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to bring main activity back: ${e.message}")
                        }
                    }
                }, BRING_BACK_DELAY_MS)
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
     * If the cluster session hasn't come alive after HEALTH_CHECK_DELAY_MS,
     * tear down the stale task and retry the full binding chain.
     */
    private fun scheduleHealthCheck(launchLease: ClusterBindingRegistry.ManagerLease) {
        handler.postDelayed({
            synchronized(ClusterBindingLifecycle.lock) {
                if (!enabled) return@synchronized
                if (!ClusterBindingState.isManagerCurrent(launchLease)) return@synchronized
                if (ClusterBindingState.hasLiveSession(launchLease)) {
                    Log.i(TAG, "Health check: cluster session alive")
                    healthRetryCount = 0
                    relaunching = false
                    return@synchronized
                }
                healthRetryCount++
                if (healthRetryCount > MAX_HEALTH_RETRIES) {
                    Log.w(TAG, "Health check: max retries ($MAX_HEALTH_RETRIES) exceeded — giving up")
                    DiagnosticLog.w("cluster", "Cluster binding failed after $MAX_HEALTH_RETRIES retries")
                    healthRetryCount = 0
                    relaunching = false
                    return@synchronized
                }
                Log.w(TAG, "Health check: session not alive — retry $healthRetryCount/$MAX_HEALTH_RETRIES")
                DiagnosticLog.w("cluster", "Cluster session not alive — retrying ($healthRetryCount/$MAX_HEALTH_RETRIES)")
                restartClusterBinding()
            }
        }, HEALTH_CHECK_DELAY_MS)
    }

    /**
     * Tear down and re-establish the cluster binding chain.
     * Called when the cluster session is detected as dead, or when nav state needs reset.
     */
    fun restartClusterBinding() {
        synchronized(ClusterBindingLifecycle.lock) {
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
            ClusterNavigationState.clear()
            ClusterMainSession.invalidateBindingGeneration(retiredLease.generation, "binding restart")
            finishClusterTask(retiredLease, "restart")
            val replacementLease = ClusterBindingState.openManager()
            bindingLease = replacementLease
            DiagnosticLog.i(
                "cluster",
                "Cluster binding generation replaced: old=${retiredLease.generation} new=${replacementLease.generation}",
            )

            handler.postDelayed({
                if (ClusterBindingState.isManagerCurrent(replacementLease)) launchClusterBinding()
            }, RELAUNCH_DELAY_MS)
        }
    }

    /**
     * Check if the cluster session is alive; if not, restart the binding chain.
     * Called after bridge reconnect (Hello received) and when returning from Settings.
     */
    fun ensureAlive() {
        synchronized(ClusterBindingLifecycle.lock) {
            if (!enabled) return@synchronized
            if (ClusterBindingState.hasLiveSession(bindingLease)) return@synchronized
            if (relaunching) return@synchronized

            Log.w(TAG, "Cluster session not alive — relaunching binding")
            DiagnosticLog.w("cluster", "Cluster session not alive — relaunching binding")
            relaunching = true
            restartClusterBinding()
        }
    }

    /**
     * Stop monitoring and release handler callbacks.
     */
    fun release() {
        synchronized(ClusterBindingLifecycle.lock) {
            enabled = false
            handler.removeCallbacksAndMessages(null)
            healthRetryCount = 0
            relaunching = false
            val retiredLease = bindingLease
            if (!ClusterBindingState.closeManager(retiredLease)) {
                DiagnosticLog.i(
                    "cluster",
                    "Cluster manager release ignored: retired generation=${retiredLease.generation}",
                )
                return@synchronized
            }
            ClusterMainSession.invalidateBindingGeneration(retiredLease.generation, "manager release")
            applyClusterComponentState(false)
            finishClusterTask(retiredLease, "release")
        }
    }

    private fun finishClusterTask(
        lease: ClusterBindingRegistry.ManagerLease,
        reason: String,
    ) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val task = am.appTasks.firstOrNull {
                it.taskInfo.baseActivity?.className == CAR_APP_ACTIVITY_CLASS
            }
            if (task == null) {
                DiagnosticLog.i(
                    "cluster",
                    "Cluster task teardown outcome=not-found reason=$reason generation=${lease.generation}",
                )
                return
            }
            task.finishAndRemoveTask()
            DiagnosticLog.i(
                "cluster",
                "Cluster task teardown outcome=finished reason=$reason generation=${lease.generation}",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to finish CarAppActivity task: ${e.message}")
            DiagnosticLog.w(
                "cluster",
                "Cluster task teardown outcome=failed reason=$reason generation=${lease.generation} error=${e.message}",
            )
        }
    }
}

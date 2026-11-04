package com.openautolink.app.cluster

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import com.openautolink.app.diagnostics.DiagnosticLog

/**
 * Makes AndroidX's main-display bootstrap activity non-visible and non-interactive
 * before [androidx.car.app.activity.BaseCarAppActivity] creates its host surface.
 * Once the cluster session is usable, its own task is moved behind the existing
 * foreground task without destroying the Activity or its renderer connection.
 */
internal object ClusterBootstrapWindowProtector : Application.ActivityLifecycleCallbacks {

    private const val CAR_APP_ACTIVITY = "androidx.car.app.activity.CarAppActivity"
    private val taskState = ClusterBootstrapTaskState<Activity>()

    fun install(application: Application) {
        NativeBootstrapDiagnostics.install(application)
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity.javaClass.name != CAR_APP_ACTIVITY) return

        val generation = activity.intent.getLongExtra(
            CLUSTER_BINDING_GENERATION_EXTRA,
            Long.MIN_VALUE,
        )
        val map = ClusterBindingState.frozenMapBinding()
        if (map?.generation == generation && map.coordinator.isLive()) {
            ClusterPhysicalHostState.record(generation, activity)
            activity.setTheme(android.R.style.Theme_Material_NoActionBar)
            val window = activity.window
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
            window.setFormat(android.graphics.PixelFormat.OPAQUE)
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.attributes = window.attributes.apply { alpha = 1f }
            DiagnosticLog.i("cluster", "Map host window opaque no-touch generation=$generation display=${activity.display?.displayId}")
            return // Never register a map owner for icon moveTaskToBack.
        }
        taskState.protect(generation, activity)

        val window = activity.window
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        )
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val attributes = window.attributes
        attributes.alpha = 0f
        window.attributes = attributes
        DiagnosticLog.i(
            "cluster",
            "Cluster bootstrap window protected generation=$generation alpha=0 focusable=false touchable=false",
        )
    }

    fun moveTaskToBack(generation: Long) {
        if (ClusterBindingState.frozenMapBinding()?.generation == generation) return
        val target = taskState.requestBackground(generation)
        if (target == null) {
            DiagnosticLog.w(
                "cluster",
                "Cluster bootstrap task background outcome=stale-or-missing generation=$generation",
            )
            return
        }
        postMoveTaskToBack(target)
    }

    private fun postMoveTaskToBack(target: ClusterBootstrapTaskState.Target<Activity>) {
        val activity = target.owner
        activity.window.decorView.post {
            if (!taskState.isCurrent(target) || activity.isFinishing || activity.isDestroyed) {
                return@post
            }

            try {
                val moved = activity.moveTaskToBack(true)
                DiagnosticLog.i(
                    "cluster",
                    "Cluster bootstrap task background outcome=${if (moved) "moved" else "rejected"} generation=${target.generation}",
                )
            } catch (e: Exception) {
                DiagnosticLog.w(
                    "cluster",
                    "Cluster bootstrap task background outcome=failed generation=${target.generation} error=${e.message}",
                )
            }
        }
    }

    private fun traceMapActivity(activity: Activity, event: String) {
        if (activity.javaClass.name == CAR_APP_ACTIVITY) {
            val nativeGeneration = activity.intent?.getLongExtra(CLUSTER_BINDING_GENERATION_EXTRA, Long.MIN_VALUE)
            if (nativeGeneration != null && nativeGeneration != Long.MIN_VALUE &&
                ClusterBindingState.frozenMapBinding()?.generation != nativeGeneration) {
                // Freeze callback-time flags/identity here; worker never retains an Activity.
                try {
                    NativeBootstrapDiagnostics.offer(BootstrapDiagnostics.Snapshot(
                        nativeGeneration, System.identityHashCode(activity), activity.taskId,
                        activity.display?.displayId ?: -1, event, activity.isFinishing,
                        activity.isChangingConfigurations, android.os.SystemClock.elapsedRealtime(),
                    ))
                } catch (_: Exception) { NativeBootstrapDiagnostics.rejectCapture() }
            }
        }
        val generation = activity.intent?.getLongExtra(CLUSTER_BINDING_GENERATION_EXTRA, Long.MIN_VALUE) ?: return
        val receipt = ClusterPhysicalHostState.current(generation)
        val current = receipt?.activity?.get() === activity
        if (!current && (activity.javaClass.name != CAR_APP_ACTIVITY ||
            ClusterBindingState.frozenMapBinding()?.generation != generation)) return
        runCatching {
            val decor = activity.window?.decorView
            DiagnosticLog.i("cluster", "OPTIONAL_ACTIVITY_V1 event=$event generation=$generation " +
                "elapsedMs=${android.os.SystemClock.elapsedRealtime()} activity=${System.identityHashCode(activity)} " +
                "physical=${activity.display?.displayId} current=$current finishing=${activity.isFinishing} " +
                "destroyed=${activity.isDestroyed} decorAttached=${decor?.isAttachedToWindow} " +
                "windowVisibility=${decor?.windowVisibility} shown=${decor?.isShown} focus=${decor?.hasWindowFocus()} oemVisibility=unknown")
        }
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = traceMapActivity(activity, "create")
    override fun onActivityStarted(activity: Activity) = traceMapActivity(activity, "start")

    override fun onActivityResumed(activity: Activity) {
        traceMapActivity(activity, "resume")
        taskState.pendingTargetFor(activity)?.let { postMoveTaskToBack(it) }
    }

    override fun onActivityPaused(activity: Activity) = traceMapActivity(activity, "pause")
    override fun onActivityStopped(activity: Activity) = traceMapActivity(activity, "stop")
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        traceMapActivity(activity, "destroy")
        ClusterPhysicalHostState.destroy(activity)
        taskState.destroy(activity)
    }
}

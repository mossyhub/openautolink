package com.openautolink.app.diagnostics

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.openautolink.app.transport.bluetooth.CarBluetoothConnectionObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Application-owned finite observer; does not own a projection session or extend process lifetime.
 * All receiver/lifecycle operations are main-thread confined. Disk calls stay with the recorder worker.
 */
class BluetoothReconnectProcess(
    private val app: Application,
    val recorder: BluetoothReconnectRecorder,
    private val scope: CoroutineScope,
    private val sdkInt: Int,
) {
    private var observer: CarBluetoothConnectionObserver? = null
    private var deadline: Job? = null
    private var statusWatcher: Job? = null
    private var operationGeneration = 0L
    private var deletePending = false
    private var lifecycle: Application.ActivityLifecycleCallbacks? = null
    private var startedActivities = 0
    private var lastCoverage: String? = null
    val coverage = MutableStateFlow("not registered — no observation")
    val busy = MutableStateFlow(false)
    val notice = MutableStateFlow("")
    private val sessionLock = Any()
    private var sessionRun = 0L
    private val sessions = java.util.IdentityHashMap<Any, Int>()

    internal fun markSession(key: Any, state: String) {
        if (state !in setOf("created", "authenticated", "retired") || !recorder.isRunning()) return
        val id = synchronized(sessionLock) {
            val currentRun = recorder.token()
            if (sessionRun != currentRun) { sessions.clear(); sessionRun = currentRun }
            sessions[key] ?: if (sessions.size < 32) (sessions.size + 1).also { sessions[key] = it } else 0
        }
        recorder.offer("session", state, session = id)
    }

    /** Process-owned operation survives leaving Diagnostics, but never a process restart. */
    fun requestStart(consent: Boolean, setupMediaOff: Boolean) {
        if (!consent || busy.value || recorder.isRunning()) return
        val operation = ++operationGeneration
        busy.value = true
        scope.launch {
            try {
                val started = withContext(Dispatchers.IO) { recorder.start(true) }
                if (operation != operationGeneration) return@launch
                if (started) {
                    if (setupMediaOff) recorder.bookmark("phone_on_media_off")
                    recorder.offer("lifecycle", "foreground")
                    arm()
                    notice.value = "Armed. Return to the car within 10 minutes. Setup bookmark is user attestation at Arm, not a policy read."
                } else notice.value = "Start unavailable — storage/previous save pending; no new observation"
            } finally { if (operation == operationGeneration) busy.value = false }
        }
    }

    fun requestDelete() {
        if (deletePending) return
        deletePending = true
        operationGeneration++
        revoke()
        busy.value = true
        scope.launch {
            try { notice.value = if (withContext(Dispatchers.IO) { recorder.delete() }) "Local recorder evidence deleted" else "Delete incomplete — retry; no new observation" }
            finally { busy.value = false; deletePending = false }
        }
    }

    fun arm() {
        if (!recorder.isRunning() || observer != null) return
        lastCoverage = null
        observer = CarBluetoothConnectionObserver(app, MutableStateFlow(true), scope, sdkInt,
            recorderEvidence = { kind, state, previous, device ->
                if (kind != "coverage" || state != lastCoverage) {
                    if (kind == "coverage") lastCoverage = state
                    if (kind == "coverage") coverage.value = state
                    recorder.offer(kind, state, previous, device)
                }
            }, recorderDeviceLimit = 8).also { it.start() }
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { if (startedActivities++ == 0) recorder.offer("lifecycle", "foreground") }
            override fun onActivityStopped(activity: Activity) { startedActivities = (startedActivities - 1).coerceAtLeast(0); if (startedActivities == 0) recorder.offer("lifecycle", "background") }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        lifecycle = callbacks
        app.registerActivityLifecycleCallbacks(callbacks)
        statusWatcher = scope.launch {
            recorder.status.collect {
                if (!recorder.isRunning()) { release(); coverage.value = "not registered — recorder inactive" }
            }
        }
        deadline = scope.launch {
            // One finite elapsed deadline. No alarm, wake lock, boot receiver or FGS.
            delay(recorder.remainingMs())
            recorder.expire()
            release()
        }
    }

    private fun release() {
        observer?.close(); observer = null
        lifecycle?.let { app.unregisterActivityLifecycleCallbacks(it) }; lifecycle = null
        startedActivities = 0
        synchronized(sessionLock) { sessions.clear() }
        deadline?.cancel(); deadline = null
        statusWatcher?.cancel(); statusWatcher = null
    }

    fun stop() { release(); recorder.stop(); coverage.value = "not registered — stopped" }
    fun revoke() { recorder.revoke(); release(); coverage.value = "not registered — consent revoked" }
}

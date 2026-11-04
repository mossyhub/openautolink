package com.openautolink.app.cluster

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.secondary.BoundedPageDiagnosticSink
import java.util.ArrayDeque
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Passive, process-lifetime observation only. No lifecycle/ownership operations run here. */
internal class BootstrapDiagnostics(
    private val inventory: (Snapshot) -> String,
    private val persist: (String) -> Unit,
) {
    internal data class Snapshot(
        val generation: Long, val activity: Int, val task: Int, val display: Int,
        val event: String, val finishing: Boolean, val changing: Boolean,
        val elapsedMs: Long, val inventory: Boolean = false,
        val caller: TeardownCaller = TeardownCaller.UNKNOWN, val owner: Int = 0,
        val outcome: TeardownOutcome = TeardownOutcome.NONE,
        val selectedTask: Int = -1, val eventElapsedMs: Long = elapsedMs,
    )
    internal enum class TeardownCaller { UNKNOWN, RESTART, RELEASE, NEW_PROJECTION }
    internal enum class TeardownOutcome { NONE, BEFORE_LOOKUP, NOT_FOUND, REMOVE_REQUEST, REMOVE_RETURNED, FAILED }
    // Reuse the central bounded text sink for ordinary records. Terminal has an independent
    // latest slot and is persisted by our passive inventory worker, never behind detail backlog.
    private val detailSink = BoundedPageDiagnosticSink(capacity = 64, persist = persist)
    private val knownEvents = setOf("create", "start", "resume", "pause", "stop", "destroy", "teardown")
    private val lock = ReentrantLock()
    private val queue = ArrayDeque<Snapshot>()
    // One independently retained latest terminal; overload coalesces, never grows a terminal queue.
    private val terminal = AtomicReference<Snapshot?>()
    private val coalesced = AtomicLong()
    private val rejected = AtomicLong()
    private val lastFinalGeneration = AtomicLong(Long.MIN_VALUE)
    private val wake = Semaphore(0)
    // Four most recent monotonically allocated generations, at most 13 detail admissions each.
    private val budgets = linkedMapOf<Long, Int>()
    private var retiredThrough = Long.MIN_VALUE
    private val inventoryCounts = mutableMapOf<Long, Int>()
    init {
        Thread({
            while (true) {
                wake.acquire()
                val end = terminal.getAndSet(null)
                if (end != null) emit(end)
                val item = run {
                    lock.lock()
                    try { queue.pollFirst() } finally { lock.unlock() }
                }
                if (item != null) emit(item)
            }
        }, "OalBootstrapDiagnostic").apply { isDaemon = true; start() }
    }
    fun rejectCapture() { rejected.incrementAndGet() }
    /** Never waits for a worker, filesystem, sink, inventory Binder, or another producer. */
    fun offer(snapshot: Snapshot) {
        if (snapshot.event !in knownEvents) { rejected.incrementAndGet(); return }
        if (snapshot.event == "destroy" && !snapshot.changing) {
            // Final teardown has an independent allowance even after ordinary detail exhaustion.
            // Repeated final callbacks cannot reset the lifetime budget for an old generation.
            val previous = lastFinalGeneration.get()
            if (snapshot.generation <= previous ||
                !lastFinalGeneration.compareAndSet(previous, snapshot.generation)) {
                rejected.incrementAndGet(); return
            }
            if (terminal.getAndSet(snapshot.copy(inventory = true)) != null) coalesced.incrementAndGet()
            else wake.release()
            return
        }
        if (!lock.tryLock()) { rejected.incrementAndGet(); return }
        try {
            if (snapshot.generation <= retiredThrough) { rejected.incrementAndGet(); return }
            if (!budgets.containsKey(snapshot.generation)) {
                if (budgets.size == 4) {
                    val oldest = budgets.keys.minOrNull()!!
                    if (snapshot.generation < oldest) { rejected.incrementAndGet(); return }
                    budgets.remove(oldest)
                    inventoryCounts.remove(oldest)
                    retiredThrough = maxOf(retiredThrough, oldest)
                }
                budgets[snapshot.generation] = 0
            }
            val count = budgets.getValue(snapshot.generation)
            if (count >= 13 || queue.size >= 64) { rejected.incrementAndGet(); return }
            budgets[snapshot.generation] = count + 1
            // Four ordinary inventories maximum; final destroy has its independent allowance.
            val wanted = snapshot.inventory || count == 0 || (snapshot.event == "stop" &&
                !stoppedGenerations.contains(snapshot.generation))
            val inventories = inventoryCounts[snapshot.generation] ?: 0
            val admitted = wanted && inventories < 4
            if (admitted) inventoryCounts[snapshot.generation] = inventories + 1
            else if (wanted) rejected.incrementAndGet()
            queue.addLast(snapshot.copy(inventory = admitted))
            if (snapshot.event == "stop") stoppedGenerations.add(snapshot.generation)
            stoppedGenerations.retainAll(budgets.keys)
        } finally { lock.unlock() }
        wake.release()
    }
    private val stoppedGenerations = mutableSetOf<Long>()
    private fun emit(snapshot: Snapshot) {
        fun write(message: String) {
            if (snapshot.event == "destroy" && !snapshot.changing) persist(message)
            else detailSink.tryOffer(message)
        }
        try {
            write("BOOTSTRAP_LIFETIME_V1 event=${snapshot.event} generation=${snapshot.generation} " +
                "activity=${snapshot.activity} task=${snapshot.task} display=${snapshot.display} " +
                "callbackElapsedMs=${snapshot.elapsedMs} finishing=${if (snapshot.activity == 0) "unknown" else snapshot.finishing} " +
                "changingConfigurations=${if (snapshot.activity == 0) "unknown" else snapshot.changing} finalizationCaller=UNKNOWN " +
                "caller=${snapshot.caller} owner=${snapshot.owner} outcome=${snapshot.outcome} " +
                "selectedTask=${snapshot.selectedTask} eventElapsedMs=${snapshot.eventElapsedMs} " +
                "rejected=${rejected.get()} terminalCoalesced=${coalesced.get()} sinkDropped=${detailSink.dropped}")
            if (snapshot.inventory) {
                write("BOOTSTRAP_TASKS_V1 cause=${snapshot.event} generation=${snapshot.generation} " +
                    "activity=${snapshot.activity} callbackTask=${snapshot.task} callbackElapsedMs=${snapshot.elapsedMs} " +
                    "readElapsedMs=${SystemClock.elapsedRealtime()} observation=asynchronous caller=${snapshot.caller} " +
                    "outcome=${snapshot.outcome} selectedTask=${snapshot.selectedTask} ${inventory(snapshot)} " +
                    "rejected=${rejected.get()} terminalCoalesced=${coalesced.get()} sinkDropped=${detailSink.dropped}")
            }
        } catch (_: Exception) { rejected.incrementAndGet() }
    }
}

internal object NativeBootstrapDiagnostics {
    @Volatile private var recorder: BootstrapDiagnostics? = null
    private val latestCallback = AtomicReference<BootstrapDiagnostics.Snapshot?>()
    @Synchronized fun install(context: Context) {
        if (recorder != null) return
        val app = context.applicationContext
        recorder = BootstrapDiagnostics({ snapshot ->
            try {
                val tasks = app.getSystemService(ActivityManager::class.java).appTasks
                // appTasks is our own public task inventory; never query global/hidden task APIs.
                val infos = tasks.take(8).map { it.taskInfo }
                val rows = infos.map { info ->
                    fun component(component: android.content.ComponentName?): String = when {
                        component == null -> "unknown"
                        component.packageName != app.packageName -> "redacted"
                        component.className == "androidx.car.app.activity.CarAppActivity" -> "CarAppActivity"
                        component.className == "${app.packageName}.MainActivity" -> "MainActivity"
                        component.className == "${app.packageName}.cluster.ClusterMapLaunchActivity" -> "ClusterMapLaunchActivity"
                        else -> "own-other"
                    }
                    val generation = if (info.baseActivity?.packageName == app.packageName &&
                        info.baseActivity?.className == "androidx.car.app.activity.CarAppActivity")
                        info.baseIntent.getLongExtra(CLUSTER_BINDING_GENERATION_EXTRA, Long.MIN_VALUE) else Long.MIN_VALUE
                    // RecentTaskInfo exposes no public display ID in the app SDK.
                    // Keep the Activity callback's display separately; never use hidden fields.
                    "${info.taskId}:${component(info.baseActivity)}:${component(info.topActivity)}:display=unknown:generation=$generation"
                }
                val exists = if (snapshot.task < 0) "unknown"
                    else if (infos.any { it.taskId == snapshot.task }) "true"
                    else if (tasks.size > 8) "unknown" else "false"
                "callbackTaskExists=$exists tasks=${rows.joinToString(",")} truncated=${tasks.size > 8} " +
                    "readCompletedElapsedMs=${SystemClock.elapsedRealtime()}"
            } catch (_: Exception) { "callbackTaskExists=unknown inventory=unavailable" }
        }, { DiagnosticLog.i("cluster", it) })
    }
    fun offer(snapshot: BootstrapDiagnostics.Snapshot) {
        latestCallback.set(snapshot)
        recorder?.offer(snapshot)
    }
    fun rejectCapture() { recorder?.rejectCapture() }
    fun teardown(generation: Long, owner: Int, reason: String, task: Int,
        outcome: BootstrapDiagnostics.TeardownOutcome) {
        val caller = when (reason) {
            "restart" -> BootstrapDiagnostics.TeardownCaller.RESTART
            "release" -> BootstrapDiagnostics.TeardownCaller.RELEASE
            "new projection output" -> BootstrapDiagnostics.TeardownCaller.NEW_PROJECTION
            else -> BootstrapDiagnostics.TeardownCaller.UNKNOWN
        }
        val observed = latestCallback.get()?.takeIf { it.generation == generation }
            ?: BootstrapDiagnostics.Snapshot(generation, 0, -1, -1, "teardown", false, false, -1)
        recorder?.offer(observed.copy(event = "teardown",
            inventory = outcome == BootstrapDiagnostics.TeardownOutcome.BEFORE_LOOKUP ||
                outcome == BootstrapDiagnostics.TeardownOutcome.REMOVE_RETURNED,
            caller = caller, owner = owner, outcome = outcome, selectedTask = task,
            eventElapsedMs = SystemClock.elapsedRealtime()))
    }
}

package com.openautolink.app.diagnostics

import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local, finite process observation. start/delete/flush are worker-thread disk operations.
 * offer never performs IO. No consent survives process death; an old open file is unknown coverage.
 * Strings enter persistence only after full-string finite validation; no Intent/device is queued.
 */
class BluetoothReconnectRecorder(private val directory: File) : AutoCloseable {
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }
    internal var beforeWrite: () -> Unit = {}
    private val lock = java.lang.Object()
    // One drain only, bounded executor admission, and no idle always-on thread after a run.
    private val worker = java.util.concurrent.ThreadPoolExecutor(0, 1, 5L, TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(1),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "oal-bt-recorder").apply { isDaemon = true } })
    private val queue = ArrayDeque<String>()
    private val ring = ArrayDeque<Pair<Long, String>>()
    private var draining = false
    private var terminal: String? = null
    private var active: File? = null
    private var run = 0L
    private var startMs = 0L
    private var seq = 0L
    private var epoch = 0
    private var detail = 0
    private var dropped = 0L
    private var rejected = 0L
    private var discontinuities = 0L
    private val lastStates = mutableMapOf<Pair<String, Int>, String>()
    private var running = false
    private var failed = false
    // Worker-owned per-run census; at most 1024 admitted rows plus one terminal.
    // Failed batches are not retried. The next successful atomic checkpoint carries their loss.
    private var attempted = 0
    private var saved = 0
    private var lost = 0
    private var failedBatches = 0
    private var closed = false
    private var revision = 0L
    private var lastPersisted = 0L
    private val mutableStatus = MutableStateFlow("Off — fresh consent required; process gaps unobserved")
    val status = mutableStatus.asStateFlow()

    companion object {
        const val DURATION_MS = 600_000L
        const val MAX_BYTES = 512 * 1024L
        const val TTL_MS = 7 * 86_400_000L
        val runName = Regex("bluetooth_reconnect_run_[0-9]{19}\\.log")
        private val kinds = setOf("run", "bookmark", "adapter", "epoch", "hfp-client", "a2dp-sink", "hfp-audio", "hfp-voice", "coverage", "lifecycle", "session")
        private val states = setOf("recording", "saved", "expired", "unclean", "revoked", "unknown", "on", "off", "turning-on", "turning-off", "connected", "connecting", "disconnected", "disconnecting", "started", "stopped", "foreground", "background", "registered", "permission_missing", "unavailable", "malformed_event", "capacity", "disabled", "authenticated", "retired", "created", "phone_on_media_off", "media_seen_on", "oal_selected", "bt_selected", "returned_to_car", "play_pressed")
        private val row = Regex("bt_reconnect_v1 run=[0-9]{1,19} seq=[0-9]{1,19} ingress_ms=[0-9]{1,19} epoch=[0-9]{1,10} device=[0-8] kind=(?:${kinds.joinToString("|")}) state=(?:${states.joinToString("|")}) previous=(?:${states.joinToString("|")}) authority=(?:user_report|advisory_unknown_origin|own_process) session=[0-9]{1,10}")
        fun validRow(value: String) = value.length <= 512 && row.matches(value)
    }

    /** Reserve disk capacity before admission; no automatic re-arm or permission prompt. */
    @Synchronized fun start(consent: Boolean): Boolean {
        try {
            val admissionRevision = synchronized(lock) { if (!consent || closed || running || draining || terminal != null) return false; revision }
            if (!recoverPreviousRuns()) return false
            val retained = directory.listFiles()?.filter { runName.matches(it.name) }?.sortedByDescending { it.lastModified() } ?: return storageFailure()
            for (file in retained.drop(2)) if (!file.delete()) return storageFailure()
            val token = (java.security.SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(1)
            val target = File(directory, "bluetooth_reconnect_run_%019d.log".format(java.util.Locale.ROOT, token))
            synchronized(lock) {
                if (closed || running || draining || revision != admissionRevision) return false
                revision++
                active = target; run = token; startMs = clock(); seq = 0; epoch = 0; detail = 0
                dropped = 0; rejected = 0; discontinuities = 0; lastPersisted = 0; lastStates.clear(); failed = false; ring.clear(); queue.clear()
                attempted = 0; saved = 0; lost = 0; failedBatches = 0
                running = true
                mutableStatus.value = "Recording locally (10 minutes); delivery not guaranteed; WP PRE unsupported"
                enqueue("run", "recording", "unknown", 0, 0)
            }
            return true
        } catch (_: java.io.IOException) { return storageFailure() }
        catch (_: SecurityException) { return storageFailure() }
    }

    /** On a new process/access: missed interval is unknown, and no old consent is restored. */
    @Synchronized internal fun recoverPreviousRuns(): Boolean {
        try {
            synchronized(lock) { if (running || draining) return true }
            if (!directory.exists() && !directory.mkdirs()) return storageFailure()
            val pending = directory.listFiles()?.filter { it.name.matches(Regex("bluetooth_reconnect_run_[0-9]{19}\\.log\\.pending")) } ?: return storageFailure()
            for (file in pending) if (!file.delete()) return storageFailure()
            val files = directory.listFiles()?.filter { runName.matches(it.name) } ?: return storageFailure()
            val now = System.currentTimeMillis()
            for (file in files) {
                if (file == synchronized(lock) { active }) continue
                if (file.length() > MAX_BYTES || file.lastModified() < now - TTL_MS) {
                    if (!file.delete()) return storageFailure()
                } else {
                    val text = file.readText()
                    // A stop reason is not a durability receipt. Legacy runs and checkpoints without
                    // an atomically co-written terminal census have unknown trailing coverage.
                    val terminalDurable = text.lineSequence().any {
                        it.startsWith("coverage ") && it.contains(" terminal_durable=true ")
                    }
                    if (terminalDurable || text.contains("unclean_process_gap")) continue
                    // Do not invent death time or a continuous elapsed interval across restart/boot.
                    val bytes = text + "\ncoverage=unclean_process_gap persistence=unknown incomplete=true missed_interval=unknown restart_requires_consent=true\n"
                    if (bytes.toByteArray().size > MAX_BYTES) return storageFailure()
                    val modified = file.lastModified()
                    if (!atomic(file, bytes)) return storageFailure()
                    file.setLastModified(modified)
                }
            }
            return true
        } catch (_: java.io.IOException) { return storageFailure() }
        catch (_: SecurityException) { return storageFailure() }
    }

    /** Expected filesystem failures are unknown coverage, never an empty inventory.
     * Catch only IO/permission failures at the disk boundary, not coroutine cancellation.
     */
    private fun storageFailure(): Boolean = synchronized(lock) {
        failed = true; running = false; ring.clear()
        mutableStatus.value = "Storage failed — incomplete evidence; coverage unknown; retry with fresh consent"
        false
    }

    fun bookmark(kind: String): Boolean = if (kind in setOf("phone_on_media_off", "media_seen_on", "oal_selected", "bt_selected", "returned_to_car", "play_pressed")) offer("bookmark", kind) else false

    internal fun offer(kind: String, state: String, previous: String = "unknown", device: Int = 0, session: Int = 0): Boolean = synchronized(lock) {
        if (!running) return false
        if (clock() - startMs >= DURATION_MS) { finish("expired"); return false }
        if (kind !in kinds || state !in states || previous !in states || device !in 0..8 || session !in 0..Int.MAX_VALUE) {
            rejected++; return false
        }
        if (kind == "epoch") { epoch++; lastStates.clear() }
        if (kind in setOf("hfp-client", "a2dp-sink", "hfp-audio", "hfp-voice") && device > 0) {
            val key = kind to device
            val last = lastStates[key]
            if (last != null && previous != "unknown" && previous != last) discontinuities++
            lastStates[key] = state
        }
        enqueue(kind, state, previous, device, session)
    }

    private fun enqueue(kind: String, state: String, previous: String, device: Int, session: Int): Boolean {
        val now = clock().coerceAtLeast(0)
        val authority = if (kind == "bookmark") "user_report" else if (kind in setOf("adapter", "hfp-client", "a2dp-sink", "hfp-audio", "hfp-voice")) "advisory_unknown_origin" else "own_process"
        val value = "bt_reconnect_v1 run=$run seq=${++seq} ingress_ms=$now epoch=$epoch device=$device kind=$kind state=$state previous=$previous authority=$authority session=$session"
        check(validRow(value))
        if (queue.size >= 64 || detail >= 1024) { dropped++; return false }
        detail++; queue.addLast(value)
        ring.addLast(now to value)
        while (ring.size > 256 || (ring.isNotEmpty() && now - ring.first.first > 120_000)) ring.removeFirst()
        schedule()
        return true
    }

    private fun schedule() {
        if (draining) return
        draining = true
        worker.execute { drain() }
    }

    private fun finish(reason: String) {
        if (!running) return
        running = false
        terminal = "bt_reconnect_v1 run=$run seq=${++seq} ingress_ms=${clock().coerceAtLeast(0)} epoch=$epoch device=0 kind=run state=$reason previous=unknown authority=own_process session=0"
        ring.clear()
        mutableStatus.value = "Stopping — durability pending"
        schedule()
    }

    fun stop() = synchronized(lock) { finish("saved") }
    internal fun expire() = synchronized(lock) { if (running && clock() - startMs >= DURATION_MS) finish("expired") }
    internal fun isRunning() = synchronized(lock) { running }
    internal fun token() = synchronized(lock) { run }
    internal fun remainingMs() = synchronized(lock) { if (running) (DURATION_MS - (clock() - startMs)).coerceIn(0, DURATION_MS) else 0L }
    internal fun revoke() = synchronized(lock) { revision++; running = false; queue.clear(); terminal = null; ring.clear(); lastStates.clear(); mutableStatus.value = "Revoked — deletion pending" }

    private fun drain() {
        while (true) {
            val batch: List<String>
            val end: String?
            val file: File
            val census: String
            val expectedRevision: Long
            val count: Int
            synchronized(lock) {
                if (queue.isEmpty() && terminal == null) { draining = false; lock.notifyAll(); return }
                batch = queue.toList(); queue.clear(); end = terminal; terminal = null
                file = active ?: run { draining = false; lock.notifyAll(); return }
                expectedRevision = revision
                count = batch.size + if (end == null) 0 else 1
                attempted += count
                // Only a successful atomic replacement can publish this prospective receipt.
                // Open checkpoints are always pending: an unwritable tail/terminal cannot erase
                // that uncertainty, even when no later write can make the failure count durable.
                val persistence = if (failed) "incomplete" else if (end == null) "pending" else "complete"
                census = "coverage dropped=$dropped rejected=$rejected discontinuities=$discontinuities detail=$detail last_durable_ms=$lastPersisted persistence=$persistence attempted=$attempted saved=${saved + count} lost=$lost failed_batches=$failedBatches terminal_durable=${end != null} coverage_scope=admitted_rows terminal_state=stop_reason_only process_gap=unobserved wp_pre=unsupported policy=unobservable selected_phone=unknown\n"
            }
            try {
                beforeWrite()
                val prior = if (file.exists()) file.readLines().filterNot { it.startsWith("coverage ") }.joinToString("\n", postfix = "\n") else ""
                val bytes = prior + batch.joinToString("\n", postfix = if (batch.isNotEmpty()) "\n" else "") + (end?.plus("\n") ?: "") + census
                if (bytes.toByteArray().size > MAX_BYTES || !atomic(file, bytes)) throw java.io.IOException()
                synchronized(lock) { saved += count; lastPersisted = clock(); if (expectedRevision == revision && end != null && !failed) mutableStatus.value = if (end.contains("state=expired")) "Expired — saved locally; fresh consent required" else "Saved locally — upload remains manual" }
            } catch (_: Exception) {
                synchronized(lock) { lost += count; failedBatches++; failed = true; running = false; ring.clear(); if (expectedRevision == revision) mutableStatus.value = "Storage failed — incomplete evidence; trailing durability unknown; fresh consent required" }
            }
        }
    }

    private fun atomic(file: File, content: String): Boolean {
        val pending = File(file.parentFile, file.name + ".pending")
        return try {
            pending.outputStream().use { stream -> stream.write(content.toByteArray()); stream.fd.sync() }
            if (!pending.renameTo(file)) throw java.io.IOException()
            true
        } catch (_: Exception) { pending.delete(); false }
    }

    fun flush(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceIn(1, 2000))
        synchronized(lock) {
            while (draining) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                try { lock.wait(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining))) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
            }
            return !failed && queue.isEmpty() && terminal == null
        }
    }

    /** Revokes ingress first. Run deletion waits on IO worker, not on main/Bluetooth callbacks. */
    @Synchronized fun delete(): Boolean {
        revoke()
        // A failed write is not a reason to prohibit deletion; only outstanding IO is.
        flush(2000)
        val ok = try {
            if (synchronized(lock) { draining }) false
            else if (java.nio.file.Files.notExists(directory.toPath())) true
            else directory.listFiles()?.filter { runName.matches(it.name) || it.name.matches(Regex("bluetooth_reconnect_run_[0-9]{19}\\.log\\.pending")) }?.all { it.delete() } ?: false
        } catch (_: java.io.IOException) { false }
          catch (_: SecurityException) { false }
        if (!ok) synchronized(lock) { failed = true }
        mutableStatus.value = if (ok) "Off — local evidence deleted" else "Deletion failed — evidence retained or unknown; retry"
        return ok
    }

    override fun close() { synchronized(lock) { finish("saved"); closed = true }; worker.shutdown() }
}

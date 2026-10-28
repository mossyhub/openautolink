package com.openautolink.app.diagnostics

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID

/** Finite coverage only. No local report, package name, path or throwable can enter this type. */
enum class MediaCoverageStatus { NOT_COLLECTED, COMPLETE, PARTIAL, DENIED, FAILED, TRUNCATED }
enum class MediaHandlerKind { FIXED_MEDIA_HOST, OTHER, UNRESOLVED, DENIED, FAILED, NOT_COLLECTED }
data class MediaResourceCounts(val present: Int = 0, val absent: Int = 0, val denied: Int = 0, val failed: Int = 0, val truncated: Int = 0)
data class MediaCoverage(val status: MediaCoverageStatus = MediaCoverageStatus.NOT_COLLECTED,
    val processed: Int = 0, val failed: Int = 0, val truncated: Int = 0,
    val inspectionRun: Long = 0, val capturedWall: Long = 0, val elapsedMs: Long = 0,
    val startWall: Long = 0, val sameProcess: Boolean = true,
    val mediaHandler: MediaHandlerKind = MediaHandlerKind.NOT_COLLECTED,
    val homeHandler: MediaHandlerKind = MediaHandlerKind.NOT_COLLECTED,
    val resources: MediaResourceCounts = MediaResourceCounts())
enum class MediaStoreStatus { AVAILABLE, UNAVAILABLE }

/** Serialized process boundary, independent of file logging and UI. Immutable atomic publications. */
class MediaDiagnosticStore(private val directory: () -> File?, private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile var status = MediaStoreStatus.AVAILABLE; private set
    fun save(own: MediaOwnSnapshot, coverage: MediaCoverage,
        outcomes: MediaOutcomeSummary.Snapshot = MediaOutcomeSummary.process.snapshot(),
        accepted: () -> Boolean = { true }): Long? = synchronized(lock) {
        var atomic: AtomicFile? = null
        var stream: java.io.FileOutputStream? = null
        try {
            if (!accepted()) return@synchronized null
            val dir = directory() ?: throw java.io.IOException()
            check(dir.mkdirs() || dir.isDirectory)
            val now = clock().coerceAtLeast(1)
            val maximum = dir.listFiles().orEmpty().filter { runName.matches(it.name) }
                .mapNotNull { sequence(it.name) }.maxOrNull() ?: 0L
            val run = maxOf(now, maximum + 1, lastSequence + 1)
            check(run > 0)
            val assignedCoverage = if (coverage.inspectionRun == 0L && coverage.status != MediaCoverageStatus.NOT_COLLECTED) coverage.copy(inspectionRun = run) else coverage
            val content = encode(run, now, own, assignedCoverage, outcomes)
            check(content.size <= MAX_BYTES)
            val file = File(dir, "media_integration_run_${run.toString().padStart(19, '0')}.log")
            check(!file.exists())
            atomic = AtomicFile(file)
            stream = atomic.startWrite()
            stream.write(content)
            if (!accepted()) { atomic.failWrite(stream); stream = null; return@synchronized null }
            atomic.finishWrite(stream)
            stream = null
            file.setLastModified(now)
            lastSequence = run
            status = MediaStoreStatus.AVAILABLE
            pruneLocked(dir, now)
            run
        } catch (_: Exception) {
            if (stream != null) try { atomic?.failWrite(stream) } catch (_: Exception) { }
            status = MediaStoreStatus.UNAVAILABLE
            null
        }
    }
    fun prune() = synchronized(lock) { try { directory()?.let { pruneLocked(it, clock()) } } catch (_: Exception) { status = MediaStoreStatus.UNAVAILABLE } }
    private fun pruneLocked(dir: File, now: Long) {
        val files = dir.listFiles().orEmpty().filter { runName.matches(it.name) }.sortedByDescending { it.name }
        files.filter { now - it.lastModified() > TTL_MS }.forEach { it.delete() }
        files.filter { it.exists() }.drop(MAX_RUNS).forEach { it.delete() }
    }
    companion object {
        const val MAX_BYTES = 32768L
        const val MAX_RUNS = 3
        const val TTL_MS = 7 * 86_400_000L
        val runName = Regex("media_integration_run_[0-9]{19}\\.log")
        private val lock = Any()
        private var lastSequence = 0L
        private val processNonce = UUID.randomUUID().toString()
        @Volatile var latestCoverage = MediaCoverage()
        private data class Terminal(val context: Context, val own: MediaOwnSnapshot?,
            val outcomes: MediaOutcomeSummary.Snapshot, val coverage: MediaCoverage)
        private val terminalLock = Object()
        private val terminals = java.util.ArrayDeque<Terminal>()
        private var terminalRunning = false
        /** Latest-three bounded handoff. No filesystem, logger, PM or HTTP on producer threads. */
        fun retainTerminal(context: Context, own: MediaOwnSnapshot?, outcomes: MediaOutcomeSummary.Snapshot) {
            synchronized(terminalLock) {
                if (terminals.size == MAX_RUNS) terminals.removeFirst()
                terminals.addLast(Terminal(context.applicationContext, own, outcomes, latestCoverage))
                if (terminalRunning) return
                terminalRunning = true
                try { Thread({
                    while (true) {
                        val next = synchronized(terminalLock) {
                            if (terminals.isEmpty()) {
                                terminalRunning = false; terminalLock.notifyAll(); null
                            } else terminals.removeFirst()
                        } ?: break
                        forContext(next.context).save(next.own ?: MediaDiagnosticHooks.own(), next.coverage, next.outcomes)
                    }
                }, "oal-media-terminal").apply { isDaemon = true }.start() }
                catch (_: Exception) { terminalRunning = false; terminalLock.notifyAll() }
            }
        }
        fun retainForegroundTerminal(context: Context, outcomes: MediaOutcomeSummary.Snapshot) = retainTerminal(context, null, outcomes)
        fun flushTerminals(timeoutMs: Long = 2000): Boolean = synchronized(terminalLock) {
            val end = System.nanoTime() + timeoutMs.coerceIn(0, 10000) * 1_000_000
            while (terminalRunning) {
                val remaining = end - System.nanoTime()
                if (remaining <= 0) return@synchronized false
                try { terminalLock.wait(maxOf(1, remaining / 1_000_000)) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); return@synchronized false }
            }
            terminals.isEmpty()
        }
        fun sequence(name: String): Long? = if (runName.matches(name)) name.removePrefix("media_integration_run_").removeSuffix(".log").toLongOrNull() else null
        fun forContext(context: Context) = MediaDiagnosticStore({ context.getExternalFilesDir(null)?.let { File(it, "openautolink/logs") } })
        fun prepareForManualUpload(context: Context): MediaStoreStatus {
            if (!flushTerminals()) return MediaStoreStatus.UNAVAILABLE
            val store = forContext(context)
            store.prune()
            store.save(MediaDiagnosticHooks.own(), latestCoverage)
            if (!com.openautolink.app.media.MediaReadinessDiagnostics.retain(context)) return MediaStoreStatus.UNAVAILABLE
            return store.status
        }
        private fun encode(run: Long, now: Long, own: MediaOwnSnapshot, c: MediaCoverage, o: MediaOutcomeSummary.Snapshot): ByteArray = buildString {
            appendLine("media_summary_schema=2")
            appendLine("run=$run")
            appendLine("process=$processNonce")
            appendLine("captured_ms=$now")
            appendLine("initialized=${own.initialized}")
            appendLine("token_present=${own.tokenPresent}")
            appendLine("active=${own.active}")
            appendLine("phone_playing=${own.phonePlaying}")
            appendLine("submitted=${own.submitted}")
            appendLine("cached=${own.cached}")
            appendLine("mode=${own.mode}")
            appendLine("owner=${own.owner}")
            appendLine("admission=${own.admission}")
            appendLine("inspection_status=${c.status}")
            appendLine("media_handler=${c.mediaHandler}")
            appendLine("home_handler=${c.homeHandler}")
            appendLine("resources_present=${c.resources.present.coerceIn(0, 16)}")
            appendLine("resources_absent=${c.resources.absent.coerceIn(0, 16)}")
            appendLine("resources_denied=${c.resources.denied.coerceIn(0, 16)}")
            appendLine("resources_failed=${c.resources.failed.coerceIn(0, 16)}")
            appendLine("resources_truncated=${c.resources.truncated.coerceIn(0, 16)}")
            appendLine("inspection_run=${c.inspectionRun.coerceAtLeast(0)}")
            appendLine("inspection_start_ms=${c.startWall.coerceAtLeast(0)}")
            appendLine("inspection_end_ms=${c.capturedWall.coerceAtLeast(0)}")
            appendLine("inspection_elapsed_ms=${c.elapsedMs.coerceAtLeast(0)}")
            appendLine("inspection_processed=${c.processed.coerceIn(0, 1000000)}")
            appendLine("inspection_failed=${c.failed.coerceIn(0, 1000000)}")
            appendLine("inspection_truncated=${c.truncated.coerceIn(0, 1000000)}")
            appendLine("age_semantics=WALL_APPROXIMATE_MONOTONIC_UNKNOWN")
            appendLine("clock_rollback=${c.capturedWall > now}")
            appendLine("control_received=${o.controlReceived}")
            appendLine("playback_submitted=${o.playbackSubmitted}")
            appendLine("metadata_submitted=${o.metadataSubmitted}")
            appendLine("losses=${o.losses}")
            appendLine("rejected=${o.rejected}")
            appendLine("last_outcome=${o.last}")
            appendLine("submission_failed=${o.submissionFailed}")
            appendLine("recoveries=${o.recoveries}")
            appendLine("last_recovery=${o.lastRecovery}")
            appendLine("foreground_events=${o.foregroundEvents}")
            appendLine("foreground_rejected=${o.foregroundRejected}")
            appendLine("last_foreground=${o.lastForeground}")
            appendLine("foreground_stage=${o.foregroundStage?.name ?: "UNKNOWN"}")
            appendLine("foreground_status=${o.foregroundStatus?.name ?: "UNKNOWN"}")
            appendLine("foreground_mask=${o.foregroundMask}")
            appendLine("before_process=UNKNOWN")
        }.toByteArray(Charsets.UTF_8)
        /** Whitelist schema validation at upload boundary, not just a filename/size check. */
        fun valid(file: File): Boolean = try {
            if (!file.isFile || file.length() !in 1..MAX_BYTES || !runName.matches(file.name)) false
            else {
                val rows = file.readText(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
                val schema = rows.firstOrNull()?.substringAfter('=')
                val added = setOf("submission_failed", "recoveries", "last_recovery", "foreground_events", "foreground_rejected", "last_foreground", "foreground_stage", "foreground_status", "foreground_mask")
                val expected = encode(sequence(file.name)!!, 1, MediaOwnSnapshot(), MediaCoverage(), MediaOutcomeSummary.Snapshot())
                    .toString(Charsets.UTF_8).trim().lines().map { it.substringBefore('=') }
                    .filter { schema != "1" || it !in added }
                val values = rows.map { it.substringAfter('=', "") }
                rows.map { it.substringBefore('=') } == expected && rows.zip(values).all { (row, value) ->
                    when (row.substringBefore('=')) {
                        "media_summary_schema" -> value in listOf("1", "2")
                        "run" -> value == sequence(file.name).toString()
                        "process" -> Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(value)
                        "initialized", "token_present", "clock_rollback" -> value in listOf("true", "false")
                        "active", "phone_playing", "before_process" -> value in MediaTruth.entries.map { it.name }
                        "submitted", "cached" -> value in MediaSubmittedState.entries.map { it.name }
                        "mode" -> value in MediaExperiment.entries.map { it.name }
                        "owner" -> value in MediaOwnerKind.entries.map { it.name }
                        "admission" -> value in MediaAdmission.entries.map { it.name }
                        "inspection_status" -> value in MediaCoverageStatus.entries.map { it.name }
                        "media_handler", "home_handler" -> value in MediaHandlerKind.entries.map { it.name }
                        "last_outcome", "last_recovery" -> value in MediaOutcomeSummary.Outcome.entries.map { it.name }
                        "last_foreground" -> value in MediaOutcomeSummary.ForegroundOutcome.entries.map { it.name }
                        "foreground_stage" -> value == "UNKNOWN" || value in com.openautolink.app.carplay.CarPlayForegroundStage.entries.map { it.name }
                        "foreground_status" -> value == "UNKNOWN" || value in com.openautolink.app.carplay.CarPlayForegroundStatus.entries.map { it.name }
                        "foreground_mask" -> value in com.openautolink.app.carplay.CarPlayForegroundMask.entries.map { it.name }
                        "age_semantics" -> value == "WALL_APPROXIMATE_MONOTONIC_UNKNOWN"
                        else -> Regex("[0-9]{1,19}").matches(value) && value.toLongOrNull() != null
                    }
                }
            }
        } catch (_: Exception) { false }
    }
}

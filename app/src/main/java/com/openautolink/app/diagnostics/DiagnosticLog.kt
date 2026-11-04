package com.openautolink.app.diagnostics

import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A single local log entry captured by [DiagnosticLog].
 */
data class LocalLogEntry(
    val timestamp: Long,
    val level: DiagnosticLevel,
    val tag: String,
    val message: String,
)

internal const val MAX_LOCAL_DIAGNOSTIC_MESSAGE_LENGTH = 500

internal fun fitLocalDiagnosticMessage(message: String): String =
    message.take(MAX_LOCAL_DIAGNOSTIC_MESSAGE_LENGTH)

/**
 * Global diagnostic logger — subsystems call this to emit diagnostic log events.
 *
 * This is a thin facade over [RemoteDiagnostics] that allows any subsystem
 * to log without holding a direct reference to the diagnostics instance.
 * The session manager sets the active instance; when null, calls are no-ops.
 *
 * Local buffering is **always on** — entries are kept in a fixed-capacity
 * ring buffer (500 entries, ~500 KB worst case) and snapshotted to a
 * StateFlow for UI collection. This ensures logs are available when the
 * diagnostics screen opens, even if the log-producing event happened earlier.
 *
 * Thread-safe: synchronized ring buffer, volatile flags.
 */
object DiagnosticLog {

    private const val MAX_LOCAL_ENTRIES = 500

    /**
     * StateFlow update coalescing window. Per-entry list rebuilds were a major
     * source of GC pressure during long sessions (every JNI/Kotlin log line
     * allocated a 500-element list). We now rebuild at most this often.
     */
    private const val SNAPSHOT_INTERVAL_MS = 250L

    /**
     * When false (default), DEBUG entries are dropped on the floor before
     * touching the ring, file logger, or remote sinks. Flip via [setVerbose]
     * when investigating an issue.
     */
    @Volatile
    private var verbose: Boolean = false

    fun setVerbose(enabled: Boolean) { verbose = enabled }
    fun isVerbose(): Boolean = verbose

    /**
     * Called from native C++ via JNI to route native log lines into the
     * Kotlin diagnostic pipeline (ring buffer, file logger, TCP server).
     * Registered in aasdk_jni.cpp JNI_OnLoad.
     */
    @JvmStatic
    fun nativeLog(level: Int, tag: String, msg: String) {
        val diagLevel = when (level) {
            3 -> DiagnosticLevel.DEBUG   // ANDROID_LOG_DEBUG
            4 -> DiagnosticLevel.INFO    // ANDROID_LOG_INFO
            5 -> DiagnosticLevel.WARN    // ANDROID_LOG_WARN
            else -> DiagnosticLevel.ERROR // ANDROID_LOG_ERROR + anything else
        }
        if (diagLevel == DiagnosticLevel.DEBUG && !verbose) return
        addLocal(diagLevel, tag, msg)
        instance?.log(diagLevel, tag, msg)
    }

    @Volatile
    var instance: RemoteDiagnostics? = null

    /** File log writer for USB stick logging. Set by ProjectionViewModel. */
    @Volatile
    var fileLogWriter: FileLogWriter? = null

    private val ring = ArrayDeque<LocalLogEntry>(MAX_LOCAL_ENTRIES)
    private val _localLogs = MutableStateFlow<List<LocalLogEntry>>(emptyList())
    val localLogs: StateFlow<List<LocalLogEntry>> = _localLogs.asStateFlow()

    /** No-op — local capture is always active. Kept for API compatibility. */
    fun startLocalCapture() { /* always on */ }

    /** No-op — logs are retained across screen navigation. Kept for API compatibility. */
    fun stopLocalCapture() { /* don't clear */ }

    fun d(tag: String, msg: String) {
        if (!verbose) return
        addLocal(DiagnosticLevel.DEBUG, tag, msg)
        instance?.log(DiagnosticLevel.DEBUG, tag, msg)
    }

    fun i(tag: String, msg: String) {
        addLocal(DiagnosticLevel.INFO, tag, msg)
        instance?.log(DiagnosticLevel.INFO, tag, msg)
    }

    fun w(tag: String, msg: String) {
        addLocal(DiagnosticLevel.WARN, tag, msg)
        instance?.log(DiagnosticLevel.WARN, tag, msg)
    }

    fun e(tag: String, msg: String) {
        addLocal(DiagnosticLevel.ERROR, tag, msg)
        instance?.log(DiagnosticLevel.ERROR, tag, msg)
    }

    fun clearLocal() {
        synchronized(ring) { ring.clear() }
        _localLogs.value = emptyList()
        snapshotPending.set(false)
        snapshotHandler.removeCallbacks(snapshotRunnable)
    }

    // Coalesced StateFlow snapshot. We post a single delayed runnable on a
     // background HandlerThread; only one snapshot is in flight at a time.
     // This collapses bursts of log entries (common during connect/IDR/SDR)
     // into one allocation per [SNAPSHOT_INTERVAL_MS].
     private val snapshotThread = HandlerThread("OalLogSnap").apply {
         isDaemon = true
         start()
     }
     private val snapshotHandler = Handler(snapshotThread.looper)
     private val snapshotPending = AtomicBoolean(false)
     private val snapshotRunnable = Runnable {
         snapshotPending.set(false)
         val snapshot = synchronized(ring) { ring.toList() }
         _localLogs.value = snapshot
     }

     private fun addLocal(level: DiagnosticLevel, tag: String, msg: String) {
        val wallMs = System.currentTimeMillis()
        // Independent best-effort live observer sees the original emitted message, before
        // the existing UI's 500-character cap. File logs and LogUploader are unchanged.
        runCatching { com.openautolink.app.diagnostics.live.LiveLogSink.diagnostic(wallMs, level.name, tag, msg) }
        val entry = LocalLogEntry(
            timestamp = wallMs,
            level = level,
            tag = tag,
            message = fitLocalDiagnosticMessage(msg),
        )
        synchronized(ring) {
            if (ring.size >= MAX_LOCAL_ENTRIES) ring.pollFirst()
            ring.addLast(entry)
        }
        // Coalesce StateFlow rebuilds — at most one every SNAPSHOT_INTERVAL_MS.
        if (snapshotPending.compareAndSet(false, true)) {
            snapshotHandler.postDelayed(snapshotRunnable, SNAPSHOT_INTERVAL_MS)
        }
        // Stream entry to file log writer if active. The other historical
        // streaming targets (TCP log server, reverse ADB tunnel) were
        // removed — file + the in-app diagnostics screen are the only
        // sinks now.
        fileLogWriter?.write(entry)
    }
}

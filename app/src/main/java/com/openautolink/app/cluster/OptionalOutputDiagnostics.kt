package com.openautolink.app.cluster

import com.openautolink.app.diagnostics.DiagnosticLog
import java.util.concurrent.atomic.AtomicLong

/** Finite, non-secret renderer receipts. Never pass exception messages, preferences or payloads.
 * Hot paths update counters; only first boundaries and a five-second census reach the file sink.
 * Session/output are the existing projection lease, not phone/device identity.
 */
internal class OptionalOutputDiagnostics(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sink: (Boolean, String) -> Unit = { warn, line ->
        if (warn) DiagnosticLog.w("optional-video", line) else DiagnosticLog.i("optional-video", line)
    },
) {
    private val renderer = ids.incrementAndGet()
    @Volatile private var lease = ClusterOutputLease(-1, -1)
    @Volatile private var epoch = -1L
    private var firstDecoded = false
    private var firstPresented = false
    private var nextSummary = clockMs() + 5000
    fun bind(value: ClusterOutputLease) { lease = value }
    fun stream(value: Long) {
        if (epoch == value) return
        epoch = value; firstDecoded = false; firstPresented = false
        event("stream_begin")
    }
    fun event(stage: String, fields: String = "", warn: Boolean = false) {
        val owner = lease
        // Call sites exclusively supply finite labels/numeric geometry. Independent of verbose.
        val line = "OPTIONAL_VIDEO_V1 session=${owner.projectionEpoch} output=${owner.outputEpoch} renderer=$renderer stream=$epoch stage=$stage $fields"
        runCatching { sink(warn, line.take(480)) }
    }
    fun progress(decoded: Long, presented: Long, pts: Long) {
        if (decoded > 0 && !firstDecoded) { firstDecoded = true; event("first_decode", "ptsUs=$pts") }
        if (presented > 0 && !firstPresented) {
            firstPresented = true
            event("FIRST_PRESENT", "ptsUs=$pts proof=texture_latched_draw_swap oemVisibility=unknown")
        }
    }
    fun summary(fields: String, terminal: Boolean = false) {
        val now = clockMs()
        if (!terminal && now < nextSummary) return
        nextSummary = now + 5000
        event(if (terminal) "terminal" else "summary", fields)
    }
    fun summaryDue(): Boolean = clockMs() >= nextSummary
    fun failure(operation: String, failure: Exception) {
        val code = (failure as? ClusterGraphicsFailure)?.code ?: (failure as? android.media.MediaCodec.CodecException)?.errorCode ?: 0
        event("failure", "operation=$operation reason=${reason(failure)} code=$code", true)
    }
    companion object {
        private val ids = AtomicLong()
        fun reason(failure: Exception): String = when (failure) {
            is ClusterGraphicsFailure -> failure.reason
            is android.media.MediaCodec.CodecException -> "codec_exception"
            is IllegalArgumentException -> "invalid_argument"
            is IllegalStateException -> "illegal_state"
            is SecurityException -> "security_exception"
            is java.io.IOException -> "io_exception"
            else -> "platform_exception"
        }
        /** Codec component names are technical identifiers; do not admit arbitrary text. */
        fun codecName(name: String?): String = name?.takeIf {
            it.length <= 160 && it.matches(Regex("[A-Za-z0-9_.-]+"))
        } ?: "unavailable"
    }
}
internal class ClusterGraphicsFailure(val reason: String, val code: Int) : IllegalStateException(reason)

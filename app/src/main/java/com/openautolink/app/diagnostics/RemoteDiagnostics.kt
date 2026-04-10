package com.openautolink.app.diagnostics

import com.openautolink.app.transport.ControlMessage

/**
 * Remote diagnostics interface — sends structured logs and telemetry
 * to the bridge over the control channel for SSH-based observation.
 */
interface RemoteDiagnostics {
    /** Whether remote diagnostics are currently active. */
    val enabled: Boolean

    /** Current minimum log level filter. */
    val minLevel: DiagnosticLevel

    /** Enable/disable remote diagnostics. */
    fun setEnabled(enabled: Boolean)

    /** Set minimum log level to send. */
    fun setMinLevel(level: DiagnosticLevel)

    /** Log a diagnostic event. Rate-limited to 20/sec. */
    fun log(level: DiagnosticLevel, tag: String, msg: String)

    /** Send a telemetry snapshot. Called periodically (every 5s). */
    fun sendTelemetry(telemetry: TelemetrySnapshot)
}

enum class DiagnosticLevel {
    DEBUG, INFO, WARN, ERROR;

    fun toWire(): String = name

    companion object {
        fun fromWire(value: String): DiagnosticLevel =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: INFO
    }
}

data class TelemetrySnapshot(
    val video: VideoTelemetry? = null,
    val audio: AudioTelemetry? = null,
    val session: SessionTelemetry? = null,
    val cluster: ClusterTelemetry? = null,
)

data class VideoTelemetry(
    val fps: Float,
    val decoded: Long,
    val dropped: Long,
    val codec: String,
    val width: Int,
    val height: Int,
    val bitrateKbps: Float = 0f,
)

data class AudioTelemetry(
    val active: List<String>,
    val underruns: Map<String, Long>,
    val framesWritten: Map<String, Long>,
    val flowDrops: Long = 0,
    val flowEmits: Long = 0,
    val maxWriteMs: Map<String, Long> = emptyMap(),
    val slowWrites: Map<String, Long> = emptyMap(),
    val maxGapMs: Map<String, Long> = emptyMap(),
    val hwUnderruns: Map<String, Long> = emptyMap(),
)

data class SessionTelemetry(
    val state: String,
    val uptimeMs: Long,
)

data class ClusterTelemetry(
    val bound: Boolean,
    val alive: Boolean,
    val rebinds: Int,
)

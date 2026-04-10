package com.openautolink.app.diagnostics

import com.openautolink.app.audio.AudioPlayer
import com.openautolink.app.session.SessionState
import com.openautolink.app.transport.ConnectionManager
import com.openautolink.app.video.VideoDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Periodically collects telemetry from app subsystems and sends
 * via [RemoteDiagnostics]. Runs every 5 seconds when diagnostics are enabled.
 */
class TelemetryCollector(
    private val scope: CoroutineScope,
    private val diagnostics: RemoteDiagnostics,
    private val sessionState: StateFlow<SessionState>,
) {

    private var collectJob: Job? = null
    private var sessionStartMs: Long = 0L

    // References set by the session manager as subsystems are created
    var videoDecoder: VideoDecoder? = null
    var audioPlayer: AudioPlayer? = null
    var connectionManager: ConnectionManager? = null
    var clusterBound: Boolean = false
    var clusterAlive: Boolean = false
    var clusterRebinds: Int = 0

    fun start() {
        sessionStartMs = System.currentTimeMillis()
        collectJob?.cancel()
        collectJob = scope.launch {
            while (true) {
                delay(5000)
                if (diagnostics.enabled) {
                    collect()
                }
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }

    private fun collect() {
        val videoStats = videoDecoder?.stats?.value
        val audioStats = audioPlayer?.stats?.value

        val snapshot = TelemetrySnapshot(
            video = videoStats?.let {
                VideoTelemetry(
                    fps = it.fps,
                    decoded = it.framesDecoded,
                    dropped = it.framesDropped,
                    codec = it.codec,
                    width = it.width,
                    height = it.height,
                    bitrateKbps = it.bitrateKbps,
                )
            },
            audio = audioStats?.let {
                val cm = connectionManager
                AudioTelemetry(
                    active = it.activePurposes.map { p -> p.name.lowercase() },
                    underruns = it.underruns.mapKeys { (k, _) -> k.name.lowercase() },
                    framesWritten = it.framesWritten.mapKeys { (k, _) -> k.name.lowercase() },
                    flowDrops = cm?.audioFlowDrops ?: 0,
                    flowEmits = cm?.audioFlowEmits ?: 0,
                    maxWriteMs = it.maxWriteMs.mapKeys { (k, _) -> k.name.lowercase() },
                    slowWrites = it.slowWrites.mapKeys { (k, _) -> k.name.lowercase() },
                    maxGapMs = it.maxGapMs.mapKeys { (k, _) -> k.name.lowercase() },
                    hwUnderruns = it.hwUnderruns.mapKeys { (k, _) -> k.name.lowercase() },
                )
            },
            session = SessionTelemetry(
                state = sessionState.value.name,
                uptimeMs = System.currentTimeMillis() - sessionStartMs,
            ),
            cluster = ClusterTelemetry(
                bound = clusterBound,
                alive = clusterAlive,
                rebinds = clusterRebinds,
            ),
        )

        diagnostics.sendTelemetry(snapshot)
    }
}

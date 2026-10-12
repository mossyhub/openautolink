package com.shilapi.xcertplay.media

import android.media.AudioManager
import java.io.Closeable
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Read-only public snapshots, not owner attribution or proof of audible output.
 * No playback/routing callback is registered: bounded polling also observes zero own owners.
 * Binder queries and platform track getters never run on control/video/audio IO workers.
 */
internal class PassiveAudioRouteDiagnostics(
    private val manager: AudioManager?,
    private val ownSnapshot: () -> List<String>,
    private val report: (String) -> Unit,
) : Closeable {
    private val publication = Any()
    private var closed = false
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task,"carplay-audio-route-diagnostics").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
    }
    init { executor.scheduleWithFixedDelay(::sample,0,5,TimeUnit.SECONDS) }
    private fun sample() {
        // Query outside publication lock: close can revoke promptly even if an OEM Binder stalls.
        val own = runCatching { ownSnapshot() }.getOrDefault(emptyList())
        publish(own)
        val observed = runCatching { checkNotNull(manager).activePlaybackConfigurations }
        val players = observed.getOrNull()
        val lines = if (players == null) listOf(
            "media stage=device-playback-summary scope=device owner=unknown result=unavailable observed=0 emitted=0 truncated=false"
        ) else buildList {
            val count = minOf(players.size,MAX_PLAYERS)
            add("media stage=device-playback-summary scope=device owner=unknown result=ok observed=${minOf(players.size,9999)} emitted=$count truncated=${players.size > MAX_PLAYERS}")
            // Do not read/export UID, PID, player/session/device IDs, address or product name.
            for (index in 0 until count) {
                val player = players[index]
                val attrs = runCatching { player.audioAttributes }.getOrNull()
                val device = runCatching { player.audioDeviceInfo?.type }.getOrNull()
                add("media stage=device-playback index=$index scope=device owner=unknown usage=${bounded(attrs?.usage)} content=${bounded(attrs?.contentType)} device=${bounded(device)}")
            }
        }
        publish(lines)
    }
    private fun publish(lines: List<String>) {
        synchronized(publication) {
            if (closed) return
            lines.forEach { runCatching { report(it) } }
        }
    }
    override fun close() {
        synchronized(publication) { closed = true }
        executor.shutdownNow()
    }
    val isRetired: Boolean get() = executor.isTerminated
    companion object {
        const val MAX_PLAYERS = 8
        fun bounded(value: Int?): Int? = value?.takeIf { it in 0..9999 }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Best-effort observation, not a recorder owner. One process-wide OS query at most, no backlog.
 * A stalled Binder call can occupy this daemon, never capture, close or retirement. There is no
 * cancellation/join of OS calls: revoke only fences their eventual receipt. No OS listeners or
 * capture resources are created. Never inspect recording clients other than this exact recorder.
 */
internal class MicrophonePolicyDiagnostics(
    private val query: () -> List<String>,
    private val report: (String) -> Unit,
) : AutoCloseable {
    private val revoked = AtomicBoolean()
    private val attempts = AtomicInteger()
    fun sample() {
        if (revoked.get()) return
        while (true) {
            val used = attempts.get()
            if (used >= 3) return
            if (attempts.compareAndSet(used, used + 1)) break
        } // Includes rejected offers; never queue/retry or exceed the finite owner budget.
        try {
            queries.execute {
                if (revoked.get()) return@execute
                val receipts = runCatching(query).getOrNull() ?: return@execute
                receipts.forEach { receipt ->
                    if (!revoked.get()) runCatching { report(receipt) }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { /* bounded best effort */ }
    }
    override fun close() { revoked.set(true) }

    companion object {
        private val queries = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue(),
            { task -> Thread(task, "carplay-mic-policy").apply { isDaemon = true } })

        fun forRecorder(app: Context, record: AudioRecord, source: MicrophoneSource, rate: Int,
            report: (String) -> Unit): MicrophonePolicyDiagnostics {
            val routeObserver = OwnRecorderRouteObserver()
            lateinit var policy: MicrophonePolicyDiagnostics
            policy = MicrophonePolicyDiagnostics({
                fun current() = !policy.revoked.get()
                if (!current()) return@MicrophonePolicyDiagnostics emptyList()
                val permission = runCatching {
                    if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        "granted" else "denied"
                }.getOrDefault("unknown")
                if (!current()) return@MicrophonePolicyDiagnostics emptyList()
                val manager = runCatching { app.getSystemService(AudioManager::class.java) }.getOrNull()
                if (!current()) return@MicrophonePolicyDiagnostics emptyList()
                val mute = runCatching { manager!!.isMicrophoneMute.toString() }.getOrDefault("unknown")
                if (!current()) return@MicrophonePolicyDiagnostics emptyList()
                // Share each exact-recorder getter result with old coarse and new logical receipts.
                val routed = runCatching { record.routedDevice }
                if (!current()) return@MicrophonePolicyDiagnostics emptyList()
                val route = routed.mapCatching { device ->
                    when {
                        device == null -> "none"
                        !device.isSource -> "unknown"
                        device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin"
                        device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
                        device.type in setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY) -> "usb"
                        device.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET) -> "bluetooth"
                        else -> "other"
                    }
                }.getOrDefault("unknown")
                // API 29+, module minSdk 32. Null/restricted config does not mean unsilenced.
                val config = runCatching { record.activeRecordingConfiguration }
                if (!current()) return@MicrophonePolicyDiagnostics emptyList()
                val activeDevice = config.mapCatching { it?.audioDevice }
                if (!current()) return@MicrophonePolicyDiagnostics emptyList()
                val logicalRoute = routeObserver.receipt(record, config, ::current, routed, activeDevice)
                val silenced = config.mapCatching { it?.isClientSilenced?.toString() }.getOrNull() ?: "unknown"
                val unknown = listOf(permission, mute, route, silenced).count { it == "unknown" }
                val result = if (unknown == 0) "ok" else if (unknown == 4) "unavailable" else "partial"
                listOf(
                    "media stage=microphone-policy scope=own source=$source rate=$rate permission=$permission mute=$mute route=$route silenced=$silenced result=$result",
                    MicrophoneConfigurationDiagnostics.receipt(record, manager, source, rate, config, activeDevice, ::current),
                ) + (if (current()) MicrophoneEffectDiagnostics.receipts(config, source, rate) else emptyList()) +
                    listOfNotNull(logicalRoute) + routeObserver.mappingReceipts(record, ::current)
            }, report)
            return policy
        }
    }
}

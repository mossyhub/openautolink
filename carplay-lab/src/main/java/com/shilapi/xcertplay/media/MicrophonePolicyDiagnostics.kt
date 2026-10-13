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

/** Best-effort observation, not a recorder owner. One process-wide OS query at most, no backlog.
 * A stalled Binder call can occupy this daemon, never capture, close or retirement. There is no
 * cancellation/join of OS calls: revoke only fences their eventual receipt. No OS listeners or
 * capture resources are created. Never inspect recording clients other than this exact recorder.
 */
internal class MicrophonePolicyDiagnostics(
    private val query: () -> String,
    private val report: (String) -> Unit,
) : AutoCloseable {
    private val revoked = AtomicBoolean()
    private var attempts = 0
    fun sample() {
        if (revoked.get() || attempts >= 3) return
        attempts++ // called only by capture owner; rejected work is deliberately not retried
        try {
            queries.execute {
                if (revoked.get()) return@execute
                val receipt = runCatching(query).getOrNull() ?: return@execute
                if (!revoked.get()) runCatching { report(receipt) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { /* bounded best effort */ }
    }
    override fun close() { revoked.set(true) }

    companion object {
        private val queries = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue(),
            { task -> Thread(task, "carplay-mic-policy").apply { isDaemon = true } })

        fun forRecorder(app: Context, record: AudioRecord, source: MicrophoneSource, rate: Int,
            report: (String) -> Unit): MicrophonePolicyDiagnostics = MicrophonePolicyDiagnostics({
            val permission = runCatching {
                if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    "granted" else "denied"
            }.getOrDefault("unknown")
            val mute = runCatching { app.getSystemService(AudioManager::class.java).isMicrophoneMute.toString() }
                .getOrDefault("unknown")
            val route = runCatching {
                val device = record.routedDevice
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
            // API 29+, available at this module's minSdk 32. Exact recorder configuration only;
            // null/restricted results do not mean unsilenced. No session/device IDs or names.
            val silenced = runCatching { record.activeRecordingConfiguration?.isClientSilenced?.toString() }
                .getOrNull() ?: "unknown"
            val unknown = listOf(permission, mute, route, silenced).count { it == "unknown" }
            val result = if (unknown == 0) "ok" else if (unknown == 4) "unavailable" else "partial"
            "media stage=microphone-policy scope=own source=$source rate=$rate permission=$permission mute=$mute route=$route silenced=$silenced result=$result"
        }, report)
    }
}

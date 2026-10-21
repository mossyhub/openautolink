// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder

/** Only public fields of the exact recorder. No identifiers, names, addresses or vendor mapping.
 * `unknown` means an observed value is outside our finite vocabulary; `unavailable` means a getter
 * failed/null where a value is required; `absent` means no active configuration was returned.
 * Called only by the existing bounded policy query worker, never capture IO.
 */
internal object MicrophoneConfigurationDiagnostics {
    fun receipt(record: AudioRecord, manager: AudioManager?, source: MicrophoneSource, rate: Int,
        config: Result<AudioRecordingConfiguration?>,
        activeDevice: Result<AudioDeviceInfo?> = config.mapCatching { it?.audioDevice },
        current: () -> Boolean = { true }): String {
        fun observe(read: () -> String): String =
            if (current()) runCatching(read).getOrDefault("unavailable") else "unavailable"
        val state = if (config.isFailure) "unavailable" else if (config.getOrNull() == null) "absent" else "present"
        fun field(read: (AudioRecordingConfiguration) -> String): String =
            if (state != "present") state else observe { read(config.getOrThrow()!!) }
        val clientFormat = config.mapCatching { if (current()) it?.clientFormat else null }
        val deviceFormat = config.mapCatching { if (current()) it?.format else null }
        fun format(value: Result<AudioFormat?>, read: (AudioFormat) -> String): String =
            if (state != "present") state else observe { value.getOrThrow()?.let(read) ?: "unavailable" }
        return "media stage=microphone-config scope=own requested=$source rate=$rate" +
            " recorder=${observe { source(record.audioSource) }}" +
            " mode=${observe { manager?.mode?.let(::mode) ?: "unavailable" }} config=$state" +
            " client=${field { source(it.clientAudioSource) }} active=${field { source(it.audioSource) }}" +
            " clientRate=${format(clientFormat) { rate(it.sampleRate) }} deviceRate=${format(deviceFormat) { rate(it.sampleRate) }}" +
            " clientChannels=${format(clientFormat) { channels(it.channelCount) }} deviceChannels=${format(deviceFormat) { channels(it.channelCount) }}" +
            " clientEncoding=${format(clientFormat) { encoding(it.encoding) }} deviceEncoding=${format(deviceFormat) { encoding(it.encoding) }}" +
            " route=${if (state != "present") state else observe { route(activeDevice.getOrThrow()) }}"
    }

    private fun source(value: Int): String = when (value) {
        MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
        MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
        MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
        MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        MediaRecorder.AudioSource.VOICE_PERFORMANCE -> "VOICE_PERFORMANCE"
        else -> "unknown"
    }
    private fun mode(value: Int): String = when (value) {
        AudioManager.MODE_NORMAL -> "normal"
        AudioManager.MODE_RINGTONE -> "ringtone"
        AudioManager.MODE_IN_CALL -> "call"
        AudioManager.MODE_IN_COMMUNICATION -> "communication"
        else -> "unknown"
    }
    private fun rate(value: Int): String = if (value in setOf(8000,16000,24000,32000,44100,48000,96000,192000)) value.toString() else "unknown"
    private fun channels(value: Int): String = when (value) { 1 -> "mono"; 2 -> "stereo"; else -> "unknown" }
    private fun encoding(value: Int): String = when (value) {
        AudioFormat.ENCODING_PCM_16BIT -> "pcm16"
        AudioFormat.ENCODING_PCM_FLOAT -> "float"
        AudioFormat.ENCODING_PCM_8BIT -> "pcm8"
        else -> "unknown"
    }
    private fun route(device: AudioDeviceInfo?): String = when {
        device == null -> "none"
        !device.isSource -> "unknown"
        else -> when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "bluetooth"
            AudioDeviceInfo.TYPE_BUS -> "bus" // Type only; never claim this is GM Call_In_Mic.
            else -> "unknown"
        }
    }
}

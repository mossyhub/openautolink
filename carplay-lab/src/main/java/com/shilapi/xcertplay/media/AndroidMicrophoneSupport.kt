// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder

/** Probe on a receiver/control worker only; initialize/start no recording while probing. */
object AndroidMicrophoneSupport {
    @Volatile private var context: Context? = null
    private var configured: Map<Long, String>? = null
    private var sources: Set<MicrophoneSource> = emptySet()
    @Synchronized fun initialize(context: Context) {
        val app = context.applicationContext
        if (this.context !== app) {
            configured = null
            sources = emptySet()
        }
        this.context = app
    }
    fun permissionGranted(): Boolean = context?.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Parent may use this result to gate config.microphone before starting the receiver. */
    @Synchronized fun probe(context: Context): MicrophoneCapability {
        initialize(context)
        configured = null
        sources = emptySet()
        return capability
    }

    val capability: MicrophoneCapability
        @Synchronized get() {
            if (!permissionGranted()) return MicrophoneCapability.NONE
            val bits = runCatching { names().keys.toSet() }.getOrDefault(emptySet())
            if (!permissionGranted()) return MicrophoneCapability.NONE
            return MicrophoneCapability.of(listOf("default", "telephony", "speechrecognition")
                .filter { MicrophoneSupport.source(it) in sources }.associateWith { bits.toSet() })
        }
    val availableBits: Set<Long> get() {
        val current = capability
        return listOf("default", "telephony", "speechrecognition").flatMap { current.bits(it) }.toSet()
    }
    fun encoderName(bits: Long): String? = if (permissionGranted()) runCatching { names()[bits] }.getOrNull() else null

    @Synchronized private fun names(): Map<Long, String> {
        if (!permissionGranted()) return emptyMap()
        val manager = context?.getSystemService(AudioManager::class.java) ?: return emptyMap()
        val microphoneTypes = setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
        if (manager.getDevices(AudioManager.GET_DEVICES_INPUTS).none { it.isSource && it.type in microphoneTypes })
            return emptyMap()
        configured?.let { return it }
        // Probe each actual capture source independently. A failed call source must not promote MIC.
        sources = MicrophoneSource.values().filter { source ->
            var candidate: AudioRecord? = null
            try {
                val record = recorder(source)
                candidate = record
                record.state == AudioRecord.STATE_INITIALIZED
            } catch (_: Exception) { false }
            finally { runCatching { candidate?.release() } }
        }.toSet()
        if (sources.isEmpty() || !permissionGranted()) return emptyMap()
        val names = OpusEncoder.inventory()
        val result = linkedMapOf<Long, String>()
        MicrophoneSupport.implementedBits.forEach { bit ->
            names.firstOrNull { OpusEncoder.probe(it, if (bit == 0x20000000L) 64000 else 96000) }
                ?.let { result[bit] = it }
        }
        configured = result
        return if (permissionGranted()) result else emptyMap()
    }

    internal fun recorder(source: MicrophoneSource): AudioRecord {
        val app = checkNotNull(context)
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Microphone consent unavailable")
        val minimum = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0)
        return AudioRecord.Builder().setAudioSource(when (source) {
            MicrophoneSource.MIC -> MediaRecorder.AudioSource.MIC
            MicrophoneSource.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            MicrophoneSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }).setAudioFormat(AudioFormat.Builder().setSampleRate(48000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, 1920 * 4)).build()
    }
}

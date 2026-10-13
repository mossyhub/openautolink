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

/** Probe on a receiver/control worker only; never start recording while qualifying offers. */
object AndroidMicrophoneSupport {
    @Volatile private var context: Context? = null
    private var configured: Map<Long, String>? = null
    private var qualified = MicrophoneCapability.NONE
    @Synchronized fun initialize(context: Context) {
        val app = context.applicationContext
        if (this.context !== app) {
            configured = null
            qualified = MicrophoneCapability.NONE
        }
        this.context = app
    }
    fun permissionGranted(): Boolean = context?.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Synchronized fun probe(context: Context): MicrophoneCapability {
        initialize(context)
        configured = null
        qualified = MicrophoneCapability.NONE
        return capability
    }

    val capability: MicrophoneCapability
        @Synchronized get() {
            if (!permissionGranted()) return MicrophoneCapability.NONE
            runCatching { names() }.getOrElse { return MicrophoneCapability.NONE }
            return if (permissionGranted()) qualified else MicrophoneCapability.NONE
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
            return emptyMap<Long, String>().also { qualified = MicrophoneCapability.NONE; configured = null }
        configured?.let { return it }
        // A recorder/encoder qualified for 48k MIC says nothing about 24k VOICE_RECOGNITION.
        val rates = MicrophoneSource.values().associateWith { source ->
            setOf(24000, 48000).filter { rate -> qualifies(source, rate) }.toSet()
        }
        val names = runCatching { OpusEncoder.inventory() }.getOrDefault(emptyList())
        val result = linkedMapOf<Long, String>()
        if (rates.values.any { 48000 in it }) MicrophoneSupport.opusBits.forEach { bit ->
            names.firstOrNull { OpusEncoder.probe(it, MicrophoneSupport.bitrate(bit)) }
                ?.let { result[bit] = it }
        }
        qualified = MicrophoneCapability.of(listOf("default", "telephony", "speechrecognition").associateWith { kind ->
            val sourceRates = rates[MicrophoneSupport.source(kind)].orEmpty()
            buildSet {
                if (24000 in sourceRates) add(0x40L)
                if (48000 in sourceRates) addAll(result.keys)
            }
        })
        configured = result
        return if (permissionGranted()) result else emptyMap()
    }

    private fun qualifies(source: MicrophoneSource, rate: Int): Boolean {
        var record: AudioRecord? = null
        var valid = false
        try {
            record = recorder(source, rate)
            valid = record.state == AudioRecord.STATE_INITIALIZED && record.sampleRate == rate &&
                record.channelCount == 1 && record.audioFormat == AudioFormat.ENCODING_PCM_16BIT
        } catch (_: Exception) { valid = false }
        finally { try { record?.release() } catch (_: Exception) { valid = false } }
        return valid && permissionGranted()
    }

    internal fun policyDiagnostics(record: AudioRecord, source: MicrophoneSource, rate: Int,
        report: (String) -> Unit): MicrophonePolicyDiagnostics =
        MicrophonePolicyDiagnostics.forRecorder(checkNotNull(context), record, source, rate, report)

    internal fun recorder(source: MicrophoneSource, sampleRate: Int = 48000): AudioRecord {
        require(sampleRate in setOf(24000,48000))
        val app = checkNotNull(context)
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Microphone consent unavailable")
        val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0)
        return AudioRecord.Builder().setAudioSource(when (source) {
            MicrophoneSource.MIC -> MediaRecorder.AudioSource.MIC
            MicrophoneSource.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            MicrophoneSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }).setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, sampleRate / 50 * 2 * 4)).build()
    }
}

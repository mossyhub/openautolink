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
import com.shilapi.xcertplay.media.CaptureAcquisitionLedger.Action
import com.shilapi.xcertplay.media.CaptureAcquisitionLedger.Result

/** Probe on a receiver/control worker only; never start recording while qualifying offers. */
object AndroidMicrophoneSupport {
    @Volatile private var context: Context? = null
    private var configured: Map<Long, String>? = null
    // Encoder PCM is always converted 48k mono, independent of recorder qualification.
    private var configuredEncoders: Map<Long, String>? = null
    private var qualified = MicrophoneCapability.NONE
    private data class CaptureShape(val source: MicrophoneSource, val rate: Int, val channels: Int)
    private val releasedShapes = mutableSetOf<CaptureShape>()
    /** Read immediately under the qualification monitor; only validated, released probes count. */
    @Synchronized internal fun supportsSpeechCapture(selection: CarPlayMicrophoneIndependentSelection): Boolean {
        val profile = selection.captureProfile()
        val converter = CarPlayMicrophonePcmConverter(profile, 24000)
        return CaptureShape(MicrophoneSource.VOICE_RECOGNITION, converter.captureRate,
            converter.captureChannels) in releasedShapes
    }
    @Synchronized fun initialize(context: Context) {
        val app = context.applicationContext
        if (this.context !== app) {
            configured = null
            configuredEncoders = null
            qualified = MicrophoneCapability.NONE
        }
        this.context = app
    }
    fun permissionGranted(): Boolean = context?.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Synchronized fun probe(context: Context): MicrophoneCapability {
        initialize(context)
        releasedShapes.clear()
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

    /** Replacement capture owners use encoder evidence independent of legacy mono capture. */
    @Synchronized internal fun experimentEncoderName(bits: Long): String? =
        if (permissionGranted()) runCatching { encoderNames()[bits] }.getOrNull() else null

    @Synchronized private fun encoderNames(): Map<Long, String> {
        if (!permissionGranted()) return emptyMap()
        CaptureAcquisitionLedger.current.get()?.root?.event(Action.ENCODER_CACHE,
            if (configuredEncoders == null) Result.MISS else Result.HIT)
        configuredEncoders?.let { return it }
        val inventory = runCatching { OpusEncoder.inventory() }.getOrDefault(emptyList())
        val result = linkedMapOf<Long, String>()
        MicrophoneSupport.opusBits.forEach { bit ->
            inventory.firstOrNull { OpusEncoder.probe(it, MicrophoneSupport.bitrate(bit)) }
                ?.let { result[bit] = it }
        }
        configuredEncoders = result
        return if (permissionGranted()) result else emptyMap()
    }

    @Synchronized private fun names(): Map<Long, String> {
        if (!permissionGranted()) return emptyMap()
        val manager = context?.getSystemService(AudioManager::class.java) ?: return emptyMap()
        val microphoneTypes = setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
        CaptureAcquisitionLedger.current.get()?.root?.event(Action.INPUTS)
        if (manager.getDevices(AudioManager.GET_DEVICES_INPUTS).none { it.isSource && it.type in microphoneTypes })
            return emptyMap<Long, String>().also { qualified = MicrophoneCapability.NONE; configured = null }
        CaptureAcquisitionLedger.current.get()?.root?.event(Action.RECORDER_CACHE,
            if (configured == null) Result.MISS else Result.HIT)
        configured?.let { return it }
        // A recorder/encoder qualified for 48k MIC says nothing about 24k VOICE_RECOGNITION.
        val rates = listOf(MicrophoneSource.MIC, MicrophoneSource.VOICE_RECOGNITION).associateWith { source ->
            // A diagnostic reservation is temporary, not a negative capability to cache.
            setOf(24000, 48000).filter { rate -> qualifies(source, rate) ?: return emptyMap() }.toSet()
        }
        val call = checkNotNull(CarPlayMicrophoneCapturePolicy.profile("telephony", null))
        val converter = CarPlayMicrophonePcmConverter(call, 24000)
        val callQualified = qualifies(checkNotNull(call.source), converter.captureRate, converter.captureChannels)
            ?: return emptyMap()
        val result = if (callQualified || rates.values.any { 48000 in it }) encoderNames() else emptyMap()
        qualified = MicrophoneCapability.of(listOf("default", "telephony", "speechrecognition").associateWith { kind ->
            val sourceRates = rates[MicrophoneSupport.source(kind)].orEmpty()
            buildSet {
                if (if (kind == "telephony") callQualified else 24000 in sourceRates) add(0x40L)
                if (if (kind == "telephony") callQualified else 48000 in sourceRates) addAll(result.keys)
            }
        })
        configured = result
        return if (permissionGranted()) result else emptyMap()
    }

    private fun qualifies(source: MicrophoneSource, rate: Int, channels: Int = 1): Boolean? {
        val observation = CaptureAcquisitionLedger.current.get()?.owner(CaptureAcquisitionLedger.Kind.PROBE, source, rate, channels)
        val lease = MicrophoneCaptureFence.tryNormal() ?: return null.also { observation?.event(Action.CREATE, Result.BLOCKED) }
        var record: AudioRecord? = null
        var valid = false
        try {
            observation?.event(Action.CREATE)
            record = recorder(source, rate, channels, observation)
            valid = record.state == AudioRecord.STATE_INITIALIZED && record.sampleRate == rate &&
                record.channelCount == channels && record.audioFormat == AudioFormat.ENCODING_PCM_16BIT
            observation?.event(Action.VALIDATE, if (valid) Result.OK else Result.FAILED)
        } catch (_: Exception) { valid = false; observation?.event(Action.VALIDATE, Result.FAILED) }
        finally {
            observation?.event(Action.RELEASE_BEGIN, if (record == null) Result.ABSENT else Result.ATTEMPT)
            try {
                record?.release(); lease.release()
                observation?.event(Action.RELEASED, if (record == null) Result.ABSENT else Result.OK)
            }
            catch (_: Exception) { valid = false; observation?.event(Action.RELEASE_FAILED, Result.FAILED) } // retain fence
        }
        return (valid && permissionGranted()).also {
            if (it) releasedShapes.add(CaptureShape(source, rate, channels))
        }
    }

    @Synchronized fun experimentCapability(context: Context, profile: CarPlayMicrophoneExperimentProfile): MicrophoneCapability {
        initialize(context)
        releasedShapes.clear()
        if (!permissionGranted()) return MicrophoneCapability.NONE
        val encoders = encoderNames()
        return MicrophoneCapability.of(listOf("default", "telephony", "speechrecognition").associateWith { kind ->
            val source = profile.source ?: checkNotNull(MicrophoneSupport.source(kind))
            MicrophoneSupport.implementedBits.filter { bit ->
                val wireRate = if (bit in MicrophoneSupport.pcmBits) 24000 else 48000
                val converter = CarPlayMicrophonePcmConverter(profile, wireRate)
                val valid = qualifies(source, converter.captureRate, converter.captureChannels)
                    ?: return MicrophoneCapability.NONE
                valid && (bit in MicrophoneSupport.pcmBits || encoders.containsKey(bit))
            }.toSet()
        })
    }

    internal fun policyDiagnostics(record: AudioRecord, source: MicrophoneSource, rate: Int,
        report: (String) -> Unit): MicrophonePolicyDiagnostics =
        MicrophonePolicyDiagnostics.forRecorder(checkNotNull(context), record, source, rate, report)

    internal fun recorder(source: MicrophoneSource, sampleRate: Int = 48000, channels: Int = 1,
        observation: CaptureAcquisitionLedger.Owner? = null): AudioRecord {
        require(sampleRate in setOf(24000,48000) && channels in 1..2)
        val app = checkNotNull(context)
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Microphone consent unavailable")
        val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minimum = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0)
        observation?.event(Action.BUFFER, Result.OK, maxOf(minimum * 2, sampleRate / 50 * channels * 2 * 4))
        return AudioRecord.Builder().setAudioSource(when (source) {
            MicrophoneSource.MIC -> MediaRecorder.AudioSource.MIC
            MicrophoneSource.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            MicrophoneSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }).setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
            .setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, sampleRate / 50 * channels * 2 * 4)).build()
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder

/** All potentially blocking Android work runs only on the bounded diagnostic owner. */
internal class MicrophoneDiagnosticAndroid(
    context: Context,
    private val factory: (MicrophoneDiagnosticShape) -> AudioRecord = ::createRecord
) : MicrophoneDiagnosticPlatform {
    private val app = context.applicationContext
    private val manager get() = app.getSystemService(AudioManager::class.java)
    private var named: AudioDeviceInfo? = null

    override fun inventory(): MicrophoneDiagnosticInventory {
        named = null
        return try {
            val devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val matches = mutableListOf<AudioDeviceInfo>()
            val capabilities = mutableListOf<MicrophoneDiagnosticCapabilities>()
            for (device in devices.take(16)) {
                if (!device.isSource) continue
                if (device.address == "Call_In_Mic") matches.add(device)
                val rates = device.sampleRates
                val masks = device.channelMasks
                val indices = device.channelIndexMasks
                val safeRates = rates.take(16).filter { it in RATES }.distinct()
                val safeMasks = masks.take(16).filter { it in MASKS }.distinct()
                val safeIndices = indices.take(16).filter { it in INDEX_MASKS }.distinct()
                capabilities.add(MicrophoneDiagnosticCapabilities(safeRates, safeMasks, safeIndices,
                    rates.isEmpty(), masks.isEmpty(), indices.isEmpty(),
                    rates.size > 16 || masks.size > 16 || indices.size > 16 ||
                        rates.take(16).any { it !in RATES } || masks.take(16).any { it !in MASKS } ||
                        indices.take(16).any { it !in INDEX_MASKS }))
            }
            val match = when {
                devices.size > 16 -> MicrophoneDiagnosticMatch.TRUNCATED
                matches.isEmpty() -> MicrophoneDiagnosticMatch.ABSENT
                matches.size == 1 -> MicrophoneDiagnosticMatch.UNIQUE
                else -> MicrophoneDiagnosticMatch.MULTIPLE
            }
            if (match == MicrophoneDiagnosticMatch.UNIQUE) named = matches.single()
            MicrophoneDiagnosticInventory(match, capabilities.toList())
        } catch (_: Throwable) {
            named = null
            MicrophoneDiagnosticInventory(MicrophoneDiagnosticMatch.UNAVAILABLE, emptyList())
        }
    }

    override fun policy(): MicrophoneDiagnosticReason = try {
        when {
            app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> MicrophoneDiagnosticReason.PERMISSION
            manager.mode != AudioManager.MODE_NORMAL -> MicrophoneDiagnosticReason.MODE
            manager.isMicrophoneMute -> MicrophoneDiagnosticReason.MUTED
            else -> MicrophoneDiagnosticReason.NONE
        }
    } catch (_: Throwable) { MicrophoneDiagnosticReason.POLICY_UNKNOWN }

    override fun open(shape: MicrophoneDiagnosticShape): MicrophoneDiagnosticRecord {
        check(policy() == MicrophoneDiagnosticReason.NONE)
        // Do not perform fallible property queries after allocation until the wrapper owns release.
        val record = factory(shape)
        val selected = named
        return object : MicrophoneDiagnosticRecord {
            override fun preferNamedInput(): Boolean = selected?.let { record.setPreferredDevice(it) } ?: false
            override fun start() {
                check(record.state == AudioRecord.STATE_INITIALIZED && record.audioFormat == AudioFormat.ENCODING_PCM_16BIT)
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            }
            override fun observe(): MicrophoneDiagnosticObservation {
                val own = record.format
                check(own.encoding == AudioFormat.ENCODING_PCM_16BIT)
                val config = runCatching { record.activeRecordingConfiguration }.getOrNull()
                val route = runCatching { record.routedDevice }.getOrNull()
                val routeId = runCatching { route?.id }.getOrNull()
                val namedId = runCatching { selected?.id }.getOrNull()
                return MicrophoneDiagnosticObservation(
                    publicShape(record.audioSource, own),
                    runCatching { config?.let { publicShape(it.clientAudioSource, it.clientFormat) } }.getOrNull(),
                    runCatching { config?.let { publicShape(it.audioSource, it.format) } }.getOrNull(),
                    runCatching { config?.isClientSilenced }.getOrNull(),
                    routeId, if (routeId == null || namedId == null) null else routeId == namedId)
            }
            override fun read(buffer: ShortArray) = record.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
            override fun stop() = record.stop()
            override fun release() = record.release()
        }
    }

    private companion object {
        val RATES = setOf(8000, 16000, 24000, 32000, 44100, 48000, 96000, 192000)
        val MASKS = setOf(AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_FRONT or AudioFormat.CHANNEL_IN_BACK)
        val INDEX_MASKS = setOf(1, 3, 7, 15, 31, 63, 127, 255)
        fun publicShape(source: Int, format: AudioFormat) = MicrophoneDiagnosticShape(
            source.takeIf { it in setOf(0,1,5,6,7,9,10) } ?: -1,
            format.sampleRate.takeIf { it in RATES } ?: -1,
            format.channelCount.takeIf { it in 1..8 } ?: -1,
            format.channelMask.takeIf { it == 0 || it in MASKS } ?: -1,
            format.channelIndexMask.takeIf { it == 0 || it in INDEX_MASKS } ?: -1)
        fun createRecord(shape: MicrophoneDiagnosticShape): AudioRecord {
            require(shape.source in setOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION))
            require(shape.sampleRate in setOf(16000,24000,48000) && shape.channelCount in 1..2)
            val minimum = AudioRecord.getMinBufferSize(shape.sampleRate, shape.channelMask, AudioFormat.ENCODING_PCM_16BIT)
            require(minimum > 0 && minimum <= 1024 * 1024)
            return try {
                AudioRecord.Builder().setAudioSource(shape.source)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(shape.sampleRate).setChannelMask(shape.channelMask).build())
                    .setBufferSizeInBytes(maxOf(minimum * 2, shape.sampleRate / 10 * shape.channelCount * 2)).build()
            } catch (denied: SecurityException) {
                // open() checked policy, but permission can be revoked during allocation.
                // Preserve the exception for the diagnostic owner's failure/lease cleanup path.
                throw denied
            }
        }
    }
}

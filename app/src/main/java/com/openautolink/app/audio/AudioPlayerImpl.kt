package com.openautolink.app.audio

import android.media.AudioManager
import android.util.Log
import com.openautolink.app.transport.AudioPurpose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 5-purpose AudioTrack management — routes incoming audio frames to
 * pre-allocated per-purpose slots with cross-purpose ducking.
 *
 * Both AA session audio (media, navigation, alerts) and BT HFP audio
 * (phone calls, voice assistant) arrive as OAL PCM frames with different
 * purpose values. This class handles both paths uniformly.
 *
 * Default sample rates/channels per protocol spec:
 *   MEDIA:      48000 Hz, Stereo
 *   NAVIGATION: 16000 Hz, Mono
 *   ASSISTANT:  16000 Hz, Mono
 *   PHONE_CALL:  8000 Hz, Mono
 *   ALERT:      24000 Hz, Mono
 *
 * Slots are pre-allocated at initialize() and reused. The bridge sends
 * audio_start with actual sample_rate/channels before streaming begins,
 * and the slot is recreated only if the format changes.
 */
class AudioPlayerImpl(private val audioManager: AudioManager) : AudioPlayer {

    companion object {
        private const val TAG = "AudioPlayerImpl"

        /** Default formats per purpose — used until bridge sends audio_start. */
        private val DEFAULT_FORMATS = mapOf(
            AudioPurpose.MEDIA to AudioFormat(48000, 2),
            AudioPurpose.NAVIGATION to AudioFormat(16000, 1),
            AudioPurpose.ASSISTANT to AudioFormat(16000, 1),
            AudioPurpose.PHONE_CALL to AudioFormat(8000, 1),
            AudioPurpose.ALERT to AudioFormat(24000, 1)
        )
    }

    private data class AudioFormat(val sampleRate: Int, val channels: Int)

    private val slots = mutableMapOf<AudioPurpose, AudioPurposeSlot>()
    private val slotFormats = mutableMapOf<AudioPurpose, AudioFormat>()
    private val focusManager = AudioFocusManager(audioManager)
    private val coordinator = AudioPurposeCoordinator()

    private val _stats = MutableStateFlow(AudioStats())
    override val stats: StateFlow<AudioStats> = _stats.asStateFlow()

    override val callState: StateFlow<CallState> = coordinator.callState

    private var initialized = false

    override fun initialize() {
        if (initialized) return

        // Pre-allocate all 5 AudioTrack slots with default formats
        for (purpose in AudioPurpose.entries) {
            val fmt = DEFAULT_FORMATS[purpose] ?: AudioFormat(48000, 2)
            val slot = AudioPurposeSlot(purpose, fmt.sampleRate, fmt.channels)
            slot.initialize()
            slots[purpose] = slot
            slotFormats[purpose] = fmt
        }

        // Request session-level audio focus once
        focusManager.requestSessionFocus(
            onLost = { pauseAllActive() },
            onRegained = { resumeAllPaused() }
        )

        initialized = true
        Log.i(TAG, "Audio player initialized with ${slots.size} purpose slots")
        updateStats()
    }

    override fun release() {
        if (!initialized) return

        for ((_, slot) in slots) {
            slot.release()
        }
        slots.clear()
        slotFormats.clear()
        focusManager.releaseFocus()
        coordinator.reset()
        initialized = false

        Log.i(TAG, "Audio player released")
        _stats.value = AudioStats()
    }

    override fun onAudioFrame(frame: AudioFrame) {
        if (!frame.isPlayback) return

        val slot = slots[frame.purpose]
        if (slot == null) {
            Log.w(TAG, "No slot for purpose ${frame.purpose}")
            return
        }

        slot.feedPcm(frame.data)
    }

    override fun startPurpose(purpose: AudioPurpose, sampleRate: Int, channels: Int) {
        val existingSlot = slots[purpose]
        val requestedFmt = AudioFormat(sampleRate, channels)

        // Check if format changed from what the slot was created with — recreate if so
        if (existingSlot != null) {
            val currentFmt = slotFormats[purpose]
            if (currentFmt != requestedFmt && !existingSlot.isActive) {
                Log.i(TAG, "Recreating $purpose slot: ${sampleRate}Hz ${channels}ch")
                existingSlot.release()
                val newSlot = AudioPurposeSlot(purpose, sampleRate, channels)
                newSlot.initialize()
                slots[purpose] = newSlot
                slotFormats[purpose] = requestedFmt
            }
        }

        val slot = slots[purpose] ?: return
        slot.start()

        // Notify coordinator and apply volume ducking
        val actions = coordinator.onPurposeStarted(purpose)
        applyVolumeActions(actions)

        Log.i(TAG, "Started $purpose: ${sampleRate}Hz ${channels}ch (call=${coordinator.callState.value})")
        updateStats()
    }

    override fun stopPurpose(purpose: AudioPurpose) {
        val slot = slots[purpose] ?: return
        slot.stop()

        // Notify coordinator and restore volumes
        val actions = coordinator.onPurposeStopped(purpose)
        applyVolumeActions(actions)

        Log.i(TAG, "Stopped $purpose (call=${coordinator.callState.value})")
        updateStats()
    }

    override fun setVolume(purpose: AudioPurpose, volume: Float) {
        slots[purpose]?.setVolume(volume)
    }

    /**
     * Apply a batch of volume actions from the coordinator.
     * Sets each active purpose's AudioTrack volume.
     */
    private fun applyVolumeActions(actions: List<VolumeAction>) {
        for (action in actions) {
            val slot = slots[action.purpose] ?: continue
            slot.setVolume(action.volume)
            if (action.volume < AudioPurposeCoordinator.NORMAL_VOLUME) {
                Log.d(TAG, "Ducked ${action.purpose} to ${action.volume}")
            }
        }
    }

    /**
     * Pause all active slots on external audio focus loss.
     * Does NOT clear ring buffers — overflow drops oldest naturally.
     */
    private fun pauseAllActive() {
        for ((purpose, slot) in slots) {
            if (slot.isActive) {
                slot.pause()
                Log.d(TAG, "Paused $purpose (focus loss)")
            }
        }
        updateStats()
    }

    /**
     * Resume all previously-active slots on external audio focus regain.
     * Slots that were explicitly stopped (not paused) won't resume.
     */
    private fun resumeAllPaused() {
        for ((purpose, slot) in slots) {
            if (!slot.isActive && slot.isPausedByFocus) {
                slot.resume()
                if (slot.isActive) {
                    Log.d(TAG, "Resumed $purpose (focus regain)")
                }
            }
        }
        // Re-apply coordinator volumes after resuming
        val actions = AudioPurpose.entries
            .filter { slots[it]?.isActive == true }
            .let { activePurposes ->
                activePurposes.map { purpose ->
                    coordinator.onPurposeStarted(purpose)
                }.flatten()
            }
        applyVolumeActions(actions)
        updateStats()
    }

    private fun updateStats() {
        val active = slots.filter { it.value.isActive }.keys
        val underruns = slots.mapValues { it.value.underrunCount.get() }
            .filter { it.value > 0 }
        val written = slots.mapValues { it.value.framesWritten.get() }
            .filter { it.value > 0 }

        _stats.value = AudioStats(
            activePurposes = active,
            underruns = underruns,
            framesWritten = written
        )
    }
}

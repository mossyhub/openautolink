package com.openautolink.app.audio

import com.openautolink.app.transport.AudioPurpose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates cross-purpose audio behavior: volume ducking and call lifecycle.
 *
 * When multiple audio purposes are active simultaneously, this coordinator
 * decides which purposes should be ducked (reduced volume) or paused.
 *
 * Priority order (highest first):
 *   PHONE_CALL > ASSISTANT > ALERT > NAVIGATION > MEDIA
 *
 * AAOS handles hardware-level mixing via AudioAttributes, but the app must
 * manage its own volume levels for a good in-car experience (e.g., ducking
 * music while a nav prompt plays over the phone call).
 *
 * Call state machine:
 *   IDLE → RINGING (alert starts) → IN_CALL (phone_call starts) → IDLE (phone_call stops)
 */
class AudioPurposeCoordinator {

    companion object {
        /** Volume level for ducked media during nav prompts. */
        const val DUCK_VOLUME_NAV = 0.5f

        /** Volume level for ducked media during alerts (ring). */
        const val DUCK_VOLUME_ALERT = 0.3f

        /** Volume level for ducked media during phone call. */
        const val DUCK_VOLUME_CALL = 0.15f

        /** Volume level for ducked media during assistant. */
        const val DUCK_VOLUME_ASSISTANT = 0.1f

        /** Normal volume level. */
        const val NORMAL_VOLUME = 1.0f
    }

    private val activePurposes = mutableSetOf<AudioPurpose>()

    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    /**
     * Notify the coordinator that a purpose has started.
     * Returns a list of volume actions the caller should apply.
     */
    fun onPurposeStarted(purpose: AudioPurpose): List<VolumeAction> {
        activePurposes.add(purpose)
        updateCallState()
        return computeActions()
    }

    /**
     * Notify the coordinator that a purpose has stopped.
     * Returns a list of volume actions the caller should apply.
     */
    fun onPurposeStopped(purpose: AudioPurpose): List<VolumeAction> {
        activePurposes.remove(purpose)
        updateCallState()
        return computeActions()
    }

    /** Reset all state (session end or phone disconnect). */
    fun reset() {
        activePurposes.clear()
        _callState.value = CallState.IDLE
    }

    /** Current set of active purposes (for testing/diagnostics). */
    fun activePurposes(): Set<AudioPurpose> = activePurposes.toSet()

    private fun updateCallState() {
        val hasCall = AudioPurpose.PHONE_CALL in activePurposes
        val hasAlert = AudioPurpose.ALERT in activePurposes

        _callState.value = when {
            hasCall -> CallState.IN_CALL
            hasAlert -> CallState.RINGING
            else -> CallState.IDLE
        }
    }

    /**
     * Compute the desired volume for every active purpose based on what's
     * currently playing. Returns actions for ALL active purposes so the
     * caller can apply them atomically.
     */
    private fun computeActions(): List<VolumeAction> {
        val actions = mutableListOf<VolumeAction>()

        // Determine the highest-priority active purpose
        val highestPriority = activePurposes
            .minByOrNull { it.priority() }

        for (purpose in AudioPurpose.entries) {
            if (purpose !in activePurposes) continue

            val targetVolume = computeTargetVolume(purpose, highestPriority)
            actions.add(VolumeAction(purpose, targetVolume))
        }

        return actions
    }

    private fun computeTargetVolume(
        purpose: AudioPurpose,
        highestPriority: AudioPurpose?
    ): Float {
        if (highestPriority == null || highestPriority == purpose) {
            return NORMAL_VOLUME
        }

        // Only duck lower-priority purposes
        if (purpose.priority() <= highestPriority.priority()) {
            return NORMAL_VOLUME
        }

        // MEDIA gets ducked by everything above it
        if (purpose == AudioPurpose.MEDIA) {
            return when (highestPriority) {
                AudioPurpose.PHONE_CALL -> DUCK_VOLUME_CALL
                AudioPurpose.ASSISTANT -> DUCK_VOLUME_ASSISTANT
                AudioPurpose.ALERT -> DUCK_VOLUME_ALERT
                AudioPurpose.NAVIGATION -> DUCK_VOLUME_NAV
                else -> NORMAL_VOLUME
            }
        }

        // NAVIGATION ducked by PHONE_CALL/ASSISTANT
        if (purpose == AudioPurpose.NAVIGATION) {
            return when (highestPriority) {
                AudioPurpose.PHONE_CALL, AudioPurpose.ASSISTANT -> DUCK_VOLUME_NAV
                else -> NORMAL_VOLUME
            }
        }

        return NORMAL_VOLUME
    }

    /**
     * Priority ordering — lower number = higher priority.
     */
    private fun AudioPurpose.priority(): Int = when (this) {
        AudioPurpose.PHONE_CALL -> 0
        AudioPurpose.ASSISTANT -> 1
        AudioPurpose.ALERT -> 2
        AudioPurpose.NAVIGATION -> 3
        AudioPurpose.MEDIA -> 4
    }
}

/**
 * A volume adjustment action for a specific purpose.
 */
data class VolumeAction(
    val purpose: AudioPurpose,
    val volume: Float
)

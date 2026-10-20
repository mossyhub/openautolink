// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

/** Local capture only: never changes the negotiated mono codec, RTP clock or wire rate. */
internal object CarPlayMicrophoneCapturePolicy {
    private val provenCall = CarPlayMicrophoneExperimentProfile(
        source = MicrophoneSource.VOICE_RECOGNITION,
        rate = CarPlayMicrophoneCaptureRate.HZ_48000,
        channels = CarPlayMicrophoneChannels.TWO,
        selection = CarPlayMicrophoneChannelSelection.AVERAGE)

    // Explicit receiver-frozen manual selection wins; disarm affects future receivers only.
    fun profile(kind: String, experiment: CarPlayMicrophoneExperimentProfile?): CarPlayMicrophoneExperimentProfile? =
        experiment ?: provenCall.takeIf { kind.equals("telephony", ignoreCase = true) }
}

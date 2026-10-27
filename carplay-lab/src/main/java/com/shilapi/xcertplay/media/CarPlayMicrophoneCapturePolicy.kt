// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

/** Receiver-frozen accepted local acquisition; negotiated formats and RTP remain unchanged. */
internal object CarPlayMicrophoneCapturePolicy {
    val production = CarPlayMicrophoneExperimentProfile(
        source = MicrophoneSource.VOICE_RECOGNITION,
        rate = CarPlayMicrophoneCaptureRate.HZ_16000,
        channels = CarPlayMicrophoneChannels.MONO,
        aaStyle = true)

    // Explicit advanced receiver snapshot always wins. Clearing it restores production.
    fun profile(kind: String, experiment: CarPlayMicrophoneExperimentProfile?): CarPlayMicrophoneExperimentProfile? =
        experiment ?: production.takeIf { MicrophoneSupport.source(kind) != null }
}

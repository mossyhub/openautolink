// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

enum class CarPlayMicrophoneCaptureRate { NEGOTIATED, HZ_24000, HZ_48000 }
enum class CarPlayMicrophoneChannels { MONO, TWO }
enum class CarPlayMicrophoneChannelSelection { AVERAGE, FIRST, SECOND }
enum class CarPlayMicrophoneCodec { AUTO, LPCM, OPUS }
enum class CarPlayMicrophoneQualificationHistory { NORMAL, MANUAL_VR48_TWO }
enum class CarPlayMicrophoneSpeechCapture { VR24_MONO, VR48_TWO }
/** Independent lab axes, not a production microphone route or a forced wire format. */
data class CarPlayMicrophoneIndependentSelection(
    val history: CarPlayMicrophoneQualificationHistory = CarPlayMicrophoneQualificationHistory.NORMAL,
    val speechCapture: CarPlayMicrophoneSpeechCapture = CarPlayMicrophoneSpeechCapture.VR48_TWO,
) {
    internal fun captureProfile() = CarPlayMicrophoneExperimentProfile(
        source = MicrophoneSource.VOICE_RECOGNITION,
        rate = if (speechCapture == CarPlayMicrophoneSpeechCapture.VR48_TWO)
            CarPlayMicrophoneCaptureRate.HZ_48000 else CarPlayMicrophoneCaptureRate.HZ_24000,
        channels = if (speechCapture == CarPlayMicrophoneSpeechCapture.VR48_TWO)
            CarPlayMicrophoneChannels.TWO else CarPlayMicrophoneChannels.MONO)
    internal fun qualificationProfile() = if (history == CarPlayMicrophoneQualificationHistory.NORMAL) null
        else CarPlayMicrophoneIndependentSelection(speechCapture = CarPlayMicrophoneSpeechCapture.VR48_TWO).captureProfile()
    internal fun receipt(receiver: Long) = "media stage=microphone-control receiver=$receiver history=${if (history == CarPlayMicrophoneQualificationHistory.NORMAL) "normal" else "manual-vr48-two"} live=${if (speechCapture == CarPlayMicrophoneSpeechCapture.VR24_MONO) "vr24-mono" else "vr48-two"}"
}
data class CarPlayMicrophoneExperimentProfile(
    val source: MicrophoneSource? = null,
    val rate: CarPlayMicrophoneCaptureRate = CarPlayMicrophoneCaptureRate.NEGOTIATED,
    val channels: CarPlayMicrophoneChannels = CarPlayMicrophoneChannels.MONO,
    val selection: CarPlayMicrophoneChannelSelection = CarPlayMicrophoneChannelSelection.AVERAGE,
    val codec: CarPlayMicrophoneCodec = CarPlayMicrophoneCodec.AUTO,
    val independent: CarPlayMicrophoneIndependentSelection? = null,
) {
    init {
        require(channels == CarPlayMicrophoneChannels.TWO || selection == CarPlayMicrophoneChannelSelection.AVERAGE) {
            "Channel selection requires two-channel capture"
        }
    }
}
data class CarPlayMicrophoneExperimentStatus(val profile: CarPlayMicrophoneExperimentProfile?, val remainingMillis: Long) {
    val armed: Boolean get() = profile != null
}
internal class CarPlayMicrophoneExperimentRegistry(private val nowMillis: () -> Long) {
    private var profile: CarPlayMicrophoneExperimentProfile? = null
    private var armedAt = 0L
    @Synchronized fun arm(profile: CarPlayMicrophoneExperimentProfile) {
        armedAt = nowMillis()
        this.profile = profile
    }
    @Synchronized fun disarm() { profile = null }
    @Synchronized fun status(): CarPlayMicrophoneExperimentStatus {
        val remaining = (900000L - (nowMillis() - armedAt).coerceAtLeast(0)).coerceIn(0, 900000)
        if (remaining == 0L) profile = null
        return CarPlayMicrophoneExperimentStatus(profile, if (profile == null) 0 else remaining)
    }
    @Synchronized fun snapshot(): CarPlayMicrophoneExperimentProfile? = status().profile
}
/** Memory only. The app must enforce explicit consent, foreground and Park before arm. */
object CarPlayMicrophoneExperiments {
    private val registry = CarPlayMicrophoneExperimentRegistry { System.nanoTime() / 1_000_000 }
    fun arm(profile: CarPlayMicrophoneExperimentProfile) = registry.arm(profile)
    fun disarm() = registry.disarm()
    fun status() = registry.status()
    fun snapshot() = registry.snapshot()
}

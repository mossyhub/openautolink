// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

enum class CarPlayMicrophoneCaptureRate { NEGOTIATED, HZ_24000, HZ_48000, HZ_16000 }
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
    val aaStyle: Boolean = false,
) {
    init {
        require(!aaStyle || (source == MicrophoneSource.VOICE_RECOGNITION && rate == CarPlayMicrophoneCaptureRate.HZ_16000 && channels == CarPlayMicrophoneChannels.MONO && independent == null && codec == CarPlayMicrophoneCodec.AUTO))
        require(channels == CarPlayMicrophoneChannels.TWO || selection == CarPlayMicrophoneChannelSelection.AVERAGE) {
            "Channel selection requires two-channel capture"
        }
    }
}
data class CarPlayMicrophoneExperimentStatus(val profile: CarPlayMicrophoneExperimentProfile?) {
    val armed: Boolean get() = profile != null
}
internal class CarPlayMicrophoneExperimentRegistry {
    private var profile: CarPlayMicrophoneExperimentProfile? = null
    @Synchronized fun arm(profile: CarPlayMicrophoneExperimentProfile) { this.profile = profile }
    @Synchronized fun disarm() { profile = null }
    @Synchronized fun status() = CarPlayMicrophoneExperimentStatus(profile)
    @Synchronized fun snapshot(): CarPlayMicrophoneExperimentProfile? = profile
}
/** Default off, until Off or app restart. Snapshots never mutate an active recorder. */
object CarPlayMicrophoneExperiments {
    private val registry = CarPlayMicrophoneExperimentRegistry()
    fun arm(profile: CarPlayMicrophoneExperimentProfile) = registry.arm(profile)
    fun disarm() = registry.disarm()
    fun status() = registry.status()
    fun snapshot() = registry.snapshot()
}

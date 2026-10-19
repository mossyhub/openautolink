// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

enum class CarPlayMicrophoneCaptureRate { NEGOTIATED, HZ_24000, HZ_48000 }
enum class CarPlayMicrophoneChannels { MONO, TWO }
enum class CarPlayMicrophoneChannelSelection { AVERAGE, FIRST, SECOND }
enum class CarPlayMicrophoneCodec { AUTO, LPCM, OPUS }
data class CarPlayMicrophoneExperimentProfile(
    val source: MicrophoneSource? = null,
    val rate: CarPlayMicrophoneCaptureRate = CarPlayMicrophoneCaptureRate.NEGOTIATED,
    val channels: CarPlayMicrophoneChannels = CarPlayMicrophoneChannels.MONO,
    val selection: CarPlayMicrophoneChannelSelection = CarPlayMicrophoneChannelSelection.AVERAGE,
    val codec: CarPlayMicrophoneCodec = CarPlayMicrophoneCodec.AUTO,
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

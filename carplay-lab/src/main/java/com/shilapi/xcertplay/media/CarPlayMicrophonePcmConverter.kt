// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

class CarPlayMicrophonePcmConverter(private val profile: CarPlayMicrophoneExperimentProfile, private val negotiatedRate: Int) {
    val captureRate: Int = when (profile.rate) {
        CarPlayMicrophoneCaptureRate.NEGOTIATED -> negotiatedRate
        CarPlayMicrophoneCaptureRate.HZ_24000 -> 24000
        CarPlayMicrophoneCaptureRate.HZ_48000 -> 48000
    }
    val captureChannels = if (profile.channels == CarPlayMicrophoneChannels.TWO) 2 else 1
    val captureFrameBytes = captureRate / 50 * captureChannels * 2
    init { require(negotiatedRate == 24000 || negotiatedRate == 48000) }
    fun convert(pcm: ByteArray): ByteArray {
        require(pcm.size == captureFrameBytes) { "Capture frame must be exactly twenty milliseconds" }
        if (captureChannels == 1 && captureRate == negotiatedRate) return pcm
        fun sample(index: Int): Int = ((pcm[index * 2].toInt() and 255) or
            (pcm[index * 2 + 1].toInt() shl 8)).toShort().toInt()
        fun mono(index: Int): Int {
            if (captureChannels == 1) return sample(index)
            return when (profile.selection) {
                CarPlayMicrophoneChannelSelection.FIRST -> sample(index * 2)
                CarPlayMicrophoneChannelSelection.SECOND -> sample(index * 2 + 1)
                CarPlayMicrophoneChannelSelection.AVERAGE -> (sample(index * 2) + sample(index * 2 + 1)) / 2
            }
        }
        val output = ByteArray(negotiatedRate / 50 * 2)
        for (index in 0 until negotiatedRate / 50) {
            val value = when {
                captureRate == negotiatedRate -> mono(index)
                captureRate > negotiatedRate -> (mono(index * 2) + mono(index * 2 + 1)) / 2
                else -> mono(index / 2)
            }
            output[index * 2] = value.toByte()
            output[index * 2 + 1] = (value shr 8).toByte()
        }
        return output
    }
}

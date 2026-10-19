package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CarPlayMicrophonePcmConverterTest {
    private fun pcm(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        values.forEach { putShort(it.toShort()) }
    }.array()
    private fun samples(bytes: ByteArray): List<Int> = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().let { b ->
        List(b.remaining()) { b.get().toInt() }
    }
    @Test fun signedStereoSelectionAndAveragingHaveExactTwentyMillisecondWireLength() {
        for (selection in CarPlayMicrophoneChannelSelection.entries) {
            val converter = CarPlayMicrophonePcmConverter(CarPlayMicrophoneExperimentProfile(
                channels = CarPlayMicrophoneChannels.TWO, selection = selection), 24000)
            val frame = pcm(*List(480) { listOf(-32768, 32767) }.flatten().toIntArray())
            val result = samples(converter.convert(frame))
            assertEquals(480, result.size)
            val expected = when (selection) {
                CarPlayMicrophoneChannelSelection.AVERAGE -> 0
                CarPlayMicrophoneChannelSelection.FIRST -> -32768
                CarPlayMicrophoneChannelSelection.SECOND -> 32767
            }
            assertTrue(result.all { it == expected })
        }
    }
    @Test fun boundedTwoToOneRateConversionKeepsSignedSamplesAndFrameClock() {
        val down = CarPlayMicrophonePcmConverter(CarPlayMicrophoneExperimentProfile(rate = CarPlayMicrophoneCaptureRate.HZ_48000), 24000)
        val result = samples(down.convert(pcm(*List(480) { listOf(-30000, -10000) }.flatten().toIntArray())))
        assertEquals(List(480) { -20000 }, result)
        val up = CarPlayMicrophonePcmConverter(CarPlayMicrophoneExperimentProfile(rate = CarPlayMicrophoneCaptureRate.HZ_24000), 48000)
        assertEquals(List(960) { 12345 }, samples(up.convert(pcm(*IntArray(480) { 12345 }))))
    }
    @Test fun identityPreservesEveryByteAndRejectsPartialFramesOrUnsupportedRates() {
        val identity = CarPlayMicrophonePcmConverter(CarPlayMicrophoneExperimentProfile(), 48000)
        val input = ByteArray(1920) { it.toByte() }
        assertArrayEquals(input, identity.convert(input))
        assertThrows(IllegalArgumentException::class.java) { identity.convert(ByteArray(1919)) }
        assertThrows(IllegalArgumentException::class.java) { CarPlayMicrophonePcmConverter(CarPlayMicrophoneExperimentProfile(), 16000) }
    }
}

package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AaStyleCaptureTest {
    @Test fun sixteenKilohertzConvertsToNegotiatedTwentyFourAndFortyEight() {
        val rate=CarPlayMicrophoneCaptureRate.entries.firstOrNull { it.name=="HZ_16000" }
        assertNotNull("AA-style capture needs a genuinely qualified 16k profile",rate)
        val profile=CarPlayMicrophoneExperimentProfile(source=MicrophoneSource.VOICE_RECOGNITION,rate=rate!!)
        for(wire in listOf(24000,48000)) {
            val converter=CarPlayMicrophonePcmConverter(profile,wire)
            assertEquals(640,converter.captureFrameBytes)
            val input=ByteArray(640)
            for(i in 0 until 320) { val v=if(i%2==0) -32768 else 32767; input[i*2]=v.toByte();input[i*2+1]=(v shr 8).toByte() }
            val output=converter.convert(input)
            assertEquals(wire/50*2,output.size)
            for(i in 0 until wire/50) {
                val source=i*16000/wire
                val actual=(output[i*2].toInt() and 255) or (output[i*2+1].toInt() shl 8)
                assertEquals(if(source%2==0) -32768 else 32767,actual)
            }
        }
    }
}

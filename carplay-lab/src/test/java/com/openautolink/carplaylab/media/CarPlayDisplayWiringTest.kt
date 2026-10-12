package com.openautolink.carplaylab.media

import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayHid
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CarPlayDisplayWiringTest {
    @Test fun receiverAndSinkShareTheQualifiedDisplayAndDecoder() {
        val receiver = File("src/main/java/com/openautolink/carplaylab/media/ProjectionReceiver.kt").readText()
        val sink = File("src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt").readText()
        assertTrue(receiver.contains("CarPlayDisplayPolicy.videoSelection(display)"))
        assertTrue(receiver.contains("fps = videoSelection.fps"))
        assertEquals(2, Regex("videoSelection = videoSelection").findAll(receiver).count())
        assertTrue(receiver.contains("display = displaySelection"))
        assertTrue(receiver.contains("microphoneCapability, displayConfiguration"))
        val qualification = File("src/main/java/com/shilapi/xcertplay/media/CarPlayVideoQualification.kt").readText()
        assertTrue(sink.contains("CarPlayVideoSelection(display.display.width,"))
        assertTrue(sink.contains("display.display.height,display.display.fps,display.decoderName)"))
        assertTrue(sink.contains("CarPlayVideoQualification.mediaFormat(job.codec,job.bytes,videoSelection)"))
        assertTrue(qualification.contains("MediaFormat.createVideoFormat(mime(codec),selection.width,selection.height)"))
        assertTrue(qualification.contains("setInteger(MediaFormat.KEY_FRAME_RATE,selection.fps)"))
        assertTrue(sink.contains("videoCodecFactory(checkNotNull(videoSelection.decoderName(job.codec)))"))
        assertTrue(sink.contains("MediaCodec::createByCodecName"))
        assertTrue(sink.contains("CarPlayVideoQualification.supports(caps,fmt,videoSelection.fps)"))
        assertFalse(sink.substringBefore("private class AudioOutputTrack").contains("createDecoderByType"))
        assertTrue(sink.contains("CarPlayDisplayPolicy.outputDiagnostic("))
    }

    @Test fun aspectFitTouchExcludesLetterboxAndUsesAdvertisedHidCoordinatesAtBothResolutions() {
        for (resolution in CarPlayResolution.PRESETS) {
            val display = CarPlayDisplayPreferences(resolution)
            val rect = CarPlayDisplayPolicy.fitRect(display, 1000, 1000)!!
            assertEquals(display.width.toFloat() / display.height, rect.width / rect.height, 0.001f)
            assertEquals((1000f - rect.width) / 2, rect.left, 0.001f)
            assertEquals((1000f - rect.height) / 2, rect.top, 0.001f)
            assertNull(MediaPolicy.touch(0, rect.left - 1, rect.top - 1, rect))
            assertEquals(TouchPoint(true, .5f, .5f), MediaPolicy.touch(0, 500f, 500f, rect))
            assertEquals(TouchPoint(false, 1f, 0f), MediaPolicy.touch(1, 1500f, 0f, rect))
            val point = MediaPolicy.touch(2, rect.left + rect.width, rect.top + rect.height, rect)!!
            // Exact production sendTouch contract: normalized -> advertised dimensions -> HID LE16.
            val config = benchReceiverConfig("00:11:22:33:44:55", 41234, display = display)
            val report = AirPlayHid.touchReport(listOf(AirPlayContact(0,
                point.x.toDouble() * config.main.widthPixels, point.y.toDouble() * config.main.heightPixels, point.down)))
            fun u16(offset: Int) = (report[offset].toInt() and 255) or ((report[offset + 1].toInt() and 255) shl 8)
            assertEquals(display.width, u16(2))
            assertEquals(display.height, u16(4))
        }
        assertNull(CarPlayDisplayPolicy.fitRect(CarPlayDisplayPreferences(), 0, 1000))
    }

    @Test fun negotiatedDimensionsAreNumericValidatedAndKeepAdvertisedInputSpaceSeparate() {
        val display = CarPlayDisplayPreferences(CarPlayResolution.FULL_HD1080)
        val message = CarPlayDisplayPolicy.outputDiagnostic(display, 1920, 1088, 0, 0, 1919, 1079)
        assertEquals("BENCH_DIAG videoOutput codec=H264 advertised=1920x1080 coded=1920x1088 visible=1920x1080", message)
        assertNull(CarPlayDisplayPolicy.outputDiagnostic(display, -1, 1080))
        assertNull(CarPlayDisplayPolicy.outputDiagnostic(display, Int.MAX_VALUE, 1080))
        assertNull(CarPlayDisplayPolicy.outputDiagnostic(display, 1920, 1080, 0, 0, 1920, 1079))
        assertNull(CarPlayDisplayPolicy.outputDiagnostic(display, 1920, 1080, 20, 0, 10, 1079))
        assertEquals("BENCH_DIAG videoOutput codec=H264 advertised=1920x1080 coded=1280x720 visible=1280x720",
            CarPlayDisplayPolicy.outputDiagnostic(display, 1280, 720))
    }
}

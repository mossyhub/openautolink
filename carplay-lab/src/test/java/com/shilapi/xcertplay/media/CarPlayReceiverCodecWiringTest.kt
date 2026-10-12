package com.shilapi.xcertplay.media

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Integration wiring guard; codec negotiation and sink execution have separate executable tests. */
class CarPlayReceiverCodecWiringTest {
    @Test fun receiverAndEngineForwardTheQualifiedCodecReceipt() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "carplay-lab/src/main").isDirectory }
        val receiver = File(root, "carplay-lab/src/main/java/com/openautolink/carplaylab/media/ProjectionReceiver.kt").readText()
        val engine = File(root, "carplay-lab/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaEngine.kt").readText()
        assertTrue(receiver.contains("CarPlayDisplayPolicy.videoSelection(display)"))
        assertEquals(2, Regex("videoSelection = videoSelection").findAll(receiver).count())
        assertTrue(receiver.contains("hevc = videoSelection.hevcOffered"))
        assertTrue(engine.contains("require(session.acceptsVideoCodec(codec))"))
        assertFalse(engine.contains("require(codec == VideoCodec.H264)"))
    }
}

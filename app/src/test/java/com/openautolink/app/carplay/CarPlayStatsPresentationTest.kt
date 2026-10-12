package com.openautolink.app.carplay

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CarPlayStatsPresentationTest {
    @Test fun numericReceiverMeasurementsReplaceUnavailableWithoutUsingStatusOrAaCounters() {
        val telemetry = com.openautolink.carplaylab.media.ReceiverTelemetrySnapshot(
            generation = 1, advertisedWidth = 1920, advertisedHeight = 1080,
            decoderName = "actual.decoder", codedWidth = 1280, codedHeight = 720,
            visibleWidth = 1280, visibleHeight = 720, renderedFrames = 31, renderedFps = 30.0,
            audioStreamObserved = true, audioAcceptedPackets = 4, audioWrittenBytes = 42)
        val rows = rows(CarPlayUiState(evidence = CarPlayEvidence(telemetry = telemetry)))
        assertEquals("actual.decoder", rows["Decoder"])
        assertEquals("1280×720", rows["Resolution"])
        assertEquals("1920×1080", rows["Advertised"])
        assertEquals("30.0", rows["FPS"])
        assertEquals("31", rows["Frames"])
        assertEquals("confirmed", rows["Rendered"])
        assertEquals("stream observed · audibility unverified", rows["Audio"])
        assertEquals("4 packets", rows["Accepted"])
        assertEquals("42 bytes", rows["Written"])
        assertEquals("unavailable", rows["Bitrate"])
    }

    @Test fun receiverWithoutAudioStreamDoesNotInventZeroWrites() {
        val telemetry = com.openautolink.carplaylab.media.ReceiverTelemetrySnapshot(2)
        val evidence = CarPlayEvidence(telemetry = telemetry)
        val rows = rows(CarPlayUiState(evidence = evidence))
        assertEquals("no audio stream observed", rows["Audio"])
        assertEquals("unavailable", rows["Written"])
        assertEquals("unavailable", rows["Accepted"])
        assertNull(evidence.accept("media stage=session-ended").telemetry)
    }

    private fun rows(state: CarPlayUiState): Map<String, String> = CarPlayStatsPresentation.rows(state)

    @Test fun absentCarPlayMetricsAreUnavailableRatherThanAaDefaults() {
        val rows = rows(CarPlayUiState(selected = true, busy = true))
        assertEquals("CarPlay", rows["Protocol"])
        for (label in listOf("Decoder", "Resolution", "FPS", "Frames", "Bitrate", "Dropped", "Audio", "Rate", "Written", "Underruns")) {
            assertEquals(label, "unavailable", rows[label])
        }
        assertEquals("unconfirmed", rows["Rendered"])
        assertEquals("unavailable", rows["Touches"])
        assertFalse(rows.containsKey("DPI setting"))
        assertFalse(rows.containsKey("Phone"))
    }

    @Test fun onlyActualCarPlayEvidencePromotesRenderAuthenticationAndTouchReceipts() {
        val evidence = CarPlayEvidence().accept("media stage=runtime-authentication-accepted")
            .accept("media stage=video-first-frame-confirmed count=1")
            .accept("media stage=touch-sent count=1")
        val rows = rows(CarPlayUiState(selected = true, busy = true, evidence = evidence))
        assertEquals("accepted", rows["Auth"])
        assertEquals("confirmed", rows["Rendered"])
        assertEquals("1 sent", rows["Touches"])
        assertEquals("unavailable", rows["Frames"])
        assertEquals("unavailable", rows["Audio"])
    }

    /** Source contract only, not rendered Compose proof. */
    @Test fun bothProtocolsReuseTheExistingStatsPanelAndCarPlayCannotReadAaCounters() {
        val root = File("src/main/java/com/openautolink/app")
        val layer = File(root, "carplay/CarPlayProjectionLayer.kt").readText()
        val screen = File(root, "ui/projection/ProjectionScreen.kt").readText()
        assertTrue(layer.contains("StatsForNerdsOverlay("))
        assertTrue(layer.contains("CarPlayStatsPresentation.rows(state)"))
        assertTrue(layer.contains("if (controls.showStats)"))
        assertFalse(layer.contains("controls.videoStats"))
        assertFalse(layer.contains("controls.audioStats"))
        assertTrue(screen.contains("StatsForNerdsOverlay("))
        assertTrue("Shared diagnostics backend is available", layer.contains("onFileLogging = viewModel::toggleFileLogging"))
        assertTrue(layer.contains("onUpload = viewModel::uploadLogsNow"))
    }
}

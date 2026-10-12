package com.openautolink.carplaylab.media

import org.junit.Assert.*
import org.junit.Test

class ReceiverTelemetryTest {
    @Test fun monotonicRenderWindowExpiresAndNeverUsesAdvertisedFps() {
        val counter = ReceiverTelemetry(8)
        counter.advertised(1920, 1080)
        counter.frameRendered(1_000_000_000)
        assertNull(counter.snapshot(1_000_000_000).renderedFps)
        repeat(30) { counter.frameRendered(1_000_000_000 + (it + 1) * 1_000_000_000L / 30) }
        assertEquals(30.0, counter.snapshot(2_000_000_000).renderedFps!!, 0.01)
        assertEquals(31L, counter.snapshot(2_000_000_000).renderedFrames)
        assertEquals(1920, counter.snapshot(2_000_000_000).advertisedWidth)
        assertNull(counter.snapshot(5_000_000_001).renderedFps)
        counter.frameRendered(1) // out-of-order callback cannot manufacture a rate
        assertNull(counter.snapshot(5_000_000_001).renderedFps)
    }

    @Test fun audioCountsOnlyAcceptedPacketsAndPositiveWritesWithoutAudibilityClaim() {
        val counter = ReceiverTelemetry(9)
        counter.audioPacketAccepted()
        counter.audioWritten(42)
        assertNull(counter.snapshot(0).audioWrittenBytes)
        counter.audioStreamStarted()
        assertTrue(counter.snapshot(0).audioStreamObserved)
        assertEquals(0L, counter.snapshot(0).audioAcceptedPackets)
        counter.audioPacketAccepted()
        counter.audioWritten(0)
        counter.audioWritten(-1)
        counter.audioWritten(42)
        assertEquals(1L, counter.snapshot(0).audioAcceptedPackets)
        assertEquals(42L, counter.snapshot(0).audioWrittenBytes)
        counter.retire()
        counter.audioStreamStarted()
        counter.audioPacketAccepted()
        counter.audioWritten(42)
        assertFalse(counter.snapshot(0).audioStreamObserved)
        assertNull(counter.snapshot(0).audioWrittenBytes)
    }

    @Test fun absentMeasurementsStayAbsentAndRetiredGenerationRejectsCallbacks() {
        val counter = ReceiverTelemetry(7)
        assertNull(counter.snapshot(0).renderedFrames)
        assertNull(counter.snapshot(0).audioAcceptedPackets)
        counter.decoderConfigured("actual.decoder")
        counter.outputFormat(1280, 720, 1280, 720)
        counter.frameRendered(1_000_000_000)
        val observed = counter.snapshot(1_000_000_000)
        assertEquals(7L, observed.generation)
        assertEquals("actual.decoder", observed.decoderName)
        assertEquals(1280, observed.codedWidth)
        assertEquals(1L, observed.renderedFrames)
        counter.retire()
        counter.frameRendered(2_000_000_000)
        assertNull(counter.snapshot(2_000_000_000).renderedFrames)
        assertNull(counter.snapshot(2_000_000_000).decoderName)
    }
}

package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class MediaEngineDiagnosticsTest {
    private fun session(): AirPlaySession = mock(AirPlaySession::class.java).also {
        `when`(it.sharedSecret).thenReturn(ByteArray(32) { 7 })
    }
    private val sink = object : MediaSink {
        override fun supportsAudio(format: AudioFormat) = true
    }
    private val offer = mapOf<String, Any?>("audioFormat" to 8388608L,
        "audioType" to "media", "streamConnectionID" to 1L, "spf" to 1024)

    @Test fun diagnosticsRequireLiveExactOwnerAndStopAfterTeardown() {
        val received = mutableListOf<Pair<AirPlaySession, String>>()
        val engine = CarPlayMediaEngine(sink, onDiagnostic = { s, m -> received += s to m })
        val owner = session(); val other = session()
        engine.reportDiagnostic(owner, "before")
        assertTrue(received.isEmpty())
        assertNotNull(engine.onAudio(owner, 102, offer))
        try {
            engine.reportDiagnostic(other, "foreign")
            engine.reportDiagnostic(owner, "live")
            assertEquals(listOf(owner to "live"), received)
            `when`(owner.isClosed).thenReturn(true)
            engine.reportDiagnostic(owner, "closed")
            assertEquals(1, received.size)
            `when`(owner.isClosed).thenReturn(false)
        } finally { engine.onSessionClosed(owner) }
        engine.reportDiagnostic(owner, "retired")
        assertEquals(1, received.size)
    }

    @Test fun failingDiagnosticConsumerCannotEndMediaOwner() {
        val engine = CarPlayMediaEngine(sink, onDiagnostic = { _, _ -> error("test sink") })
        val owner = session()
        assertNotNull(engine.onAudio(owner, 102, offer))
        try {
            engine.reportDiagnostic(owner, "live")
            assertTrue(engine.owns(owner))
        } finally { engine.onSessionClosed(owner) }
    }
}

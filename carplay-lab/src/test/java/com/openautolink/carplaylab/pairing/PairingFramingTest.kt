package com.openautolink.carplaylab.pairing

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class PairingFramingTest {
    @Test fun fragmentedBodyAndPipelinedRequestsRemainSeparate() {
        val input = ByteArrayInputStream("POST /pair-verify RTSP/1.0\r\nCSeq: 8\r\nContent-Length: 3\r\n\r\nabcGET /info RTSP/1.0\r\n\r\n".toByteArray())
        val reader = PairingFraming(input)
        assertArrayEquals("abc".toByteArray(), reader.readRequest()!!.body)
        assertEquals("/info", reader.readRequest()!!.path)
        assertNull(reader.readRequest())
    }
    @Test fun invalidLengthsAndAmbiguousHeadersFailClosed() {
        for (header in listOf("Content-Length: -1", "Content-Length: 2147483647", "Content-Length: x", "Content-Length: 0\r\nContent-Length: 1", "Transfer-Encoding: chunked", "Broken")) {
            try {
                PairingFraming(ByteArrayInputStream("POST /pair-setup RTSP/1.0\r\n$header\r\n\r\n".toByteArray())).readRequest()
                fail("Accepted malformed framing")
            } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun truncatedBodyFails() {
        try {
            PairingFraming(ByteArrayInputStream("POST /pair-setup RTSP/1.0\r\nContent-Length: 2\r\n\r\nx".toByteArray())).readRequest()
            fail("Accepted truncated body")
        } catch (_: java.io.EOFException) { }
    }
    @Test fun encryptedFramesSwitchWithoutReadingAhead() {
        val a = ByteArray(32) { 1 }; val b = ByteArray(32) { 2 }
        val sender = ControlCipher(b, a)
        val first = "GET /info RTSP/1.0\r\n\r\n".toByteArray()
        val receiver = PairingFraming(ByteArrayInputStream(sender.encrypt(first)), ControlCipher(a, b))
        assertEquals("/info", receiver.readRequest()!!.path)
    }
}

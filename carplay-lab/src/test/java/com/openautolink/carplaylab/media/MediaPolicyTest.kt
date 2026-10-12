package com.openautolink.carplaylab.media

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class MediaPolicyTest {
    @Test fun onlyExactSelectedNumericPeerIsAdmitted() {
        val policy = MediaPolicy("192.168.1.25")
        assertTrue(policy.allows(InetAddress.getByName("192.168.1.25")))
        assertFalse(policy.allows(InetAddress.getByName("192.168.1.26")))
        assertThrows(IllegalArgumentException::class.java) { MediaPolicy("phone.local") }
    }
    @Test fun touchScalesClampsAndRejectsInvalidGeometry() {
        assertEquals(TouchPoint(true, .5f, .5f), MediaPolicy.touch(0, 640f, 360f, 1280, 720))
        assertEquals(TouchPoint(false, 1f, 0f), MediaPolicy.touch(3, 2000f, -2f, 1280, 720))
        assertNull(MediaPolicy.touch(2, Float.NaN, 0f, 1280, 720))
        assertNull(MediaPolicy.touch(2, 1f, 1f, 0, 720))
        assertNull(MediaPolicy.touch(5, 1f, 1f, 1280, 720))
    }
    @Test fun acceptsOnlyExactSupportedPcmNegotiation() {
        assertTrue(MediaPolicy.supportsAudio(0x8000L, 100))
        assertTrue(MediaPolicy.supportsAudio(0x400L, 101))
        assertFalse(MediaPolicy.supportsAudio(0L, 100))
        assertTrue(MediaPolicy.supportsAudio(0x800000L, 102)) // RED: type-102 AAC-LC was rejected.
        assertFalse(MediaPolicy.supportsAudio(0x70000000L, 100))
        assertFalse(MediaPolicy.supportsAudio(0x8001L, 100))
    }
}

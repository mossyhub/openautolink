package com.openautolink.carplaylab.intake

import org.junit.Assert.*
import org.junit.Test

class OalNmeaCacheTest {
    companion object {
        fun sentence(body: String): String = "$" + body + "*" + "%02X".format(body.fold(0) { a, c -> a xor c.code })
        val gga = sentence("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,")
        val rmc = sentence("GNRMC,123519,A,4807.038,N,01131.000,E,0.0,0.0,230394,,")
    }
    @Test fun rejectsCorruptInvalidFixAndUnboundedSentencesWithoutKeepingOldFix() {
        val cache = OalNmeaCache { 100L }
        assertTrue(cache.accept(gga))
        assertFalse(cache.accept(gga.dropLast(2) + "00"))
        assertNull(cache.latest(setOf(1)))
        assertFalse(cache.accept(sentence("GPGGA,123519,4807.038,N,01131.000,E,0,08,0.9,545.4,M,46.9,M,,")))
        assertFalse(cache.accept(sentence("GNRMC,123519,V,4807.038,N,01131.000,E,0.0,0.0,230394,,")))
        assertFalse(cache.accept(sentence("GNRMC,123519,A,9999.0,N,01131.000,E,0.0,0.0,230394,,")))
        assertFalse(cache.accept("$" + "GPGGA," + "x".repeat(4096)))
        assertFalse(cache.accept(sentence("ZZRMC,123519,A,4807.038,N,01131.000,E,0.0,0.0,230394,,")))
        assertNull(cache.latest(setOf(1,2)))
    }

    @Test fun sendsOnlyRequestedFreshChecksumValidHostSentences() {
        var now = 10L
        val cache = OalNmeaCache { now }
        assertTrue(cache.accept(gga + "\r\n"))
        assertTrue(cache.accept(rmc))
        assertEquals(gga + "\r\n", cache.latest(setOf(1)))
        assertEquals(rmc + "\r\n", cache.latest(setOf(2,4)))
        assertNull(cache.latest(setOf(4)))
        assertEquals(gga + "\r\n" + rmc + "\r\n", cache.latest(setOf(1,2)))
        now = 3011L
        assertNull(cache.latest(setOf(1,2)))
    }
}

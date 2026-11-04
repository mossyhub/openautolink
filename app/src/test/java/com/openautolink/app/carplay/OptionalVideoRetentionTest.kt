package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test

class OptionalVideoRetentionTest {
    @Test fun startupAndTerminalSurviveOrdinaryDiagnosticFlood() {
        val lines = mutableListOf<String>()
        val reporter = CarPlayAudioDiagnosticReporter(1, { 0L }) { lines += it }
        repeat(128) { reporter.record("media stage=surface-lost") }
        for (type in listOf(110, 111)) {
            for (stage in listOf("tcp-attached", "config-delivered", "authenticated-frame-delivered")) {
                reporter.record("media stage=$stage kind=video type=$type")
                assertTrue("missing $stage/$type", lines.any { "stage=$stage kind=video type=$type" in it })
            }
            reporter.record("media stage=screen-terminal kind=video type=$type generation=7 reason=malformed")
            assertTrue(lines.any { "type=$type generation=7 reason=malformed" in it })
        }
        assertNull(CarPlayAudioDiagnostics.sanitize(1,"media stage=screen-terminal kind=video type=112 generation=7 reason=private"))
    }
}

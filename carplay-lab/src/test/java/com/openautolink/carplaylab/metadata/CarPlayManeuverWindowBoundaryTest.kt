package com.openautolink.carplaylab.metadata

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test

class CarPlayManeuverWindowBoundaryTest {
    private fun CarPlayMetadataIntake.define(id: Int, type: Int = 2) {
        handle(Iap2Messages.buildRaw(0x5202) { u16(0, 42); u16(1, id); u8(3, type); string(4, "Private road") })
    }
    private fun CarPlayMetadataIntake.select(id: Int) {
        handle(Iap2Messages.buildRaw(0x5201) { u8(1, 1); u16List(13, listOf(id)) })
    }
    @Test fun initialBurstAndSparseOpaqueIdsUseInsertionNotModuloOrNumericOrder() {
        val ids = listOf(65000, 42, 99, 3, 800, 7, 5000, 0, 65535)
        for (candidate in ids) {
            val intake = CarPlayMetadataIntake { 0L }.apply { authenticate() }
            ids.forEach { intake.define(it) }
            intake.select(candidate)
            if (candidate == ids.first()) assertNull(intake.navigation)
            else assertEquals(2, intake.navigation?.maneuverType)
        }
    }
    @Test fun replacementDoesNotRefreshInsertionAgeOrConsumeAnotherSlot() {
        val intake = CarPlayMetadataIntake { 0L }.apply { authenticate() }
        (0..7).forEach { intake.define(it) }
        intake.define(0, 3)
        intake.select(0)
        assertEquals(3, intake.navigation?.maneuverType)
        intake.define(8)
        intake.select(1)
        assertEquals(2, intake.navigation?.maneuverType)
        intake.select(0)
        assertNull("replaced oldest is not promoted into the sender's new window", intake.navigation)
    }
    @Test fun changedSelectionWaitsForItsExactLaterDefinition() {
        val intake = CarPlayMetadataIntake { 0L }.apply { authenticate() }
        intake.define(65000, 11); intake.select(65000)
        (1..9).forEach { intake.define(it) }
        intake.select(42000)
        assertNull("no arbitrary cached or old displayed fallback", intake.navigation)
        intake.define(42001, 3)
        assertNull(intake.navigation)
        intake.define(42000, 4)
        assertEquals(4, intake.navigation?.maneuverType)
    }
    @Test fun emptyStopArrivalExpiryAndRevokeDiscardEvictedDisplaySnapshot() {
        for (terminal in listOf("empty", "stop", "arrival", "expiry", "revoke")) {
            var now = 0L
            val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
            intake.define(0, 11); intake.select(0)
            (1..8).forEach { intake.define(it) }
            assertEquals(11, intake.navigation?.maneuverType)
            when (terminal) {
                "empty" -> intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13, emptyList()) })
                "stop" -> intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1, 0) })
                "arrival" -> intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1, 2) })
                "expiry" -> { now = 120000; intake.tick() }
                "revoke" -> intake.revoke()
            }
            assertNull(terminal, intake.navigation)
            intake.select(0)
            assertNull("$terminal cannot resurrect evicted display", intake.navigation)
        }
    }
    @Test fun finiteWindowReceiptsExposeCountsNotIdsOrRoadAndRemainRateLimited() {
        var now = 0L
        val lines = mutableListOf<String>()
        val intake = CarPlayMetadataIntake { now }.apply { authenticate(); onDiagnostic = lines::add }
        (0..7).forEach { intake.define(it) }
        intake.select(0)
        intake.define(8)
        intake.define(8, 3)
        now = 5000
        intake.select(0)
        assertTrue("cache diagnostics must expose FIFO pressure and separate display", lines.any {
            it.startsWith("carplay-nav-cache epoch=1 size=8 selected=true inserts=9 replacements=1 evictions=1 eviction=oldest")
        })
        assertFalse(lines.any { it.contains("Private road") || it.contains("id=") })
        val before = lines.size
        repeat(1000) { intake.define(8, 3) }
        assertEquals("unchanged cache churn is rate limited", before, lines.size)
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1, 0) })
        now += 5000
        intake.select(42)
        assertTrue("route cache counters reset on route clear", lines.any {
            it.startsWith("carplay-nav-cache epoch=2 size=0 selected=false inserts=0 replacements=0 evictions=0 eviction=none")
        })
    }
}

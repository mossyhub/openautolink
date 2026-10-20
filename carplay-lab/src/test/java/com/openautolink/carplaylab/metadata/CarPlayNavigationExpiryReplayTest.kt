package com.openautolink.carplaylab.metadata

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test

/** Source-shaped synthetic traffic through the actual 536 parser, not captured drive IDs. */
class CarPlayNavigationExpiryReplayTest {
    private class Replay {
        var now = 0L
        val receipts = mutableListOf<String>()
        val intake = CarPlayMetadataIntake { now }.apply { authenticate(); onDiagnostic = receipts::add }
        fun define(id: Int) = intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1, id); u8(3, id + 1) })
        fun select(id: Int) = intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1, 1); u16List(13, listOf(id)) })
        fun seed() { (0..7).forEach(::define); select(0); assertEquals(1, intake.navigation?.maneuverType) }
        fun expire() { now = 120_001; intake.tick(); assertNull(intake.routeMetadata); assertNull(intake.navigation) }
    }
    @Test fun sameSessionActiveSelectionAfterOwnershipExpiryNeedsPreviouslySentDefinition() {
        val r = Replay(); r.seed(); r.expire()
        // No new authentication/session, no new definition1: sender still knows its window.
        r.select(1)
        assertNotNull("fresh active/current delta must reacquire route ownership", r.intake.routeMetadata)
        println(r.receipts.joinToString("\n"))
        assertEquals("local ownership expiry must not destroy same-session definition1", 2, r.intake.navigation?.maneuverType)
    }
    @Test fun exactSameTrafficBeforeDeadlineResolvesWithoutResend() {
        val r = Replay(); r.seed(); r.now = 119_999; r.intake.tick(); r.select(1)
        assertEquals(2, r.intake.navigation?.maneuverType)
        assertNull(r.intake.navigation?.distanceMeters)
        assertNull(r.intake.navigation?.remainingSeconds)
    }
    @Test fun expiredDefinitionAndEstimateOnlyCannotResurrectRoute() {
        val r = Replay(); r.seed(); r.expire(); r.define(1)
        r.intake.handle(Iap2Messages.buildRaw(0x5201) { u32(10, 25) })
        assertNull(r.intake.routeMetadata); assertNull(r.intake.navigation)
    }
    @Test fun stopArrivalAndRevocationRejectOldDefinitions() {
        for (terminal in listOf(0, 2)) {
            val r = Replay(); r.seed()
            r.intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1, terminal) })
            r.select(1); assertNull(r.intake.navigation)
        }
        val r = Replay(); r.seed(); r.intake.revoke(); r.intake.authenticate(); r.select(1)
        assertNull(r.intake.routeMetadata); assertNull(r.intake.navigation)
    }
    @Test fun explicitEmptyClearsDisplayButIsNotCurrentlyDictionaryReset() {
        val r = Replay(); r.seed()
        r.intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13, emptyList()) })
        assertNull(r.intake.navigation)
        r.select(1); assertEquals("baseline empty-list is a selection clear, not a cache reset", 2, r.intake.navigation?.maneuverType)
    }
    @Test fun maneuverOnlyTrafficDoesNotRenewRouteDeadline() {
        val r = Replay(); r.seed()
        for (t in listOf(30_000L, 60_000L, 90_000L, 119_999L)) { r.now=t; r.define(7) }
        r.expire(); assertTrue(r.receipts.any { "reason=expired" in it })
        assertTrue(r.receipts.any { "kind=maneuver" in it && "parsed=12" in it })
    }
}

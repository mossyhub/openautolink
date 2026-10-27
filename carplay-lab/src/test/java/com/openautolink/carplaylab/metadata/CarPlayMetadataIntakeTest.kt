package com.openautolink.carplaylab.metadata

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test

class CarPlayMetadataIntakeTest {
    @Test fun explicitPlaybackPresenceComesOnlyFromFullyValidatedCurrentFrame() {
        val intake=CarPlayMetadataIntake { 0L }.apply { authenticate() }
        val getter=intake.javaClass.methods.firstOrNull { it.name=="getExplicitPlayback" }
        assertNotNull("real intake must expose parsed playback presence independently of inherited snapshots",getter)
        fun event()=getter!!.invoke(intake)
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(1) { u8(0,2) } })
        assertNotNull(event())
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"Title") } })
        assertNull("title-only is not playback evidence",event())
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(1) { u8(0,99) } })
        val unknown=event(); assertNotNull(unknown)
        assertNull(unknown!!.javaClass.getMethod("getPlaying").invoke(unknown))
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(1) { u8(0,1); u8(0,2) } })
        assertNull("malformed frame supplies no authority",event())
    }

    @Test fun changedOrEmptyCurrentInvalidatesImmediatelyAtEveryOwnershipAge() {
        for (age in listOf(0L, 30_000L, 119_999L)) {
            var now = 0L
            val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
            intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
            intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,8); u8(3,3) })
            intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)); u32(10,123) })
            val epoch = intake.routeMetadata?.epoch
            now = age
            intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13,listOf(8)) })
            assertEquals(3, intake.navigation?.maneuverType)
            assertNull("new instruction must not inherit previous turn distance", intake.navigation?.distanceMeters)
            intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13,emptyList()) })
            assertNull(intake.navigation)
            intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u32(10,77) })
            assertNull("omission must preserve an explicitly cleared selection", intake.navigation)
            intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
            assertNull("cached entries are not arbitrary fallback selections", intake.navigation)
            intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13,listOf(99)) })
            assertNull("unknown selected ID must not fall back to any cached entry", intake.navigation)
            intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13,listOf(7)) })
            assertEquals("late explicit selection joins its cached definition", 2, intake.navigation?.maneuverType)
            assertEquals(epoch, intake.routeMetadata?.epoch)
        }
    }

    @Test fun eachEstimateExpiresWithoutBeingRenewedByOtherRouteFields() {
        var now = 0L
        val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)); u32(10,123); string(3,"Current"); u64(6,60); u32(7,1000) })
        now = 29_000
        intake.handle(Iap2Messages.buildRaw(0x5201) { u32(7,900) })
        now = 30_000
        intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13,listOf(7)) })
        assertEquals(2, intake.navigation?.maneuverType)
        assertNull(intake.navigation?.distanceMeters)
        assertNull(intake.navigation?.road)
        assertNull(intake.navigation?.remainingSeconds)
        assertEquals(900, intake.navigation?.remainingMeters)
        now = 59_000; intake.tick()
        assertNull(intake.navigation?.remainingMeters)
        assertNotNull(intake.navigation)
    }

    @Test fun absentCurrentAtOwnershipDeadlineCannotReplayOldSelection() {
        var now = 0L
        val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        val epoch = intake.routeMetadata?.epoch
        now = 120_000
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u32(10,44) })
        assertNull(intake.routeMetadata)
        assertNull(intake.navigation)
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,3) })
        assertNull("details cannot choose the expired selected ID", intake.navigation)
        intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13,listOf(7)) })
        assertNull("current-only cannot unlock expiry", intake.routeMetadata)
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        assertNotEquals(epoch, intake.routeMetadata?.epoch)
        assertEquals(3, intake.navigation?.maneuverType)
    }

    @Test fun explicitActiveCurrentAtDeadlineReusesCacheButEmptyCannotAcquire() {
        for (current in listOf(emptyList(), listOf(7))) {
            var now = 0L
            val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
            intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
            intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
            val epoch = intake.routeMetadata?.epoch
            now = 120_000
            intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,current) })
            if (current.isEmpty()) assertNull(intake.routeMetadata)
            else {
                assertNotEquals(epoch, intake.routeMetadata?.epoch)
                assertEquals("fresh active/current reuses bounded definitions", 2, intake.navigation?.maneuverType)
            }
            intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,3) })
            if (current.isEmpty()) assertNull("empty selection stays empty despite fresh details", intake.navigation)
            else assertEquals(3, intake.navigation?.maneuverType)
        }
    }

    @Test fun unauthenticatedFramesCannotPrimeRouteOrManeuverCache() {
        val intake = CarPlayMetadataIntake { 0L }
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        assertNull(intake.routeMetadata)
        intake.authenticate()
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        assertNull(intake.navigation)
    }

    @Test fun quietValidSelectionSurvivesThirtySecondsAndOmittedCurrentDeltas() {
        for (activeState in listOf(false, true)) {
            var now = 0L
            val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
            intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2); string(4,"Turn road") })
            intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)); u32(10,350) })
            val epoch = intake.routeMetadata?.epoch
            now = 30_000; intake.tick()
            assertEquals("quiet route is not an invalid selection", 2, intake.navigation?.maneuverType)
            assertNull("estimates age independently of the selected instruction", intake.navigation?.distanceMeters)
            now = 56_000
            intake.handle(Iap2Messages.buildRaw(0x5201) { if (activeState) u8(1,1); u32(10,99) })
            assertEquals(2, intake.navigation?.maneuverType)
            assertEquals(99, intake.navigation?.distanceMeters)
            assertEquals(epoch, intake.routeMetadata?.epoch)
            now = 175_999; intake.tick()
            assertEquals(2, intake.navigation?.maneuverType)
            now = 176_000; intake.tick()
            assertNull(intake.routeMetadata)
        }
    }

    @Test fun selectedIdOnlyRecoversAfterIncidentLengthGapWithoutRepeatedDetails() {
        var now = 0L
        val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2); string(4,"Turn road"); u8(8,0) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)); u32(10,350); u64(6,120); u32(7,1500) })
        val epoch = requireNotNull(intake.routeMetadata).epoch
        now = 30_110; intake.tick()
        assertNotNull("quiet selection survives estimate expiry", intake.navigation)
        assertEquals(epoch, intake.routeMetadata?.epoch)
        now = 56_000
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        assertNotNull("fresh selected ID must reuse route-scoped definition after 56s gap", intake.navigation)
        val recovered = requireNotNull(intake.navigation)
        assertEquals(2, recovered.maneuverType)
        assertEquals("Turn road", recovered.road)
        assertNull(recovered.distanceMeters)
        assertNull(recovered.remainingSeconds)
        assertNull(recovered.remainingMeters)
        assertEquals(epoch, intake.routeMetadata?.epoch)
    }

    @Test fun absentCurrentFramesRetainSelectionAndDetailsDoNotChooseAnotherId() {
        for (activeState in listOf(false, true)) {
            var now = 0L
            val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
            intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
            intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
            val epoch = intake.routeMetadata?.epoch
            now = 29_999; intake.tick()
            now = 30_000
            intake.handle(Iap2Messages.buildRaw(0x5201) { if (activeState) u8(1,1); u32(10,99) })
            assertEquals("omitted current list inherits selection (active=$activeState)", 2, intake.navigation?.maneuverType)
            intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,3) })
            assertEquals("selected details can update a still-owned selection", 3, intake.navigation?.maneuverType)
            assertEquals(epoch, intake.routeMetadata?.epoch)
            now = 56_000
            intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13,listOf(7)) })
            assertEquals(3, intake.navigation?.maneuverType)
            assertEquals("fresh pending estimate is retained", 99, intake.navigation?.distanceMeters)
        }
    }

    @Test fun estimatesExpireIndependentlyOfExplicitSelectionRefresh() {
        var now = 0L
        val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        now = 30_000
        intake.handle(Iap2Messages.buildRaw(0x5201) { string(3,"Pending road"); u32(10,99); u64(6,100); u32(7,1000) })
        assertNotNull(intake.navigation)
        now = 59_999; intake.tick()
        now = 60_000
        intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13,listOf(7)) })
        assertNotNull(intake.navigation)
        val recovered = requireNotNull(intake.navigation)
        assertNull("pending estimates must not outlive their own route-frame deadline", recovered.distanceMeters)
        assertNull(recovered.road)
        assertNull(recovered.remainingSeconds)
        assertNull(recovered.remainingMeters)
        intake.handle(Iap2Messages.buildRaw(0x5201) { u32(10,42); u64(6,50); u32(7,500) })
        assertEquals(42, intake.navigation?.distanceMeters)
        assertEquals(50L, intake.navigation?.remainingSeconds)
        assertEquals(500, intake.navigation?.remainingMeters)
    }

    @Test fun routeOnlyRecoveryHonorsExactInstructionAndOwnershipDeadlines() {
        for (deadline in listOf(30_000L, 119_999L, 120_000L)) {
            var now = 0L
            val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
            intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
            intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)); u32(10,99) })
            val epoch = intake.routeMetadata?.epoch
            now = 29_999; intake.tick()
            now = deadline
            intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
            if (deadline < 120_000L) {
                assertEquals("definition remains owned at $deadline", 2, intake.navigation?.maneuverType)
                assertEquals(epoch, intake.routeMetadata?.epoch)
            } else {
                assertEquals("fresh active/current reacquires after exact expiry", 2, intake.navigation?.maneuverType)
                assertNotEquals(epoch, intake.routeMetadata?.epoch)
            }
            assertNull("pre-deadline estimate cannot survive renewal", intake.navigation?.distanceMeters)
        }
    }

    @Test fun retainedDefinitionsAreLatestEightWithOneQuietDisplaySnapshot() {
        var now = 0L
        val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        now = 30_000; intake.tick()
        val futureIds = (8..40).toList()
        for (id in futureIds) {
            intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,id); u8(3,3) })
            assertEquals("future details cannot replace selected instruction", 2, intake.navigation?.maneuverType)
        }
        // Leaving the displayed ID discards its separate snapshot: it must not
        // be available for arbitrary later reselection outside the sender window.
        intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13,listOf(0)) })
        val retained = (listOf(7) + futureIds).filter { id ->
            intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13,listOf(id)) })
            intake.navigation != null
        }.toSet()
        assertEquals("exactly the latest eight definitions remain selectable", futureIds.takeLast(8).toSet(), retained)
    }

    @Test fun stopArrivalAndEmptyCurrentCannotReplayRetainedStaleSelection() {
        for (terminal in listOf(0, 2, null)) {
            var now = 0L
            val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
            intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
            intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
            val epoch = intake.routeMetadata?.epoch
            now = 30_000; intake.tick()
            intake.handle(Iap2Messages.buildRaw(0x5201) { if (terminal == null) u16List(13,emptyList()) else u8(1,terminal) })
            intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,8); u8(3,3) })
            assertNull("empty or terminated selection cannot revive on unrelated details", intake.navigation)
            intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
            if (terminal == null) {
                assertEquals(2, intake.navigation?.maneuverType)
                assertEquals(epoch, intake.routeMetadata?.epoch)
            } else {
                assertNull("new route must not reuse a terminated route's definition", intake.navigation)
                assertNotEquals(epoch, intake.routeMetadata?.epoch)
            }
            intake.revoke()
            intake.authenticate()
            intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
            assertNull(intake.routeMetadata)
        }
    }

    @Test fun acceptedRouteCrossingInstructionDeadlineRetainsDefinitionButPurgesEstimates() {
        var now = 0L
        val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)); string(3,"Old road"); u32(10,10); u64(6,20); u32(7,30) })
        val epoch = intake.routeMetadata?.epoch
        now = 29_999; intake.tick()
        now = 30_001
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        assertEquals("fresh selection must reuse the definition even before a scheduled tick", 2, intake.navigation?.maneuverType)
        assertNull(intake.navigation?.road); assertNull(intake.navigation?.distanceMeters)
        assertNull(intake.navigation?.remainingSeconds); assertNull(intake.navigation?.remainingMeters)
        assertEquals(epoch, intake.routeMetadata?.epoch)
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,3) })
        val fresh = requireNotNull(intake.navigation)
        assertEquals(3, fresh.maneuverType)
        assertNull(fresh.road); assertNull(fresh.distanceMeters)
        assertNull(fresh.remainingSeconds); assertNull(fresh.remainingMeters)
    }

    @Test fun acceptedRouteCrossingOwnershipDeadlineCreatesNewEpochWithBoundedCache() {
        var now = 0L
        val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        val epoch = intake.routeMetadata?.epoch
        now = 119_999; intake.tick()
        now = 120_001
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        assertNotEquals("route renewal cannot erase a crossed ownership deadline", epoch, intake.routeMetadata?.epoch)
        assertEquals(2, intake.navigation?.maneuverType)
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,3) })
        assertEquals(3, intake.navigation?.maneuverType)
    }

    @Test fun acceptedManeuverCrossingOwnershipDeadlineCannotRetainOldRoute() {
        var now = 0L
        val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        val epoch = intake.routeMetadata?.epoch
        now = 119_999; intake.tick()
        now = 120_001
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,8); u8(3,3) })
        assertNull("maneuver traffic must enforce old ownership expiry", intake.routeMetadata)
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        assertNotEquals(epoch, intake.routeMetadata?.epoch)
        assertEquals("fresh active/current may reuse the exact retained definition", 2, intake.navigation?.maneuverType)
    }

    @Test fun invalidFramesCrossingDeadlinesLeaveOldSnapshotUntouchedUntilTick() {
        for (deadline in listOf(30_000L, 120_000L)) {
            var now = 0L
            val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
            intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
            intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
            now = deadline - 1; intake.tick()
            val before = intake.routeMetadata
            now = deadline + 1
            intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,99); u16List(13,listOf(8)) })
            assertEquals(before, intake.routeMetadata)
            intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,8); u8(3,3); bytes(11,byteArrayOf(1)) })
            assertEquals(before, intake.routeMetadata)
            intake.tick()
            if (deadline == 120_000L) assertNull(intake.routeMetadata)
            else assertNotNull("estimate expiry must not discard selection", intake.navigation)
        }
    }

    @Test fun staleLoadingRouteRetainsCachedFutureDetailsForFreshSelection() {
        var now = 0L
        val intake = CarPlayMetadataIntake { now }
        intake.authenticate()
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(8)) })
        val epoch = intake.routeMetadata?.epoch
        assertNull(intake.navigation)
        now = 30_000; intake.tick()
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        assertEquals("fresh explicit selection confirms a route-scoped future definition", 2, intake.navigation?.maneuverType)
        assertEquals(epoch, intake.routeMetadata?.epoch)
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,3) })
        assertEquals(3, intake.navigation?.maneuverType)
    }

    @Test fun quietInstructionAndRouteExpireAtTwoMinutesDespiteManeuverTraffic() {
        var now = 0L
        val intake = CarPlayMetadataIntake { now }
        intake.authenticate()
        fun details() = intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        fun route() = intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        details(); route()
        val epoch = requireNotNull(intake.routeMetadata).epoch
        now = 29_999; intake.tick(); assertNotNull(intake.navigation)
        now = 30_000; intake.tick()
        assertNotNull("quiet selected turn has no invented 30s TTL", intake.navigation)
        assertNotNull("instruction expiry is not route termination", intake.routeMetadata)
        assertEquals(epoch, intake.routeMetadata?.epoch)
        details()
        assertNotNull("details update the still-valid selected instruction", intake.navigation)
        now = 119_999; intake.tick(); assertNotNull(intake.routeMetadata)
        now = 120_000; intake.tick(); assertNull(intake.routeMetadata)
        route()
        assertEquals("fresh active/current may reacquire the retained exact instruction", 2, intake.navigation?.maneuverType)
        assertNotEquals(epoch, intake.routeMetadata?.epoch)
        details(); assertNotNull(intake.navigation)
    }

    @Test fun firstParseFailuresAndFirstManeuverAreNotHiddenBySameWindowRoute() {
        val lines = mutableListOf<String>()
        val intake = CarPlayMetadataIntake { 0L }.apply { onDiagnostic = lines::add }
        intake.authenticate()
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,8); u8(3,2) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,99) })
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,8) })
        assertTrue(lines.any { it.startsWith("carplay-nav kind=maneuver parsed=1 failed=0 result=parsed") })
        assertTrue(lines.any { it.startsWith("carplay-nav kind=route parsed=1 failed=1 result=failed") })
        assertTrue(lines.any { it.startsWith("carplay-nav kind=maneuver parsed=1 failed=1 result=failed") })
        repeat(100) { intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,8) }) }
        assertEquals("same-window failures must remain bounded",4,lines.count { it.startsWith("carplay-nav kind=") })
        assertEquals("each gated receipt has exactly one cache snapshot",8,lines.size)
        assertNull(intake.navigation)
    }

    @Test fun observabilityPreservesAbsentEmptyStopArrivalEvictionAndRevokeSemantics() {
        var now = 0L
        val lines = mutableListOf<String>()
        val intake = CarPlayMetadataIntake { now }.apply { onDiagnostic = lines::add }
        intake.authenticate()
        fun route() { intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) }) }
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2); string(4,"Private") })
        route(); assertNotNull(intake.navigation)
        intake.handle(Iap2Messages.buildRaw(0x5201) { u32(10,99) })
        assertNotNull(intake.navigation) // absent current list retains selected maneuver
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,0); u8(1,1) })
        assertNotNull(intake.navigation) // duplicate TLVs still rejected
        intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13,emptyList()) })
        assertNull(intake.navigation) // no speculative empty-list grace
        route(); assertNotNull(intake.navigation)
        for (id in 8..15) intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,id); u8(3,2) })
        assertNotNull(intake.navigation) // separate display survives FIFO eviction
        intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13,listOf(8)) })
        assertNotNull(intake.navigation) // entering window evicted stale 7, not next 8
        route()
        assertNull(intake.navigation) // evicted old display cannot be reselected
        assertTrue(lines.any { it.contains("transition=clear reason=cache_miss") })
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        route()
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,2) })
        assertNull(intake.navigation)
        assertTrue(lines.any { it.contains("transition=clear reason=arrived") })
        route(); assertNull(intake.navigation) // arrival purged cached maneuvers
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        intake.revoke(); assertNull(intake.navigation)
        assertTrue(lines.any { it.contains("transition=clear reason=revoke") })
        intake.authenticate(); route(); assertNull(intake.navigation)
        assertFalse(lines.any { it.contains("Private") })
    }

    @Test fun trackChangeWithoutNewArtReferenceClearsPreviousCover() {
        val intake = CarPlayMetadataIntake { 0L }; intake.authenticate()
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"First"); u8(26,7) } })
        intake.offerArtwork(7, byteArrayOf(1))
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"Second") } })
        assertNull(intake.artwork)
        assertNull(intake.media?.artworkTransferId)
        intake.offerArtwork(7, byteArrayOf(2))
        assertNull(intake.artwork)
    }

    @Test fun bluetoothBootstrapDoesNotClaimLongLivedMetadataEndpoints() {
        val base = Iap2Messages.buildRaw(0x1d01) {
            u16List(6,listOf(0xaa01,0x5000,0x5002,0x5200,0x5203))
            u16List(7,listOf(0xaa00,0x5001,0x5201,0x5202))
            group(30) { u16(0,42); string(1,"RouteGuidance") }
        }
        val b = com.shilapi.xcertplay.iap2.body.Iap2BodyReader.of(CarPlayMetadataIdentification.bootstrap(base))
        assertEquals(listOf(0xaa01),b.u16List(6))
        assertEquals(listOf(0xaa00),b.u16List(7))
        assertFalse(b.has(30))
    }

    @Test fun malformedAndOversizedFramesCannotPartiallyMutateState() {
        val intake = CarPlayMetadataIntake { 0L }
        intake.authenticate()
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"Good") } })
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"Bad"); bytes(4,byteArrayOf(1)) } })
        assertEquals("Good",intake.media?.title)
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"x".repeat(20000)) } })
        assertEquals("Good",intake.media?.title)
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u32(10,10); u16List(13,listOf(7)) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,0); bytes(10,byteArrayOf(1)) })
        assertEquals(10,intake.navigation?.distanceMeters)
        intake.revoke()
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"Late") } })
        assertNull(intake.media)
        assertNull(intake.navigation)
    }

    @Test fun artworkIsBoundToAuthenticatedCurrentTransferAndRetiredGeneration() {
        val intake = CarPlayMetadataIntake { 0L }
        intake.offerArtwork(7, byteArrayOf(1,2))
        intake.authenticate()
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(0) { u8(26,7) } })
        assertNull(intake.artwork)
        intake.offerArtwork(7, byteArrayOf(1,2))
        assertArrayEquals(byteArrayOf(1,2),intake.artwork)
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(0) { u8(26,8) } })
        assertNull(intake.artwork)
        intake.offerArtwork(7, byteArrayOf(3))
        assertNull(intake.artwork)
        intake.offerArtwork(8, byteArrayOf(4))
        intake.revoke()
        intake.authenticate()
        intake.offerArtwork(8, byteArrayOf(5))
        assertNull(intake.artwork)
        assertNull(intake.media)
    }

    @Test fun boundedNavigationIsProviderAgnosticAndRetiresOnNoRoute() {
        var now = 0L
        val intake = CarPlayMetadataIntake { now }
        intake.authenticate()
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2); string(4,"Road"); u8(8,0) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u32(10,350); u16List(13,listOf(7)); u64(6,120); u32(7,1500) })
        assertEquals(2,intake.navigation?.maneuverType)
        assertEquals(350,intake.navigation?.distanceMeters)
        assertEquals("Road",intake.navigation?.road)
        now = 30000
        intake.tick()
        assertNotNull(intake.navigation)
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,0) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)) })
        assertNull(intake.navigation) // previous route's cached index must not revive
    }

    @Test fun authenticatedNowPlayingRetainsOmittedFieldsWithoutInventingPlayback() {
        val intake = CarPlayMetadataIntake { 0L }
        val title = Iap2Messages.buildRaw(0x5001) { group(0) { string(1, "Song"); string(12,"Artist") } }
        intake.handle(title)
        assertNull(intake.media)
        intake.authenticate()
        intake.handle(title)
        assertEquals("Song", intake.media?.title)
        assertNull(intake.media?.playing)
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(1) { u32(1,1234); u8(0,1) } })
        assertEquals("Artist",intake.media?.artist)
        assertEquals(1234L,intake.media?.elapsedMillis)
        assertEquals(true,intake.media?.playing)
    }
}

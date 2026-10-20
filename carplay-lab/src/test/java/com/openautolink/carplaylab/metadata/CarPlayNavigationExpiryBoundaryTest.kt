package com.openautolink.carplaylab.metadata

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test

class CarPlayNavigationExpiryBoundaryTest {
    private class Replay {
        var now = 0L
        val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
        fun define(id: Int, type: Int = id + 1) = intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,id); u8(3,type) })
        fun select(id: Int) = intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(id)) })
        fun seed() { (0..7).forEach { define(it) }; select(0) }
        fun expire() { now=120_000; intake.tick(); assertNull(intake.routeMetadata); assertNull(intake.navigation) }
    }
    @Test fun postExpiryAdmissionRequiresActiveAndNonemptyCurrentTogether() {
        for (kind in 0..10) {
            val r=Replay(); r.seed(); r.expire()
            val frame=when(kind) {
                0 -> Iap2Messages.buildRaw(0x5201) { u8(1,1) }
                1 -> Iap2Messages.buildRaw(0x5201) { u32(10,99) }
                2 -> Iap2Messages.buildRaw(0x5201) { u8(1,1); u32(10,99) }
                3 -> Iap2Messages.buildRaw(0x5201) { u16List(13,listOf(1)) }
                4 -> Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,emptyList()) }
                5 -> Iap2Messages.buildRaw(0x5201) { u8(1,3); u16List(13,listOf(1)) }
                6 -> Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(1)); bytes(10,byteArrayOf(1)) }
                7 -> Iap2Messages.buildRaw(0x5202) { u16(1,1); u8(3,2) }
                8 -> Iap2Messages.buildRaw(0x5001) { }
                9 -> Iap2Messages.buildRaw(0x5201) { string(3,"ignored"); u64(6,88); u32(7,77) }
                else -> Iap2Messages.buildRaw(0x5201) { }
            }
            r.intake.handle(frame)
            assertNull("no ownership from kind=$kind",r.intake.routeMetadata)
            assertNull(r.intake.navigation)
            r.select(1)
            assertEquals(2,r.intake.navigation?.maneuverType)
            assertNull(r.intake.navigation?.distanceMeters)
            assertNull(r.intake.navigation?.road)
            assertNull(r.intake.navigation?.remainingSeconds)
            assertNull(r.intake.navigation?.remainingMeters)
        }
    }
    @Test fun expiryDiscardsAllEstimatesAndStartsNewEpoch() {
        val r=Replay(); r.seed(); val epoch=r.intake.routeMetadata?.epoch
        r.intake.handle(Iap2Messages.buildRaw(0x5201) { string(3,"old"); u32(10,4); u64(6,5); u32(7,6) })
        r.expire(); r.now=900_000; r.intake.tick(); assertNull(r.intake.routeMetadata)
        r.select(0)
        assertNotEquals(epoch,r.intake.routeMetadata?.epoch)
        assertEquals(1,r.intake.navigation?.maneuverType)
        assertNull(r.intake.navigation?.road); assertNull(r.intake.navigation?.distanceMeters)
        assertNull(r.intake.navigation?.remainingSeconds); assertNull(r.intake.navigation?.remainingMeters)
    }
    @Test fun expiredEvictedDisplayCannotReturnAndReplacementDoesNotRefreshFifo() {
        val r=Replay(); r.seed(); r.define(0,40); r.define(8)
        assertEquals(40,r.intake.navigation?.maneuverType)
        r.expire(); r.select(0); assertNull(r.intake.navigation)
        r.select(1); assertEquals(2,r.intake.navigation?.maneuverType)
        r.define(9); r.select(1); assertEquals(2,r.intake.navigation?.maneuverType)
        r.select(8); r.select(1); assertNull(r.intake.navigation)
    }
    @Test fun terminalAfterExpiryPurgesRetainedDefinitionsAndSameIdGeometry() {
        for (state in listOf(0,2)) {
            val r=Replay(); r.seed(); r.expire()
            r.intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,state) })
            r.select(1); assertNull(r.intake.navigation)
            r.define(1,55); assertEquals(55,r.intake.navigation?.maneuverType)
            assertNull(r.intake.navigation?.junctionAnglesDegrees)
        }
    }
    @Test fun sameRouteEmptyRetainsDictionaryButNotDisplayAndCannotUnlockExpiryFence() {
        val r=Replay(); r.seed()
        r.intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13,emptyList()) })
        assertNull(r.intake.navigation); r.select(1); assertEquals(2,r.intake.navigation?.maneuverType)
        r.expire()
        r.intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,emptyList()) })
        assertNull(r.intake.routeMetadata)
        r.intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13,listOf(1)) })
        assertNull(r.intake.routeMetadata)
        r.select(1); assertEquals(2,r.intake.navigation?.maneuverType)
    }
    @Test fun replacementIntakeAndRevocationCannotBorrowDefinitions() {
        val r=Replay(); r.seed(); r.expire(); r.intake.revoke(); r.intake.authenticate(); r.select(1)
        assertNull(r.intake.routeMetadata)
        val fresh=Replay(); fresh.select(1); assertNull(fresh.intake.navigation)
    }
    @Test fun admittedMissingExactDefinitionCanOnlyBeRepairedByThatDefinition() {
        val r=Replay(); r.seed(); r.expire(); r.select(65535)
        assertNotNull(r.intake.routeMetadata); assertNull(r.intake.navigation)
        r.define(8); assertNull(r.intake.navigation)
        r.define(65535,42); assertEquals(42,r.intake.navigation?.maneuverType)
    }
}

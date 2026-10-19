package com.openautolink.carplaylab.metadata

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test

/** Source-level binary reconstruction, NOT an owner capture.
 * accessoryd 24A446 @0x100177a28 / @0x100177bf8 emits repeated U16 parameters
 * for component IDs / junction angles. Index=U16 and type=U8 remain correct.
 */
class CarPlayNavigationContractTest {
    @Test fun publishedGeometryCannotMutateRetainedManeuver() {
        val intake = CarPlayMetadataIntake { 0L }.apply { authenticate() }
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 4); i16(10, -45) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1, 1); u16List(13, listOf(7)) })
        val angles = intake.navigation!!.junctionAnglesDegrees!!
        assertThrows(UnsupportedOperationException::class.java) { (angles as MutableList<Int>)[0] = 99 }
        intake.handle(Iap2Messages.buildRaw(0x5201) { u32(10, 20) })
        assertEquals(listOf(-45), intake.navigation?.junctionAnglesDegrees)
    }

    @Test fun malformedArraysScalarsAndLateTruncationRemainTransactional() {
        val intake = CarPlayMetadataIntake { 0L }.apply { authenticate() }
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 2) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1, 1); u16List(13, listOf(7)); u32(10, 12) })
        val before = intake.navigation
        val badManeuvers = listOf(
            Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 1); bytes(10, byteArrayOf(1)) },
            Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 1); bytes(0, byteArrayOf(1, 2, 3)) },
            Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 1); u8(3, 2) },
            Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 1); bytes(11, byteArrayOf(1)) }
        )
        badManeuvers.forEach { intake.handle(it); assertEquals(before, intake.navigation) }
        val good = Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 1) }
        intake.handle(com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x5202, good.payload + byteArrayOf(0)))
        assertEquals(before, intake.navigation)
        intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13, (1..9).toList()); u32(10, 33) })
        assertEquals(before, intake.navigation)
        intake.handle(Iap2Messages.buildRaw(0x5201) { bytes(13, byteArrayOf(1)); u32(10, 33) })
        assertEquals(before, intake.navigation)
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 255); u8(8, 255); u8(9, 255) })
        assertEquals("unknown maneuver enum is retained, never converted to straight", 255, intake.navigation?.maneuverType)
        assertEquals(255, intake.navigation?.drivingSide)
        assertEquals(255, intake.navigation?.junctionType)
    }

    @Test fun overflowingPublishedDistanceRejectsWholeRouteDelta() {
        val intake = CarPlayMetadataIntake { 0L }.apply { authenticate() }
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 2) })
        intake.handle(Iap2Messages.buildRaw(0x5201) {
            u8(1, 1); u16List(13, listOf(7)); u32(10, 12); string(3, "Before")
        })
        val before = intake.navigation
        for (id in listOf(7, 10)) {
            intake.handle(Iap2Messages.buildRaw(0x5201) { u32(id, 0xffffffffL); string(3, "After") })
            assertEquals("overflow must not silently retain old distance while committing the new road", before, intake.navigation)
        }
    }

    @Test fun signedJunctionGeometryReachesParentSeamWithoutAaEnumGuesses() {
        val intake = CarPlayMetadataIntake { 0L }.apply { authenticate() }
        intake.handle(Iap2Messages.buildRaw(0x5202) {
            u16(1, 7); u8(3, 4); u8(8, 0); u8(9, 1)
            i16(10, -45); i16(10, 45); i16(11, -180)
        })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1, 1); u16List(13, listOf(7)) })
        assertEquals(-180, intake.navigation?.junctionExitAngleDegrees)
        assertEquals(1, intake.navigation?.junctionType)
        assertEquals(listOf(-45, 45), intake.navigation?.junctionAnglesDegrees)
    }

    @Test fun playbackRawEnumReachesParentSeamWithoutInventingBoolean() {
        val intake = CarPlayMetadataIntake { 0L }.apply { authenticate() }
        for (status in listOf(0, 1, 2, 3, 4, 255)) {
            intake.handle(Iap2Messages.buildRaw(0x5001) { group(1) { u8(0, status) } })
            assertEquals("the additive seam preserves the actual received enum", status, intake.media?.playbackStatus)
        }
    }

    @Test fun pausedPlaybackStatusKeepsMetadataInsteadOfRejectingFrame() {
        val intake = CarPlayMetadataIntake { 0L }.apply { authenticate() }
        intake.handle(Iap2Messages.buildRaw(0x5001) {
            group(0) { string(1, "Synthetic track") }
            group(1) { u8(0, 2); u32(1, 1234) }
        })
        assertEquals("synthetic paused status is a valid enum, not a Boolean", "Synthetic track", intake.media?.title)
        assertEquals(false, intake.media?.playing)
        assertEquals(1234L, intake.media?.elapsedMillis)
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(1) { u32(1, 2345) } })
        assertEquals(false, intake.media?.playing)
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(1) { u8(0, 1) } })
        assertEquals(true, intake.media?.playing)
        intake.handle(Iap2Messages.buildRaw(0x5001) { group(1) { u8(0, 255); u32(1, 3456) } })
        assertNull("unknown enums must not invent playing or stopped", intake.media?.playing)
        assertEquals(3456L, intake.media?.elapsedMillis)
    }

    @Test fun firmwareNonterminalRouteStatesDoNotDiscardCurrentManeuver() {
        val intake = CarPlayMetadataIntake { 0L }.apply { authenticate() }
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 2) })
        for (state in 3..6) {
            intake.handle(Iap2Messages.buildRaw(0x5201) {
                u8(1, state); u16List(13, listOf(7)); u32(10, state * 10)
            })
            assertEquals("firmware converter accepts route states 0..6", state * 10, intake.navigation?.distanceMeters)
        }
        val before = intake.navigation
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1, 255); u32(10, 999) })
        assertEquals("unknown route states remain transactional rejects", before, intake.navigation)
    }

    @Test fun futureManeuverBurstRetainsDisplayOutsideStrictLatestEightWindow() {
        val intake = CarPlayMetadataIntake { 0L }.apply { authenticate() }
        intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 2) })
        intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1, 1); u16List(13, listOf(7)) })
        for (id in 8..40) intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1, id); u8(3, 1) })
        assertEquals("one separate display snapshot survives FIFO window churn", 2, intake.navigation?.maneuverType)
        intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13, listOf(40)) })
        assertEquals(1, intake.navigation?.maneuverType)
        intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13, listOf(7)) })
        assertNull("old display is not protected as a reusable ninth cache entry", intake.navigation)
        intake.handle(Iap2Messages.buildRaw(0x5201) { u16List(13, listOf(8)) })
        assertNull("unselected future entries still have bounded eviction", intake.navigation)
    }

    @Test fun firmwareRepeatedArrayParametersPublishManeuver() {
        val lines = mutableListOf<String>()
        val intake = CarPlayMetadataIntake { 0L }.apply { authenticate(); onDiagnostic = lines::add }
        intake.handle(Iap2Messages.buildRaw(0x5202) {
            u16(0, 42); u16(0, 43)
            u16(1, 7); u8(3, 2); string(4, "Synthetic road")
            u8(8, 0); u8(9, 0)
            i16(10, -90); i16(10, 0); i16(10, 90)
            i16(11, 90)
        })
        intake.handle(Iap2Messages.buildRaw(0x5201) {
            u16(0, 42); u16(0, 43); u8(1, 1)
            u32(10, 350); u16List(13, listOf(7, 8))
        })
        assertNotNull("legal repeated arrays must not be rejected as duplicate scalars", intake.navigation)
        assertEquals(2, intake.navigation?.maneuverType)
        assertEquals(350, intake.navigation?.distanceMeters)
        assertFalse(lines.any { it.contains("result=failed") })
        assertFalse(lines.any { it.contains("Synthetic road") })
    }
}

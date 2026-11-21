package com.openautolink.app.drivecard

import com.openautolink.app.secondary.SecondaryLayoutBox
import org.junit.Assert.*
import org.junit.Test

class DriveCardLayoutTest {
    @Test fun defaultDocksInsideRightSideAndReservesNativeStatusStrip() {
        val b=DriveCardLayout.DEFAULT.box
        assertTrue(b.left >= .5);assertTrue(b.right >= .92);assertTrue(b.bottom <= .85)
        assertTrue(b.right-b.left >= .25);assertTrue(b.bottom-b.top >= .24)
    }
    @Test fun dragAndResizeStayInOwnedRightRegion() {
        val b=DriveCardLayout.DEFAULT
        val moved=b.move(-10.0,10.0)
        assertEquals(.5,moved.box.left,0.0);assertTrue(moved.box.bottom<=.86)
        val resized=b.resize(10.0,-10.0)
        assertTrue(resized.box.right<=.94);assertEquals("Minimum normalized height",.18,resized.box.bottom-resized.box.top,1e-12)
        assertThrows(IllegalArgumentException::class.java) { DriveCardLayout(SecondaryLayoutBox(.0,.0,1.0,1.0)) }
        assertThrows(IllegalArgumentException::class.java) { DriveCardLayout(b.box, Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { DriveCardLayout(b.box, .19) }
    }
    @Test fun autoCannotTrustUnqualifiedOrRevokedVehicleUnitCache() {
        val data=com.openautolink.app.transport.ControlMessage.VehicleData(distanceDisplayUnits=0x24,vhalRegistrationGeneration=3)
        assertNull(DriveCardUnits.currentVehicleUnits(data,1000))
        val sample=com.openautolink.app.transport.VehiclePropertyObservation(100_000_000,100,0,registrationGeneration=3,propertyId=0x11400600,subscriptionActive=true)
        fun with(s:com.openautolink.app.transport.VehiclePropertyObservation)=data.copy(evObservationMetadata=mapOf("DISTANCE_DISPLAY_UNITS" to s))
        assertEquals(0x24,DriveCardUnits.currentVehicleUnits(with(sample),1000))
        assertNull(DriveCardUnits.currentVehicleUnits(with(sample.copy(status=1)),1000))
        assertNull(DriveCardUnits.currentVehicleUnits(with(sample.copy(subscriptionActive=false)),1000))
        assertNull(DriveCardUnits.currentVehicleUnits(with(sample.copy(registrationGeneration=2)),1000))
        assertNull(DriveCardUnits.currentVehicleUnits(with(sample.copy(timestampElapsedNanos=2_000_000_000)),1000))
        assertNull(DriveCardUnits.currentVehicleUnits(with(sample.copy(propertyId=0x11600207)),1000))
        assertNull(DriveCardUnits.currentVehicleUnits(with(sample),31_000))
        assertNull(DriveCardUnits.currentVehicleUnits(with(sample.copy(statusReadElapsedMs=30_000)),31_000))
        assertEquals(0x24,DriveCardUnits.currentVehicleUnits(with(sample.copy(receivedElapsedMs=30_000,statusReadElapsedMs=30_000)),31_000))
        assertNull(DriveCardUnits.currentVehicleUnits(with(sample).copy(vhalRegistrationGeneration=0),1000))
        assertNull(DriveCardUnits.currentVehicleUnits(with(sample.copy(statusReadElapsedMs=32_000)),31_000))
        assertNull(DriveCardUnits.currentVehicleUnits(with(sample).copy(distanceDisplayUnits=99),1000))
    }
    @Test fun imperialConversionIsDisplayOnlyAndHonorsExplicitChoice() {
        assertEquals(3.106855961,DriveCardUnits.efficiency(5.0,true),.00000001)
        assertTrue(DriveCardUnits.imperial("imperial",0x23,java.util.Locale.FRANCE))
        assertFalse(DriveCardUnits.imperial("metric",0x24,java.util.Locale.US))
        assertTrue(DriveCardUnits.imperial("auto",0x24,java.util.Locale.FRANCE))
        assertFalse(DriveCardUnits.imperial("auto",0x23,java.util.Locale.US))
        assertFalse(DriveCardUnits.imperial("auto",0x21,java.util.Locale.US))
        assertTrue(DriveCardUnits.imperial("auto",null,java.util.Locale.US))
        assertFalse(DriveCardUnits.imperial("auto",99,java.util.Locale.FRANCE))
    }
}

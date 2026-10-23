package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayHostMediaArmTest {
    @Test fun exclusiveInactiveAndSuppressModesExpireWithoutMutatingFrozenLease() {
        var now=1L
        val arm=PlayingPublicationArm { now }
        val method=arm.javaClass.methods.firstOrNull { it.name=="arm" && it.parameterTypes.contentEquals(arrayOf(CarPlayHostMediaMode::class.java)) }
        assertNotNull("one exclusive arm must accept INACTIVE",method)
        method!!.invoke(arm,CarPlayHostMediaMode.INACTIVE)
        val snapshot=arm.javaClass.getMethod("snapshotMode")
        val frozen=CarPlayHostMediaLease(snapshot.invoke(arm) as CarPlayHostMediaMode)
        arm.arm()
        assertEquals(CarPlayHostMediaMode.SUPPRESS_PLAYING,snapshot.invoke(arm))
        now+=PlayingPublicationArm.DURATION_MILLIS
        assertEquals(CarPlayHostMediaMode.NORMAL,snapshot.invoke(arm))
        assertEquals(CarPlayHostMediaMode.INACTIVE,frozen.mode)
        method.invoke(arm,CarPlayHostMediaMode.INACTIVE);arm.disarm()
        assertEquals(CarPlayHostMediaMode.NORMAL,snapshot.invoke(arm))
        assertEquals(CarPlayHostMediaMode.NORMAL,snapshot.invoke(PlayingPublicationArm { now }))
    }
}

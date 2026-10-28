package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application=android.app.Application::class, sdk=[32], manifest=org.robolectric.annotation.Config.NONE)
class CarPlayHostMediaArmTest {
    @Test fun applicationFixtureDoesNotBootProcessServices() {
        // AGP's Build System API can supply the manifest despite Config.NONE.
        // This arm-only fixture must not start process-owned Android workers.
        assertEquals(
            "This fixture must not boot OalApplication process services",
            android.app.Application::class.java,
            org.robolectric.RuntimeEnvironment.getApplication().javaClass,
        )
    }

    @Test fun exclusiveInactiveAndSuppressModesStayEnabledWithoutMutatingFrozenLease() {
        val arm=PlayingPublicationArm()
        val method=arm.javaClass.methods.firstOrNull { it.name=="arm" && it.parameterTypes.contentEquals(arrayOf(CarPlayHostMediaMode::class.java)) }
        assertNotNull("one exclusive arm must accept INACTIVE",method)
        method!!.invoke(arm,CarPlayHostMediaMode.INACTIVE)
        val snapshot=arm.javaClass.getMethod("snapshotMode")
        val frozen=CarPlayHostMediaLease(snapshot.invoke(arm) as CarPlayHostMediaMode)
        arm.arm()
        assertEquals(CarPlayHostMediaMode.SUPPRESS_PLAYING,snapshot.invoke(arm))
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofDays(1))
        assertEquals(CarPlayHostMediaMode.SUPPRESS_PLAYING,snapshot.invoke(arm))
        assertEquals(CarPlayHostMediaMode.INACTIVE,frozen.mode)
        method.invoke(arm,CarPlayHostMediaMode.INACTIVE);arm.disarm()
        assertEquals(CarPlayHostMediaMode.NORMAL,snapshot.invoke(arm))
        assertEquals(CarPlayHostMediaMode.NORMAL,snapshot.invoke(PlayingPublicationArm()))
    }
}

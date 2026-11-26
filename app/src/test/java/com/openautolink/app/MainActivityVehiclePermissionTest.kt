package com.openautolink.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [32, 34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MainActivityVehiclePermissionTest {
    @Test fun `AAOS startup requests car energy if missing`() {
        assertEquals(setOf(Manifest.permission.RECORD_AUDIO, CAR_ENERGY), request(true, false))
    }

    @Test fun `AAOS startup does not request granted car energy again`() {
        assertEquals(setOf(Manifest.permission.RECORD_AUDIO), request(true, true))
    }

    @Test fun `AAOS startup requests car speed if missing`() {
        assertEquals(setOf(Manifest.permission.RECORD_AUDIO, CAR_SPEED), request(true, true, false))
    }

    @Test fun `non automotive startup does not request car energy`() {
        assertEquals(setOf(Manifest.permission.RECORD_AUDIO), request(false, false))
    }

    private fun request(automotive: Boolean, energyGranted: Boolean, speedGranted: Boolean = true): Set<String> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, automotive)
        shadowOf(activity.application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        if (energyGranted) shadowOf(activity.application).grantPermissions(CAR_ENERGY)
        if (speedGranted) shadowOf(activity.application).grantPermissions(CAR_SPEED)
        // Run the real startup request without starting projection or process services.
        MainActivity::class.java.getDeclaredMethod("requestMissingPermissions").apply {
            isAccessible = true
        }.invoke(activity)
        return shadowOf(activity).lastRequestedPermission.requestedPermissions.toSet()
    }

    private companion object {
        const val CAR_ENERGY = "android.car.permission.CAR_ENERGY"
        const val CAR_SPEED = "android.car.permission.CAR_SPEED"
    }
}

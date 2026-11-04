package com.openautolink.app.diagnostics.gm

import android.app.Application
import android.content.*
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GmOwnRelayAdmissionTest {
    @Test fun ownRelayRequestsItsExistingSignaturePermissionWithoutAnyOemGrant() {
        val app=RuntimeEnvironment.getApplication()
        val requested=app.packageManager.getPackageInfo(app.packageName,PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue("The own protected relay needs its own signature permission, not an OEM grant",requested.contains(app.packageName+".permission.OBSERVE_CLUSTER_PAGE"))
    }
}

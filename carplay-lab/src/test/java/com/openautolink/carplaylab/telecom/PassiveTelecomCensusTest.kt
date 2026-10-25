package com.openautolink.carplaylab.telecom

import android.content.ComponentName
import android.content.Context
import android.telecom.PhoneAccountHandle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], manifest = Config.NONE)
class PassiveTelecomCensusTest {
    @org.junit.Before fun grantOwnRead() {
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.READ_PHONE_NUMBERS)
    }
    @Test fun censusReportsPublicFeaturesAndResolvesSettingsWithoutLaunching() {
        val context: Context = RuntimeEnvironment.getApplication()
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        pm.setSystemFeature(android.content.pm.PackageManager.FEATURE_CONNECTION_SERVICE, true)
        pm.setSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE, true)
        val intent = android.content.Intent(android.telecom.TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
        val activity = pm.addActivityIfNotPresent(ComponentName("offline.settings", "Accounts")).apply {
            exported = true; enabled = true; applicationInfo.enabled = true
            applicationInfo.flags = android.content.pm.ApplicationInfo.FLAG_SYSTEM
        }
        val info = android.content.pm.ResolveInfo().apply { activityInfo = activity }
        pm.addResolveInfoForIntent(intent, info)
        val handle = PhoneAccountHandle(ComponentName(context.packageName, "FutureOptInService"), "own-research")
        val result = PassiveTelecomCensus.read(context, handle)
        assertEquals(true, result.connectionServiceFeature)
        assertEquals(true, result.automotiveFeature)
        assertEquals(true, result.telecomPresent)
        assertEquals(true, result.settingsResolvable)
        assertNull(org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test fun censusDoesNotRegisterAnAbsentOwnAccount() {
        val context: Context = RuntimeEnvironment.getApplication()
        val handle = PhoneAccountHandle(ComponentName(context.packageName, "FutureOptInService"), "own-research")
        val result = PassiveTelecomCensus.read(context, handle)
        assertEquals(android.os.Build.VERSION.SDK_INT, result.sdk)
        assertEquals(PassiveTelecomCensus.Account.ABSENT, result.ownAccount)
        assertNull(context.getSystemService(android.telecom.TelecomManager::class.java).getPhoneAccount(handle))
    }
    @Test fun rejectsOtherPackagesBeforeReadingTheirAccount() {
        val context: Context = RuntimeEnvironment.getApplication()
        val other = PhoneAccountHandle(ComponentName("not.our.package", "Service"), "foreign")
        assertThrows(IllegalArgumentException::class.java) { PassiveTelecomCensus.read(context, other) }
    }
}

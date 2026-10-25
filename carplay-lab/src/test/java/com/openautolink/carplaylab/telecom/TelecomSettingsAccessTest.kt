package com.openautolink.carplaylab.telecom

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], manifest = Config.NONE)
class TelecomSettingsAccessTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val exact = ComponentName("com.android.server.telecom", "com.android.server.telecom.settings.EnableAccountPreferenceActivity")
    private fun install(): ActivityInfo = shadowOf(app.packageManager).addActivityIfNotPresent(exact).apply {
        exported = true; enabled = true
        applicationInfo.enabled = true
    }
    @org.robolectric.annotation.Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
    class ResolverShadow : org.robolectric.shadows.ShadowApplicationPackageManager() {
        @org.robolectric.annotation.Implementation override fun resolveActivity(intent: Intent, flags: Int): android.content.pm.ResolveInfo? {
            if (intent.action == android.telecom.TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS) return resolver
            return super.resolveActivity(intent, flags)
        }
        @org.robolectric.annotation.Implementation override fun queryIntentActivities(intent: Intent, flags: Int): MutableList<android.content.pm.ResolveInfo> {
            if (intent.action == android.telecom.TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS) return mutableListOf()
            return super.queryIntentActivities(intent, flags)
        }
        companion object { var resolver: android.content.pm.ResolveInfo? = null }
    }
    @Test @Config(shadows = [ResolverShadow::class])
    fun synthesizedResolverIsNotAHandlerAdvertisingPhoneAccountsAction() {
        val info = shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName("android", "android.ResolverActivity")).apply {
            exported = true; enabled = true; applicationInfo.enabled = true; applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM
        }
        ResolverShadow.resolver = android.content.pm.ResolveInfo().apply { activityInfo = info }
        assertEquals(TelecomAccountSettings.Availability.UNAVAILABLE, TelecomAccountSettings.inspect(app).availability)
        assertEquals(TelecomAccountSettings.Launch.UNAVAILABLE, TelecomAccountSettings.launch(app))
        assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun exactCandidateIsLaunchableEvenWhenIntegrationIsOff() {
        install()
        val experiment = ManagedTelecomExperiment(app)
        assertTrue(experiment.openEnableSettings())
        assertEquals(exact, shadowOf(app).nextStartedActivity.component)
        assertFalse(experiment.enabledForProcess)
    }
    @Test fun publicActionFallbackRequiresAccessibleSystemHandlerAndUsesExactResolvedTarget() {
        val component = ComponentName("system.accounts", "system.accounts.PhoneAccounts")
        val info = shadowOf(app.packageManager).addActivityIfNotPresent(component).apply {
            exported = true; enabled = true; applicationInfo.enabled = true
            applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(android.telecom.TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS),
            android.content.pm.ResolveInfo().apply { activityInfo = info })
        assertEquals(TelecomAccountSettings.Availability.PUBLIC_ACTION_CANDIDATE, TelecomAccountSettings.inspect(app).availability)
        assertEquals(TelecomAccountSettings.Launch.PUBLIC_ACTION_CANDIDATE_LAUNCHED, TelecomAccountSettings.launch(app))
        assertEquals(component, shadowOf(app).nextStartedActivity.component)
        info.applicationInfo.flags = 0
        assertEquals(TelecomAccountSettings.Availability.UNAVAILABLE, TelecomAccountSettings.inspect(app).availability)
        info.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM; info.permission = "permission.not.granted"
        assertEquals(TelecomAccountSettings.Availability.UNAVAILABLE, TelecomAccountSettings.inspect(app).availability)
        info.permission = null; info.exported = false
        assertEquals(TelecomAccountSettings.Availability.UNAVAILABLE, TelecomAccountSettings.inspect(app).availability)
        info.exported = true; info.enabled = false
        assertEquals(TelecomAccountSettings.Availability.UNAVAILABLE, TelecomAccountSettings.inspect(app).availability)
    }
    @Test fun launchSecurityAndActivityNotFoundAreNotReportedAsUsableSettings() {
        install()
        for ((failure, expected) in listOf(SecurityException("private") to TelecomAccountSettings.Launch.SECURITY_EXCEPTION,
            android.content.ActivityNotFoundException("private") to TelecomAccountSettings.Launch.ACTIVITY_NOT_FOUND)) {
            val context = object : android.content.ContextWrapper(app) {
                override fun startActivity(intent: Intent) { throw failure }
            }
            assertEquals(expected, TelecomAccountSettings.launch(context))
        }
    }
    @Test fun userDisabledComponentAndApplicationAreUnavailable() {
        install()
        app.packageManager.setComponentEnabledSetting(exact, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0)
        assertEquals(TelecomAccountSettings.Availability.UNAVAILABLE, TelecomAccountSettings.inspect(app).availability)
        app.packageManager.setComponentEnabledSetting(exact, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0)
        app.packageManager.setApplicationEnabledSetting(exact.packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
        assertEquals(TelecomAccountSettings.Availability.UNAVAILABLE, TelecomAccountSettings.inspect(app).availability)
    }
    @Test fun inaccessibleExactNeverLaunches() {
        val info = install()
        val experiment = ManagedTelecomExperiment(app)
        info.exported = false
        assertFalse(experiment.openEnableSettings())
        info.exported = true; info.permission = "ungranted.permission"
        assertFalse(experiment.openEnableSettings())
        info.permission = null; info.enabled = false
        assertFalse(experiment.openEnableSettings())
        info.enabled = true; info.applicationInfo.enabled = false
        assertFalse(experiment.openEnableSettings())
        assertNull(shadowOf(app).nextStartedActivity)
    }
}

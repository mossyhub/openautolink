package com.openautolink.carplaylab.telecom

import android.content.*
import android.content.pm.*
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], manifest = Config.NONE)
class CarPlaySoundSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun exact() = shadowOf(app.packageManager).addActivityIfNotPresent(CarPlaySoundSettings.exactComponent).apply {
        exported = true; enabled = true; applicationInfo.enabled = true
    }
    @Test fun exactExportedSoundPageWorksWithoutAccountOrPermissionAndLaunchFailureIsFinite() {
        val info = exact()
        assertEquals("com.android.car.settings.common.CarSettingActivities\$SoundSettingsActivity", info.name)
        assertEquals(TelecomAccountSettings.Launch.EXACT_CANDIDATE_LAUNCHED, CarPlaySoundSettings.launch(app))
        assertEquals(CarPlaySoundSettings.exactComponent, shadowOf(app).nextStartedActivity.component)
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { throw SecurityException("private") }
        }
        assertEquals(TelecomAccountSettings.Launch.SECURITY_EXCEPTION, CarPlaySoundSettings.launch(context))
    }
    @Test fun disabledUnexportedAndPermissionProtectedExactTargetsAreNeverLaunched() {
        val info = exact()
        info.exported = false
        assertEquals(TelecomAccountSettings.Launch.UNAVAILABLE, CarPlaySoundSettings.launch(app))
        info.exported = true; info.permission = "private.permission"
        assertEquals(TelecomAccountSettings.Launch.UNAVAILABLE, CarPlaySoundSettings.launch(app))
        info.permission = null; info.applicationInfo.permission = "private.app.permission"
        assertEquals(TelecomAccountSettings.Launch.UNAVAILABLE, CarPlaySoundSettings.launch(app))
        info.applicationInfo.permission = null
        app.packageManager.setComponentEnabledSetting(CarPlaySoundSettings.exactComponent, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
        assertEquals(TelecomAccountSettings.Launch.UNAVAILABLE, CarPlaySoundSettings.launch(app))
        app.packageManager.setComponentEnabledSetting(CarPlaySoundSettings.exactComponent, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0)
        app.packageManager.setApplicationEnabledSetting(info.packageName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
        assertEquals(TelecomAccountSettings.Launch.UNAVAILABLE, CarPlaySoundSettings.launch(app))
        assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun nonSystemSoundActionCannotBeUsedAsFallback() {
        val target = ComponentName("third.party", "third.party.Sound")
        val info = shadowOf(app.packageManager).addActivityIfNotPresent(target).apply {
            exported = true; enabled = true; applicationInfo.enabled = true; applicationInfo.flags = 0
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Settings.ACTION_SOUND_SETTINGS), ResolveInfo().apply { activityInfo = info })
        assertEquals(TelecomAccountSettings.Launch.UNAVAILABLE, CarPlaySoundSettings.launch(app))
        assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun stableSoundActionFallbackMustBeAdvertisedAccessibleAndSystemOnly() {
        val target = ComponentName("system.sound", "system.sound.Sound")
        val info = shadowOf(app.packageManager).addActivityIfNotPresent(target).apply {
            exported = true; enabled = true; applicationInfo.enabled = true; applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Settings.ACTION_SOUND_SETTINGS), ResolveInfo().apply { activityInfo = info })
        val resolved = app.packageManager.resolveActivity(Intent(Settings.ACTION_SOUND_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY)!!.activityInfo
        assertTrue("fixture did not publish system origin", resolved.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        assertEquals(TelecomAccountSettings.Launch.PUBLIC_ACTION_CANDIDATE_LAUNCHED, CarPlaySoundSettings.launch(app))
        assertEquals(target, shadowOf(app).nextStartedActivity.component)
        assertNull(shadowOf(app).nextStartedActivity)
    }
}

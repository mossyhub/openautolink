package com.openautolink.app.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AndroidBluetoothSettingsLauncherTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val component = ComponentName("com.android.car.settings",
        "com.android.car.settings.common.CarSettingActivities\$BluetoothSettingsActivity")
    private fun installed(): ActivityInfo = shadowOf(app.packageManager).addActivityIfNotPresent(component).apply {
        exported = true; enabled = true; applicationInfo.enabled = true
    }
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val starts = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) { starts += intent }
    }

    @Test fun disabledOrPermissionProtectedActivityIsNotLaunched() {
        val info = installed()
        val context = RecordingContext(app)
        info.enabled = false
        assertEquals(AndroidBluetoothSettingsLauncher.Result.UNAVAILABLE, AndroidBluetoothSettingsLauncher.launch(context))
        info.enabled = true; info.applicationInfo.enabled = false
        assertEquals(AndroidBluetoothSettingsLauncher.Result.UNAVAILABLE, AndroidBluetoothSettingsLauncher.launch(context))
        info.applicationInfo.enabled = true; info.permission = "vendor.permission.PRIVATE"
        assertEquals(AndroidBluetoothSettingsLauncher.Result.BLOCKED, AndroidBluetoothSettingsLauncher.launch(context))
        info.permission = null; info.applicationInfo.permission = "vendor.permission.APP_PRIVATE"
        assertEquals(AndroidBluetoothSettingsLauncher.Result.BLOCKED, AndroidBluetoothSettingsLauncher.launch(context))
        assertTrue(context.starts.isEmpty())
    }

    @Test fun expectedLaunchFailuresReturnFiniteResultsWithoutFallbackOrExceptionText() {
        installed()
        for ((failure, expected) in listOf(
            android.content.ActivityNotFoundException("secret vendor details") to AndroidBluetoothSettingsLauncher.Result.UNAVAILABLE,
            SecurityException("secret device address") to AndroidBluetoothSettingsLauncher.Result.BLOCKED,
        )) {
            var attempts = 0
            val context = object : ContextWrapper(app) {
                override fun startActivity(intent: Intent) { attempts++; throw failure }
            }
            val result = AndroidBluetoothSettingsLauncher.launch(context)
            assertEquals(expected, result)
            assertFalse(result.message.contains("secret"))
            assertEquals(1, attempts)
        }
    }

    @Test fun missingTargetHasNoImplicitFallback() {
        val context = RecordingContext(app)
        assertEquals(AndroidBluetoothSettingsLauncher.Result.UNAVAILABLE, AndroidBluetoothSettingsLauncher.launch(context))
        assertTrue(context.starts.isEmpty())
    }

    @Test fun runtimeDisableOverridesPreventLaunch() {
        installed()
        val context = RecordingContext(app)
        val pm = app.packageManager
        for (state in listOf(android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)) {
            pm.setApplicationEnabledSetting(component.packageName, state, 0)
            assertEquals(AndroidBluetoothSettingsLauncher.Result.UNAVAILABLE, AndroidBluetoothSettingsLauncher.launch(context))
        }
        pm.setApplicationEnabledSetting(component.packageName, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0)
        pm.setComponentEnabledSetting(component, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0)
        assertEquals(AndroidBluetoothSettingsLauncher.Result.UNAVAILABLE, AndroidBluetoothSettingsLauncher.launch(context))
        assertTrue(context.starts.isEmpty())
    }

    @Test fun packageInspectionSecurityDenialIsBlockedWithoutLaunching() {
        val pm = io.mockk.mockk<android.content.pm.PackageManager>()
        io.mockk.every { pm.getActivityInfo(any<ComponentName>(), any<Int>()) } throws SecurityException("private vendor inspection detail")
        var attempts = 0
        val context = object : ContextWrapper(app) {
            override fun getPackageManager() = pm
            override fun startActivity(intent: Intent) { attempts++ }
        }
        val result = AndroidBluetoothSettingsLauncher.launch(context)
        assertEquals(AndroidBluetoothSettingsLauncher.Result.BLOCKED, result)
        assertFalse(result.message.contains("private"))
        assertEquals(0, attempts)
    }

    @Test fun unexportedTargetIsBlockedWithoutStartingOrImplicitFallback() {
        installed().exported = false
        val context = RecordingContext(app)
        assertEquals(AndroidBluetoothSettingsLauncher.Result.BLOCKED, AndroidBluetoothSettingsLauncher.launch(context))
        assertTrue(context.starts.isEmpty())
    }
}

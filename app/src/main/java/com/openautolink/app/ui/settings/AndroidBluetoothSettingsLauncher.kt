package com.openautolink.app.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Public exported UI only: no profile writes, extras or fallback destination. */
internal object AndroidBluetoothSettingsLauncher {
    private val component = ComponentName("com.android.car.settings",
        "com.android.car.settings.common.CarSettingActivities\$BluetoothSettingsActivity")

    enum class Result(val message: String) {
        OPENED("Android Bluetooth settings opened. Controls depend on this vehicle; no profile changes were made by OAL."),
        UNAVAILABLE("Android Bluetooth settings is unavailable on this vehicle. The existing Bluetooth settings button is unchanged."),
        BLOCKED("Android Bluetooth settings is blocked by this vehicle's permissions or policy."),
        PARK_REQUIRED("Park could not be confirmed. Safely park, wait for live vehicle data, and tap again. The existing Bluetooth settings button is unchanged."),
        FOREGROUND_REQUIRED("Return to this Settings screen before opening Android Bluetooth settings."),
    }

    fun launch(context: Context): Result {
        val pm = context.packageManager
        val info = try {
            pm.getActivityInfo(component, android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            // Visibility and installed-state uncertainty are not permission to guess another UI.
            return Result.UNAVAILABLE
        } catch (_: SecurityException) {
            return Result.BLOCKED
        }
        if (!info.enabled || !info.applicationInfo.enabled) return Result.UNAVAILABLE
        if (!info.exported || !info.permission.isNullOrEmpty() ||
            !info.applicationInfo.permission.isNullOrEmpty()) return Result.BLOCKED
        val disabledStates = setOf(
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
        )
        return try {
            if (pm.getApplicationEnabledSetting(component.packageName) in disabledStates ||
                pm.getComponentEnabledSetting(component) in disabledStates) return Result.UNAVAILABLE
            context.startActivity(Intent().setComponent(component))
            Result.OPENED
        } catch (_: android.content.ActivityNotFoundException) {
            Result.UNAVAILABLE
        } catch (_: SecurityException) {
            Result.BLOCKED
        }
    }
}

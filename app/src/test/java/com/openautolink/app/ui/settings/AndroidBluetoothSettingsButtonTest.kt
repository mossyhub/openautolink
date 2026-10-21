package com.openautolink.app.ui.settings

import android.content.ComponentName
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openautolink.app.ui.testing.createOalComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AndroidBluetoothSettingsButtonTest {
    @get:Rule val compose = createOalComposeRule()
    private var parked = true

    @org.junit.Before fun parkFixture() {
        io.mockk.mockkObject(com.openautolink.app.ui.diagnostics.MicrophoneDiagnosticPark)
        io.mockk.every { com.openautolink.app.ui.diagnostics.MicrophoneDiagnosticPark.isFreshPark(any(), any(), any()) } answers { parked }
    }
    @org.junit.After fun clearParkFixture() {
        io.mockk.unmockkObject(com.openautolink.app.ui.diagnostics.MicrophoneDiagnosticPark)
    }

    @Test fun unknownOrNonParkShowsRetryGuidanceAndRechecksParkOnNextUserClick() {
        val app = RuntimeEnvironment.getApplication()
        val target = ComponentName("com.android.car.settings", "com.android.car.settings.common.CarSettingActivities\$BluetoothSettingsActivity")
        shadowOf(app.packageManager).addActivityIfNotPresent(target).apply { exported = true; enabled = true }
        parked = false
        compose.setContent { MaterialTheme { AndroidBluetoothSettingsButton() } }
        compose.onNodeWithText("Android Bluetooth settings").performClick()
        compose.onNodeWithText(AndroidBluetoothSettingsLauncher.Result.PARK_REQUIRED.message).assertExists()
        assertNull(shadowOf(app).nextStartedActivity)
        parked = true
        compose.onNodeWithText("Android Bluetooth settings").performClick()
        assertEquals(target, shadowOf(app).nextStartedActivity?.component)
        compose.onNodeWithText(AndroidBluetoothSettingsLauncher.Result.OPENED.message).assertExists()
    }

    @Test fun backgroundPanelDoesNotLaunchEvenWithParkEvidence() {
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        owner.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                MaterialTheme { AndroidBluetoothSettingsButton() }
            }
        }
        compose.onNodeWithText("Android Bluetooth settings").performClick()
        compose.onNodeWithText(AndroidBluetoothSettingsLauncher.Result.FOREGROUND_REQUIRED.message).assertExists()
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test fun unavailableTargetHasVisibleFiniteOutcomeAndNoLaunch() {
        compose.setContent { MaterialTheme { AndroidBluetoothSettingsButton() } }
        compose.onNodeWithText("Android Bluetooth settings").performClick()
        compose.onNodeWithText(AndroidBluetoothSettingsLauncher.Result.UNAVAILABLE.message).assertExists()
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test fun blockedTargetHasVisibleFiniteOutcomeAndNoLaunch() {
        val app = RuntimeEnvironment.getApplication()
        val target = ComponentName("com.android.car.settings", "com.android.car.settings.common.CarSettingActivities\$BluetoothSettingsActivity")
        shadowOf(app.packageManager).addActivityIfNotPresent(target).apply { exported = false; enabled = true }
        compose.setContent { MaterialTheme { AndroidBluetoothSettingsButton() } }
        compose.onNodeWithText("Android Bluetooth settings").performClick()
        compose.onNodeWithText(AndroidBluetoothSettingsLauncher.Result.BLOCKED.message).assertExists()
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun composingDoesNotLaunchAndExplicitUserClickOpensOnlyAndroidSettingsWithoutExtras() {
        val app = RuntimeEnvironment.getApplication()
        val target = ComponentName("com.android.car.settings", "com.android.car.settings.common.CarSettingActivities\$BluetoothSettingsActivity")
        shadowOf(app.packageManager).addActivityIfNotPresent(target).apply { exported = true; enabled = true }
        compose.setContent { MaterialTheme { AndroidBluetoothSettingsButton() } }
        compose.waitForIdle()
        assertNull("Composition must not start Settings", shadowOf(app).nextStartedActivity)
        compose.onNodeWithText("Android Bluetooth settings").performClick()
        compose.waitForIdle()
        val intent = shadowOf(app).nextStartedActivity
        assertNotNull("Only a user click may start the alternate Settings", intent)
        assertEquals(target, intent.component)
        assertNull(intent.extras)
        assertNull(intent.action)
        assertNull(intent.data)
        assertNull("No implicit fallback or second launch", shadowOf(app).nextStartedActivity)
    }
}

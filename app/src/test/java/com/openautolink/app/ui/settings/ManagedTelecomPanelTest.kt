package com.openautolink.app.ui.settings

import android.telecom.TelecomManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import com.openautolink.carplaylab.telecom.ManagedTelecomExperiment
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = android.app.Application::class)
class ManagedTelecomPanelTest {
    @get:Rule val compose = createOalComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val experiment get() = ManagedTelecomExperiment.get(app)
    @Before fun reset() { com.openautolink.app.ui.testing.resetNativeTelecomTestProcess(app); shadowOf(app).grantPermissions(android.Manifest.permission.READ_PHONE_NUMBERS) }
    @After fun close() { com.openautolink.app.ui.testing.resetNativeTelecomTestProcess(app) }
    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10000) {
            compose.onNodeWithTag("telecomStatus").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].none { it.text == "WORKING" }
        }
    }
    @org.robolectric.annotation.Implements(TelecomManager::class)
    class RefusingRegistration : org.robolectric.shadows.ShadowTelecomManager() {
        @org.robolectric.annotation.Implementation override fun registerPhoneAccount(account: android.telecom.PhoneAccount) {
            throw SecurityException("private synthetic registration denial")
        }
    }
    @Test @Config(shadows = [RefusingRegistration::class])
    fun registrationFailureDoesNotHideAccountSettingsOrInspect() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { ManagedTelecomPanel() } } }
        click("telecomEnabled")
        compose.onNodeWithTag("telecomEnabled").assertIsOff()
        compose.onNodeWithTag("telecomStatus").assertTextContains("DENIED_OR_UNAVAILABLE", substring = true)
        compose.onNodeWithTag("telecomEnableSettings").assertIsEnabled()
        compose.onNodeWithTag("telecomInspect").assertIsEnabled()
    }
    @org.robolectric.annotation.Implements(TelecomManager::class)
    class HeldRefusingRegistration : org.robolectric.shadows.ShadowTelecomManager() {
        companion object {
            var entered = java.util.concurrent.CountDownLatch(1)
            var release = java.util.concurrent.CountDownLatch(1)
        }
        @org.robolectric.annotation.Implementation override fun registerPhoneAccount(account: android.telecom.PhoneAccount) {
            entered.countDown()
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "registration was not released" }
            throw SecurityException("private synthetic held registration denial")
        }
    }
    @Test @Config(shadows = [HeldRefusingRegistration::class])
    fun settingsDuringFailedEnableRetiresPendingGenerationWithoutReplacingLatestStatus() {
        HeldRefusingRegistration.entered = java.util.concurrent.CountDownLatch(1)
        HeldRefusingRegistration.release = java.util.concurrent.CountDownLatch(1)
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { ManagedTelecomPanel() } } }
        compose.waitForIdle()
        while (shadowOf(app).nextStartedActivity != null) { }
        compose.onNodeWithTag("telecomEnabled").assertIsOff().performScrollTo().performClick()
        try {
            assertTrue("registration did not enter", HeldRefusingRegistration.entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            compose.onNodeWithTag("telecomEnabled").assertIsOn()
            compose.onNodeWithTag("telecomEnableSettings").assertIsEnabled().performScrollTo().performClick()
        } finally {
            HeldRefusingRegistration.release.countDown()
        }
        compose.waitUntil(10000) {
            compose.onNodeWithTag("telecomStatus").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].any { it.text.startsWith("UNAVAILABLE") }
        }
        assertFalse("failed registration must leave the backend Off", experiment.enabledForProcess)
        assertNull("settings must stay on this page", shadowOf(app).nextStartedActivity)
        compose.onNodeWithTag("telecomStatus").assertTextContains("UNAVAILABLE · Account settings unavailable", substring = true)
        compose.onNodeWithTag("telecomEnabled").assertIsOff()
        compose.onNodeWithTag("telecomEnableSettings").assertIsEnabled()
        click("telecomReadback")
        compose.onNodeWithTag("telecomEnabled").assertIsOff()
    }
    @Test fun exactSettingsLaunchRemainsAvailableWithMissingPermissionAndNoRegistration() {
        shadowOf(app).denyPermissions(android.Manifest.permission.READ_PHONE_NUMBERS)
        val exact = android.content.ComponentName("com.android.server.telecom", "com.android.server.telecom.settings.EnableAccountPreferenceActivity")
        shadowOf(app.packageManager).addActivityIfNotPresent(exact).apply {
            exported = true; enabled = true; applicationInfo.enabled = true
        }
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { ManagedTelecomPanel() } } }
        compose.waitForIdle()
        while (shadowOf(app).nextStartedActivity != null) { }
        val controllerContext = experiment.javaClass.getDeclaredField("context").apply { isAccessible = true }.get(experiment)
        assertSame("Robolectric process singleton retained a different Application", app, controllerContext)
        click("telecomEnableSettings")
        assertEquals(exact, shadowOf(app).nextStartedActivity.component)
        assertFalse(experiment.enabledForProcess)
        compose.onNodeWithTag("telecomStatus").assertTextContains("EXACT_CANDIDATE_LAUNCHED", substring = true)
        assertNull(app.getSystemService(TelecomManager::class.java).getPhoneAccount(experiment.handle))
    }
    @Test fun settingsAndInspectRemainReachableWhileOff() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { ManagedTelecomPanel() } } }
        compose.onNodeWithTag("telecomEnableSettings").assertIsEnabled()
        compose.onNodeWithTag("telecomInspect").assertExists()
    }
    @Test fun simpleTogglesRegisterOnlyOnEnableAndKeepSystemAccountEnableSeparate() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { ManagedTelecomPanel() } } }
        compose.waitForIdle()
        val manager = app.getSystemService(TelecomManager::class.java)
        assertNull(manager.getPhoneAccount(experiment.handle))
        compose.onNodeWithTag("telecomDisclosureConsent").assertDoesNotExist()
        compose.onNodeWithTag("telecomControlsConsent").assertDoesNotExist()
        compose.onNodeWithTag("telecomEnabled").assertIsOff()
        compose.onNodeWithTag("telecomControlsEnabled").assertIsNotEnabled()
        click("telecomEnabled")
        assertNotNull(manager.getPhoneAccount(experiment.handle))
        assertFalse(experiment.armed)
        compose.onNodeWithTag("telecomEnabled").assertIsOn()
        shadowOf(manager).enablePhoneAccount(experiment.handle, true) // simulated OS user action
        click("telecomReadback")
        assertTrue(experiment.armed)
        assertFalse(experiment.nativeControlsEnabled)
        click("telecomControlsEnabled")
        assertTrue(experiment.nativeControlsEnabled)
        compose.onNodeWithTag("telecomControlsEnabled").assertIsOn()
        click("telecomControlsEnabled")
        assertFalse(experiment.nativeControlsEnabled)
        click("telecomEnabled")
        assertFalse(experiment.armed)
        assertNull(manager.getPhoneAccount(experiment.handle))
        compose.onNodeWithTag("telecomEnabled").assertIsOff()
    }
    @Test fun unavailableSettingsNeverLaunchFromCompositionOrInventFallback() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { ManagedTelecomPanel() } } }
        compose.waitForIdle()
        // Compose itself launches its test Activity; drain that known host before this assertion.
        while (shadowOf(app).nextStartedActivity != null) { }
        click("telecomEnableSettings")
        assertNull(shadowOf(app).nextStartedActivity)
        assertNull(app.getSystemService(TelecomManager::class.java).getPhoneAccount(experiment.handle))
    }
}

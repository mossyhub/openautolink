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
    @Test fun ringtoneExperimentIsSimpleDefaultOffProcessChoiceAndSoundSettingsDoesNotEnableAccount() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { ManagedTelecomPanel() } } }
        compose.waitForIdle()
        while (shadowOf(app).nextStartedActivity != null) { }
        compose.onNodeWithTag("telecomRingtoneOnly").assertIsOff().performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(experiment.carPlayRingtoneOnly)
        assertFalse(experiment.enabledForProcess)
        compose.onNodeWithText("Suppresses extra native ringtone; firmware-dependent. Leave off if alerts disappear.").assertExists()
        val exact = android.content.ComponentName("com.android.car.settings", "com.android.car.settings.common.CarSettingActivities\$SoundSettingsActivity")
        shadowOf(app.packageManager).addActivityIfNotPresent(exact).apply {
            exported = true; enabled = true; applicationInfo.enabled = true
        }
        click("telecomSoundSettings")
        assertEquals(exact, shadowOf(app).nextStartedActivity.component)
        assertFalse(experiment.enabledForProcess)
        compose.onNodeWithTag("telecomRingtoneOnly").performScrollTo().performClick()
        compose.waitForIdle(); assertFalse(experiment.carPlayRingtoneOnly)
    }
    @Test fun nativeReceiptExportsAfterDiagnosticsRouteIsGoneWithoutOptionalFileLogging() {
        val visible = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { MaterialTheme { if (visible.value) Column(Modifier.verticalScroll(rememberScrollState())) { ManagedTelecomPanel() } } }
        compose.waitForIdle()
        visible.value = false; compose.waitForIdle()
        compose.onNodeWithTag("telecomEnabled").assertDoesNotExist()
        experiment.enable()
        val manager = app.getSystemService(TelecomManager::class.java)
        shadowOf(manager).enablePhoneAccount(experiment.handle, true); experiment.refresh()
        val bridge = experiment.authenticatedLink { true }!!
        fun field(id: Int, value: ByteArray) = byteArrayOf(0, (value.size + 4).toByte(), 0, id.toByte()) + value
        bridge.handle(com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4155,
            field(4, "00000000-0000-4000-8000-000000000088".toByteArray() + byteArrayOf(0)) +
            field(2, byteArrayOf(2)) + field(3, byteArrayOf(1)) + field(9, byteArrayOf(0))))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        bridge.close(); shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(com.openautolink.carplaylab.telecom.NativeCallDiagnostics.flush())
        val file = java.io.File(app.getExternalFilesDir(null), "openautolink/logs/native_call_controls.log")
        val bytes = file.readBytes(); assertTrue(bytes.toString(Charsets.UTF_8).contains("reason=OBSERVED"))
        file.setLastModified(1)
        com.openautolink.app.diagnostics.LogUploadArchive.create(
            com.openautolink.app.diagnostics.LogUploadArchive.select(listOf(file), System.currentTimeMillis()), app.cacheDir).use { archive ->
            java.util.zip.ZipFile(archive.file).use { zip ->
                val entry = zip.getEntry(file.name); assertNotNull(entry)
                assertArrayEquals(bytes, zip.getInputStream(entry).readBytes())
            }
        }
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

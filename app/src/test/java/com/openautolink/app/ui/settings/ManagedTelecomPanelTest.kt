package com.openautolink.app.ui.settings

import android.telecom.TelecomManager
import androidx.activity.compose.setContent
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
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { LegacyManagedTelecomPanel() } } }
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
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { LegacyManagedTelecomPanel() } } }
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
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { LegacyManagedTelecomPanel() } } }
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
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { LegacyManagedTelecomPanel() } } }
        compose.onNodeWithTag("telecomEnableSettings").assertIsEnabled()
        compose.onNodeWithTag("telecomInspect").assertExists()
    }
    @Test fun ringtoneExperimentIsSimpleDefaultOffProcessChoiceAndSoundSettingsDoesNotEnableAccount() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { LegacyManagedTelecomPanel() } } }
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
        compose.setContent { MaterialTheme { if (visible.value) Column(Modifier.verticalScroll(rememberScrollState())) { LegacyManagedTelecomPanel() } } }
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
    @org.robolectric.annotation.Implements(TelecomManager::class)
    class HeldSuccessfulRegistration : org.robolectric.shadows.ShadowTelecomManager() {
        companion object {
            var entered = java.util.concurrent.CountDownLatch(1)
            var release = java.util.concurrent.CountDownLatch(1)
        }
        @org.robolectric.annotation.Implementation
        override fun registerPhoneAccount(account: android.telecom.PhoneAccount) {
            entered.countDown()
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "registration was not released" }
            super.registerPhoneAccount(account)
        }
    }

    /**
     * Suspend the actual Enable callback at the OS boundary before letting IO return.
     * Hold Compose frames, not the IO worker, while observing the exact panel status write.
     * This distinguishes continuation affinity from a later layout/observation timeout.
     * The old simple-toggle test and all its account-authority assertions remain unchanged.
     */
    @Test @Config(shadows = [HeldSuccessfulRegistration::class])
    fun heldEnablePublishesItsUiCompletionOnTheAndroidViewThread() {
        HeldSuccessfulRegistration.entered = java.util.concurrent.CountDownLatch(1)
        HeldSuccessfulRegistration.release = java.util.concurrent.CountDownLatch(1)
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { LegacyManagedTelecomPanel() } } }
        compose.waitForIdle()
        compose.onNodeWithTag("telecomEnabled").assertIsOff().performScrollTo().performClick()
        var observer: androidx.compose.runtime.snapshots.ObserverHandle? = null
        try {
            assertTrue("actual registration did not enter", HeldSuccessfulRegistration.entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            compose.waitForIdle()
            compose.onNodeWithTag("telecomStatus").assertTextEquals("WORKING")
            val publications = java.util.concurrent.LinkedBlockingQueue<Thread>()
            observer = androidx.compose.runtime.snapshots.Snapshot.registerGlobalWriteObserver { changed ->
                val value = (changed as? androidx.compose.runtime.State<*>)?.value
                if (value is String && value.startsWith("REGISTERED_USER_ENABLE_REQUIRED · receiptSaved=")) {
                    publications.offer(Thread.currentThread())
                }
            }
            // No recomposition/frame may race the completion observation. A real Android
            // Main handler is still pumped, so a correctly confined callback can complete.
            compose.mainClock.autoAdvance = false
            HeldSuccessfulRegistration.release.countDown()
            compose.waitUntil(10000) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                publications.isNotEmpty()
            }
            assertSame("held Enable returned to a worker instead of the Android view thread",
                android.os.Looper.getMainLooper().thread, publications.peek())
        } finally {
            HeldSuccessfulRegistration.release.countDown()
            observer?.dispose()
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        val manager = app.getSystemService(TelecomManager::class.java)
        assertNotNull(manager.getPhoneAccount(experiment.handle))
        assertFalse("registration must not simulate Android user account enablement", experiment.armed)
        compose.onNodeWithTag("telecomEnabled").assertIsOn()
        compose.onNodeWithTag("telecomControlsEnabled").assertIsNotEnabled()
        compose.onNodeWithTag("telecomStatus").assertTextContains("REGISTERED_USER_ENABLE_REQUIRED", substring = true)
    }

    @Test fun simpleTogglesRegisterOnlyOnEnableAndKeepSystemAccountEnableSeparate() {
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { LegacyManagedTelecomPanel() } } }
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
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { LegacyManagedTelecomPanel() } } }
        compose.waitForIdle()
        // Compose itself launches its test Activity; drain that known host before this assertion.
        while (shadowOf(app).nextStartedActivity != null) { }
        click("telecomEnableSettings")
        assertNull(shadowOf(app).nextStartedActivity)
        assertNull(app.getSystemService(TelecomManager::class.java).getPhoneAccount(experiment.handle))
    }
}

/**
 * Causal controls: no Compose test rule and no replacement Recomposer/effect context.
 * Mount the actual panels in a real ComponentActivity's normal window composition,
 * invoke their public semantics actions, and let Android's real Main handler deliver IO
 * completion. These are module tests, not an extracted callback or wrapper simulation.
 */
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = android.app.Application::class)
class ManagedTelecomAndroidMainThreadTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val experiment get() = ManagedTelecomExperiment.get(app)

    @Before fun reset() {
        HeldSuccessfulRemoval.hold = false
        HeldSuccessfulRemoval.release.countDown()
        com.openautolink.app.ui.testing.resetNativeTelecomTestProcess(app)
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_PHONE_NUMBERS)
    }
    @After fun close() {
        HeldSuccessfulRemoval.hold = false
        HeldSuccessfulRemoval.release.countDown()
        com.openautolink.app.ui.testing.resetNativeTelecomTestProcess(app)
    }

    @org.robolectric.annotation.Implements(TelecomManager::class)
    class HeldSuccessfulRemoval : org.robolectric.shadows.ShadowTelecomManager() {
        companion object {
            @Volatile var hold = false
            var entered = java.util.concurrent.CountDownLatch(1)
            var release = java.util.concurrent.CountDownLatch(1)
        }
        @org.robolectric.annotation.Implementation
        override fun unregisterPhoneAccount(handle: android.telecom.PhoneAccountHandle) {
            if (hold) {
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "removal was not released" }
            }
            super.unregisterPhoneAccount(handle)
        }
    }

    private fun pumpUntil(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16))
            Thread.yield()
        }
        assertTrue(message, condition())
    }

    private fun root(view: android.view.View): androidx.compose.ui.platform.ViewRootForTest? {
        if (view is androidx.compose.ui.platform.ViewRootForTest) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) root(view.getChildAt(index))?.let { return it }
        }
        return null
    }
    private fun descendants(node: androidx.compose.ui.semantics.SemanticsNode): Sequence<androidx.compose.ui.semantics.SemanticsNode> = sequence {
        yield(node)
        node.children.forEach { yieldAll(descendants(it)) }
    }
    private fun tagged(activity: androidx.activity.ComponentActivity, tag: String): androidx.compose.ui.semantics.SemanticsNode? {
        val owner = root(activity.window.decorView) ?: return null
        return descendants(owner.semanticsOwner.rootSemanticsNode).singleOrNull {
            it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.TestTag) &&
                it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] == tag
        }
    }
    private fun click(activity: androidx.activity.ComponentActivity, tag: String) {
        val node = checkNotNull(tagged(activity, tag)) { "missing actual semantics node: $tag" }
        assertFalse("actual UI action disabled: $tag", node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled))
        assertTrue("actual UI callback rejected: $tag",
            node.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action?.invoke() == true)
    }
    private fun observeStatus(prefix: String, publications: java.util.concurrent.LinkedBlockingQueue<Thread>) =
        androidx.compose.runtime.snapshots.Snapshot.registerGlobalWriteObserver { changed ->
            val value = (changed as? androidx.compose.runtime.State<*>)?.value
            if (value is String && value.startsWith(prefix)) publications.offer(Thread.currentThread())
        }

    @Test @Config(shadows = [ManagedTelecomPanelTest.HeldSuccessfulRegistration::class])
    fun sameHeldLegacyEnableReturnsThroughTheUnmodifiedAndroidWindowMainHandler() {
        val held = ManagedTelecomPanelTest.HeldSuccessfulRegistration
        held.entered = java.util.concurrent.CountDownLatch(1)
        held.release = java.util.concurrent.CountDownLatch(1)
        val controller = org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val publications = java.util.concurrent.LinkedBlockingQueue<Thread>()
        val observer = observeStatus("REGISTERED_USER_ENABLE_REQUIRED · receiptSaved=", publications)
        try {
            activity.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { LegacyManagedTelecomPanel() } } }
            pumpUntil("legacy panel did not attach") { tagged(activity, "telecomEnabled") != null }
            click(activity, "telecomEnabled")
            pumpUntil("actual registration did not enter") { held.entered.count == 0L }
            assertTrue("must not publish success across the held OS boundary", publications.isEmpty())
            held.release.countDown()
            pumpUntil("Android window did not publish Enable completion") { publications.isNotEmpty() }
            assertSame("normal window completion escaped the Android view thread",
                android.os.Looper.getMainLooper().thread, publications.peek())
            pumpUntil("actual panel did not render registration result") {
                tagged(activity, "telecomStatus")?.config?.get(androidx.compose.ui.semantics.SemanticsProperties.Text)
                    ?.any { it.text.startsWith("REGISTERED_USER_ENABLE_REQUIRED") } == true
            }
            assertNotNull(app.getSystemService(TelecomManager::class.java).getPhoneAccount(experiment.handle))
            assertFalse("normal window must preserve separate OS user enablement", experiment.armed)
        } finally {
            held.release.countDown()
            observer.dispose()
            controller.pause().stop().destroy()
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertTrue("real Activity must complete destruction", activity.isDestroyed)
    }

    @Test @Config(shadows = [HeldSuccessfulRemoval::class])
    fun shippedRepairCallbackReturnsThroughTheUnmodifiedAndroidWindowMainHandler() {
        assertEquals(ManagedTelecomExperiment.Result.REGISTERED_USER_ENABLE_REQUIRED, experiment.enable())
        HeldSuccessfulRemoval.entered = java.util.concurrent.CountDownLatch(1)
        HeldSuccessfulRemoval.release = java.util.concurrent.CountDownLatch(1)
        val controller = org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val publications = java.util.concurrent.LinkedBlockingQueue<Thread>()
        val observer = observeStatus("DISABLED_AND_REMOVED", publications)
        try {
            activity.setContent { MaterialTheme { ManagedTelecomPanel() } }
            pumpUntil("production repair panel did not attach") { tagged(activity, "telecomCleanup") != null }
            HeldSuccessfulRemoval.hold = true
            click(activity, "telecomCleanup")
            pumpUntil("actual account removal did not enter") { HeldSuccessfulRemoval.entered.count == 0L }
            assertTrue("must not publish removal across the held OS boundary", publications.isEmpty())
            pumpUntil("busy state was not rendered while real removal is held") {
                tagged(activity, "telecomCleanup")?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == true
            }
            HeldSuccessfulRemoval.release.countDown()
            pumpUntil("production callback did not publish acknowledged removal") { publications.isNotEmpty() }
            assertSame("production repair completion escaped the Android view thread",
                android.os.Looper.getMainLooper().thread, publications.peek())
            pumpUntil("production repair did not render completed status and clear busy") {
                tagged(activity, "telecomStatus")?.config?.get(androidx.compose.ui.semantics.SemanticsProperties.Text)
                    ?.any { it.text == "DISABLED_AND_REMOVED" } == true &&
                    tagged(activity, "telecomCleanup")?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false
            }
            assertNull(app.getSystemService(TelecomManager::class.java).getPhoneAccount(experiment.handle))
            assertFalse(experiment.enabledForProcess)
        } finally {
            HeldSuccessfulRemoval.hold = false
            HeldSuccessfulRemoval.release.countDown()
            observer.dispose()
            controller.pause().stop().destroy()
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertTrue("real Activity must complete destruction", activity.isDestroyed)
    }
}

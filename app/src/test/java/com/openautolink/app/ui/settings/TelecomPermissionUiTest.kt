package com.openautolink.app.ui.settings

import android.Manifest
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.core.app.ActivityOptionsCompat
import com.openautolink.app.ui.testing.createOalComposeRule
import com.openautolink.carplaylab.telecom.ManagedTelecomExperiment
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [32, 34], application = android.app.Application::class)
class TelecomPermissionUiTest {
    @get:Rule val compose = createOalComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val experiment get() = ManagedTelecomExperiment.get(app)
    private var requestCode = -1
    private var requests = 0
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            assertEquals(Manifest.permission.READ_PHONE_NUMBERS, input)
            val intent = contract.createIntent(app, input)
            assertEquals(ActivityResultContracts.RequestMultiplePermissions.ACTION_REQUEST_PERMISSIONS, intent.action)
            assertArrayEquals(arrayOf(Manifest.permission.READ_PHONE_NUMBERS), intent.getStringArrayExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS))
            requests++; requestCode = code
        }
    }
    private var visible by mutableStateOf(true)
    private lateinit var hostLifecycle: androidx.lifecycle.LifecycleRegistry
    @Before fun reset() { com.openautolink.app.ui.testing.resetNativeTelecomTestProcess(app); shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_NUMBERS) }
    @After fun cleanup() { com.openautolink.app.ui.testing.resetNativeTelecomTestProcess(app) }
    private fun content() {
        compose.setContent {
            val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            SideEffect { hostLifecycle = owner.lifecycle as androidx.lifecycle.LifecycleRegistry }
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }) { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                if (visible) ManagedTelecomPanel()
            } } }
        }
    }
    private fun grant() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_NUMBERS) // OS boundary only
        compose.runOnIdle { registry.dispatchResult(requestCode, true) }
    }
    private fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun settle() {
        compose.waitUntil(10000) {
            compose.onNodeWithTag("telecomStatus").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].none { it.text == "WORKING" }
        }
    }
    @Test fun realGrantCallbackContinuesOnlyExplicitEnableAndReadbackRemainsUserDisabled() {
        content(); click("telecomEnabled"); grant()
        compose.waitUntil(10000) { experiment.enabledForProcess }; settle()
        assertEquals(1, requests)
        assertFalse(experiment.armed)
        compose.onNodeWithTag("telecomEnabled").assertIsOn()
        compose.onNodeWithTag("telecomControlsEnabled").assertIsNotEnabled()
        compose.onNodeWithTag("telecomStatus").assertTextContains("REGISTERED_USER_ENABLE_REQUIRED", substring = true)
    }
    @Test fun deniedAndRepeatedPermanentStyleDeniedResultsNeverRegister() {
        content()
        repeat(2) {
            click("telecomEnabled")
            compose.runOnIdle { registry.dispatchResult(requestCode, false) }
            settle()
            compose.onNodeWithTag("telecomEnabled").assertIsOff()
            compose.onNodeWithTag("telecomStatus").assertTextContains("READ_PERMISSION_REQUIRED", substring = true)
            assertFalse(experiment.enabledForProcess)
            val receipt = java.io.File(app.getExternalFilesDir(null), "openautolink/logs/telecom_account_access.log")
            assertTrue("actual OS denial callback did not persist a receipt", receipt.isFile)
            assertTrue("actual OS denial callback did not persist its finite read-permission outcome", receipt.readText().contains("stage=FIRST_OWN_READ outcome=READ_PERMISSION_REQUIRED"))
            val bytes = receipt.readBytes()
            com.openautolink.app.diagnostics.LogUploadArchive.create(
                com.openautolink.app.diagnostics.LogUploadArchive.select(listOf(receipt), System.currentTimeMillis()), app.cacheDir).use { archive ->
                java.util.zip.ZipFile(archive.file).use { zip ->
                    assertArrayEquals(bytes, zip.getInputStream(zip.getEntry(receipt.name)).readBytes())
                }
            }
        }
        assertEquals(2, requests)
        assertNull(app.getSystemService(android.telecom.TelecomManager::class.java).getPhoneAccount(experiment.handle))
    }
    @Test fun routeDisposalInvalidatesPendingPermissionWithoutEnablingOnLateGrant() {
        content(); click("telecomEnabled")
        compose.runOnIdle { visible = false }
        grant(); compose.waitForIdle()
        assertFalse(experiment.enabledForProcess)
        assertNull(app.getSystemService(android.telecom.TelecomManager::class.java).getPhoneAccount(experiment.handle))
    }
    @Test fun resumingAfterExternalPermissionRevocationUpdatesUiWithoutRequestingAgain() {
        content(); click("telecomEnabled"); grant()
        compose.waitUntil(10000) { experiment.enabledForProcess }; settle()
        shadowOf(app.getSystemService(android.telecom.TelecomManager::class.java)).enablePhoneAccount(experiment.handle, true)
        click("telecomReadback"); settle(); assertTrue(experiment.armed)
        compose.runOnIdle {
            hostLifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
            shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_NUMBERS)
            hostLifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        }
        compose.onNodeWithTag("telecomStatus").assertTextContains("READ_PERMISSION_REQUIRED", substring = true)
        compose.onNodeWithTag("telecomControlsEnabled").assertIsNotEnabled()
        assertFalse(experiment.armed)
        assertEquals(1, requests)
    }
    @Test fun revokedGrantAndRefreshShowUnknownPermissionRequiredInsteadOfCachedEnabled() {
        content(); click("telecomEnabled"); grant()
        compose.waitUntil(10000) { experiment.enabledForProcess }; settle()
        shadowOf(app.getSystemService(android.telecom.TelecomManager::class.java)).enablePhoneAccount(experiment.handle, true)
        click("telecomReadback"); settle(); assertTrue(experiment.armed)
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        click("telecomReadback"); settle()
        assertFalse(experiment.armed)
        compose.onNodeWithTag("telecomControlsEnabled").assertIsNotEnabled()
        compose.onNodeWithTag("telecomStatus").assertTextContains("READ_PERMISSION_REQUIRED", substring = true)
        click("telecomInspect"); settle()
        compose.onNodeWithTag("telecomStatus").assertTextContains("ownAccount=UNKNOWN", substring = true)
        compose.onNodeWithTag("telecomStatus").assertTextContains("readPermissionGranted=false", substring = true)
    }
    @Test fun explicitEnableRequestsRealPermissionContractAndOffFencesLateGrant() {
        content(); compose.waitForIdle()
        assertEquals(-1, requestCode)
        click("telecomEnabled"); compose.waitForIdle()
        assertTrue("explicit enable did not request OS permission", requestCode >= 0)
        assertFalse(experiment.enabledForProcess)
        click("telecomEnabled"); settle()
        click("telecomEnabled") // old request is still outstanding: do not adopt its result
        assertEquals(1, requests)
        compose.onNodeWithTag("telecomEnabled").assertIsOff()
        val oldStatus = compose.onNodeWithTag("telecomStatus").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].toString()
        grant(); compose.waitForIdle()
        assertFalse(experiment.enabledForProcess)
        compose.onNodeWithTag("telecomEnabled").assertIsOff()
        assertEquals(oldStatus, compose.onNodeWithTag("telecomStatus").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].toString())
    }
}

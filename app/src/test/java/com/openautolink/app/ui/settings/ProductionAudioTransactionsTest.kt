package com.openautolink.app.ui.settings

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.telecom.TelecomManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.openautolink.app.carplay.*
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.CarPlayCallPreferences
import com.openautolink.app.ui.testing.createOalComposeRule
import com.openautolink.carplaylab.telecom.ManagedTelecomExperiment
import com.openautolink.carplaylab.telecom.TelecomAccountSettings
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual production binder/controller/repository; only Android OS boundaries are shadowed.
 * The retirement latch models the exact installed capture owner's acknowledgement, not hardware.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [32, 34], application = Application::class, shadows = [AcknowledgedForegroundShadow::class])
class ProductionAudioTransactionsTest {
    @get:Rule val compose: ComposeContentTestRule = createOalComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val backend get() = ManagedTelecomExperiment.get(app)
    private lateinit var bindings: AudioSettingsBindings
    private lateinit var lifecycle: LifecycleRegistry
    private lateinit var activity: android.app.Activity
    private var mounted by mutableStateOf(true)
    private var reconnects = 0
    private data class Request(val code: Int, val permission: String)
    private val requests = mutableListOf<Request>()
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            val intent = contract.createIntent(app, input)
            assertEquals(ActivityResultContracts.RequestMultiplePermissions.ACTION_REQUEST_PERMISSIONS, intent.action)
            val permission = intent.getStringArrayExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS)!!.single()
            requests.add(Request(code, permission))
        }
    }

    @Before fun prepareProcess() {
        // Retire actual process owner before replacing Robolectric's Application; no lease reset.
        val old = ReflectionHelpers.getStaticField<CarPlayCallController?>(CarPlayCallController::class.java, "instance")
        old?.close()
        ReflectionHelpers.setStaticField(CarPlayCallController::class.java, "instance", null)
        com.openautolink.app.ui.testing.resetNativeTelecomTestProcess(app)
        runBlocking { AppPreferences.getInstance(app).updateCarPlayCallPreferences { CarPlayCallPreferences() } }
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.RECORD_AUDIO)
        val info = shadowOf(app.packageManager).addActivityIfNotPresent(TelecomAccountSettings.exactComponent)
        info.exported = true; info.enabled = true; info.applicationInfo.enabled = true
    }
    @After fun retireProcess() {
        if (::activity.isInitialized) CarPlayForegroundVisibility.paused(activity)
        ReflectionHelpers.getStaticField<CarPlayCallController?>(CarPlayCallController::class.java, "instance")?.close()
        ReflectionHelpers.setStaticField(CarPlayCallController::class.java, "instance", null)
        backend.disable()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun mount() {
        compose.setContent {
            lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle as LifecycleRegistry
            activity = androidx.compose.ui.platform.LocalContext.current as android.app.Activity
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }) {
                if (mounted) {
                    val actual = rememberProductionAudioBindings { reconnects++ }
                    SideEffect { bindings = actual }
                    MaterialTheme { AudioMicrophoneSettings("car", {}, actual) }
                }
            }
        }
        compose.waitUntil(10000) { ::bindings.isInitialized && bindings.calls.preferencesLoaded }
        compose.waitForIdle()
        CarPlayForegroundVisibility.resumed(activity)
    }
    private fun action(block: AudioSettingsBindings.() -> Unit) { compose.runOnIdle { bindings.block() }; compose.waitForIdle() }
    private fun settle() { compose.waitUntil(10000) { !bindings.calls.busy }; compose.waitForIdle() }
    private fun resume() {
        compose.runOnIdle {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            // MainActivity's production resume also reconciles the exact lease.
            CarPlaySessionLeases.reconcile(app)
        }
        settle()
    }
    private fun result(request: Request, granted: Boolean) {
        if (granted) shadowOf(app).grantPermissions(request.permission) else shadowOf(app).denyPermissions(request.permission)
        // Use the real contract parser and registered production callback, not a helper mapper.
        compose.runOnIdle {
            assertTrue(registry.dispatchResult(request.code, android.app.Activity.RESULT_OK,
                Intent().putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, arrayOf(request.permission))
                    .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                        intArrayOf(if (granted) android.content.pm.PackageManager.PERMISSION_GRANTED else android.content.pm.PackageManager.PERMISSION_DENIED))))
        }
        compose.waitForIdle()
    }
    private fun setupRequest(): Request {
        action { onSetUpCalls() }
        compose.waitUntil(10000) { requests.isNotEmpty() }
        return requests.last().also { assertEquals(Manifest.permission.READ_PHONE_NUMBERS, it.permission) }
    }
    private fun nextServiceStart(): Intent {
        shadowOf(Looper.getMainLooper()).idle()
        return requireNotNull(shadowOf(app).nextStartedService)
    }

    @Test fun revokedAdvertisedLeaseRegrantOffersOnlyExplicitReconnectAfterExactRetirement() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        mount()
        val lease = CarPlaySessionLeases.forContext(app).acquire()
        val service = Robolectric.buildService(CarPlaySessionService::class.java).create()
        val entered = CountDownLatch(1); val acknowledge = CountDownLatch(1)
        try {
            service.get().onStartCommand(nextServiceStart(), 0, 1)
            lease.awaitReady(); lease.inputAdvertised(true)
            assertTrue(lease.captureAllowed())
            lease.onCaptureRetirement {
                assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                entered.countDown(); check(acknowledge.await(10, TimeUnit.SECONDS))
            }
            shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
            CarPlaySessionLeases.reconcile(app)
            assertFalse(lease.captureAllowed())
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(CarPlaySessionLeases.status().retiring)
            assertTrue(CarPlaySessionLeases.status().reconnectRequired)
            assertEquals(CarPlayForegroundPolicy.BASE or CarPlayForegroundPolicy.MIC, service.get().foregroundServiceType)
            shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
            resume()
            assertNull("No reconnect before exact capture retirement acknowledgement", bindings.microphoneAction)
            action { onMicrophoneAction() }
            assertEquals(0, reconnects)
            acknowledge.countDown()
            compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle(); !CarPlaySessionLeases.status().retiring }
            assertEquals(CarPlayForegroundPolicy.BASE, service.get().foregroundServiceType)
            shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
            resume()
            compose.waitUntil(10000) { CarPlaySessionLeases.status().permissionGranted }
            compose.waitForIdle()
            assertEquals(true, CarPlaySessionLeases.status().inputAdvertised)
            assertFalse(lease.captureAllowed())
            assertTrue(CarPlaySessionLeases.status().reconnectRequired)
            assertEquals("Reconnect CarPlay", bindings.microphoneAction)
            assertTrue(bindings.microphoneMessage.contains("Reconnect"))
            assertEquals(0, reconnects); assertTrue(requests.isEmpty())
            assertNull(shadowOf(app).nextStartedService)
            compose.onNodeWithTag("carPlayMicrophoneAction").assertTextContains("Reconnect CarPlay").performClick()
            compose.waitForIdle()
            assertEquals(1, reconnects)
            assertFalse("Explicit UI action cannot clear the old owner's fence", lease.captureAllowed())
            val successor = CarPlaySessionLeases.forContext(app).acquire()
            try {
                service.get().onStartCommand(nextServiceStart(), 0, 2)
                successor.awaitReady(); successor.inputAdvertised(true)
                assertTrue("Only a new exact owner can regain capture", successor.captureAllowed())
                assertFalse(CarPlaySessionLeases.status().reconnectRequired)
                assertFalse(lease.captureAllowed())
            } finally { successor.release() }
        } finally { acknowledge.countDown(); lease.release(); service.destroy() }
    }

    @Test fun mountAndResumeReadBackWithoutLaunchingPermissionOrSettings() {
        mount(); resume()
        assertEquals(CarPlayCallSetup.SETUP_REQUIRED, bindings.calls.setup)
        assertTrue(requests.isEmpty()); assertEquals(0, reconnects)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun explicitPhonePermissionDenialThenGrantLaunchesActualAccountSettingsAndResumeReadsReady() {
        mount()
        val denied = setupRequest(); result(denied, false); settle()
        assertEquals(CarPlayCallSetup.SETUP_REQUIRED, bindings.calls.setup)
        assertTrue(bindings.calls.message.contains("Phone permission"))
        assertNull(shadowOf(app).nextStartedActivity)
        action { onSetUpCalls() }
        compose.waitUntil(10000) { requests.size == 2 }
        result(requests.last(), true); settle()
        assertEquals(CarPlayCallSetup.ENABLE_ACCOUNT, bindings.calls.setup)
        assertEquals(TelecomAccountSettings.exactComponent, shadowOf(app).nextStartedActivity?.component)
        shadowOf(app.getSystemService(TelecomManager::class.java)).enablePhoneAccount(backend.handle, true)
        resume()
        compose.waitUntil(10000) { bindings.calls.setup == CarPlayCallSetup.READY }
        assertEquals(2, requests.size); assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(com.openautolink.carplaylab.telecom.HfpCallGuard.Status.UNKNOWN,
            CarPlayCallController.get(app).state.value.hfp)
        assertEquals(0, reconnects)
    }

    @Test fun hidingCallPageRetainsOutstandingOsSlotAndLateGrantCannotAdoptReplacementSetup() {
        mount(); val old = setupRequest()
        action { onCallSetupHidden() }
        action { onSetUpCalls() }
        assertEquals(1, requests.size)
        result(old, true); settle()
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(CarPlayCallHandler.CARPLAY, bindings.calls.handler)
        // Only a new explicit action can continue using current OS permission.
        action { onSetUpCalls() }; settle()
        assertEquals(TelecomAccountSettings.exactComponent, shadowOf(app).nextStartedActivity?.component)
        assertEquals(1, requests.size)
    }

    @Test fun handlerSwitchKeepsOldPermissionSlotButLateGrantCannotReselectOrOpenSettings() {
        mount(); val old = setupRequest()
        action { onCallHandlerChange(CarPlayCallHandler.CAR_BLUETOOTH) }; settle()
        action { onCallHandlerChange(CarPlayCallHandler.CARPLAY) }; settle()
        action { onSetUpCalls() }
        assertEquals(1, requests.size)
        action { onCallHandlerChange(CarPlayCallHandler.CAR_BLUETOOTH) }; settle()
        result(old, true); settle()
        assertEquals(CarPlayCallHandler.CAR_BLUETOOTH, bindings.calls.handler)
        assertEquals(CarPlayCallPreferences.Handler.CAR_BLUETOOTH,
            CarPlayCallController.get(app).state.value.preferences?.handler)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun disposedBinderCannotContinueItsOldPermissionIntoRemountedSuccessor() {
        mount(); val old = setupRequest()
        compose.runOnIdle { mounted = false }; compose.waitForIdle()
        result(old, true)
        compose.runOnIdle { mounted = true }; compose.waitForIdle()
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(1, requests.size); assertEquals(0, reconnects)
    }

    @Test fun separateRecordAudioCallbackKeepsFrozenNoneAndAdmittedCallRefusesReconnect() {
        mount()
        val lease = CarPlaySessionLeases.forContext(app).acquire()
        val service = Robolectric.buildService(CarPlaySessionService::class.java).create()
        try {
            service.get().onStartCommand(nextServiceStart(), 0, 1)
            lease.awaitReady(); lease.inputAdvertised(false)
            compose.waitForIdle()
            val phone = setupRequest()
            action { onMicrophoneAction() }
            compose.waitUntil(10000) { requests.size == 2 }
            val mic = requests.last(); assertEquals(Manifest.permission.RECORD_AUDIO, mic.permission)
            result(mic, false)
            assertEquals(CarPlayCallSetup.SETUP_REQUIRED, bindings.calls.setup)
            assertFalse(backend.hasAdmittedCall)
            assertEquals("Enable microphone", bindings.microphoneAction)
            // Phone and microphone permissions have separate real registry continuations.
            result(phone, true); settle()
            assertEquals(TelecomAccountSettings.exactComponent, shadowOf(app).nextStartedActivity?.component)
            assertFalse(CarPlaySessionLeases.status().permissionGranted)
            action { onMicrophoneAction() }
            compose.waitUntil(10000) { requests.size == 3 }
            result(requests.last(), true)
            compose.waitForIdle()
            assertEquals(false, CarPlaySessionLeases.status().inputAdvertised)
            assertEquals("Reconnect CarPlay", bindings.microphoneAction)
            assertEquals(0, reconnects)
            shadowOf(app.getSystemService(TelecomManager::class.java)).enablePhoneAccount(backend.handle, true)
            resume(); compose.waitUntil(10000) { bindings.calls.setup == CarPlayCallSetup.READY }
            val link = requireNotNull(backend.authenticatedLink { true })
            fun field(id: Int, bytes: ByteArray) = byteArrayOf(0, (bytes.size + 4).toByte(), 0, id.toByte()) + bytes
            link.handle(com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4155,
                field(4, "00000000-0000-4000-8000-000000000001\u0000".toByteArray()) +
                    field(2, byteArrayOf(2)) + field(3, byteArrayOf(1)) + field(9, byteArrayOf(0))))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Actual backend must own an admitted call", backend.hasAdmittedCall)
            action { onMicrophoneAction() }
            assertEquals(0, reconnects)
            assertEquals("Reconnect after the current call ends.", bindings.microphoneMessage)
            assertTrue("Reconnect refusal must not hang up the call", backend.hasAdmittedCall)
            link.handle(com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4155,
                field(4, "00000000-0000-4000-8000-000000000001\u0000".toByteArray()) + field(2, byteArrayOf(0))))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(backend.hasAdmittedCall)
            action { onMicrophoneAction() }; assertEquals(1, reconnects)
        } finally { lease.release(); service.destroy() }
    }

    @Test fun productionNativeAudioActionUsesActualLauncherAndReturnedRequestMessage() {
        mount()
        action { onOpenVehicleAudio() }
        assertEquals("Vehicle Audio requested.", bindings.actionMessage)
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals("android.car.intent.action.MEDIA_TEMPLATE", intent?.action)
        assertEquals(com.openautolink.app.media.OalMediaHostLauncher.intent(activity)
            .getStringExtra("android.car.intent.extra.MEDIA_COMPONENT"),
            intent?.getStringExtra("android.car.intent.extra.MEDIA_COMPONENT"))
        assertTrue(requests.isEmpty()); assertEquals(0, reconnects)
    }

    @Test fun actualActivityRegistrySeparatesOverlappingSafAndPhoneResultsAcrossRecreation() {
        val host = Robolectric.buildActivity(ProductionAudioTransactionsActivity::class.java).setup()
        fun drain(done: () -> Boolean) = compose.waitUntil(10000) {
            shadowOf(Looper.getMainLooper()).idle(); done()
        }
        try {
            val first = host.get()
            drain { first.binding?.calls?.preferencesLoaded == true }
            val vm = first.exporter
            first.binding!!.onSetUpCalls()
            var phone: org.robolectric.shadows.ShadowActivity.IntentForResult? = null
            drain { if (phone == null) phone = shadowOf(first).nextStartedActivityForResult; phone != null }
            // ComponentActivity routes RequestPermission through Activity.requestPermissions;
            // its OS intent differs from the contract's internal request intent above.
            assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", phone!!.intent.action)
            assertArrayEquals(arrayOf(Manifest.permission.READ_PHONE_NUMBERS), phone!!.intent.getStringArrayExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES"))
            vm.requestExport()
            var picker: org.robolectric.shadows.ShadowActivity.IntentForResult? = null
            drain { if (picker == null) picker = shadowOf(first).nextStartedActivityForResult; picker != null }
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker!!.intent.action)
            // ShadowActivity records startActivityForResult in both queues; consume the
            // already-observed SAF launch before asserting that later callbacks launch nothing.
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, shadowOf(app).nextStartedActivity?.action)
            assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", shadowOf(app).nextStartedActivity?.action)
            assertNull(shadowOf(app).nextStartedActivity)
            assertNotEquals(phone!!.requestCode, picker!!.requestCode)
            vm.cancelExport()
            host.recreate()
            val next = host.get()
            drain { next.binding?.calls?.preferencesLoaded == true }
            assertSame("SAF is Activity-VM owned, not a settings-route VM", vm, next.exporter)
            assertNull(shadowOf(next).nextStartedActivityForResult)
            assertTrue(vm.state.value.exportBusy)
            assertTrue(next.activityResultRegistry.dispatchResult(picker!!.requestCode, android.app.Activity.RESULT_CANCELED, null))
            drain { !vm.state.value.exportBusy }
            assertEquals(CarPlayCallSetup.SETUP_REQUIRED, next.binding!!.calls.setup)
            shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_NUMBERS)
            assertTrue(next.activityResultRegistry.dispatchResult(phone!!.requestCode, android.app.Activity.RESULT_OK,
                Intent().putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, arrayOf(Manifest.permission.READ_PHONE_NUMBERS))
                    .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED))))
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("Late phone result cannot open settings for the recreated successor", shadowOf(app).nextStartedActivity)
            assertNull(shadowOf(next).nextStartedActivityForResult)
            assertEquals(0, next.reconnects)
            assertEquals(com.openautolink.app.ui.diagnostics.MediaExportPhase.CANCELED, vm.state.value.exportPhase)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun actualActivityRecreationRetainsPhoneSlotWithoutAdoptingSuccessorSetup() {
        val host = Robolectric.buildActivity(ProductionAudioTransactionsActivity::class.java).setup()
        fun drain(done: () -> Boolean) = compose.waitUntil(10000) {
            shadowOf(Looper.getMainLooper()).idle(); done()
        }
        try {
            val first = host.get()
            drain { first.binding?.calls?.preferencesLoaded == true }
            first.binding!!.onSetUpCalls() // A: denied permission, actual shared Activity registry.
            var phone: org.robolectric.shadows.ShadowActivity.IntentForResult? = null
            drain { if (phone == null) phone = shadowOf(first).nextStartedActivityForResult; phone != null }
            assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", phone!!.intent.action)
            assertArrayEquals(arrayOf(Manifest.permission.READ_PHONE_NUMBERS),
                phone!!.intent.getStringArrayExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES"))
            assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", shadowOf(app).nextStartedActivity?.action)
            assertNull(shadowOf(app).nextStartedActivity)
            host.recreate()
            val next = host.get()
            drain { next.binding?.calls?.preferencesLoaded == true }
            assertNotSame(first, next)
            compose.waitForIdle()
            assertTrue(next.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                app.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS))
            assertEquals(CarPlayCallHandler.CARPLAY, next.binding!!.calls.handler)
            assertFalse("Recreated binding must not retain the old continuation's busy state", next.binding!!.calls.busy)
            assertFalse(next.isDestroyed)
            assertFalse(next.isFinishing)
            assertNull(shadowOf(next).nextStartedActivityForResult)
            assertNull(shadowOf(next).lastRequestedPermission)
            compose.runOnIdle { next.binding!!.onSetUpCalls() } // B: explicit successor before A.
            compose.waitForIdle()
            // Serialize readback behind B's prepare, then drain its main-thread continuation.
            runBlocking { CarPlayCallController.get(app).prepare() }
            drain { !next.binding!!.calls.busy || shadowOf(next).lastRequestedPermission != null }
            compose.waitForIdle()
            // Android may suppress a concurrent dialog; assert the actual OS request boundary too.
            assertNull("B must not request permission while A occupies the OS slot", shadowOf(next).lastRequestedPermission)
            assertNull("B must retain A's outstanding OS permission slot", shadowOf(next).nextStartedActivityForResult)
            assertNull(shadowOf(app).nextStartedActivity)
            shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_NUMBERS)
            assertTrue(next.activityResultRegistry.dispatchResult(phone!!.requestCode, android.app.Activity.RESULT_OK,
                Intent().putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, arrayOf(Manifest.permission.READ_PHONE_NUMBERS))
                    .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED))))
            drain { !next.binding!!.calls.busy }
            assertNull("A cannot continue B into Android account settings", shadowOf(app).nextStartedActivity)
            assertNull(shadowOf(next).nextStartedActivityForResult)
            assertEquals(CarPlayCallHandler.CARPLAY, next.binding!!.calls.handler)
            assertEquals(CarPlayCallPreferences.Handler.CARPLAY,
                CarPlayCallController.get(app).state.value.preferences?.handler)
            assertEquals(0, next.reconnects)
            next.binding!!.onSetUpCalls() // C: A consumed; only this explicit action may continue.
            drain { !next.binding!!.calls.busy && next.binding!!.calls.setup == CarPlayCallSetup.ENABLE_ACCOUNT }
            assertEquals(TelecomAccountSettings.exactComponent, shadowOf(app).nextStartedActivity?.component)
            assertNull(shadowOf(app).nextStartedActivity)
            assertEquals(TelecomAccountSettings.exactComponent, shadowOf(next).nextStartedActivityForResult?.intent?.component)
            assertNull(shadowOf(next).nextStartedActivityForResult)
            assertEquals(0, next.reconnects)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun actualRegistryPhoneLaunchFailureRollsBackOnlyPhoneSlotAndAllowsExplicitRetry() {
        permissionLaunchFailure(Manifest.permission.READ_PHONE_NUMBERS)
    }

    @Test fun actualRegistryMicrophoneLaunchFailureRollsBackOnlyMicSlotAndAllowsExplicitRetry() {
        permissionLaunchFailure(Manifest.permission.RECORD_AUDIO)
    }

    private fun permissionLaunchFailure(failedPermission: String) {
        val phoneFails = failedPermission == Manifest.permission.READ_PHONE_NUMBERS
        val otherPermission = if (phoneFails) Manifest.permission.RECORD_AUDIO else Manifest.permission.READ_PHONE_NUMBERS
        val host = Robolectric.buildActivity(PermissionLaunchFailureActivity::class.java).setup()
        fun drain(done: () -> Boolean) = compose.waitUntil(10000) {
            shadowOf(Looper.getMainLooper()).idle(); done()
        }
        fun invoke(activity: PermissionLaunchFailureActivity, permission: String) {
            compose.runOnIdle {
                if (permission == Manifest.permission.READ_PHONE_NUMBERS) activity.binding!!.onSetUpCalls()
                else activity.binding!!.onMicrophoneAction()
            }
            // Serialize the controller IO lane before reading the UI continuation.
            runBlocking { CarPlayCallController.get(app).prepare() }
            shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle()
        }
        fun deliver(activity: PermissionLaunchFailureActivity, request: PermissionLaunchRequest, granted: Boolean) {
            if (granted) shadowOf(app).grantPermissions(request.permission)
            else shadowOf(app).denyPermissions(request.permission)
            compose.runOnIdle {
                assertTrue(activity.permissionRegistry.dispatchResult(request.code, android.app.Activity.RESULT_OK,
                    Intent().putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, arrayOf(request.permission))
                        .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                            intArrayOf(if (granted) android.content.pm.PackageManager.PERMISSION_GRANTED else android.content.pm.PackageManager.PERMISSION_DENIED))))
            }
            runBlocking { CarPlayCallController.get(app).prepare() }
            shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle()
        }
        try {
            val first = host.get()
            drain { first.binding?.calls?.preferencesLoaded == true }
            invoke(first, otherPermission)
            drain { first.permissionRegistry.accepted.size == 1 }
            val other = first.permissionRegistry.accepted.single()
            assertEquals(otherPermission, other.permission)
            first.permissionRegistry.throwOnceFor = failedPermission
            invoke(first, failedPermission) // The real registry removes its launched key and rethrows.
            drain { first.permissionRegistry.attempts.size == 2 }
            assertEquals(listOf(otherPermission, failedPermission), first.permissionRegistry.attempts)
            assertEquals(1, first.permissionRegistry.accepted.size)
            if (phoneFails) {
                assertFalse("Failed phone launch must release busy without waiting for a result", first.binding!!.calls.busy)
                assertTrue(first.binding!!.calls.message.contains("Try again"))
                assertNull("Successful microphone request must keep its own slot", first.binding!!.microphoneAction)
            } else {
                assertEquals("Enable microphone", first.binding!!.microphoneAction)
                assertTrue(first.binding!!.microphoneMessage.contains("Try again"))
                assertTrue("Failed mic launch must not clear the outstanding phone request", first.binding!!.calls.busy)
            }
            assertNull(shadowOf(app).nextStartedActivity)
            assertEquals(0, first.reconnects)
            host.recreate()
            val next = host.get()
            drain { next.binding?.calls?.preferencesLoaded == true }
            assertTrue("No permission launch on recreation", next.permissionRegistry.attempts.isEmpty())
            invoke(next, otherPermission)
            assertTrue("Successful pre-recreation request still owns the other slot", next.permissionRegistry.attempts.isEmpty())
            invoke(next, failedPermission)
            drain { next.permissionRegistry.accepted.size == 1 }
            assertEquals(listOf(failedPermission), next.permissionRegistry.attempts)
            val retry = next.permissionRegistry.accepted.single()
            invoke(next, failedPermission)
            assertEquals("No duplicate while explicit retry is outstanding", 1, next.permissionRegistry.attempts.size)
            deliver(next, retry, false)
            if (phoneFails) drain { !next.binding!!.calls.busy }
            else assertEquals("Enable microphone", next.binding!!.microphoneAction)
            assertEquals("Callback must not automatically retry", 1, next.permissionRegistry.attempts.size)
            // A successful callback consumes its own slot; another explicit request can launch.
            invoke(next, failedPermission)
            drain { next.permissionRegistry.accepted.size == 2 }
            deliver(next, next.permissionRegistry.accepted.last(), true)
            if (phoneFails) {
                drain { !next.binding!!.calls.busy }
                assertEquals(TelecomAccountSettings.exactComponent, shadowOf(app).nextStartedActivity?.component)
                assertNull(next.binding!!.microphoneAction)
            } else {
                assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
                    app.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
                assertNull(shadowOf(app).nextStartedActivity)
            }
            deliver(next, other, false) // Restored result consumes only its old OS slot, no continuation.
            assertFalse(next.binding!!.calls.busy)
            if (phoneFails) assertEquals("Enable microphone", next.binding!!.microphoneAction)
            invoke(next, otherPermission)
            drain { next.permissionRegistry.accepted.size == 3 }
            assertEquals(otherPermission, next.permissionRegistry.accepted.last().permission)
            deliver(next, next.permissionRegistry.accepted.last(), false)
            assertEquals(3, next.permissionRegistry.attempts.size)
            assertEquals(0, next.reconnects)
            assertNull(shadowOf(app).nextStartedActivity)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun bothRealNavigationSettingsSitesShareProductionBindingAndExplicitViewModelReconnect() {
        val source = java.io.File("src/main/java/com/openautolink/app/ui/AppNavHost.kt").let {
            if (it.isFile) it else java.io.File("src/main/java/com/openautolink/app/ui/navigation/AppNavHost.kt")
        }.readText()
        assertEquals(1, Regex("rememberProductionAudioBindings\\(").findAll(source).count())
        assertTrue(source.contains("projectionViewModel::reconnectCarPlay"))
        assertEquals(2, Regex("audioBindings = audioBindings").findAll(source).count())
    }
}

/** Only the OS launch boundary fails; register/launch/result/save/restore are AndroidX code. */
data class PermissionLaunchRequest(val code: Int, val permission: String)

class ThrowOncePermissionRegistry(private val context: android.content.Context) : ActivityResultRegistry() {
    var throwOnceFor: String? = null
    val attempts = mutableListOf<String>()
    val accepted = mutableListOf<PermissionLaunchRequest>()
    override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
        val intent = contract.createIntent(context, input)
        assertEquals(ActivityResultContracts.RequestMultiplePermissions.ACTION_REQUEST_PERMISSIONS, intent.action)
        val permission = intent.getStringArrayExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS)!!.single()
        attempts.add(permission)
        if (throwOnceFor == permission) {
            throwOnceFor = null
            throw IllegalStateException("Injected permission launch failure")
        }
        accepted.add(PermissionLaunchRequest(code, permission))
    }
}

class PermissionLaunchFailureActivity : androidx.activity.ComponentActivity() {
    var binding: AudioSettingsBindings? = null
    var reconnects = 0
    val permissionRegistry = ThrowOncePermissionRegistry(this)
    override fun onResume() {
        super.onResume()
        CarPlayForegroundVisibility.resumed(this)
        CarPlaySessionLeases.reconcile(this)
    }
    override fun onPause() {
        CarPlayForegroundVisibility.paused(this)
        super.onPause()
    }
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        permissionRegistry.onRestoreInstanceState(state?.getBundle("permission-registry"))
        setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = permissionRegistry
            }) {
                val actual = rememberProductionAudioBindings { reconnects++ }
                SideEffect { binding = actual }
                MaterialTheme { AudioMicrophoneSettings("car", {}, actual) }
            }
        }
    }
    override fun onSaveInstanceState(outState: android.os.Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBundle("permission-registry", android.os.Bundle().also(permissionRegistry::onSaveInstanceState))
    }
}

/** Real ComponentActivity registry: both production binders register their actual contracts. */
class ProductionAudioTransactionsActivity : androidx.activity.ComponentActivity() {
    var binding: AudioSettingsBindings? = null
    var reconnects = 0
    lateinit var exporter: com.openautolink.app.ui.diagnostics.MediaDiagnosticsViewModel
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        exporter = androidx.lifecycle.ViewModelProvider(this)[com.openautolink.app.ui.diagnostics.MediaDiagnosticsViewModel::class.java]
        com.openautolink.app.ui.diagnostics.bindMediaDiagnosticsExport(this, exporter)
        setContent {
            val actual = rememberProductionAudioBindings { reconnects++ }
            SideEffect { binding = actual }
            MaterialTheme { AudioMicrophoneSettings("car", {}, actual) }
        }
    }
}

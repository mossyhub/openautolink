package com.openautolink.app.ui.projection

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.*
import android.net.VpnService
import android.view.Surface
import androidx.lifecycle.viewModelScope
import com.openautolink.app.carplay.*
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.CarPlaySettings
import com.openautolink.app.input.IgnitionMonitor
import com.openautolink.app.session.SessionManager
import com.openautolink.app.session.SessionState
import com.openautolink.app.transport.PhoneDiscovery
import com.openautolink.app.transport.bluetooth.AaWirelessBtControl
import com.openautolink.app.transport.usb.UsbConnectionManager
import com.openautolink.app.transport.usb.UsbConnectionState
import com.shilapi.xcertplay.media.CarPlayVideoQualification
import com.shilapi.xcertplay.media.VideoDecoderProbe
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Actual picker entry -> manager -> ViewModel admission/factory -> coordinator -> runtime ->
 * real permission waiter. Only platform services, AA retirement and decoder inventory are fixtures.
 * A null USB open is an intentional terminal sentinel after a real grant, not protocol success. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@OptIn(ExperimentalCoroutinesApi::class)
class CarPlayExplicitPermissionRetryTest {
    companion object { private val enumerationIds = java.util.concurrent.atomic.AtomicInteger(7000) }
    private val enumerationId = enumerationIds.incrementAndGet()
    private val main = StandardTestDispatcher()
    private val usb = mockk<UsbManager>(relaxed = true)
    private val app = spyk(RuntimeEnvironment.getApplication()) {
        every { getSystemService(Context.USB_SERVICE) } returns usb
        every { getSystemService(UsbManager::class.java) } returns usb
    }
    private lateinit var vm: ProjectionViewModel
    private lateinit var manager: UsbConnectionManager
    private lateinit var preferences: AppPreferences
    private val storeJob = SupervisorJob()
    @get:org.junit.Rule val temp = org.junit.rules.TemporaryFolder()
    @Volatile private var granted = false
    @Volatile private var requests = 0
    @Volatile private var nextGrant = false
    @Volatile private var duringRequest: () -> Unit = {}
    @Volatile private var duringAaRetire: () -> Unit = {}
    private val mux = mockk<UsbInterface>(relaxed = true) {
        every { interfaceClass } returns 255; every { interfaceSubclass } returns 254; every { interfaceProtocol } returns 2
    }
    private val configuration = mockk<UsbConfiguration>(relaxed = true) {
        every { interfaceCount } returns 1; every { getInterface(0) } returns mux
    }
    private val apple = mockk<UsbDevice>(relaxed = true) {
        every { deviceName } returns "/dev/bus/usb/007/$enumerationId"
        every { deviceId } returns enumerationId
        every { vendorId } returns 0x05ac; every { productId } returns 0x12a8
        every { configurationCount } returns 1; every { getConfiguration(0) } returns configuration
        every { interfaceCount } returns 1; every { getInterface(0) } returns mux
        every { serialNumber } returns "synthetic-private"
    }
    private val receipts = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val census = java.util.concurrent.ConcurrentHashMap<String, UsbDevice>().apply { put(apple.deviceName, apple) }
    private val surface = mockk<Surface> { every { isValid } returns true }
    private val aaState = MutableStateFlow(SessionState.IDLE)
    private fun field(name: String): Any? = vm.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(vm)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < deadline) {
            main.scheduler.runCurrent()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue(message + " state=" + vm.carPlayState.value, condition())
    }
    private fun setup(explicitFirst: Boolean = false, timeout: Boolean = false) = runBlocking {
        nextGrant = timeout
        Dispatchers.setMain(main)
        mockkObject(com.openautolink.app.diagnostics.DiagnosticLog)
        every { com.openautolink.app.diagnostics.DiagnosticLog.i(any(), any()) } answers {
            if (firstArg<String>() == "carplay-audio") receipts.add(secondArg())
            Unit
        }
        val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + storeJob),
            produceFile = { java.io.File(temp.root, "settings.preferences_pb") })
        preferences = AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
            .apply { isAccessible = true }.newInstance(store)
        mockkObject(AppPreferences.Companion)
        every { AppPreferences.getInstance(any()) } returns preferences
        preferences.setDirectTransport(AppPreferences.DIRECT_TRANSPORT_USB)
        preferences.setCarPlaySettings(CarPlaySettings(enabled = true, autoScale = false,
            autoResolution = false, fitToWindow = false, resolution = "720p", videoCodec = "h264"))
        mockkObject(SessionManager.Companion, PhoneDiscovery.Companion, CarPlaySessionLeases.Companion,
            AaWirelessBtControl, IgnitionMonitor, CarPlayVideoQualification)
        aaState.value = if (explicitFirst) SessionState.CONNECTING else SessionState.IDLE
        val session = mockk<SessionManager>(relaxed = true) {
            every { sessionState } returns aaState
            every { reconnectAttempt } returns MutableStateFlow(0)
            every { wakeEvents } returns kotlinx.coroutines.flow.MutableSharedFlow()
        }
        coEvery { session.stop() } answers { duringAaRetire(); aaState.value = SessionState.IDLE }
        every { SessionManager.getInstance(any(), any(), any()) } returns session
        val discovery = mockk<PhoneDiscovery>(relaxed = true) {
            every { phones } returns MutableStateFlow(emptyList())
            every { isSweeping } returns MutableStateFlow(false)
            every { sweepProgress } returns MutableStateFlow("")
        }
        every { PhoneDiscovery.getInstance(any()) } returns discovery
        every { CarPlaySessionLeases.forContext(any()) } returns CarPlaySessionLeaseSource.NONE
        every { IgnitionMonitor.isOffOrLocked() } returns false
        every { CarPlayVideoQualification.androidProbe() } returns VideoDecoderProbe { "fixture-avc" }
        coEvery { AaWirelessBtControl.retireForCarPlay() } just Runs
        every { usb.deviceList } answers { HashMap(census) }
        every { usb.hasPermission(apple) } answers { granted }
        every { usb.openDevice(any()) } returns null
        if (timeout) every { usb.openDevice(any()) } throws java.util.concurrent.TimeoutException()
        mockkStatic(VpnService::class)
        every { VpnService.prepare(any()) } returns null
        every { usb.requestPermission(apple, any<PendingIntent>()) } answers {
            requests++
            duringRequest()
            granted = nextGrant
            val intent = org.robolectric.Shadows.shadowOf(secondArg<PendingIntent>()).savedIntent
            // Deliver the real generation-qualified platform result, including denial.
            val reply = Intent(intent).putExtra(UsbManager.EXTRA_DEVICE, apple)
                .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, granted)
            val capability = secondArg<PendingIntent>()
            capability.send(app, 0, reply) // Test main thread drains the actual Android callback.
        }
        vm = ProjectionViewModel(app)
        vm.onCarPlaySurfaceAvailable(surface)
        vm.startProjectionOnce()
        if (explicitFirst) {
            await("Busy AA must expose Switch/Stay without permission") { vm.usbHotPlugPrompt.value != null }
            assertEquals(0, requests)
            vm.acceptUsbHotPlug(vm.usbHotPlugPrompt.value!!.generation)
        }
        await("Initial automatic permission request must occur") { requests == 1 }
        await("Initial denied attempt must fully retire") {
            UsbConnectionManager.connectionState.value == UsbConnectionState.AWAITING_USER_SELECTION &&
                !vm.carPlayState.value.selected
        }
        val expectedReceipt = if (timeout) "CarPlay USB stage=failed reason=TIMEOUT"
            else "CarPlay USB stage=usb-permission result=denied"
        assertTrue("Initial real runtime must report $expectedReceipt, not a fixture/setup failure: $receipts",
            receipts.any { it.contains(expectedReceipt) })
        manager = field("unifiedUsbManager") as UsbConnectionManager
        await("Wait for the actual USB override clearing boundary, not only CP deselection") {
            (field("usbSwitchPolicy") as UsbHotPlugSwitchPolicy).generation == null
        }
    }
    private fun select() {
        // Same production entry used by ProjectionScreen's picker onClick.
        UsbConnectionManager.selectDevice(UsbConnectionManager.availableDevices.value.single { it.deviceName == apple.deviceName })
    }
    private fun settled() = !vm.carPlayState.value.selected &&
        UsbConnectionManager.connectionState.value == UsbConnectionState.AWAITING_USER_SELECTION
    private fun automaticRetry(expectedRequests: Int) {
        val blocked = receipts.count { it.contains("CarPlay USB stage=retry result=blocked") }
        vm.reconnectCarPlay() // Generic rescan/rebuild is NOT a new exact picker selection.
        await("Automatic rebuilt runtime must still hit the original denial/failure fence") {
            receipts.count { it.contains("CarPlay USB stage=retry result=blocked") } > blocked
        }
        await("Automatic blocked attempt must fully retire", ::settled)
        assertEquals("No automatic permission prompt storm", expectedRequests, requests)
    }
    @After fun cleanup() {
        if (::vm.isInitialized) {
            val job = vm.viewModelScope.coroutineContext[Job]!!
            job.cancel()
            await("ViewModel coroutines must retire before removing mocks") { job.isCompleted }
            (field("unifiedUsbManager") as? UsbConnectionManager)?.stop()
            val coordinator = field("carPlayCoordinator") as CarPlayCoordinator
            if (vm.carPlayState.value.selected) {
                val retired = java.util.concurrent.CountDownLatch(1)
                coordinator.returnToAndroidAuto { retired.countDown() }
                assertTrue("Exact coordinator retirement before removing platform mocks",
                    retired.await(15, java.util.concurrent.TimeUnit.SECONDS))
            }
            coordinator.close()
        }
        runBlocking { storeJob.cancelAndJoin() }
        unmockkAll(); Dispatchers.resetMain()
    }
    @Test fun realUsbPendingHandoffClaimsFenceBeforeAaRetireAndReleasesOnlyAfterFailureClearing() {
        val boundary=java.util.concurrent.atomic.AtomicInteger()
        duringAaRetire={
            if(!CarPlayOwnership.process.blocksAndroidAuto) {
                val overlap=com.shilapi.xcertplay.media.MicrophoneCaptureFence.tryDiagnostic()
                overlap?.release()
                assertNull("real pending USB must claim before AA retire, not only CP runtime bootstrap",overlap)
                boundary.incrementAndGet()
            }
        }
        duringRequest={
            val overlap=com.shilapi.xcertplay.media.MicrophoneCaptureFence.tryDiagnostic()
            overlap?.release();assertNull("permission dispatch retains real USB/CP claims",overlap)
        }
        setup()
        assertTrue(boundary.get()>0)
        duringAaRetire={}
        var lease: com.shilapi.xcertplay.media.MicrophoneCaptureFence.Lease? = null
        try {
            // generation=null is published before cleared() releases the real capture claims.
            // Await acquisition itself, not that earlier publication; retain the exact lease.
            await("real exact failure clearing retires USB+CP claims") {
                if (lease == null) lease = com.shilapi.xcertplay.media.MicrophoneCaptureFence.tryDiagnostic()
                lease != null
            }
            assertNotNull("real exact failure clearing retires USB+CP claims", lease)
        } finally { lease?.release() }
    }
    @Test fun microphoneDiagnosticRejectsClaimedUsbWhileAaStillIdleAndUntilRetirement() = runBlocking {
        setup()
        aaState.value = SessionState.IDLE
        assertTrue(vm.microphoneDiagnosticEligible())
        val policy = field("usbSwitchPolicy") as UsbHotPlugSwitchPolicy
        // Real admission policy reproduces the pre-transport AOA interval; platform services are fixtures.
        assertTrue(policy.accept(987654L,
            resolve = { UsbHotPlugSwitchPolicy.Protocol.ANDROID_AUTO }, eligible = { true },
            claimed = {}, retire = {}, admit = { true }, cleared = {}, restore = {}))
        assertEquals(SessionState.IDLE, aaState.value)
        try {
            assertFalse("Claimed USB setup must block capture before AA CONNECTING", vm.microphoneDiagnosticEligible())
            try {
                policy.finish(987654L, retire = { throw IllegalStateException("fixture retained retirement") },
                    cleared = {}, restore = {})
                fail("Retirement fixture must fail")
            } catch (_: IllegalStateException) { }
            assertFalse("Failed USB retirement must retain diagnostic exclusion", vm.microphoneDiagnosticEligible())
        } finally { policy.finish(987654L, retire = {}, cleared = {}, restore = {}) }
        assertTrue(vm.microphoneDiagnosticEligible())
        @Suppress("UNCHECKED_CAST")
        val cp = field("_carPlayState") as MutableStateFlow<CarPlayUiState>
        cp.value = CarPlayUiState(selected = true, busy = false)
        assertTrue("Fully retired selected CarPlay must remain testable", vm.microphoneDiagnosticEligible())
        cp.value = CarPlayUiState()
    }

    @Test fun wiredTimeoutRetirementPreservesActionableFailureAfterAndroidOwnershipRestored() {
        setup(timeout = true)
        assertNotNull("Automatic USB restoration must not erase the original timeout guidance", vm.carPlayState.value.failure)
        assertTrue(vm.carPlayState.value.failure!!.userGuidance.contains("timed out"))
        assertFalse(vm.carPlayState.value.selected)
        assertFalse(vm.carPlayState.value.busy)
        verify(exactly = 1) { usb.openDevice(apple) }
        assertEquals(1, requests)
    }

    @Test fun normalAndroidDispatchDoesNotEraseTheRestoredWiredFailure() {
        setup(timeout = true)
        val failure = vm.carPlayState.value.failure
        assertNotNull(failure)
        val session = field("sessionManager") as SessionManager
        val dispatched = java.util.concurrent.atomic.AtomicBoolean()
        every { session.setDefaultPhoneName(any()) } answers { dispatched.set(true); Unit }
        runBlocking { preferences.setDirectTransport(AppPreferences.DIRECT_TRANSPORT_WPP) }
        vm.connect() // Same production normal-AA dispatch used by restoreNormalProjection.
        await("Normal AA must reach its session configuration boundary") { dispatched.get() }
        await("Normal AA dispatch must finish without consuming the CP failure receipt") { field("connectInFlight") == false }
        assertSame(failure, vm.carPlayState.value.failure)
        assertFalse(vm.carPlayState.value.selected)
        assertEquals(1, requests)
        verify(exactly = 1) { usb.openDevice(apple) }
    }

    @Test fun fencedSameIphoneSelectionExplainsFailureWithoutSwitchOrRetiringAndroidAgain() {
        setup(timeout = true)
        val session = field("sessionManager") as SessionManager
        assertTrue(vm.isUsbCarPlayRecoveryBlocked(UsbConnectionManager.availableDevices.value.single()))
        repeat(3) {
            vm.dismissCarPlayFailure()
            aaState.value = SessionState.CONNECTING
            clearMocks(session, answers = false, recordedCalls = true)
            select()
            await("A fenced attachment must explain recovery, not disappear") {
                vm.usbHotPlugPrompt.value != null || vm.carPlayState.value.failure != null
            }
            val prompt = vm.usbHotPlugPrompt.value
            if (prompt != null) {
                vm.acceptUsbHotPlug(prompt.generation)
                await("Old blocked handoff must settle for a meaningful RED") {
                    vm.carPlayState.value.failure != null && !vm.carPlayState.value.selected
                }
            }
            assertNull("Fenced same-iPhone selection must not offer a destructive Switch", prompt)
            assertFalse(vm.carPlayState.value.selected)
            coVerify(exactly = 0) { session.stop() }
            assertEquals(SessionState.CONNECTING, aaState.value)
            assertEquals(1, requests)
            verify(exactly = 1) { usb.openDevice(apple) }
            vm.returnToAndroidAuto() // Already restored: acknowledge without touching healthy AA.
            assertNull(vm.carPlayState.value.failure)
            assertTrue("Dismissal must not reopen the fenced attachment's recovery picker",
                vm.isUsbCarPlayRecoveryBlocked(UsbConnectionManager.availableDevices.value.single()))
            vm.reconnectCarPlay()
            await("Generic Retry must explain the same fence without another handoff") { vm.carPlayState.value.failure != null }
            coVerify(exactly = 0) { session.stop() }
            assertEquals(SessionState.CONNECTING, aaState.value)
        }
    }

    @Test fun failedAttachmentDoesNotHideFreshEnumerationAndStayDoesNotAuthorizeIt() {
        setup(timeout = true)
        val stale = UsbConnectionManager.availableDevices.value.single()
        census.remove(apple.deviceName)
        app.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, apple))
        await("Detach must remove the failed picker attachment") { UsbConnectionManager.availableDevices.value.isEmpty() }
        val fresh = mockk<UsbDevice>(relaxed = true) {
            every { deviceName } returns "/dev/bus/usb/008/$enumerationId"
            every { deviceId } returns enumerationId + 100000
            every { vendorId } returns 0x05ac; every { productId } returns 0x12a8
            every { configurationCount } returns 1; every { getConfiguration(0) } returns configuration
            every { interfaceCount } returns 1; every { getInterface(0) } returns mux
        }
        vm.dismissCarPlayFailure()
        aaState.value = SessionState.CONNECTING
        val session = field("sessionManager") as SessionManager
        clearMocks(session, answers = false, recordedCalls = true)
        census[fresh.deviceName] = fresh
        app.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, fresh))
        await("A fresh exact enumeration must still offer Switch/Stay") { vm.usbHotPlugPrompt.value != null }
        assertFalse(vm.isUsbCarPlayRecoveryBlocked(stale))
        assertFalse(vm.isUsbCarPlayRecoveryBlocked(UsbConnectionManager.availableDevices.value.single()))
        vm.stayOnCurrentProjection(vm.usbHotPlugPrompt.value!!.generation)
        assertNull(vm.usbHotPlugPrompt.value)
        assertEquals(SessionState.CONNECTING, aaState.value)
        coVerify(exactly = 0) { session.stop() }
        assertEquals(1, requests)
        verify(exactly = 1) { usb.openDevice(any()) }
    }

    @Test fun newAdmittedSelectionClearsOldGuidanceBeforeAwaitedAndroidRetirement() {
        setup()
        assertNotNull(vm.carPlayState.value.failure)
        val session = field("sessionManager") as SessionManager
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        coEvery { session.stop() } coAnswers {
            entered.countDown()
            withContext(Dispatchers.IO) { check(release.await(15, java.util.concurrent.TimeUnit.SECONDS)) }
            aaState.value = SessionState.IDLE
        }
        try {
            select()
            await("New valid selection must reach held real handoff retirement") { entered.count == 0L }
            assertNull("A newly claimed selection must clear stale guidance before coordinator.connect", vm.carPlayState.value.failure)
            assertEquals("No permission work before held exact retirement", 1, requests)
        } finally {
            release.countDown()
            await("New exact owner must finish permission denial and retire") { requests == 2 && settled() }
        }
    }

    @Test fun deniedAutomaticAttemptThenExplicitPickerSelectionReachesFreshPermissionWait() {
        setup()
        assertFalse("Actual permission denial remains reachable through explicit picker recovery",
            vm.isUsbCarPlayRecoveryBlocked(UsbConnectionManager.availableDevices.value.single()))
        nextGrant = false
        select()
        await("Explicit attempt must reach either fresh await or the old blocking guard") {
            requests == 2 || receipts.any { it.contains("CarPlay USB stage=retry result=blocked") }
        }
        assertEquals("Explicit same-enumeration selection must request permission again, not hit retry blocked", 2, requests)
        await("Second denial must retire without an automatic prompt storm", ::settled)
        assertEquals(2, receipts.count { it.contains("CarPlay USB stage=usb-permission result=denied") })
        repeat(3) { automaticRetry(2) }
        assertFalse(granted)
        verify(exactly = 0) { usb.openDevice(any()) }
    }

    @Test fun explicitRetryGrantReachesRealOpenBoundaryAndCannotOverrideSubsequentTransportFailure() {
        setup()
        nextGrant = true
        select()
        await("New explicit attempt must obtain a fresh platform grant") { requests == 2 }
        await("Null USB open sentinel must retire the real runtime", ::settled)
        assertTrue(receipts.any { it.contains("CarPlay USB stage=usb-permission result=granted") })
        verify(exactly = 1) { usb.openDevice(apple) }
        vm.dismissCarPlayFailure()
        select()
        await("Even explicit selection must explain the subsequent setup fence before handoff") {
            vm.carPlayState.value.failure != null
        }
        assertFalse("Preflight must not instantiate another receiver", vm.carPlayState.value.selected)
        verify(exactly = 1) { usb.openDevice(apple) }
        assertEquals(2, requests)
    }

    @Test fun originalExplicitSwitchIntentIsConsumedBeforeItsOwnDenialAndCannotAuthorizeRebuild() {
        setup(explicitFirst = true)
        repeat(3) { automaticRetry(1) }
        select()
        await("Only a NEW explicit selection may retry the denied original explicit attempt") { requests == 2 }
        await("New denial must retire", ::settled)
        verify(exactly = 0) { usb.openDevice(any()) }
    }

    @Test fun cancellationWhileFreshRetryGrantIsArrivingCannotOpenDevice() {
        setup()
        nextGrant = true
        duringRequest = { vm.stopCarPlay() }
        select()
        await("Fresh request must start under the explicit owner") { requests == 2 }
        await("Canceled exact owner must fully retire") { !vm.carPlayState.value.selected }
        verify(exactly = 0) { usb.openDevice(any()) }
    }

    @Test fun detachWhileFreshRetryGrantIsArrivingCannotOpenDevice() {
        setup()
        nextGrant = true
        duringRequest = {
            census.remove(apple.deviceName)
            app.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, apple))
        }
        select()
        await("Fresh request must start before detach") { requests == 2 }
        await("Detached exact owner must fully retire") { !vm.carPlayState.value.selected }
        verify(exactly = 0) { usb.openDevice(any()) }
    }

    @Test fun selectionRetiredWhileRealFactoryWaitsForSurfaceCannotAuthorizeLateRuntime() {
        setup()
        vm.onCarPlaySurfaceDestroyed(surface)
        select()
        await("Actual coordinator must admit the selection before Surface becomes available") {
            vm.carPlayState.value.selected && vm.carPlayState.value.busy
        }
        await("Real ViewModel factory must actually be blocked on its missing Surface") {
            Thread.getAllStackTraces().any { (thread, stack) ->
                thread.name == "oal-carplay-owner" && stack.any {
                    it.className == ProjectionViewModel::class.java.name &&
                        it.methodName == "createProjectionCarPlayRuntime"
                }
            }
        }
        manager.stop()
        vm.onCarPlaySurfaceAvailable(surface)
        await("Late factory/owner must retire without requesting permission") { !vm.carPlayState.value.selected }
        assertEquals(1, requests)
        verify(exactly = 0) { usb.openDevice(any()) }
    }
}

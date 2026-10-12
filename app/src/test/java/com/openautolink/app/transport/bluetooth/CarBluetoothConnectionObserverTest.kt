package com.openautolink.app.transport.bluetooth

import android.Manifest
import android.app.Application
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import io.mockk.*
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CarBluetoothConnectionObserverTest {
    @Test fun closedObserverCannotRestartBeforeCancelledCollectorFinishes() = runTest {
        val app = mockk<Application>(relaxed = true)
        val manager = mockk<BluetoothManager>()
        every { app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) } returns PackageManager.PERMISSION_GRANTED
        every { app.getSystemService(BluetoothManager::class.java) } returns manager
        every { manager.adapter } returns mockk<BluetoothAdapter>()
        val observer = CarBluetoothConnectionObserver(app, MutableStateFlow(true), backgroundScope, sdkInt = 33)
        observer.start()
        runCurrent()
        observer.close()
        observer.start()
        runCurrent()
        verify(exactly = 1) { app.registerReceiver(any(), any(), any(), any(), any()) }
        verify(exactly = 1) { app.unregisterReceiver(any()) }
        assertEquals(CarBluetoothUnknown.DISABLED, observer.observation.value.unknown)
    }

    @Test fun android12RegistrationAndScopeCancellationReleaseAllEffects() = runTest {
        val app = mockk<Application>(relaxed = true)
        val manager = mockk<BluetoothManager>()
        every { app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) } returns PackageManager.PERMISSION_GRANTED
        every { app.getSystemService(BluetoothManager::class.java) } returns manager
        every { manager.adapter } returns mockk<BluetoothAdapter>()
        val ownedScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val observer = CarBluetoothConnectionObserver(app, MutableStateFlow(true), ownedScope, sdkInt = 32)
        observer.start()
        observer.start()
        runCurrent()
        verify(exactly = 1) { app.registerReceiver(any(), any(), Manifest.permission.BLUETOOTH_CONNECT, null) }
        verify(exactly = 0) { app.registerReceiver(any(), any(), any(), any(), any()) }
        ownedScope.cancel()
        runCurrent()
        verify(exactly = 1) { app.unregisterReceiver(any()) }
        verify(exactly = 1) { app.unregisterActivityLifecycleCallbacks(any()) }
        assertEquals(CarBluetoothUnknown.DISABLED, observer.observation.value.unknown)
        observer.close()
    }

    @Test fun failedPreferenceCollectorReleasesListenersAndRemainsExplicitlyUnknown() = runTest {
        val app = mockk<Application>(relaxed = true)
        val manager = mockk<BluetoothManager>()
        every { app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) } returns PackageManager.PERMISSION_GRANTED
        every { app.getSystemService(BluetoothManager::class.java) } returns manager
        every { manager.adapter } returns mockk<BluetoothAdapter>()
        val observer = CarBluetoothConnectionObserver(app, flow {
            emit(true)
            throw IllegalStateException("preference unavailable")
        }, backgroundScope, sdkInt = 33)
        observer.start()
        runCurrent()
        assertEquals(CarBluetoothUnknown.UNAVAILABLE, observer.observation.value.unknown)
        verify(exactly = 1) { app.unregisterReceiver(any()) }
        assertTrue(observer.observation.value.devices.isEmpty())
        observer.close()
    }

    @Test fun applicationRegistersIndependentProcessObserverWithoutChangingAaInit() {
        val relative = "src/main/java/com/openautolink/app/OalApplication.kt"
        val file = listOf(File(relative), File("app/$relative")).first { it.isFile }
        val source = file.readText()
        val onCreate = source.substringAfter("override fun onCreate()").substringBefore("private fun loadPreviousCrash")
        assertTrue(onCreate.contains("CarBluetoothConnectionObserver("))
        assertTrue(onCreate.contains("carPlayEnabled"))
        assertTrue(onCreate.contains("it.start()"))
        assertTrue(onCreate.contains("AaWirelessBtControl.init(this)"))
        assertTrue(source.contains("carBluetoothConnectionObserver?.close()"))
    }

    @Test fun noisyBroadcastsBoundPrivateDeviceRetentionUntilPowerReset() = runTest {
        val app = mockk<Application>(relaxed = true)
        val manager = mockk<BluetoothManager>()
        every { app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) } returns PackageManager.PERMISSION_GRANTED
        every { app.getSystemService(BluetoothManager::class.java) } returns manager
        every { manager.adapter } returns mockk<BluetoothAdapter>()
        val callback = slot<BroadcastReceiver>()
        every { app.registerReceiver(capture(callback), any(), any(), any(), any()) } returns null
        val observer = CarBluetoothConnectionObserver(app, MutableStateFlow(true), backgroundScope, sdkInt = 33)
        observer.start()
        runCurrent()
        repeat(CarBluetoothProfileState.MAX_DEVICES + 10) {
            val device = mockk<BluetoothDevice>()
            val event = mockk<Intent> {
                every { getAction() } returns CarBluetoothConnectionObserver.HFP_CLIENT_ACTION
                every { getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) } returns device
                every { getIntExtra(BluetoothProfile.EXTRA_STATE, any()) } returns BluetoothProfile.STATE_CONNECTED
                every { getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, any()) } returns BluetoothProfile.STATE_CONNECTING
            }
            callback.captured.onReceive(app, event)
        }
        val retained = observer.javaClass.getDeclaredField("devices").apply { isAccessible = true }.get(observer) as Map<*, *>
        assertTrue(retained.size <= CarBluetoothProfileState.MAX_DEVICES)
        assertEquals(CarBluetoothUnknown.CAPACITY, observer.observation.value.unknown)
        assertTrue(observer.observation.value.devices.isEmpty())
        observer.close()
    }

    @Test fun unavailablePayloadRevokesEvidenceWithoutThrowingOrRemovingListener() = runTest {
        val app = mockk<Application>(relaxed = true)
        val manager = mockk<BluetoothManager>()
        every { app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) } returns PackageManager.PERMISSION_GRANTED
        every { app.getSystemService(BluetoothManager::class.java) } returns manager
        every { manager.adapter } returns mockk<BluetoothAdapter>()
        val callback = slot<BroadcastReceiver>()
        every { app.registerReceiver(capture(callback), any(), any(), any(), any()) } returns null
        val observer = CarBluetoothConnectionObserver(app, MutableStateFlow(true), backgroundScope, sdkInt = 33)
        observer.start()
        runCurrent()
        val malformed = mockk<Intent> {
            every { getAction() } returns CarBluetoothConnectionObserver.HFP_CLIENT_ACTION
            every { getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) } returns null
        }
        callback.captured.onReceive(app, malformed)
        assertEquals(CarBluetoothUnknown.MALFORMED_EVENT, observer.observation.value.unknown)
        every { malformed.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) } throws IllegalArgumentException("do not log device")
        callback.captured.onReceive(app, malformed)
        assertEquals(CarBluetoothUnknown.MALFORMED_EVENT, observer.observation.value.unknown)
        verify(exactly = 0) { app.unregisterReceiver(any()) }
        observer.close()
    }

    @Test fun foregroundPermissionRevocationUnregistersAndReportsUnknown() = runTest {
        val app = mockk<Application>(relaxed = true)
        val manager = mockk<BluetoothManager>()
        every { app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) } returns PackageManager.PERMISSION_GRANTED
        every { app.getSystemService(BluetoothManager::class.java) } returns manager
        every { manager.adapter } returns mockk<BluetoothAdapter>()
        val lifecycle = slot<Application.ActivityLifecycleCallbacks>()
        every { app.registerActivityLifecycleCallbacks(capture(lifecycle)) } just Runs
        val observer = CarBluetoothConnectionObserver(app, MutableStateFlow(true), backgroundScope, sdkInt = 33)
        observer.start()
        runCurrent()
        every { app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) } returns PackageManager.PERMISSION_DENIED
        lifecycle.captured.onActivityResumed(mockk<Activity>())
        assertEquals(CarBluetoothUnknown.PERMISSION_MISSING, observer.observation.value.unknown)
        assertTrue(observer.observation.value.devices.isEmpty())
        verify(exactly = 1) { app.unregisterReceiver(any()) }
        verify(exactly = 1) { app.unregisterActivityLifecycleCallbacks(any()) }
        observer.close()
    }

    @Test fun gmProfileBroadcastsReportOpaqueEvidenceWithoutSelectingAndPowerRevokes() = runTest {
        val app = mockk<Application>(relaxed = true)
        val manager = mockk<BluetoothManager>()
        every { app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) } returns PackageManager.PERMISSION_GRANTED
        every { app.getSystemService(BluetoothManager::class.java) } returns manager
        every { manager.adapter } returns mockk<BluetoothAdapter>()
        val callback = slot<BroadcastReceiver>()
        every { app.registerReceiver(capture(callback), any(), any(), any(), any()) } returns null
        val enabled = MutableStateFlow(true)
        val observer = CarBluetoothConnectionObserver(app, enabled, backgroundScope, sdkInt = 33)
        observer.start()
        runCurrent()
        val phone1 = mockk<BluetoothDevice>(relaxed = true)
        val phone2 = mockk<BluetoothDevice>(relaxed = true)
        fun event(action: String, device: BluetoothDevice, state: Int, previous: Int): Intent = mockk {
            every { getAction() } returns action
            every { getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) } returns device
            every { getIntExtra(BluetoothProfile.EXTRA_STATE, any()) } returns state
            every { getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, any()) } returns previous
        }
        val hfp = event(CarBluetoothConnectionObserver.HFP_CLIENT_ACTION, phone1,
            BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING)
        callback.captured.onReceive(app, hfp)
        val connected = observer.observation.value
        assertEquals(setOf("local-1"), connected.observedConnectedDevices)
        callback.captured.onReceive(app, hfp)
        assertEquals(connected, observer.observation.value)
        callback.captured.onReceive(app, event(CarBluetoothConnectionObserver.A2DP_SINK_ACTION,
            phone2, BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING))
        assertTrue(observer.observation.value.ambiguous)
        verify(exactly = 0) { phone1.address }
        verify(exactly = 0) { phone1.name }
        val power = mockk<Intent> {
            every { getAction() } returns BluetoothAdapter.ACTION_STATE_CHANGED
            every { getIntExtra(BluetoothAdapter.EXTRA_STATE, any()) } returns BluetoothAdapter.STATE_OFF
        }
        callback.captured.onReceive(app, power)
        assertTrue(observer.observation.value.devices.isEmpty())
        assertEquals(CarBluetoothUnknown.ADAPTER_OFF, observer.observation.value.unknown)
        enabled.value = false
        runCurrent()
        callback.captured.onReceive(app, hfp)
        assertEquals(CarBluetoothUnknown.DISABLED, observer.observation.value.unknown)
        assertTrue(observer.observation.value.devices.isEmpty())
        observer.close()
    }

    @Test fun processCollectorIsDefaultOffRegistersExportedPermissionFilteredAndCloses() = runTest {
        val app = mockk<Application>(relaxed = true)
        val manager = mockk<BluetoothManager>()
        every { app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) } returns PackageManager.PERMISSION_GRANTED
        every { app.getSystemService(BluetoothManager::class.java) } returns manager
        every { manager.adapter } returns mockk<BluetoothAdapter>()
        val enabled = MutableStateFlow(false)
        val observer = CarBluetoothConnectionObserver(app, enabled, backgroundScope, sdkInt = 33)
        observer.start()
        runCurrent()
        verify(exactly = 0) { app.checkSelfPermission(any()) }
        verify(exactly = 0) { app.registerReceiver(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { app.registerActivityLifecycleCallbacks(any()) }
        enabled.value = true
        runCurrent()
        verify(exactly = 1) {
            app.registerReceiver(any<BroadcastReceiver>(), any<IntentFilter>(),
                Manifest.permission.BLUETOOTH_CONNECT, null, Context.RECEIVER_EXPORTED)
        }
        assertEquals(CarBluetoothUnknown.WAITING_FOR_EVENTS, observer.observation.value.unknown)
        enabled.value = false
        runCurrent()
        verify(exactly = 1) { app.unregisterReceiver(any()) }
        verify(exactly = 1) { app.unregisterActivityLifecycleCallbacks(any()) }
        assertEquals(CarBluetoothUnknown.DISABLED, observer.observation.value.unknown)
        observer.close()
        runCurrent()
        verify(exactly = 1) { app.unregisterReceiver(any()) }
    }
}

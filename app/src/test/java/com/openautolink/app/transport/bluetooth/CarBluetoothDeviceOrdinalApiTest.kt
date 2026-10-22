package com.openautolink.app.transport.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real SDK32/33/34 Intent extraction through the registered callback, not a private helper. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [32, 33, 34], manifest = Config.NONE, application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CarBluetoothDeviceOrdinalApiTest {
    @Test fun actualSdkExtractionRejectsWrongTypesAndPreservesPrivateOrdinalsAcrossEpochs() = runTest {
        val app = mockk<Application>(relaxed = true)
        val manager = mockk<BluetoothManager>()
        every { app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) } returns PackageManager.PERMISSION_GRANTED
        every { app.getSystemService(BluetoothManager::class.java) } returns manager
        every { manager.adapter } returns mockk<BluetoothAdapter>()
        val callback = slot<BroadcastReceiver>()
        if (Build.VERSION.SDK_INT >= 33) {
            every { app.registerReceiver(capture(callback), any(), any(), any(), any()) } returns null
        } else {
            every { app.registerReceiver(capture(callback), any(), any(), any()) } returns null
        }
        data class Receipt(val event: String, val state: String, val previous: String, val device: Int)
        val rows = mutableListOf<Receipt>()
        // Deliberately omit sdkInt: verify the production default is the actual platform SDK.
        val observer = CarBluetoothConnectionObserver(app, MutableStateFlow(true), backgroundScope,
            recorderEvidence = { event, state, previous, device -> rows += Receipt(event, state, previous, device) })
        val phone = mockk<BluetoothDevice>(relaxed = true)
        val otherPhone = mockk<BluetoothDevice>(relaxed = true)
        val audio = Intent(CarBluetoothConnectionObserver.HFP_AUDIO_ACTION)
            .putExtra(BluetoothProfile.EXTRA_STATE, 2)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, phone)
        fun deliver(intent: Intent) = callback.captured.onReceive(app, intent)
        fun audioRows() = rows.filter { it.event == "hfp-audio" }
        observer.start()
        runCurrent()
        deliver(audio)
        deliver(audio)
        assertEquals(listOf(1, 1), audioRows().map { it.device })
        deliver(Intent(audio).putExtra(BluetoothDevice.EXTRA_DEVICE, otherPhone))
        assertEquals(2, audioRows().last().device)
        deliver(Intent(audio).apply { removeExtra(BluetoothDevice.EXTRA_DEVICE) })
        assertEquals(0, audioRows().last().device)

        val beforeWrong = audioRows().size
        deliver(Intent(audio).putExtra(BluetoothDevice.EXTRA_DEVICE, Bundle()))
        if (Build.VERSION.SDK_INT >= 33) {
            // Typed Android API rejects a non-BluetoothDevice as null.
            assertEquals(beforeWrong + 1, audioRows().size)
            assertEquals(0, audioRows().last().device)
        } else {
            // Legacy generic cast is caught by receive; never an escaped exception or ordinal.
            assertEquals(beforeWrong, audioRows().size)
            assertEquals(CarBluetoothUnknown.MALFORMED_EVENT, observer.observation.value.unknown)
        }
        deliver(audio)
        assertEquals(1, audioRows().last().device)

        val epochs = rows.count { it.event == "epoch" }
        deliver(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF))
        assertEquals(epochs + 1, rows.count { it.event == "epoch" })
        val poweredOffRows = audioRows().size
        deliver(audio)
        assertEquals(poweredOffRows, audioRows().size)
        deliver(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON))
        deliver(Intent(audio).putExtra(BluetoothDevice.EXTRA_DEVICE, otherPhone))
        assertEquals(1, audioRows().last().device)

        val denied = mockk<Intent> {
            every { action } returns CarBluetoothConnectionObserver.HFP_AUDIO_ACTION
            every { getIntExtra(BluetoothProfile.EXTRA_STATE, any()) } returns 2
            every { getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, any()) } returns 0
        }
        if (Build.VERSION.SDK_INT >= 33) {
            every { denied.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) } throws SecurityException("private detail")
        } else {
            @Suppress("DEPRECATION")
            every { denied.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) } throws SecurityException("private detail")
        }
        val beforeDenied = audioRows().size
        val epochsBeforeDenied = rows.count { it.event == "epoch" }
        deliver(denied)
        assertEquals(beforeDenied, audioRows().size)
        assertEquals(CarBluetoothUnknown.PERMISSION_MISSING, observer.observation.value.unknown)
        assertEquals(epochsBeforeDenied + 1, rows.count { it.event == "epoch" })
        verify(exactly = 1) { app.unregisterReceiver(any()) }
        // A retained retired callback cannot revive this epoch or reread the payload.
        deliver(audio)
        assertEquals(beforeDenied, audioRows().size)
        verify(exactly = 0) { phone.name }
        verify(exactly = 0) { phone.address }
        verify(exactly = 0) { otherPhone.name }
        verify(exactly = 0) { otherPhone.address }
        assertFalse(rows.toString().contains("private detail"))
        observer.close()
    }
}

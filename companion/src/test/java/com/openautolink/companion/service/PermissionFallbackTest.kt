package com.openautolink.companion.service

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import com.openautolink.companion.autostart.WifiJobService
import com.openautolink.companion.wifi.CarWifiEntry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 32, 34], application = Application::class,
    shadows = [PermissionFallbackTest.Adapter::class, PermissionFallbackTest.Wifi::class])
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class PermissionFallbackTest {
    companion object {
        var deniedBluetooth = false
        var bluetoothName: String? = "  Test phone  "
        var deniedWifi = false
        var visibleWifi = false
        var bluetoothReads = 0
        var wifiReads = 0
    }
    @Implements(BluetoothAdapter::class)
    class Adapter : org.robolectric.shadows.ShadowBluetoothAdapter() {
        @Implementation override fun getName(): String? {
            bluetoothReads++
            if (deniedBluetooth) throw SecurityException("revoked Bluetooth permission")
            return bluetoothName
        }
    }
    @Implements(WifiManager::class)
    class Wifi : org.robolectric.shadows.ShadowWifiManager() {
        @Implementation override fun getScanResults(): List<ScanResult> {
            wifiReads++
            if (deniedWifi) throw SecurityException("revoked scan permission")
            return if (visibleWifi) listOf(ScanResult().apply { SSID = "Bench car" }) else emptyList()
        }
    }
    private val app = RuntimeEnvironment.getApplication() as Application
    private fun readBluetoothName(): String? {
        val advertiser = TcpAdvertiser(app, object : TcpAdvertiser.StateListener {
            override fun onConnecting() = Unit
            override fun onProxyConnected() = Unit
            override fun onProxyDisconnected() = Unit
            override fun onLaunchTimeout() = Unit
        })
        // Constructor only: never starts a listener, network monitor, proxy or coroutine job.
        val method = TcpAdvertiser::class.java.getDeclaredMethod("localBluetoothName").apply { isAccessible = true }
        return method.invoke(advertiser) as String?
    }
    @Test fun BluetoothPermissionDenialKeepsNameOptionalAndLaterReadStillWorks() {
        bluetoothReads = 0; deniedBluetooth = true; bluetoothName = "  Test phone  "
        assertNull(readBluetoothName()); assertEquals(1, bluetoothReads)
        deniedBluetooth = false
        assertEquals("Test phone", readBluetoothName()); assertEquals(2, bluetoothReads)
        bluetoothName = "  "; assertNull(readBluetoothName())
        bluetoothName = null; assertNull(readBluetoothName())
    }
    @Test fun deniedScanCannotStartServiceAndVisibleScanStillStarts() {
        val prefs = app.getSharedPreferences("lint-permission-fixture", Context.MODE_PRIVATE)
        CarWifiEntry.saveAll(prefs, listOf(CarWifiEntry("Bench car", "")))
        val companion = WifiJobService.Companion
        val method = companion.javaClass.getDeclaredMethod("checkCarWifiScanAndStart", Context::class.java,
            android.content.SharedPreferences::class.java).apply { isAccessible = true }
        fun check(): Boolean = method.invoke(companion, app, prefs) as Boolean
        wifiReads = 0; deniedWifi = true; visibleWifi = true
        assertFalse(check()); assertEquals(1, wifiReads)
        assertNull(shadowOf(app).nextStartedService)
        deniedWifi = false; visibleWifi = false
        assertFalse(check()); assertEquals(2, wifiReads)
        assertNull(shadowOf(app).nextStartedService)
        visibleWifi = true
        assertTrue(check()); assertEquals(3, wifiReads)
        assertNotNull("Positive scan path must still start the real service intent", shadowOf(app).nextStartedService)
    }
}

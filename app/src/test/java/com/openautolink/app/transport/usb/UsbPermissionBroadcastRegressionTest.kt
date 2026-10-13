package com.openautolink.app.transport.usb

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.mockk.*
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], manifest = Config.NONE, application = Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UsbPermissionBroadcastRegressionTest {
    private val usb = mockk<UsbManager>(relaxed = true)
    private val filters = mutableListOf<IntentFilter>()
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getSystemService(name: String): Any? =
            if (name == Context.USB_SERVICE) usb else super.getSystemService(name)
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? {
            filters.add(filter)
            return super.registerReceiver(receiver, filter, flags)
        }
    }
    private var manager: UsbConnectionManager? = null
    private fun device(name: String) = mockk<UsbDevice>(relaxed = true) {
        every { deviceName } returns name
        every { vendorId } returns 0x22d9
        every { productId } returns 0x2764
    }
    private fun start(): UsbConnectionManager {
        every { usb.deviceList } returns hashMapOf()
        return UsbConnectionManager(context, TestScope(), {}).also { manager = it; it.start() }
    }
    private fun call(target: UsbConnectionManager, name: String, device: UsbDevice) {
        target.javaClass.getDeclaredMethod(name, UsbDevice::class.java).apply { isAccessible = true }
            .invoke(target, device)
    }
    @After fun cleanup() { manager?.stop(); unmockkAll() }

    @Test fun `actual Android permission capability matches a registered receiver`() {
        val target = start()
        val phone = device("/dev/bus/usb/002/008")
        val capability = slot<PendingIntent>()
        every { usb.requestPermission(phone, capture(capability)) } just Runs
        call(target, "requestPermissionOrConnect", phone)
        val result = shadowOf(capability.captured).savedIntent
        assertTrue("Android permission callback has no matching registered filter: ${result.data}",
            filters.any { it.match(context.contentResolver, result, false, "usb-regression") >= 0 })
        capability.captured.send(context, 0, Intent()
            .putExtra(UsbManager.EXTRA_DEVICE, phone)
            .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("USB permission denied", UsbConnectionManager.status.value)
    }

    @Test fun `old permission result cannot clear a new request for the same name`() {
        val target = start()
        val name = "/dev/bus/usb/002/008"
        val first = device(String(name.toCharArray()))
        val capability = slot<PendingIntent>()
        every { usb.requestPermission(any<UsbDevice>(), capture(capability)) } just Runs
        call(target, "requestPermissionOrConnect", first)
        val oldCapability = capability.captured
        target.failedAdmission()
        val second = device(String(name.toCharArray()))
        every { usb.deviceList } returns hashMapOf(name to second)
        target.retryCandidates()
        val currentCapability = capability.captured
        assertNotEquals(oldCapability, currentCapability)
        oldCapability.send(context, 0, Intent().putExtra(UsbManager.EXTRA_DEVICE, first)
            .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("Requesting USB permission...", UsbConnectionManager.status.value)
        currentCapability.send(context, 0, Intent().putExtra(UsbManager.EXTRA_DEVICE, second)
            .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("USB permission denied", UsbConnectionManager.status.value)
    }

    @Test fun `failed admission releases only its own pending permission`() {
        val target = start()
        val phone = device("/dev/bus/usb/002/008")
        call(target, "requestPermissionOrConnect", phone)
        target.failedAdmission()
        @Suppress("UNCHECKED_CAST")
        val pending = target.javaClass.getDeclaredField("pendingPermissionDevice")
            .apply { isAccessible = true }.get(target) as AtomicReference<String?>
        assertNull("Failed admission must release its permission guard", pending.get())
    }

    @Test fun `unrelated detach does not clear current permission`() {
        val target = start()
        val phone = device("/dev/bus/usb/002/008")
        call(target, "requestPermissionOrConnect", phone)
        call(target, "handleDeviceDetached", device("/dev/bus/usb/002/099"))
        @Suppress("UNCHECKED_CAST")
        val pending = target.javaClass.getDeclaredField("pendingPermissionDevice")
            .apply { isAccessible = true }.get(target) as AtomicReference<String?>
        assertEquals(phone.deviceName, pending.get())
    }

    @Test fun `detached parcel with equal but distinct device name clears pending permission`() {
        val target = start()
        val originalName = String("/dev/bus/usb/002/008".toCharArray())
        val detachedName = String(originalName.toCharArray())
        assertNotSame(originalName, detachedName)
        call(target, "requestPermissionOrConnect", device(originalName))
        call(target, "handleDeviceDetached", device(detachedName))
        @Suppress("UNCHECKED_CAST")
        val pending = target.javaClass.getDeclaredField("pendingPermissionDevice")
            .apply { isAccessible = true }.get(target) as AtomicReference<String?>
        assertNull("Detached device must not suppress permission for a new enumeration", pending.get())
        val replacement = device("/dev/bus/usb/002/015")
        call(target, "requestPermissionOrConnect", replacement)
        verify(exactly = 1) { usb.requestPermission(replacement, any<PendingIntent>()) }
    }
}

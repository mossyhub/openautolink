package com.openautolink.app.transport.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.mockk.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UsbUnifiedDispatchTest {
    private val usb = mockk<UsbManager>(relaxed = true)
    private val context = mockk<Context>(relaxed = true) {
        every { getSystemService(Context.USB_SERVICE) } returns usb
    }
    private val scope = TestScope()
    private val device = phone("/dev/bus/usb/001/002", 0x05ac, 0x12a8)
    private fun phone(name: String, vid: Int, pid: Int = 1) = mockk<UsbDevice>(relaxed = true) {
        every { deviceName } returns name
        every { vendorId } returns vid
        every { productId } returns pid
    }
    private fun running(manager: UsbConnectionManager): UsbConnectionManager = manager.apply {
        javaClass.getDeclaredField("isRunning").apply { isAccessible = true }.set(this, true)
    }
    private fun call(manager: UsbConnectionManager, method: String, device: UsbDevice) {
        manager.javaClass.getDeclaredMethod(method, UsbDevice::class.java)
            .apply { isAccessible = true }.invoke(manager, device)
    }
    private fun noAndroidCalls() {
        verify(exactly = 0) { usb.hasPermission(any<UsbDevice>()) }
        verify(exactly = 0) { usb.requestPermission(any<UsbDevice>(), any<PendingIntent>()) }
        verify(exactly = 0) { usb.openDevice(any()) }
    }
    @After fun cleanup() = unmockkAll()

    @Test fun `AOA utility also rejects Apple before opening a connection`() {
        assertFalse(UsbAccessoryMode.switchToAccessory(usb, device))
        noAndroidCalls()
    }

    @Test fun `disabled Apple never enters Android permission or AOA path`() {
        val manager = running(UsbConnectionManager(context, scope, {}))
        call(manager, "requestPermissionOrConnect", device)
        noAndroidCalls()
    }

    @Test fun `enabled Apple callback deduplicates attach and stale AA permission`() {
        var selections = 0
        val manager = running(UsbConnectionManager(context, scope, {}, onIphoneSelected = { selections++; true }))
        call(manager, "requestPermissionOrConnect", device)
        call(manager, "requestPermissionOrConnect", device)
        call(manager, "handleDeviceAttached", device)
        call(manager, "onPermissionGranted", device)
        assertEquals(1, selections)
        noAndroidCalls()
    }

    @Test fun `unrelated detach cannot retire Apple and reenum preserves exact owner`() {
        var retirements = 0
        var selections = 0
        val manager = running(UsbConnectionManager(context, scope, {},
            onIphoneSelected = { selections++; true }, onIphoneDetached = { _, _ -> retirements++ }))
        call(manager, "requestPermissionOrConnect", device)
        call(manager, "handleDeviceDetached", phone("unrelated", 0x18d1))
        scope.advanceTimeBy(3_001)
        scope.runCurrent()
        assertEquals(0, retirements)
        val transition = manager.iphoneReenumerationCallbacks(device)
        assertTrue(transition.first())
        assertFalse(transition.first()) // Cannot extend the window by rearming.
        call(manager, "handleDeviceDetached", device)
        scope.runCurrent()
        scope.advanceTimeBy(4_000)
        val newDevice = phone("/dev/bus/usb/001/003", 0x05ac)
        call(manager, "handleDeviceAttached", newDevice)
        assertFalse(transition.second(phone("/dev/bus/usb/002/003", 0x05ac)))
        assertEquals(0, retirements)
        assertTrue(transition.second(newDevice)) // Simulate runtime serial-qualified acknowledgement.
        call(manager, "handleDeviceDetached", device) // delayed detach from old enumeration
        scope.advanceTimeBy(3_001)
        scope.runCurrent()
        assertEquals(0, retirements)
        assertEquals(1, selections)
        call(manager, "handleDeviceDetached", newDevice)
        scope.runCurrent()
        scope.advanceTimeBy(3_001)
        scope.runCurrent()
        assertEquals(1, retirements)
        call(manager, "handleDeviceDetached", newDevice)
        assertEquals(1, retirements)
    }

    @Test fun `unverified Apple attach cannot cancel ordinary detach grace`() {
        var retirements = 0
        val manager = running(UsbConnectionManager(context, scope, {}, onIphoneSelected = { true },
            onIphoneDetached = { _, _ -> retirements++ }))
        call(manager, "requestPermissionOrConnect", device)
        call(manager, "handleDeviceDetached", device)
        scope.runCurrent()
        call(manager, "handleDeviceAttached", phone("/dev/bus/usb/001/003", 0x05ac))
        scope.advanceTimeBy(3_001); scope.runCurrent()
        assertEquals(1, retirements)
    }

    @Test fun `authorized transition expires at fifteen seconds and stale callbacks fail closed`() {
        var retirements = 0
        val manager = running(UsbConnectionManager(context, scope, {}, onIphoneSelected = { true },
            onIphoneDetached = { _, _ -> retirements++ }))
        call(manager, "requestPermissionOrConnect", device)
        val callbacks = manager.iphoneReenumerationCallbacks(device)
        val release = manager.iphoneRetirementAcknowledgement(device)
        assertTrue(callbacks.first())
        assertFalse(callbacks.second(phone("/dev/bus/usb/001/003", 0x05ac))) // no observed detach
        call(manager, "handleDeviceDetached", device)
        scope.runCurrent(); scope.advanceTimeBy(14_999); scope.runCurrent()
        assertEquals(0, retirements)
        scope.advanceTimeBy(2); scope.runCurrent()
        assertEquals(1, retirements)
        assertFalse(callbacks.second(phone("/dev/bus/usb/001/003", 0x05ac)))
        release()
        call(manager, "requestPermissionOrConnect", phone("/dev/bus/usb/001/004", 0x05ac))
        assertFalse(callbacks.first())
        assertFalse(callbacks.second(phone("/dev/bus/usb/001/005", 0x05ac)))
    }

    @Test fun `failed transition rejects polled replacement even before attach broadcast`() {
        var selections = 0
        val manager = running(UsbConnectionManager(context, scope, {}, onIphoneSelected = { selections++; true }))
        call(manager, "requestPermissionOrConnect", device)
        val release = manager.iphoneRetirementAcknowledgement(device)
        assertTrue(manager.iphoneReenumerationCallbacks(device).first())
        val replacement = phone("/dev/bus/usb/001/003", 0x05ac)
        every { usb.deviceList } returns hashMapOf(replacement.deviceName to replacement)
        release()
        call(manager, "requestPermissionOrConnect", replacement)
        assertEquals(1, selections)
    }

    @Test fun `Android replacement waits for full Apple retirement acknowledgement`() {
        var retired: (() -> Unit)? = null
        val manager = running(UsbConnectionManager(context, scope, {}, onIphoneSelected = { true },
            onIphoneDetached = { _, ack -> retired = ack }))
        call(manager, "requestPermissionOrConnect", device)
        call(manager, "handleDeviceDetached", device)
        val android = phone("android", 0x18d1)
        call(manager, "handleDeviceAttached", android)
        assertNotNull(retired)
        call(manager, "requestPermissionOrConnect", android)
        noAndroidCalls()
        retired!!()
        call(manager, "requestPermissionOrConnect", android)
        verify(exactly = 1) { usb.hasPermission(android) }
    }

    @Test fun `runtime failure release does not auto retry Apple and stale release cannot retire replacement`() {
        var selections = 0
        val manager = running(UsbConnectionManager(context, scope, {}, onIphoneSelected = { selections++; true }))
        call(manager, "requestPermissionOrConnect", device)
        val release = manager.iphoneRetirementAcknowledgement(device)
        release()
        call(manager, "requestPermissionOrConnect", device)
        assertEquals(1, selections)
        call(manager, "handleDeviceDetached", device)
        call(manager, "requestPermissionOrConnect", device)
        assertEquals(2, selections)
        release() // Old generation must not release second claim.
        call(manager, "requestPermissionOrConnect", device)
        assertEquals(2, selections)
    }

    @Test fun `stop retires Apple once and blocks restart until acknowledged`() {
        var retirements = 0
        var retired: (() -> Unit)? = null
        var selections = 0
        val manager = running(UsbConnectionManager(context, scope, {}, onIphoneSelected = { selections++; true },
            onIphoneDetached = { _, ack -> retirements++; retired = ack }))
        call(manager, "requestPermissionOrConnect", device)
        manager.stop()
        manager.stop()
        assertEquals(1, retirements)
        running(manager)
        call(manager, "requestPermissionOrConnect", device)
        assertEquals(1, selections)
        retired!!()
        val second = phone("second-apple", 0x05ac)
        call(manager, "requestPermissionOrConnect", second)
        assertEquals(2, selections)
        retired!!()
        call(manager, "requestPermissionOrConnect", second)
        assertEquals(2, selections)
    }

    @Test fun `permission denial fences duplicates and permits deliberate retry`() {
        mockkStatic(PendingIntent::class)
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setPackage(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setData(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Long>()) } answers { self as Intent }
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns mockk(relaxed = true)
        val manager = running(UsbConnectionManager(context, scope, {}))
        val android = phone("android", 0x18d1)
        call(manager, "requestPermissionOrConnect", android)
        val ownership = manager.javaClass.getDeclaredField("dispatchOwnership").apply { isAccessible = true }
            .get(manager) as UsbDispatchOwnership
        val generation = ownership.current()!!.generation
        val receiver = manager.javaClass.getDeclaredField("usbReceiver").apply { isAccessible = true }
            .get(manager) as BroadcastReceiver
        val denied = mockk<Intent> {
            every { action } returns "com.openautolink.app.USB_PERMISSION"
            every { getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) } returns android
            every { getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) } returns false
            every { getLongExtra("usb_generation", -1L) } returns generation
        }
        receiver.onReceive(context, denied)
        receiver.onReceive(context, denied)
        call(manager, "requestPermissionOrConnect", android)
        verify(exactly = 1) { usb.requestPermission(android, any<PendingIntent>()) }
        assertNull(ownership.current())
        every { usb.deviceList } returns hashMapOf("android" to android)
        manager.javaClass.getDeclaredMethod("onUserSelectedDevice", String::class.java)
            .apply { isAccessible = true }.invoke(manager, "android")
        assertNotEquals(generation, ownership.current()!!.generation)
        receiver.onReceive(context, denied) // prior dialog must not release new request
        assertNotNull(ownership.current())
        verify(exactly = 2) { usb.requestPermission(android, any<PendingIntent>()) }
    }

    @Test fun `permission request failure releases selection without an automatic reprompt`() {
        mockkStatic(PendingIntent::class)
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setPackage(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setData(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Long>()) } answers { self as Intent }
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } throws SecurityException("test")
        val manager = running(UsbConnectionManager(context, scope, {}))
        val android = phone("android", 0x18d1)
        call(manager, "requestPermissionOrConnect", android)
        call(manager, "requestPermissionOrConnect", android)
        val ownership = manager.javaClass.getDeclaredField("dispatchOwnership").apply { isAccessible = true }
            .get(manager) as UsbDispatchOwnership
        assertNull(ownership.current())
        verify(exactly = 1) { usb.hasPermission(android) }
        verify(exactly = 0) { usb.requestPermission(android, any<PendingIntent>()) }
    }

    @Test fun `Android permission request still routes with CarPlay enabled and deduplicates`() {
        mockkStatic(PendingIntent::class)
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setPackage(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().setData(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Long>()) } answers { self as Intent }
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns mockk(relaxed = true)
        val manager = running(UsbConnectionManager(context, scope, {}, onIphoneSelected = { fail("Android routed to Apple"); true }))
        val android = phone("android", 0x18d1)
        call(manager, "requestPermissionOrConnect", android)
        call(manager, "requestPermissionOrConnect", android)
        verify(exactly = 1) { usb.requestPermission(android, any<PendingIntent>()) }
    }
}

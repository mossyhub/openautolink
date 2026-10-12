package com.openautolink.app.transport.usb

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.openautolink.app.carplay.UsbHotPlugConsent
import io.mockk.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UsbHotPlugAdmissionTest {
    private val usb = mockk<UsbManager>(relaxed = true)
    private val context = mockk<Context>(relaxed = true) {
        every { getSystemService(Context.USB_SERVICE) } returns usb
    }
    private val scope = TestScope()
    private fun phone(name: String, vid: Int) = mockk<UsbDevice>(relaxed = true) {
        every { deviceName } returns name
        every { vendorId } returns vid
        every { productId } returns 1
    }
    private fun running(manager: UsbConnectionManager) = manager.apply {
        javaClass.getDeclaredField("isRunning").apply { isAccessible = true }.set(this, true)
    }
    private fun call(manager: UsbConnectionManager, method: String, device: UsbDevice) {
        manager.javaClass.getDeclaredMethod(method, UsbDevice::class.java)
            .apply { isAccessible = true }.invoke(manager, device)
    }
    private fun ownership(manager: UsbConnectionManager) = manager.javaClass.getDeclaredField("dispatchOwnership")
        .apply { isAccessible = true }.get(manager) as UsbDispatchOwnership
    private fun passive() {
        verify(exactly = 0) { usb.hasPermission(any<UsbDevice>()) }
        verify(exactly = 0) { usb.requestPermission(any<UsbDevice>(), any<PendingIntent>()) }
        verify(exactly = 0) { usb.openDevice(any()) }
    }
    @After fun cleanup() = unmockkAll()

    @Test fun pendingAndroidAndAppleNeverRequestPermissionPairOrRetireOwners() {
        for (vid in listOf(0x18d1, 0x05ac)) {
            val device = phone("phone-$vid", vid)
            var offered: Long? = null
            var appleStarts = 0
            var retirements = 0
            val manager = running(UsbConnectionManager(context, scope, {},
                onIphoneSelected = { appleStarts++; true },
                onIphoneDetached = { _, _ -> retirements++ },
                onCandidate = { generation, _ -> offered = generation }))
            every { usb.deviceList } returns hashMapOf(device.deviceName to device)
            call(manager, "requestPermissionOrConnect", device)
            call(manager, "requestPermissionOrConnect", device)
            assertNotNull(offered)
            assertNotNull(manager.pendingDevice(offered!!))
            assertNull(ownership(manager).current())
            assertEquals(0, appleStarts)
            assertEquals(0, retirements)
            passive()
        }
    }

    @Test fun stayIsAttachmentScopedAndNeverMutatesDispatchOwnership() {
        val device = phone("phone", 0x05ac)
        every { usb.deviceList } returns hashMapOf(device.deviceName to device)
        val offers = mutableListOf<Long>()
        val manager = running(UsbConnectionManager(context, scope, {},
            onCandidate = { generation, _ -> offers += generation }))
        call(manager, "requestPermissionOrConnect", device)
        manager.stay(offers.single())
        call(manager, "requestPermissionOrConnect", device)
        assertEquals(1, offers.size)
        assertNull(ownership(manager).current())
        passive()
        call(manager, "handleDeviceDetached", device)
        call(manager, "handleDeviceAttached", device)
        assertEquals(2, offers.size)
        assertNotEquals(offers[0], offers[1])
    }

    @Test fun detachPendingInvalidatesExactGenerationAndNeverAdoptsNewEnumeration() {
        val old = phone("/dev/bus/usb/001/002", 0x05ac)
        val replacement = phone("/dev/bus/usb/001/003", 0x05ac)
        var pending = -1L
        val detached = mutableListOf<Long>()
        val manager = running(UsbConnectionManager(context, scope, {},
            onCandidate = { generation, _ -> pending = generation },
            onCandidateDetached = { detached += it }))
        every { usb.deviceList } returns hashMapOf(old.deviceName to old)
        call(manager, "requestPermissionOrConnect", old)
        val first = pending
        every { usb.deviceList } returns hashMapOf(replacement.deviceName to replacement)
        call(manager, "handleDeviceDetached", old)
        call(manager, "handleDeviceAttached", replacement)
        assertEquals(listOf(first), detached)
        assertFalse(manager.accept(first))
        assertNotEquals(first, pending)
        assertNull(ownership(manager).current())
        passive()
    }

    @Test fun acceptIsSingleOwnerAndValidatedAppleReenumerationDoesNotPromptAgain() {
        val old = phone("/dev/bus/usb/001/002", 0x05ac)
        val replacement = phone("/dev/bus/usb/001/003", 0x05ac)
        val offers = mutableListOf<Long>()
        var starts = 0
        val ended = mutableListOf<Long>()
        val manager = running(UsbConnectionManager(context, scope, {},
            onCandidate = { generation, _ -> offers += generation },
            onIphoneSelected = { starts++; true }, onAdmissionEnded = { ended += it }))
        every { usb.deviceList } returns hashMapOf(old.deviceName to old)
        call(manager, "requestPermissionOrConnect", old)
        val generation = offers.single()
        assertTrue(manager.accept(generation))
        assertFalse(manager.accept(generation))
        assertEquals(1, starts)
        val transition = manager.iphoneReenumerationCallbacks(old)
        assertTrue(transition.first())
        val retired = manager.iphoneRetirementAcknowledgement(old)
        call(manager, "handleDeviceDetached", old)
        every { usb.deviceList } returns hashMapOf(replacement.deviceName to replacement)
        call(manager, "handleDeviceAttached", replacement)
        assertTrue(transition.second(replacement))
        assertEquals(1, offers.size)
        assertTrue(ended.isEmpty())
        retired()
        scope.runCurrent()
        assertEquals(listOf(generation), ended)
        call(manager, "requestPermissionOrConnect", replacement)
        assertEquals(1, starts)
        assertEquals(1, offers.size)
    }

    @Test fun rejectedAppleEndsTransientOverrideExactlyOnceWithoutAutomaticRetry() {
        val device = phone("phone", 0x05ac)
        every { usb.deviceList } returns hashMapOf(device.deviceName to device)
        var generation = -1L
        val ended = mutableListOf<Long>()
        val manager = running(UsbConnectionManager(context, scope, {}, onIphoneSelected = { false },
            onCandidate = { g, _ -> generation = g }, onAdmissionEnded = { ended += it }))
        call(manager, "requestPermissionOrConnect", device)
        assertFalse(manager.accept(generation))
        assertEquals(listOf(generation), ended)
        call(manager, "requestPermissionOrConnect", device)
        assertEquals(1, ended.size)
        assertNull(ownership(manager).current())
        passive()
    }

    @Test fun secondPendingNeverReplacesFirstAndOldAcknowledgementCannotReleaseNewOwner() {
        val one = phone("one", 0x05ac)
        val two = phone("two", 0x05ac)
        every { usb.deviceList } returns hashMapOf(one.deviceName to one, two.deviceName to two)
        val offers = mutableListOf<Long>()
        val manager = running(UsbConnectionManager(context, scope, {}, onIphoneSelected = { true },
            onCandidate = { g, _ -> offers += g }))
        call(manager, "requestPermissionOrConnect", one)
        call(manager, "requestPermissionOrConnect", two)
        assertEquals(1, offers.size)
        assertEquals(one, manager.pendingDevice(offers.single()))
        assertTrue(manager.accept(offers.single()))
        val firstAck = manager.iphoneRetirementAcknowledgement(one)
        firstAck()
        call(manager, "requestPermissionOrConnect", two)
        assertEquals(2, offers.size)
        assertTrue(manager.accept(offers.last()))
        val token = ownership(manager).current()
        firstAck()
        assertSame(token, ownership(manager).current())
        manager.failedAdmission(offers.first())
        assertSame(token, ownership(manager).current())
    }
    @Test fun pluggingSecondPhoneWhileUsbCarPlayActiveOffersConsentWithoutChangingOwner() {
        val first = phone("/dev/bus/usb/001/002", 0x05ac)
        val second = phone("/dev/bus/usb/002/003", 0x05ac)
        val offers = mutableListOf<Long>()
        var starts = 0
        val manager = running(UsbConnectionManager(context, scope, {}, onIphoneSelected = { starts++; true },
            onCandidate = { g, _ -> offers += g }))
        every { usb.deviceList } returns hashMapOf(first.deviceName to first)
        call(manager, "requestPermissionOrConnect", first)
        assertTrue(manager.accept(offers.single()))
        val token = ownership(manager).current()
        val ack = manager.iphoneRetirementAcknowledgement(first)
        every { usb.deviceList } returns hashMapOf(first.deviceName to first, second.deviceName to second)
        call(manager, "handleDeviceAttached", second)
        assertEquals(2, offers.size)
        assertSame(token, ownership(manager).current())
        assertFalse(manager.accept(offers.last()))
        assertSame(token, ownership(manager).current())
        ack()
        assertTrue(manager.accept(offers.last()))
        assertEquals(2, starts)
        passive()
    }
}

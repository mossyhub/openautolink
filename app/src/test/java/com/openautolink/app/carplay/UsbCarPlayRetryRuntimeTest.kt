package com.openautolink.app.carplay

import android.content.Context
import android.hardware.usb.*
import android.view.Surface
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class UsbCarPlayRetryRuntimeTest {
    @After fun cleanup() = unmockkAll()
    @Test fun managerRestartCannotRepeatPermissionUnderUnchangedEnumeration() {
        val context = mockk<Context>(relaxed = true)
        val usb = mockk<UsbManager>(relaxed = true)
        val phone = mockk<UsbDevice>(relaxed = true)
        val cfg = mockk<UsbConfiguration>(relaxed = true)
        val intf = mockk<UsbInterface>(relaxed = true)
        val surface = mockk<Surface> { every { isValid } returns true }
        every { phone.deviceName } returns "runtime-retry/2"
        every { phone.deviceId } returns 2
        every { phone.vendorId } returns 0x05ac
        every { phone.productId } returns 0x12a8
        every { phone.configurationCount } returns 1
        every { phone.getConfiguration(0) } returns cfg
        every { cfg.interfaceCount } returns 1
        every { cfg.getInterface(0) } returns intf
        every { intf.interfaceClass } returns 255
        every { intf.interfaceSubclass } returns 254
        every { intf.interfaceProtocol } returns 2
        every { context.getSystemService(UsbManager::class.java) } returns usb
        every { usb.deviceList } returns hashMapOf(phone.deviceName to phone)
        mockkObject(UsbCarPlayPermission)
        every { UsbCarPlayPermission.await(any(), any(), any(), any(), any()) } throws SecurityException("private")
        val statuses = mutableListOf<String>()
        repeat(2) {
            val runtime = OalUsbCarPlayRuntime(context, phone, CarPlayDisplayPreferences(), surface, statuses::add)
            try { runtime.run(); fail("Expected terminal permission failure") }
            catch (failure: CarPlayConnectionFailure) { assertEquals(CarPlayFailureKind.USB_PERMISSION, failure.kind) }
            finally { runtime.close() }
        }
        verify(exactly = 1) { UsbCarPlayPermission.await(any(), any(), any(), any(), any()) }
        assertTrue(statuses.contains("CarPlay USB stage=retry result=blocked"))
    }
}

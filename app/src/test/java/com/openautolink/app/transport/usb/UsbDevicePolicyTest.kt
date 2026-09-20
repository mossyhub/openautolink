package com.openautolink.app.transport.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDevicePolicyTest {

    @Test
    fun `Equinox Microchip controller hub is never classified as a phone`() {
        assertTrue(UsbDevicePolicy.isKnownNonPhoneDevice(0x0424, 0x49A0))
    }

    @Test
    fun `previously observed Microchip USB2517 hub is never classified as a phone`() {
        assertTrue(UsbDevicePolicy.isKnownNonPhoneDevice(0x0424, 0x4911))
    }

    @Test
    fun `Pixel USB identity remains eligible for phone classification`() {
        assertFalse(UsbDevicePolicy.isKnownNonPhoneDevice(0x18D1, 0x4EE1))
    }
}

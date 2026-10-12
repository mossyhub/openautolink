// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.usb

import org.junit.Assert.*
import org.junit.Test

class UsbCarPlayPolicyTest {
    @Test fun appleVendorWithoutUsbmuxIsNotPhone() {
        assertFalse(UsbCarPlayPolicy.isCandidate(0x05ac, listOf(Triple(3, 1, 1))))
    }
    @Test fun appleUsbmuxIsCandidateNotAuthenticatedPhone() {
        assertTrue(UsbCarPlayPolicy.isCandidate(0x05ac, listOf(Triple(255, 254, 2))))
    }
    @Test fun androidUsbmuxShapedInterfaceDoesNotBecomeCarPlay() {
        assertFalse(UsbCarPlayPolicy.isCandidate(0x18d1, listOf(Triple(255, 254, 2))))
    }
    @Test fun replacementRequiresSamePrivateSerialAndBoundedWindow() {
        assertTrue(UsbCarPlayPolicy.acceptReplacement("a", "a", true, 999))
        assertFalse(UsbCarPlayPolicy.acceptReplacement("a", "b", true, 999))
        assertFalse(UsbCarPlayPolicy.acceptReplacement("", "", true, 999))
        assertFalse(UsbCarPlayPolicy.acceptReplacement("a", "a", false, 999))
        assertFalse(UsbCarPlayPolicy.acceptReplacement("a", "a", true, 15001))
    }
}
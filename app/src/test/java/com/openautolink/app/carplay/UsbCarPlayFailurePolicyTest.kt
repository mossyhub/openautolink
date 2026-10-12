package com.openautolink.app.carplay

import com.shilapi.xcertplay.transport.*
import org.junit.Assert.*
import org.junit.Test

class UsbCarPlayFailurePolicyTest {
    @Test fun failureFenceSurvivesRuntimeRecreationUntilExactEnumerationDisappears() {
        val fence = UsbCarPlayRetryFence()
        val phone = UsbCarPlayTopology("bus/2", 2, 0x05ac, 0x12a8)
        val other = phone.copy(name = "bus/3", id = 3)
        fence.failed(phone, "PASSWORD_PROTECTED")
        repeat(3) { assertEquals("PASSWORD_PROTECTED", fence.blocked(phone, setOf(phone, other))) }
        assertNull(fence.blocked(other, setOf(phone, other)))
        assertNull(fence.blocked(phone, setOf(other)))
        assertNull(fence.blocked(phone, setOf(phone)))
    }
    @Test fun pendingAndDenialAreNotPasswordOrTransportFailures() {
        assertEquals("PASSWORD_PROTECTED", UsbCarPlayFailurePolicy.reason(LockdownPairingException.PasswordProtected))
        assertEquals("USER_DENIED", UsbCarPlayFailurePolicy.reason(LockdownPairingException.UserDeniedPairing))
        assertEquals("TRUST_PENDING_TIMEOUT", UsbCarPlayFailurePolicy.reason(LockdownPairingException.TimedOut, true))
        assertEquals("TIMEOUT", UsbCarPlayFailurePolicy.reason(LockdownPairingException.TimedOut))
        assertEquals("INVALID_RECORD", UsbCarPlayFailurePolicy.reason(LockdownPairingException.RemoteError("InvalidPairRecord")))
        assertEquals("REMOTE_ERROR", UsbCarPlayFailurePolicy.reason(LockdownPairingException.RemoteError("private-secret")))
        assertEquals("TRANSPORT_LOSS", UsbCarPlayFailurePolicy.reason(IphoneUsbException.DeviceUnavailable("private")))
        assertEquals("CANCELLED", UsbCarPlayFailurePolicy.reason(LockdownPairingException.Cancelled))
        assertEquals("PERMISSION", UsbCarPlayFailurePolicy.reason(SecurityException("private")))
        assertEquals("INVALID_RESPONSE", UsbCarPlayFailurePolicy.reason(LockdownPairingException.InvalidResponse("private")))
    }
}

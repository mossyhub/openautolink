package com.openautolink.app.carplay

import com.shilapi.xcertplay.transport.*
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class UsbLockdownPairingReceiptsTest {
    @After fun cleanup() = unmockkAll()
    @Test fun passwordResponseHasRequestedReceiptAndNeverSendsAnotherPair() = response("PasswordProtected", false)
    @Test fun pendingTrustCanBeCancelledWithoutAnotherPair() = response("PairingDialogResponsePending", true)
    @Test fun userDenialNeverSendsAnotherPair() = response("UserDeniedPairing", false)
    private fun response(error: String, cancelPending: Boolean) {
        val host = mockk<Iap2UsbMuxHost>()
        val stream = mockk<Iap2UsbMuxTcpConnection>(relaxed = true)
        every { host.connect(any(), any()) } returns stream
        mockkConstructor(LockdownPlistChannel::class)
        mockkObject(LockdownPairRecordGenerator)
        val bytes = byteArrayOf(1)
        val record = LockdownPairRecord.restore("test-host", "test-buid", "test-wifi", bytes, bytes, bytes, bytes, bytes, bytes)
        every { LockdownPairRecordGenerator.generate(any(), any(), any(), any()) } returns record
        var pairs = 0
        every { anyConstructed<LockdownPlistChannel>().request(any(), any()) } answers {
            val req = firstArg<LockdownPlistValue.Dictionary>()
            val verb = (req.entries["Request"] as LockdownPlistValue.Text).value
            val key = (req.entries["Key"] as? LockdownPlistValue.Text)?.value
            when {
                verb == "Pair" -> { pairs++; dict("Error", LockdownPlistValue.Text(error)) }
                key == "DevicePublicKey" -> dict("Value", LockdownPlistValue.Data(bytes))
                key == "WiFiAddress" -> dict("Value", LockdownPlistValue.Text("test-wifi"))
                else -> LockdownPlistValue.Dictionary(emptyMap())
            }
        }
        every { anyConstructed<LockdownPlistChannel>().close() } just Runs
        val stages = mutableListOf<LockdownPairingStage>()
        try {
            LockdownPairingClient(host).pair("test", "test-host", "test-buid", 2000, stages::add) { cancelPending && LockdownPairingStage.TRUST_PENDING in stages }
            fail("Expected password protected")
        } catch (failure: LockdownPairingException) {
            assertEquals(when(error) {
                "PasswordProtected" -> LockdownPairingException.PasswordProtected
                "UserDeniedPairing" -> LockdownPairingException.UserDeniedPairing
                else -> LockdownPairingException.Cancelled
            }, failure)
        }
        assertEquals(1, pairs)
        assertEquals(listOf(LockdownPairingStage.CONNECTING, LockdownPairingStage.HOST_BUID,
            LockdownPairingStage.DEVICE_KEY, LockdownPairingStage.WIFI_ADDRESS,
            LockdownPairingStage.RECORD_PREPARED, LockdownPairingStage.PAIR_REQUESTED) +
            if(cancelPending) listOf(LockdownPairingStage.TRUST_PENDING) else emptyList(), stages)
    }
    @Test fun plistPeerEofIsTransportLossNotMalformedPlist() {
        val stream = mockk<BlockingDuplexByteStream>(relaxed = true)
        every { stream.recv(any(), any()) } returns byteArrayOf()
        try { LockdownPlistChannel(stream).receive(100); fail("Expected EOF") }
        catch (failure: Exception) { assertTrue(failure is java.io.EOFException) }
    }
    @Test fun carkitRecordRejectionRetainsTypedCodeWithoutMessageParsing() {
        val client = LockdownCarKitClient(mockk<Iap2UsbMuxHost>())
        val method = client.javaClass.getDeclaredMethod("rejectError", LockdownPlistValue.Dictionary::class.java, String::class.java)
        method.isAccessible = true
        try {
            method.invoke(client, dict("Error", LockdownPlistValue.Text("InvalidHostID")), "StartSession")
            fail("Expected rejection")
        } catch (failure: java.lang.reflect.InvocationTargetException) {
            assertTrue(failure.cause is LockdownPairingException.RemoteError)
        }
    }
    private fun dict(key: String, value: LockdownPlistValue) = LockdownPlistValue.Dictionary(mapOf(key to value))
}

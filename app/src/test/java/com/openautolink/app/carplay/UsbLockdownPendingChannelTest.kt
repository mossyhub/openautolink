package com.openautolink.app.carplay

import com.shilapi.xcertplay.transport.*
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Scripted transport boundary; exercises the real blocking pairing loop and deadline. */
class UsbLockdownPendingChannelTest {
    @After fun cleanup() = unmockkAll()

    @Test fun pendingRetriesKeepOneChannelBuidAndRecordWithApprovalGrace() {
        val peer = Peer(listOf("PairingDialogResponsePending", "PairingDialogResponsePending", null))
        val stages = mutableListOf<LockdownPairingStage>()
        val paired = LockdownPairingClient(peer.host).pair("test", "test-host", "test-buid", 45_000, stages::add)
        assertSame(peer.record, paired.pairRecord)
        assertEquals(3, peer.pairTimes.size)
        assertEquals(1, peer.connections)
        assertEquals(1, peer.buidWrites)
        assertEquals(1, peer.generations)
        assertEquals(1, peer.keyReads)
        assertEquals(1, peer.wifiReads)
        assertEquals(1, peer.closes)
        assertEquals(1, stages.count { it == LockdownPairingStage.CONNECTING })
        assertEquals(1, stages.count { it == LockdownPairingStage.HOST_BUID })
        assertEquals(2, stages.count { it == LockdownPairingStage.TRUST_PENDING })
        assertEquals(LockdownPairingStage.PAIRED, stages.last())
        peer.pairTimes.zipWithNext().forEach { (before, after) ->
            assertTrue("Allow fifteen seconds for on-phone approval", after - before >= 15_000_000_000L)
        }
    }

    @Test fun pendingGraceIsCappedByTotalDeadlineWithoutAnotherPair() {
        val peer = Peer(listOf("PairingDialogResponsePending"))
        val start = System.nanoTime()
        expect(LockdownPairingException.TimedOut) {
            LockdownPairingClient(peer.host).pair("test", "test-host", "test-buid", 1_300)
        }
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertTrue("Deadline must cap the approval grace: $elapsed", elapsed < 3_000)
        assertEquals(1, peer.pairTimes.size)
        assertEquals(1, peer.connections)
        assertEquals(1, peer.closes)
    }

    @Test fun cancellationDuringApprovalGraceClosesOnceWithoutAnotherPair() {
        val peer = Peer(listOf("PairingDialogResponsePending"))
        val start = System.nanoTime()
        expect(LockdownPairingException.Cancelled) {
            LockdownPairingClient(peer.host).pair("test", "test-host", "test-buid", 20_000,
                isCancelled = { peer.pairTimes.isNotEmpty() && System.nanoTime() - peer.pairTimes.first() >= 200_000_000L })
        }
        assertTrue(System.nanoTime() - start < 3_000_000_000L)
        assertEquals(1, peer.pairTimes.size)
        assertEquals(1, peer.connections)
        assertEquals(1, peer.closes)
    }

    @Test fun denialAfterPendingTerminatesTheRetainedChannel() {
        val peer = Peer(listOf("PairingDialogResponsePending", "UserDeniedPairing"))
        expect(LockdownPairingException.UserDeniedPairing) {
            LockdownPairingClient(peer.host).pair("test", "test-host", "test-buid", 25_000)
        }
        assertEquals(2, peer.pairTimes.size)
        assertEquals(1, peer.connections)
        assertEquals(1, peer.buidWrites)
        assertEquals(1, peer.closes)
    }

    private fun expect(expected: LockdownPairingException, block: () -> Unit) {
        try { block(); fail("Expected $expected") }
        catch (actual: LockdownPairingException) { assertSame(expected, actual) }
    }

    private class Peer(private val errors: List<String?>) {
        val host = mockk<Iap2UsbMuxHost>()
        private val stream = mockk<Iap2UsbMuxTcpConnection>(relaxed = true)
        private val bytes = byteArrayOf(1)
        val record = LockdownPairRecord.restore("test-host", "test-buid", "test-wifi", bytes, bytes, bytes, bytes, bytes, bytes)
        var connections = 0
        var buidWrites = 0
        var generations = 0
        var keyReads = 0
        var wifiReads = 0
        var closes = 0
        val pairTimes = mutableListOf<Long>()
        private var firstPairRecord: LockdownPlistValue.Dictionary? = null

        init {
            every { host.connect(any(), any()) } answers { connections++; stream }
            mockkConstructor(LockdownPlistChannel::class)
            mockkObject(LockdownPairRecordGenerator)
            every { LockdownPairRecordGenerator.generate(any(), any(), any(), any()) } answers { generations++; record }
            every { anyConstructed<LockdownPlistChannel>().close() } answers { closes++; Unit }
            every { anyConstructed<LockdownPlistChannel>().request(any(), any()) } answers {
                val request = firstArg<LockdownPlistValue.Dictionary>()
                val verb = (request.entries["Request"] as LockdownPlistValue.Text).value
                val key = (request.entries["Key"] as? LockdownPlistValue.Text)?.value
                when {
                    verb == "Pair" -> {
                        assertEquals("Pending retry must not reopen Lockdown", 1, connections)
                        assertEquals("Pending retry must not reset BUID", 1, buidWrites)
                        assertEquals("Keep channel open throughout approval", 0, closes)
                        val sent = request.entries["PairRecord"] as LockdownPlistValue.Dictionary
                        firstPairRecord?.let { previous ->
                            assertEquals(previous.entries.keys, sent.entries.keys)
                            previous.entries.forEach { (key, value) ->
                                if (value is LockdownPlistValue.Data) {
                                    assertArrayEquals(value.bytes, (sent.entries[key] as LockdownPlistValue.Data).bytes)
                                } else assertEquals(value, sent.entries[key])
                            }
                        } ?: run { firstPairRecord = sent }
                        val index = pairTimes.size
                        pairTimes += System.nanoTime()
                        errors[index.coerceAtMost(errors.lastIndex)]?.let { dict("Error", LockdownPlistValue.Text(it)) }
                            ?: LockdownPlistValue.Dictionary(emptyMap())
                    }
                    verb == "SetValue" -> {
                        assertEquals("UntrustedHostBUID", key)
                        assertEquals(LockdownPlistValue.Text("test-buid"), request.entries["Value"])
                        buidWrites++
                        LockdownPlistValue.Dictionary(emptyMap())
                    }
                    key == "DevicePublicKey" -> { keyReads++; dict("Value", LockdownPlistValue.Data(bytes)) }
                    key == "WiFiAddress" -> { wifiReads++; dict("Value", LockdownPlistValue.Text("test-wifi")) }
                    else -> error("Unexpected request")
                }
            }
        }
        private fun dict(key: String, value: LockdownPlistValue) = LockdownPlistValue.Dictionary(mapOf(key to value))
    }
}

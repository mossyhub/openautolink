package com.openautolink.carplaylab.telecom

import android.telecom.Connection
import com.shilapi.xcertplay.airplay.PhoneControl
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], manifest = Config.NONE)
class ManagedIncomingCallControlsRegressionTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private fun field(id: Int, bytes: ByteArray): ByteArray {
        val size = bytes.size + 4
        return byteArrayOf((size ushr 8).toByte(), size.toByte(), 0, id.toByte()) + bytes
    }
    private fun text(id: Int, value: String) = field(id, value.toByteArray() + byteArrayOf(0))
    private fun call(status: Int? = null, extra: ByteArray = byteArrayOf(), uuid: String = id) = Iap2Frame(0x4155,
        text(4, uuid) + (status?.let { field(2, byteArrayOf(it.toByte())) } ?: byteArrayOf()) + extra)
    private fun ring(extra: ByteArray = byteArrayOf()) = call(2, field(3, byteArrayOf(1)) + field(9, byteArrayOf(0)) + extra)
    private fun count(value: Int?) = Iap2Frame(0x4158, value?.let { field(10, byteArrayOf(it.toByte())) } ?: byteArrayOf())

    @Test fun confirmedAnswerAfterIntermediateStatusThreeAllowsActualNativeEnd() {
        val tokens = mutableListOf<String>()
        val commands = mutableListOf<PhoneControl>()
        val receipts = mutableListOf<String>()
        val bridge = ManagedIncomingBridge({true}, tokens::add, {it(); true},
            controlIntent = { command, fence -> assertTrue(fence()); commands.add(command); true })
        bridge.diagnosticReceipt = receipts::add
        try {
            bridge.handle(ring())
            val connection = bridge.create(tokens.single())
            connection.onAnswer()
            bridge.handle(call(3)) // raw transitional fact, NOT interpreted as Active/Held
            assertEquals(Connection.STATE_RINGING, connection.state)
            connection.onDisconnect()
            assertEquals(listOf(PhoneControl.ANSWER), commands)
            bridge.handle(call(4))
            assertEquals(Connection.STATE_ACTIVE, connection.state)
            connection.onDisconnect()
            assertEquals("phone-confirmed Answer must not permanently strand End", listOf(PhoneControl.ANSWER, PhoneControl.END), commands)
            assertTrue(receipts.any { it.contains("action=ANSWER reason=PHONE_CONFIRMED") })
        } finally { bridge.close() }
    }

    @Test fun projectedPcmConnectionRequestsVoipBeforeAnyRingingOrActivePublication() {
        val tokens = mutableListOf<String>()
        val bridge = ManagedIncomingBridge({true}, tokens::add, {it(); true})
        val seen = mutableListOf<Boolean>()
        var connection: Connection? = null
        bridge.diagnosticReceipt = { line ->
            if (line.contains("boundary=CREATE")) {
                // Read-only census of the actual object while create() has not yet returned.
                val records = bridge.javaClass.getDeclaredField("records").apply { isAccessible = true }.get(bridge) as Map<*, *>
                val record = records.values.single()!!
                val creating = record.javaClass.getDeclaredField("connection").apply { isAccessible = true }.get(record) as Connection
                assertEquals(Connection.STATE_NEW, creating.state)
                seen.add(creating.audioModeIsVoip)
            }
            if (line.contains("boundary=UPDATE")) seen.add(connection!!.audioModeIsVoip)
        }
        try {
            bridge.handle(ring())
            connection = bridge.create(tokens.single())
            assertTrue("app-owned projected PCM requires communication audio classification", connection.audioModeIsVoip)
            assertEquals(Connection.STATE_RINGING, connection.state)
            bridge.handle(call(4))
            assertEquals(listOf(true, true), seen)
            assertEquals(Connection.STATE_ACTIVE, connection.state)
        } finally { bridge.close() }
    }

    @Test fun onlyExactPendingAnswerTransitionCanRecoverAndHardFailuresKeepFirstCause() {
        for (hazard in listOf("timeout", "sender", "held", "unknown", "direction", "conference", "multiple", "hint", "owner", "identity", "wrong-uuid")) {
            val tokens = mutableListOf<String>(); val commands = mutableListOf<PhoneControl>(); val receipts = mutableListOf<String>()
            var owner = true
            val bridge = ManagedIncomingBridge({true}, tokens::add, {it(); true}, current = {owner},
                controlIntent = { c, _ -> commands.add(c); hazard != "sender" })
            bridge.diagnosticReceipt = receipts::add
            try {
                bridge.handle(ring()); val native = bridge.create(tokens.single()); native.onAnswer()
                bridge.handle(call(3))
                when (hazard) {
                    "timeout" -> org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(6))
                    "held" -> bridge.handle(call(5))
                    "unknown" -> bridge.handle(call(255))
                    "direction" -> { bridge.handle(call(extra = field(3, byteArrayOf(2)))); bridge.handle(call(extra = field(3, byteArrayOf(1)))) }
                    "conference" -> { bridge.handle(call(extra = field(9, byteArrayOf(1)))); bridge.handle(call(extra = field(9, byteArrayOf(0)))) }
                    "multiple" -> { bridge.handle(call(5, uuid = "00000000-0000-4000-8000-000000000002")); bridge.handle(call(0, uuid = "00000000-0000-4000-8000-000000000002")) }
                    "hint" -> bridge.handle(count(2))
                    "owner" -> owner = false
                }
                when (hazard) {
                    "identity" -> bridge.handle(call(extra = text(1, "Private identity update")))
                    "wrong-uuid" -> bridge.handle(call(4, uuid = "00000000-0000-4000-8000-000000000099"))
                    else -> bridge.handle(call(4))
                }
                native.onDisconnect()
                assertEquals("unsafe recovery: $hazard", listOf(PhoneControl.ANSWER), commands)
                if (hazard != "owner") {
                    assertTrue("missing finite first latch: $hazard", receipts.any { it.contains("first=" + if (hazard == "sender") "SENDER_WITHHELD" else "ANSWER_TRANSITION") })
                    assertTrue(receipts.all { NativeCallDiagnostics.sanitize(it) == it })
                }
            } finally { bridge.close() }
        }
    }

    @Test fun qualifiedRejectUsesSeparateIap2IntentAndWaitsForRemoteEnd() {
        val tokens = mutableListOf<String>()
        val hid = mutableListOf<PhoneControl>()
        val rejects = mutableListOf<() -> Boolean>()
        val bridge = ManagedIncomingBridge({true}, tokens::add, {it(); true}, controlIntent={c,_ -> hid.add(c); true})
        val setter = bridge.javaClass.methods.singleOrNull { it.name == "setRejectIntent" }
        assertNotNull("qualified iAP2 reject seam missing", setter)
        setter!!.invoke(bridge, { fence: () -> Boolean -> rejects.add(fence); true })
        try {
            bridge.handle(ring())
            val connection = bridge.create(tokens.single())
            connection.onReject(); connection.onReject(0); connection.onReject("do not send SMS")
            assertEquals(1, rejects.size)
            assertTrue(rejects.single()())
            assertTrue("Reject is not HID Drop", hid.isEmpty())
            assertEquals("local rejection must wait for phone", Connection.STATE_RINGING, connection.state)
            bridge.handle(call(0))
            assertFalse(rejects.single()())
            assertEquals(Connection.STATE_DISCONNECTED, connection.state)
        } finally { bridge.close() }
    }

    @Test fun finiteReasonsExposeWithholdingWithoutCallerOrCallIdentifiers() {
        val tokens = mutableListOf<String>()
        val receipts = mutableListOf<String>()
        val bridge = ManagedIncomingBridge({true}, tokens::add, {it(); true}, controlIntent={_,_ -> true})
        val setter = bridge.javaClass.methods.singleOrNull { it.name == "setDiagnosticReceipt" }
        assertNotNull("native command withholding has no exported reason seam", setter)
        setter!!.invoke(bridge, { receipt: String -> receipts.add(receipt); Unit })
        try {
            bridge.handle(ring(text(0, "+15550123456") + text(1, "Private Contact")))
            val connection = bridge.create(tokens.single())
            connection.onDisconnect()
            assertTrue(receipts.any { it.contains("action=END reason=STATE") })
            connection.onAnswer()
            connection.onDisconnect()
            assertTrue(receipts.any { it.contains("action=END reason=PENDING") })
            bridge.handle(call(4))
            connection.onDisconnect()
            assertTrue(receipts.any { it.contains("action=END reason=QUEUED") })
            connection.onReject()
            assertTrue(receipts.any { it.contains("action=REJECT reason=UNSUPPORTED") })
            repeat(100) { connection.onDisconnect() }
            assertEquals("per-link diagnostics must be bounded", 64, receipts.size)
            assertTrue(receipts.none { it.contains(id) || it.contains("Private Contact") || it.contains("15550123456") })
        } finally { bridge.close() }
    }

    @Test fun nativeCallerIdentityIsSameUuidPrivateAndPresenceAware() {
        val tokens = mutableListOf<String>()
        val bridge = ManagedIncomingBridge({true}, tokens::add, {it(); true})
        try {
            bridge.handle(ring(text(0, "+15550123456") + text(1, "Test Contact")))
            val connection = bridge.create(tokens.single())
            assertEquals("tel", connection.address?.scheme)
            assertEquals("+15550123456", connection.address?.schemeSpecificPart)
            assertEquals(android.telecom.TelecomManager.PRESENTATION_ALLOWED, connection.addressPresentation)
            assertEquals("Test Contact", connection.callerDisplayName)
            bridge.handle(call(4))
            assertEquals("absent must preserve identity", "Test Contact", connection.callerDisplayName)
            bridge.handle(call(extra = text(1, "Updated Contact")))
            assertEquals("Updated Contact", connection.callerDisplayName)
            bridge.handle(call(extra = text(1, "Other Contact"), uuid = "00000000-0000-4000-8000-000000000002"))
            assertEquals("another UUID cannot overwrite this identity", "Updated Contact", connection.callerDisplayName)
            bridge.handle(call(extra = text(0, "") + text(1, "")))
            assertNull("present empty revokes local disclosure, not an invented number", connection.address)
            assertNull(connection.callerDisplayName)
            assertEquals(android.telecom.TelecomManager.PRESENTATION_UNKNOWN, connection.addressPresentation)
            bridge.handle(call(extra = text(0, "https://private.example/test") + text(1, "\nInjected")))
            assertNull("remote identifiers are not arbitrary launchable URIs", connection.address)
            assertNull("control characters are not native caller labels", connection.callerDisplayName)
        } finally { bridge.close() }
    }

    @Test fun communicationsDeliveryAcknowledgesRevisionWithoutGrantingCallAuthority() {
        for (hint in listOf<Int?>(null, 0, 1)) {
            val queue = java.util.ArrayDeque<() -> Unit>()
            val tokens = mutableListOf<String>()
            val commands = mutableListOf<PhoneControl>()
            val bridge = ManagedIncomingBridge({true}, tokens::add, {queue.add(it); true},
                controlIntent = { command, fence -> assertTrue(fence()); commands.add(command); true })
            try {
                bridge.handle(ring()); while (queue.isNotEmpty()) queue.removeFirst()()
                val connection = bridge.create(tokens.single())
                connection.onAnswer()
                bridge.handle(call(4)); while (queue.isNotEmpty()) queue.removeFirst()()
                assertEquals(Connection.STATE_ACTIVE, connection.state)
                bridge.handle(count(hint))
                connection.onDisconnect()
                assertEquals("queued communications must fence End", listOf(PhoneControl.ANSWER), commands)
                while (queue.isNotEmpty()) queue.removeFirst()()
                connection.onDisconnect()
                assertEquals("applied communications must not strand End (hint=$hint)", listOf(PhoneControl.ANSWER, PhoneControl.END), commands)
            } finally { bridge.close() }
        }
    }
}

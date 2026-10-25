package com.openautolink.carplaylab.telecom

import android.telecom.Connection
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], manifest = Config.NONE)
class CallerIdentityObservabilityTest {
    private val uuid = "00000000-0000-4000-8000-000000000091"
    private fun field(id: Int, bytes: ByteArray): ByteArray {
        val n = bytes.size + 4
        return byteArrayOf((n ushr 8).toByte(), n.toByte(), 0, id.toByte()) + bytes
    }
    private fun text(id: Int, value: String) = field(id, value.toByteArray() + byteArrayOf(0))
    private fun call(extra: ByteArray) = Iap2Frame(0x4155, text(4, uuid) + extra)
    @Test fun initialAndRepeatedRingingIdentityHasFiniteDecodeMergeAndBeforeStateReadback() {
        val receipts = mutableListOf<String>(); val tokens = mutableListOf<String>()
        val bridge = ManagedIncomingBridge({ true }, tokens::add, { it(); true })
        bridge.diagnosticReceipt = receipts::add
        try {
            bridge.handle(call(field(2, byteArrayOf(2)) + field(3, byteArrayOf(1)) +
                text(0, "+15550123456") + text(1, "Élodie 私密")))
            val c = bridge.create(tokens.single())
            assertEquals(Connection.STATE_RINGING, c.state)
            assertEquals("Élodie 私密", c.callerDisplayName)
            assertTrue("missing decoder identity discriminator", receipts.any { it.contains("stage=native-identity boundary=DECODE") && it.contains("remote=VALID name=VALID") })
            assertTrue("initial identity must be observed before Ringing", receipts.any { it.contains("boundary=CREATE") && it.contains("native=NEW") && it.contains("result=MATCH") })
            bridge.handle(call(text(1, "Late 私密")))
            assertEquals(Connection.STATE_RINGING, c.state)
            assertEquals("Late 私密", c.callerDisplayName)
            assertTrue(receipts.any { it.contains("boundary=UPDATE") && it.contains("native=RINGING") && it.contains("result=MATCH") })
            assertTrue(receipts.filter { it.contains("stage=native-identity") }.all { NativeCallDiagnostics.sanitize(it) == it })
            assertFalse(receipts.joinToString().contains(uuid))
            assertFalse(receipts.joinToString().contains("私密"))
            assertFalse(receipts.joinToString().contains("15550123456"))
        } finally { bridge.close() }
    }

    @Test fun absentPreservesEmptyAndInvalidClearOnlyExactCallWithoutPrivateExport() {
        val receipts = mutableListOf<String>(); val tokens = mutableListOf<String>()
        val bridge = ManagedIncomingBridge({ true }, tokens::add, { it(); true })
        bridge.diagnosticReceipt = receipts::add
        try {
            bridge.handle(call(field(2, byteArrayOf(2)) + field(3, byteArrayOf(1)) +
                text(0, "+15550123456") + text(1, "Zoë 私密")))
            val c = bridge.create(tokens.single())
            bridge.handle(call(byteArrayOf()))
            assertEquals("Zoë 私密", c.callerDisplayName)
            assertTrue(receipts.any { it.contains("boundary=DECODE") && it.contains("remote=ABSENT name=ABSENT") })
            bridge.handle(Iap2Frame(0x4155, text(4, "00000000-0000-4000-8000-000000000092") + text(1, "Other 私密")))
            assertEquals("Zoë 私密", c.callerDisplayName)
            assertTrue(receipts.any { it.contains("call=2") })
            bridge.handle(call(text(0, "") + text(1, "")))
            assertNull(c.address); assertNull(c.callerDisplayName)
            assertTrue(receipts.any { it.contains("boundary=DECODE") && it.contains("remote=EMPTY name=EMPTY") })
            bridge.handle(call(text(0, "https://private.example/") + text(1, "bad\u202ename")))
            assertNull(c.address); assertNull(c.callerDisplayName)
            assertTrue(receipts.any { it.contains("boundary=DECODE") && it.contains("remote=INVALID name=INVALID") })
            bridge.handle(call(text(0, "+15550123456") + text(1, "Only after answer 私密") + field(2, byteArrayOf(4))))
            assertEquals(Connection.STATE_ACTIVE, c.state)
            assertTrue(receipts.any { it.contains("boundary=UPDATE") && it.contains("status=4 native=RINGING") && it.contains("result=MATCH") })
            val before = receipts.size
            bridge.handle(call(field(1, byteArrayOf(0xc3.toByte(), 0x28, 0))))
            assertEquals(before + 1, receipts.size)
            assertTrue(receipts.last().contains("call=0 status=-1 native=NONE remote=UNKNOWN name=UNKNOWN result=REJECTED"))
            assertEquals("Only after answer 私密", c.callerDisplayName)
            assertTrue(receipts.all { NativeCallDiagnostics.sanitize(it) == it })
            val export = receipts.joinToString()
            for (privateValue in listOf(uuid, "私密", "15550123456", "private.example", "bad", "Other")) assertFalse(export.contains(privateValue))
            bridge.close()
            val retired = receipts.size
            bridge.handle(call(text(1, "retired private")))
            assertEquals(retired, receipts.size)
        } finally { bridge.close() }
    }

    @Test fun identitySchemaRejectsPrivateValuesAndOutOfBoundsFiniteFields() {
        val safe = "media stage=native-identity boundary=CREATE seq=1 call=1 status=2 native=NEW remote=VALID name=VALID result=MATCH"
        assertEquals(safe, NativeCallDiagnostics.sanitize(safe))
        for (bad in listOf(safe + " private", safe.replace("seq=1", "seq=65"), safe.replace("call=1", "call=17"),
            safe.replace("status=2", "status=256"), safe.replace("name=VALID", "name=Zoë"), safe.replace("call=1", "call=$uuid"))) {
            assertNull(NativeCallDiagnostics.sanitize(bad))
        }
    }
}

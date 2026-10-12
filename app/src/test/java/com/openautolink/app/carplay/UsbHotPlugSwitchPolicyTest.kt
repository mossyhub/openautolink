package com.openautolink.app.carplay
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
class UsbHotPlugSwitchPolicyTest {
    @Test fun activeOrInflightAaAndSelectedCarPlayAlwaysRequireConsent() {
        val policy = UsbHotPlugSwitchPolicy()
        assertTrue(policy.shouldPrompt(true, false, false))
        assertTrue(policy.shouldPrompt(false, true, false))
        assertTrue(policy.shouldPrompt(false, false, true))
        assertFalse(policy.shouldPrompt(false, false, false))
    }
    @Test fun wirelessCarPlayRequiresItsOwnExplicitModeAndEnablement() {
        val policy = UsbHotPlugSwitchPolicy()
        assertFalse(policy.wirelessCarPlay(true, "usb"))
        assertFalse(policy.wirelessCarPlay(false, "wireless"))
        assertFalse(policy.wirelessCarPlay(true, "unknown"))
        assertTrue(policy.wirelessCarPlay(true, "wireless"))
    }
    @Test fun acceptAwaitsRetirementAndNeverWritesEitherSavedNormalMode() = runBlocking {
        val policy = UsbHotPlugSwitchPolicy()
        val stored = mutableMapOf("aa" to "wpp", "cp" to "usb", "cpEnabled" to "true")
        val before = stored.toMap()
        val events = mutableListOf<String>()
        val retiring = CompletableDeferred<Unit>()
        val retired = CompletableDeferred<Unit>()
        val job = launch {
            assertTrue(policy.accept(1, { UsbHotPlugSwitchPolicy.Protocol.CARPLAY },
                { stored["cpEnabled"] == "true" }, { events += "claimed" },
                { events += "retiring"; retiring.complete(Unit); retired.await(); events += "retired" },
                { events += "usb-start"; true }, { events += "clear" }, { events += "restore" }))
        }
        retiring.await()
        assertEquals(listOf("claimed", "retiring"), events)
        assertEquals(1L, policy.generation)
        assertTrue(policy.shouldPrompt(false, false, false))
        retired.complete(Unit)
        job.join()
        assertEquals(listOf("claimed", "retiring", "retired", "usb-start"), events)
        assertEquals(before, stored)
        assertFalse(policy.accept(2, { UsbHotPlugSwitchPolicy.Protocol.ANDROID_AUTO }, { true },
            { fail("duplicate claim") }, { fail("duplicate retire") }, { fail("duplicate owner"); true }, {}, {}))
    }
    @Test fun detachOrDisableWhileAwaitingRetirementNeverAdmitsAndRestoresSavedPolicy() = runBlocking {
        for (disable in listOf(false, true)) {
            val policy = UsbHotPlugSwitchPolicy()
            var present = true
            var enabled = true
            val events = mutableListOf<String>()
            assertFalse(policy.accept(1, { if (present) UsbHotPlugSwitchPolicy.Protocol.CARPLAY else null },
                { enabled }, {}, { events += "retired"; if (disable) enabled = false else present = false },
                { fail("stale start"); true }, { events += "clear" }, { events += "aa-wpp" }))
            assertEquals(listOf("retired", "clear", "aa-wpp"), events)
            assertNull(policy.generation)
        }
    }
    @Test fun unplugAndStartFailureRestoreOnlyAfterExactOwnerRetirement() = runBlocking {
        val policy = UsbHotPlugSwitchPolicy()
        val events = mutableListOf<String>()
        assertTrue(policy.accept(1, { UsbHotPlugSwitchPolicy.Protocol.ANDROID_AUTO }, { true }, {}, {}, { true }, {}, {}))
        assertFalse(policy.finish(99, { fail("stale retire") }, { fail("stale clear") }, { fail("stale restore") }))
        assertTrue(policy.finish(1, { events += "usb-retired" }, { events += "clear" }, { events += "aa-wpp" }))
        assertEquals(listOf("usb-retired", "clear", "aa-wpp"), events)
        assertNull(policy.generation)
        assertFalse(policy.accept(2, { UsbHotPlugSwitchPolicy.Protocol.ANDROID_AUTO }, { true }, {},
            { events += "aa-retired" }, { false }, { events += "clear" }, { events += "aa-wpp" }))
        assertNull(policy.generation)
    }
    @Test fun androidAdmissionRemainsValidWithCarPlayDisabledAndFailedRetirementBlocksSuccessor() = runBlocking {
        val policy = UsbHotPlugSwitchPolicy()
        var cpEnabled = false
        var started = false
        assertTrue(policy.accept(1, { UsbHotPlugSwitchPolicy.Protocol.ANDROID_AUTO },
            { it == UsbHotPlugSwitchPolicy.Protocol.ANDROID_AUTO || cpEnabled }, {}, {}, { started = true; true }, {}, {}))
        assertTrue(started)
        try {
            policy.finish(1, { throw IllegalStateException("retirement failed") }, {}, {})
            fail("must propagate failed retirement")
        } catch (_: IllegalStateException) {}
        assertEquals(1L, policy.generation)
        assertFalse(policy.accept(2, { UsbHotPlugSwitchPolicy.Protocol.ANDROID_AUTO }, { true }, {}, {}, { true }, {}, {}))
    }
    @Test fun thrownStartupFailureAwaitsRetirementAgainBeforeClearingAndRestoring() = runBlocking {
        val policy = UsbHotPlugSwitchPolicy()
        val events = mutableListOf<String>()
        assertFalse(policy.accept(1, { UsbHotPlugSwitchPolicy.Protocol.ANDROID_AUTO }, { true }, {},
            { events += "retire" }, { events += "start"; throw IllegalStateException("start failure") },
            { events += "clear" }, { events += "restore" }))
        assertEquals(listOf("retire", "start", "retire", "clear", "restore"), events)
        assertNull(policy.generation)
    }
    @Test fun detachDuringSavedPreferenceReadDoesNotRetireCurrentProjection() = runBlocking {
        val policy = UsbHotPlugSwitchPolicy()
        var present = true
        assertFalse(policy.accept(1, { if (present) UsbHotPlugSwitchPolicy.Protocol.CARPLAY else null },
            { present = false; true }, { fail("stale claim") }, { fail("stale retirement") },
            { fail("stale startup"); true }, {}, {}))
        assertNull(policy.generation)
    }
    @Test fun confirmedSecondAttachmentRetiresFirstBeforeStartingAndOldCleanupCannotRestore() = runBlocking {
        val policy = UsbHotPlugSwitchPolicy()
        assertTrue(policy.accept(1, { UsbHotPlugSwitchPolicy.Protocol.ANDROID_AUTO }, { true }, {}, {}, { true }, {}, {}))
        val events = mutableListOf<String>()
        assertTrue(policy.accept(2, { UsbHotPlugSwitchPolicy.Protocol.CARPLAY }, { true }, {},
            { events += "first-retired" }, { events += "second-start"; true }, {}, {}, replaceCurrent = true))
        assertEquals(listOf("first-retired", "second-start"), events)
        assertEquals(2L, policy.generation)
        assertFalse(policy.finish(1, { fail("stale cleanup") }, {}, { fail("stale restore") }))
    }
}

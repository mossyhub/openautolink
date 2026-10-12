package com.openautolink.app.carplay

import com.openautolink.app.transport.bluetooth.AaWirelessBtControl
import com.openautolink.app.transport.bluetooth.AaWirelessBtServer
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Real switch policy -> real process advertiser retirement; only the Bluetooth OS server is fake. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF) // This app packages Android Conscrypt JNI, not the host library.
class UsbProjectionRetirementTest {
    private fun field(name: String) = AaWirelessBtControl::class.java.getDeclaredField(name).apply { isAccessible = true }

    @After fun cleanup() {
        field("btServer").set(AaWirelessBtControl, null)
        field("carPlayRetiringAdvertiser").set(AaWirelessBtControl, null)
        field("usbHandoff").set(AaWirelessBtControl, null)
    }

    @Test fun confirmedUsbSwitchAwaitsActualAdvertiserWithoutCarPlayReservation() = runBlocking {
        for (protocol in UsbHotPlugSwitchPolicy.Protocol.entries) {
            assertFalse(CarPlayOwnership.process.blocksAndroidAuto)
            val server = mockk<AaWirelessBtServer>()
            coEvery { server.stopAndAwait() } returns Unit
            field("btServer").set(AaWirelessBtControl, server)
            val policy = UsbHotPlugSwitchPolicy()
            val handoff = UsbProjectionHandoff()
            var admitted = false
            assertTrue(policy.accept(514L, { protocol }, { true }, { handoff.claim(514L) },
                { handoff.retire({}, {}) },
                { coVerify(exactly = 1) { server.stopAndAwait() }; admitted = true; true }, {}, {}))
            assertTrue(admitted)
            assertEquals(514L, policy.generation)
            assertFalse(CarPlayOwnership.process.blocksAndroidAuto)
            handoff.release()
        }
    }

    @Test fun usbFenceRejectsHandshakeFromStillInstalledWppGeneration() = runBlocking {
        val owner = AaWirelessBtControl.installSessionOwner("wpp", "synthetic-wlan")
        try {
            val handoff = UsbProjectionHandoff()
            handoff.claim(514L)
            assertNull("Claim must revoke WPP before any awaited stop", AaWirelessBtControl.beginHandshake())
            assertFalse(AaWirelessBtControl.isCurrentSessionOwner(owner))
            handoff.retire({}, {})
            handoff.release()
        } finally { AaWirelessBtControl.clearSessionOwner(owner) }
    }

    @Test fun advertiserFailureRetainsExactObjectGenerationAndBlocksSuccessorUntilRetry() = runBlocking {
        val server = mockk<AaWirelessBtServer>()
        var stops = 0
        coEvery { server.stopAndAwait() } coAnswers { if (++stops == 1) throw IllegalStateException("synthetic failure") }
        field("btServer").set(AaWirelessBtControl, server)
        val handoff = UsbProjectionHandoff()
        val policy = UsbHotPlugSwitchPolicy()
        try {
            policy.accept(514L, { UsbHotPlugSwitchPolicy.Protocol.CARPLAY }, { true }, { handoff.claim(514L) },
                { handoff.retire({}, { fail("AA stop must not precede failed advertiser") }) },
                { fail("admitted after failed stop"); true }, {}, {})
            fail("failure must propagate")
        } catch (_: IllegalStateException) { handoff.failed() }
        assertEquals(514L, policy.generation)
        assertSame(server, field("carPlayRetiringAdvertiser").get(AaWirelessBtControl))
        assertNotNull(handoff.failure.value)
        assertFalse(policy.accept(515L, { UsbHotPlugSwitchPolicy.Protocol.ANDROID_AUTO }, { true },
            { fail("unexpected claim") }, { fail("unexpected retirement") }, { true }, {}, {}))
        handoff.dismissFailure()
        assertNull(handoff.failure.value)
        var restored = false
        assertTrue(policy.finish(514L, { handoff.retire({}, {}) }, { handoff.release() }, { restored = true }))
        assertTrue(restored)
        assertNull(policy.generation)
        assertNull(handoff.failure.value)
        coVerify(exactly = 2) { server.stopAndAwait() }
    }

    @Test fun partialAaRetirementCannotReleaseCableFenceAndRetryClearsGuidance() = runBlocking {
        val handoff = UsbProjectionHandoff()
        handoff.claim(514L)
        try {
            handoff.retire({}, { throw IllegalStateException("synthetic AA close failure") })
            fail("must fail")
        } catch (_: IllegalStateException) { handoff.failed() }
        assertTrue(handoff.failure.value!!.contains("Retry USB"))
        try { handoff.release(); fail("must not restore normal WPP") } catch (_: IllegalStateException) {}
        // Explicit retry clears old guidance but does not drop its retained fence.
        handoff.claim(514L)
        assertNull(handoff.failure.value)
        try { AaWirelessBtControl.installSessionOwner("wpp", "synthetic-wlan"); fail("WPP resurrected") }
        catch (_: IllegalStateException) {}
        handoff.retire({}, {})
        handoff.admitted()
        assertNull(handoff.failure.value)
        handoff.release()
    }

    @Test fun staleUsbLeaseCannotRetireOrReleaseReplacement() = runBlocking {
        val old = AaWirelessBtControl.reserveUsbHandoff(514L)
        AaWirelessBtControl.retireForUsb(old)
        // A recreated consent manager can reuse the numeric generation; capability identity cannot.
        val replacement = AaWirelessBtControl.reserveUsbHandoff(514L)
        assertNotSame(old, replacement)
        assertFalse(AaWirelessBtControl.releaseUsbHandoff(old))
        try { AaWirelessBtControl.retireForUsb(old); fail("stale retirement") } catch (_: IllegalStateException) {}
        AaWirelessBtControl.retireForUsb(replacement)
        assertTrue(AaWirelessBtControl.releaseUsbHandoff(replacement))
    }

    @Test fun carPlayRetirementStillRequiresRealCarPlayOwnership() = runBlocking {
        val usb = AaWirelessBtControl.reserveUsbHandoff(514L)
        try { AaWirelessBtControl.retireForCarPlay(); fail("USB must not impersonate CP") }
        catch (_: IllegalStateException) {}
        AaWirelessBtControl.retireForUsb(usb)
        assertTrue(AaWirelessBtControl.releaseUsbHandoff(usb))
    }

    @Test fun switchCannotAdmitUntilAdvertiserCarPlayAndAaActuallyReturn() = runBlocking {
        val server = mockk<AaWirelessBtServer>()
        val advertiserEntered = CompletableDeferred<Unit>()
        val advertiserClosed = CompletableDeferred<Unit>()
        coEvery { server.stopAndAwait() } coAnswers { advertiserEntered.complete(Unit); advertiserClosed.await() }
        field("btServer").set(AaWirelessBtControl, server)
        val cpEntered = CompletableDeferred<Unit>()
        val cpClosed = CompletableDeferred<Unit>()
        val aaEntered = CompletableDeferred<Unit>()
        val aaClosed = CompletableDeferred<Unit>()
        val stages = mutableListOf<UsbProjectionHandoff.Stage>()
        val handoff = UsbProjectionHandoff { stages += it }
        val policy = UsbHotPlugSwitchPolicy()
        var admitted = false
        val job = launch {
            policy.accept(514L, { UsbHotPlugSwitchPolicy.Protocol.ANDROID_AUTO }, { true }, { handoff.claim(514L) },
                { handoff.retire({ cpEntered.complete(Unit); cpClosed.await() }, { aaEntered.complete(Unit); aaClosed.await() }) },
                { admitted = true; handoff.admitted(); true }, { handoff.release() }, {})
        }
        withTimeout(3_000) {
            advertiserEntered.await()
            assertFalse(cpEntered.isCompleted)
            assertFalse(admitted)
            assertEquals(514L, policy.generation)
            advertiserClosed.complete(Unit)
            cpEntered.await()
            assertFalse(aaEntered.isCompleted)
            assertFalse(admitted)
            cpClosed.complete(Unit)
            aaEntered.await()
            assertFalse(admitted)
            aaClosed.complete(Unit)
            job.join()
        }
        assertTrue(admitted)
        assertEquals(listOf(UsbProjectionHandoff.Stage.CLAIMED, UsbProjectionHandoff.Stage.ADVERTISER,
            UsbProjectionHandoff.Stage.CARPLAY, UsbProjectionHandoff.Stage.ANDROID_AUTO,
            UsbProjectionHandoff.Stage.RETIRED, UsbProjectionHandoff.Stage.ADMITTED), stages)
        handoff.release()
    }
}

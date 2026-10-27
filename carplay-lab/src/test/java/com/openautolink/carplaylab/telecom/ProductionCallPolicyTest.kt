package com.openautolink.carplaylab.telecom

import android.bluetooth.*
import android.content.Context
import android.os.Looper
import android.telecom.*
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 33, 34], manifest = Config.NONE)
class ProductionCallPolicyTest {
    private lateinit var backend: ManagedTelecomExperiment
    private lateinit var manager: TelecomManager
    @Before fun init() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_PHONE_NUMBERS)
        backend = ManagedTelecomExperiment(app)
        manager = app.getSystemService(TelecomManager::class.java)
    }
    @After fun close() { backend.disable(); shadowOf(Looper.getMainLooper()).idle() }
    private fun field(id: Int, bytes: ByteArray) = byteArrayOf(0, (bytes.size+4).toByte(), 0, id.toByte()) + bytes
    private fun call(status: Int = 2, suffix: String = "1") = Iap2Frame(0x4155,
        field(4, "00000000-0000-4000-8000-00000000000$suffix\u0000".toByteArray()) +
            field(2, byteArrayOf(status.toByte())) + field(3, byteArrayOf(1)) + field(9, byteArrayOf(0)))
    @Test fun failedLocalCleanupRetainsCommandAndAdmissionDebtUntilAcknowledgedRepair() {
        var token = ""; var sends = 0
        val bridge = ManagedIncomingBridge({ true }, { token = it }, { action -> action(); true },
            retireDispatch = { false }, controlIntent = { _, _ -> sends++; true })
        bridge.handle(call()); val connection = bridge.create(token)
        var result: java.util.concurrent.CompletableFuture<Boolean>? = null
        Thread { result = bridge.retireLocalCalls() }.also { it.start(); it.join(2000) }
        assertEquals(false, result!!.get())
        bridge.handle(call()) // fresh observation must NOT erase the failed cleanup fence
        connection.onAnswer()
        assertEquals("failed local cleanup re-enabled a native sender", 0, sends)
        bridge.close()
    }
    @Test fun offMainLocalRepairAcknowledgesOnlyAfterExactMainConnectionCleanup() {
        backend.enable(); shadowOf(manager).enablePhoneAccount(backend.handle, true); backend.refresh()
        org.robolectric.util.ReflectionHelpers.setStaticField(ManagedTelecomExperiment::class.java, "instance", backend)
        val service = Robolectric.buildService(ManagedCarPlayConnectionService::class.java).create().get()
        val os = shadowOf(manager); os.setConnectionService(service)
        val link = backend.authenticatedLink { true }!!
        link.handle(call()); shadowOf(Looper.getMainLooper()).idle()
        val connection = os.allowIncomingCall(os.onlyIncomingCall)!!
        var completion: Any? = null
        Thread { completion = backend.repairLocalPresentation() }.also { it.start(); it.join(2000); assertFalse(it.isAlive) }
        assertTrue("posted cleanup is not acknowledged cleanup", completion is java.util.concurrent.CompletableFuture<*>)
        val future = completion as java.util.concurrent.CompletableFuture<*>
        assertFalse(future.isDone)
        assertEquals(Connection.STATE_RINGING, connection.state)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true, future.get(2, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(Connection.STATE_DISCONNECTED, connection.state)
        service.onDestroy()
    }
    @Test fun guardRechecksImmediatelyBeforeAnnouncementAndDoesNotRetrySameRing() {
        backend.enable(); shadowOf(manager).enablePhoneAccount(backend.handle, true); backend.refresh()
        var checks = 0
        backend.applyProductionPolicy(true, false) { ++checks == 1 }
        val link = backend.authenticatedLink { true }!!
        link.handle(call()); shadowOf(Looper.getMainLooper()).idle()
        assertEquals("conflict between check and announcement escaped", 0, shadowOf(manager).allIncomingCalls.size)
        backend.applyProductionPolicy(true, false) { true }
        link.handle(call()); shadowOf(Looper.getMainLooper()).idle()
        assertEquals("same ring reannounced after conflict", 0, shadowOf(manager).allIncomingCalls.size)
        link.handle(call(0)); link.handle(call(suffix="2")); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, shadowOf(manager).allIncomingCalls.size)
    }
    @Test fun lateHfpDuringRingAndActiveLocallyRetiresWithoutRemoteCommandOrReannouncement() {
        backend.enable(); shadowOf(manager).enablePhoneAccount(backend.handle, true); backend.refresh()
        var clear = true
        backend.applyProductionPolicy(true, false) { clear }
        val sends = mutableListOf<com.shilapi.xcertplay.airplay.PhoneControl>()
        val link = backend.authenticatedLink(controlSender = { command, _ -> sends.add(command); true }) { true }!!
        var token = ""
        // Real service callbacks use the process singleton; keep this fixture's exact backend.
        org.robolectric.util.ReflectionHelpers.setStaticField(ManagedTelecomExperiment::class.java, "instance", backend)
        val service = Robolectric.buildService(ManagedCarPlayConnectionService::class.java).create().get()
        val os = shadowOf(manager); os.setConnectionService(service)
        for ((index, status) in listOf(2, 4).withIndex()) {
            val id = (index + 1).toString()
            link.handle(call(suffix=id)); shadowOf(Looper.getMainLooper()).idle()
            val connection = os.allowIncomingCall(os.lastIncomingCall)!!
            if (status == 4) { link.handle(call(4, id)); shadowOf(Looper.getMainLooper()).idle() }
            clear = false
            backend.repairLocalPresentation()
            connection.onAnswer(); connection.onReject(); connection.onDisconnect(); backend.refresh()
            assertEquals(Connection.STATE_DISCONNECTED, connection.state)
            assertEquals(DisconnectCause.LOCAL, connection.disconnectCause.code)
            assertTrue(sends.isEmpty())
            clear = true
            link.handle(call(status, id)); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(index + 1, os.allIncomingCalls.size)
            link.handle(call(0, id)); shadowOf(Looper.getMainLooper()).idle()
        }
        service.onDestroy()
    }
    @Test fun productionPolicyBundlesControlsWithoutDestroyingExactActiveOwnerOrRemovingAccount() {
        backend.enable(); shadowOf(manager).enablePhoneAccount(backend.handle, true); backend.refresh()
        val link = backend.authenticatedLink { true }!!
        link.handle(call()); shadowOf(Looper.getMainLooper()).idle()
        backend.applyProductionPolicy(carPlay = false, extraRingtone = false, canAdmit = { true })
        assertEquals("OpenAutoLink", manager.getPhoneAccount(backend.handle).label.toString())
        assertTrue("production controls bundled with presentation", backend.nativeControlsEnabled)
        link.handle(call(4)); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ManagedIncomingBridge.Cleanup.OPEN, link.cleanup)
        link.handle(call(0)); link.handle(call(suffix="2")); shadowOf(Looper.getMainLooper()).idle()
        assertEquals("new calls use saved handler", 1, shadowOf(manager).allIncomingCalls.size)
        assertNotNull(manager.getPhoneAccount(backend.handle))
    }
}

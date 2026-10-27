package com.openautolink.carplaylab.telecom

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.telecom.*
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.airplay.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Real decoder/bridge/ConnectionService callbacks; ONLY Android Telecom OS is shadowed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], manifest = Config.NONE)
class ManagedTelecomOsTest {
    private lateinit var experiment: ManagedTelecomExperiment
    private lateinit var manager: TelecomManager
    @Before fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.READ_PHONE_NUMBERS)
        experiment = ManagedTelecomExperiment(context)
        ReflectionHelpers.setStaticField(ManagedTelecomExperiment::class.java, "instance", experiment)
        manager = context.getSystemService(TelecomManager::class.java)
    }
    @After fun cleanup() {
        experiment.disable(); shadowOf(Looper.getMainLooper()).idle()
        ReflectionHelpers.setStaticField(ManagedTelecomExperiment::class.java, "instance", null)
    }
    private fun field(id: Int, bytes: ByteArray) = byteArrayOf(0, (bytes.size + 4).toByte(), 0, id.toByte()) + bytes
    private fun call(status: Int = 2, direction: Int = 1, id: String = "00000000-0000-4000-8000-000000000001") = Iap2Frame(0x4155,
        field(4, id.toByteArray() + byteArrayOf(0)) + field(2, byteArrayOf(status.toByte())) + field(3, byteArrayOf(direction.toByte())))
    private fun arm() {
        assertEquals(ManagedTelecomExperiment.Result.REGISTERED_USER_ENABLE_REQUIRED, experiment.enable())
        shadowOf(manager).enablePhoneAccount(experiment.handle, true) // mocked USER action, never production API
        assertEquals(ManagedTelecomExperiment.Result.ENABLED_FOR_THIS_PROCESS, experiment.refresh())
    }
    @Test fun nativeSelectionSurvivesReconnectButOldQueuedCommandNeverMigrates() {
        arm()
        experiment.enableNativeControls()
        val commands = mutableListOf<Pair<com.shilapi.xcertplay.airplay.PhoneControl, () -> Boolean>>()
        val sender: (com.shilapi.xcertplay.airplay.PhoneControl, () -> Boolean) -> Boolean = { command, current ->
            commands.add(command to current); true
        }
        val service = Robolectric.buildService(ManagedCarPlayConnectionService::class.java).create().get()
        val os = shadowOf(manager); os.setConnectionService(service)
        val old = experiment.authenticatedLink(sender) { true }!!
        old.handle(Iap2Frame(0x4155,call().payload + field(9,byteArrayOf(0))))
        shadowOf(Looper.getMainLooper()).idle()
        os.allowIncomingCall(os.onlyIncomingCall)!!.onAnswer()
        experiment.refresh() // drain serialized account/command admission
        assertEquals(1, commands.size)
        val replacement = experiment.authenticatedLink(sender) { true }!!
        assertTrue("enable choice must survive reconnect", experiment.nativeControlsEnabled)
        assertFalse("old command migrated", commands.first().second())
        replacement.handle(Iap2Frame(0x4155,call(id="00000000-0000-4000-8000-000000000002").payload + field(9,byteArrayOf(0))))
        shadowOf(Looper.getMainLooper()).idle()
        os.allowIncomingCall(os.lastIncomingCall)!!.onAnswer()
        experiment.refresh()
        assertEquals(2, commands.size)
        assertTrue(commands.last().second())
        replacement.close(); service.onDestroy()
    }
    @Test fun nativeSelectionSurvivesTemporaryAccountAuthorityLossWithoutAuthorizingCommands() {
        arm(); experiment.enableNativeControls()
        shadowOf(manager).enablePhoneAccount(experiment.handle, false)
        experiment.refresh()
        assertFalse(experiment.armed)
        shadowOf(manager).enablePhoneAccount(experiment.handle, true)
        experiment.refresh()
        assertTrue("OS unavailability must not reset the user's selection", experiment.nativeControlsEnabled)
    }
    @Test fun explicitNativeEnableRefreshesOwnAccountInsteadOfTreatingCacheAgeAsAnExperimentGate() {
        arm()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(20))
        assertEquals(ManagedTelecomExperiment.ControlResult.CONTROLS_ENABLED, experiment.enableNativeControls())
        assertTrue(experiment.nativeControlsEnabled)
    }
    @Test fun authenticatedRuntimeRefreshesExpiredAuthorityBeforeOneTimeLinkAdmission() {
        arm()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(3))
        val link=experiment.authenticatedLink { true }
        assertNotNull("one-time authenticated link permanently missed expired cache renewal",link)
        link!!.close()
    }
    @Test fun reconsentDuringOldLinkConstructionCannotInstallOldEpoch() {
        arm()
        var first = true
        val stale = experiment.authenticatedLink {
            if (first) { first = false; experiment.disable(); arm() }
            true
        }
        assertNull("old construction crossed explicit consent epoch", stale)
    }
    @Test fun concurrentInstallLeavesOnlyExactInstalledBridgeLive() {
        arm()
        val entered = java.util.concurrent.CountDownLatch(2)
        val release = java.util.concurrent.CountDownLatch(1)
        val links = java.util.concurrent.CopyOnWriteArrayList<ManagedIncomingBridge>()
        val workers = (1..2).map {
            Thread {
                var first = true
                experiment.authenticatedLink {
                    if (first) { first = false; entered.countDown(); release.await(2, java.util.concurrent.TimeUnit.SECONDS) }
                    true
                }?.let { links.add(it) }
            }.also { it.start() }
        }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)); release.countDown()
        workers.forEach { it.join(2000); assertFalse(it.isAlive) }
        links.forEach { it.handle(call()) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("orphan bridge retained announcement authority", 1, shadowOf(manager).allIncomingCalls.size)
    }
    @Test fun preparingPermissionOnUiRevokesImmediatelyButDoesNotRunConnectionCleanupInline() {
        arm()
        val service = Robolectric.buildService(ManagedCarPlayConnectionService::class.java).create().get()
        val os = shadowOf(manager); os.setConnectionService(service)
        try {
            val link = experiment.authenticatedLink { true }!!
            link.handle(call()); shadowOf(Looper.getMainLooper()).idle()
            val connection = os.allowIncomingCall(os.onlyIncomingCall)!!
            assertEquals(Connection.STATE_RINGING, connection.state)
            val generation = experiment.prepareEnable()
            assertFalse(experiment.armed)
            assertEquals("preparing an OS permission request ran Telecom cleanup inline on UI", Connection.STATE_RINGING, connection.state)
            experiment.census() // drain the dedicated account worker before the main cleanup dispatch
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Connection.STATE_DISCONNECTED, connection.state)
            experiment.cancelPendingEnable(generation)
        } finally { experiment.disable(); shadowOf(Looper.getMainLooper()).idle(); service.onDestroy() }
    }
    @Test fun offMainDisableReportsRemovedButCleanupPendingUntilMainRetires() {
        arm()
        val service = Robolectric.buildService(ManagedCarPlayConnectionService::class.java).create().get()
        val os = shadowOf(manager); os.setConnectionService(service)
        val link = experiment.authenticatedLink { true }!!
        link.handle(call()); shadowOf(Looper.getMainLooper()).idle()
        val connection = os.allowIncomingCall(os.onlyIncomingCall)!!
        var result: ManagedTelecomExperiment.Result? = null
        Thread { result = experiment.disable() }.also { it.start(); it.join(2000) }
        assertEquals("account removed is not local cleanup completion", "ACCOUNT_REMOVED_CLEANUP_PENDING", result?.name)
        assertNull(manager.getPhoneAccount(experiment.handle))
        assertEquals(Connection.STATE_RINGING, connection.state)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Connection.STATE_DISCONNECTED, connection.state)
        assertEquals(ManagedTelecomExperiment.Result.DISABLED_AND_REMOVED, experiment.disable())
        service.onDestroy()
    }
    @Test fun startupInspectAndRejectedConsentNeverRegisterOrLaunch() {
        assertFalse(experiment.armed)
        assertEquals(PassiveTelecomCensus.Account.ABSENT, experiment.census().ownAccount)
        assertNull(experiment.authenticatedLink { true })
        assertFalse(experiment.openEnableSettings())
        assertNull(manager.getPhoneAccount(experiment.handle))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }
    @Test fun persistentAccountDoesNotRestoreRuntimeConsentInNewProcessController() {
        arm()
        val fresh = ManagedTelecomExperiment(RuntimeEnvironment.getApplication())
        assertFalse(fresh.armed)
        assertNull(fresh.authenticatedLink { true })
        assertNotNull(manager.getPhoneAccount(experiment.handle))
        assertEquals(PhoneAccount.CAPABILITY_CALL_PROVIDER, manager.getPhoneAccount(experiment.handle).capabilities)
    }
    @Test fun realProtocolToManagedServiceAndDistinctCallbacksNeverGuessPhoneCommands() {
        arm()
        val service = Robolectric.buildService(ManagedCarPlayConnectionService::class.java).create().get()
        shadowOf(manager).setConnectionService(service)
        val link = experiment.authenticatedLink { true }!!
        link.handle(call()); shadowOf(Looper.getMainLooper()).idle()
        val os = shadowOf(manager)
        assertEquals(1, os.allIncomingCalls.size)
        val connection = os.allowIncomingCall(os.onlyIncomingCall)!!
        assertEquals(Connection.STATE_RINGING, connection.state)
        connection.onAnswer(); connection.onAnswer(0); connection.onReject(); connection.onReject(0)
        connection.onReject("not sent"); connection.onDisconnect()
        assertEquals(Connection.STATE_RINGING, connection.state)
        assertEquals(0, connection.connectionCapabilities)
        link.handle(call(3)); shadowOf(Looper.getMainLooper()).idle()
        connection.onAnswer(); connection.onDisconnect()
        assertEquals(Connection.STATE_RINGING, connection.state)
        link.handle(call(4)); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Connection.STATE_ACTIVE, connection.state)
        link.handle(call(5)); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Connection.STATE_HOLDING, connection.state)
        link.handle(call(0)); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Connection.STATE_DISCONNECTED, connection.state)
        assertEquals(DisconnectCause.REMOTE, connection.disconnectCause.code)
        link.handle(call()); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, os.allIncomingCalls.size)
        assertEquals(0, os.allOutgoingCalls.size)
        assertEquals(0, os.allUnknownCalls.size)
        service.onDestroy()
    }
    @Test fun lateOldSessionCreateFailsAndDisableOnlyLocallyDisconnectsCurrentCall() {
        arm()
        val os = shadowOf(manager)
        val service = Robolectric.buildService(ManagedCarPlayConnectionService::class.java).create().get()
        os.setConnectionService(service)
        var current = true
        val old = experiment.authenticatedLink { current }!!
        old.handle(call()); shadowOf(Looper.getMainLooper()).idle()
        val oldRequest = os.onlyIncomingCall
        current = false; old.close()
        val replacement = experiment.authenticatedLink { true }!!
        replacement.handle(call()); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Connection.STATE_DISCONNECTED, os.allowIncomingCall(oldRequest)!!.state)
        val connection = os.allowIncomingCall(os.lastIncomingCall)!!
        assertEquals(Connection.STATE_RINGING, connection.state)
        assertEquals(ManagedTelecomExperiment.Result.DISABLED_AND_REMOVED, experiment.disable())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(DisconnectCause.LOCAL, connection.disconnectCause.code)
        assertFalse(experiment.armed)
        assertNull(manager.getPhoneAccount(experiment.handle))
        service.onDestroy()
    }
    @Test fun queueOverflowFailsClosedAndReservedCleanupStillRetiresConnection() {
        arm()
        val service = Robolectric.buildService(ManagedCarPlayConnectionService::class.java).create().get()
        val os = shadowOf(manager); os.setConnectionService(service)
        val link = experiment.authenticatedLink { true }!!
        link.handle(call()); shadowOf(Looper.getMainLooper()).idle()
        val connection = os.allowIncomingCall(os.onlyIncomingCall)!!
        repeat(40) { link.handle(call()) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Connection.STATE_DISCONNECTED, connection.state)
        assertEquals(DisconnectCause.LOCAL, connection.disconnectCause.code)
        assertEquals(1, os.allIncomingCalls.size)
        service.onDestroy()
    }
    @Test fun productionSetupHotEnablesActualAuthenticatedRuntimeWithoutReconnectOrSeedCall() = runWiredNativeControls(null, hotEnable = true)
    @Test fun actualWiredAuthenticatedRuntimeDispatchesProtocolIntoTelecomAndRevokesOnClose() = runWiredNativeControls(null)
    @Test fun nativeQueuedRejectCannotCrossPermissionRevocation() = runWiredNativeControls("rejectPermission")
    @Test fun nativeQueuedAnswerCannotCrossControlDisable() = runWiredNativeControls("controls")
    @Test fun nativeQueuedAnswerCannotCrossAccountDisable() = runWiredNativeControls("disable")
    @Test fun nativeQueuedAnswerCannotCrossReconsent() = runWiredNativeControls("reconsent")
    @Test fun nativeQueuedAnswerCannotCrossReplacement() = runWiredNativeControls("replacement")
    @Test fun nativeQueuedAnswerCannotCrossAccountAuthorityLoss() = runWiredNativeControls("account")
    @Test fun nativeDisplayConsentAloneEmitsNoPhoneCommands() = runWiredNativeControls("display")
    private fun runWiredNativeControls(revocation: String?, hotEnable: Boolean = false) {
        if (!hotEnable) arm()
        if (!hotEnable && revocation != "display") assertEquals(ManagedTelecomExperiment.ControlResult.CONTROLS_ENABLED,
            experiment.enableNativeControls())
        val format = android.media.MediaFormat.createVideoFormat(android.media.MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
        val caps = org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(format).setIsEncoder(false).setColorFormats(intArrayOf(2130708361))
            .setProfileLevels(arrayOf(android.media.MediaCodecInfo.CodecProfileLevel().apply {
                profile = android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                level = android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel52
            })).build()
        org.robolectric.shadows.ShadowMediaCodecList.addCodec(org.robolectric.shadows.MediaCodecInfoBuilder.newBuilder()
            .setName("telecom.avc").setIsEncoder(false).setCapabilities(caps).build())
        val surface = org.mockito.Mockito.mock(android.view.Surface::class.java)
        org.mockito.Mockito.`when`(surface.isValid).thenReturn(true)
        val receipts = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val receiver = com.openautolink.carplaylab.media.ProjectionReceiver(RuntimeEnvironment.getApplication(),
            "02:00:00:00:00:01", surface, org.mockito.Mockito.mock(com.shilapi.xcertplay.mfi.MfiAuthenticator::class.java),
            wired = true, onStatus = { receipts.add(it) })
        val service = Robolectric.buildService(ManagedCarPlayConnectionService::class.java).create().get()
        val os = shadowOf(manager); os.setConnectionService(service)
        val identity = AirPlayIdentity.generate()
        val store = PairingStore()
        val session = AirPlaySession(java.net.Socket(), AirPlayConfig("test", "test", "test", "950.7.1", AirPlayDisplayConfig(800, 480)),
            identity, store, null, object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        val listener = java.net.ServerSocket(0)
        val left = SocketStream(java.net.Socket("127.0.0.1", listener.localPort))
        val right = SocketStream(listener.accept()); listener.close()
        val channel = com.shilapi.xcertplay.iap2.session.Iap2Session.openTunnel(left)
        val phone = com.shilapi.xcertplay.iap2.session.Iap2Session.openTunnel(right)
        @Suppress("UNCHECKED_CAST")
        val sessions = com.openautolink.carplaylab.media.ProjectionReceiver::class.java.getDeclaredField("sessions")
            .apply { isAccessible = true }.get(receiver) as MutableSet<com.shilapi.xcertplay.airplay.AirPlaySession>
        sessions.add(session)
        receiver.setRuntimeIdentification(com.shilapi.xcertplay.transport.Iap2IdentificationConfig(
            "OAL", "OAL", "OAL", "synthetic", "1", "Android", 1))
        try {
            assertFalse("unauthenticated session cannot consume iAP2", receiver.startWiredRuntime(channel))
            authenticateSyntheticController(session, store)
            assertTrue(channel.awaitReady(3000)); assertTrue(phone.awaitReady(3000))
            assertTrue(receiver.startWiredRuntime(channel))
            // Real iAP2 link/framer/session; synthetic endpoint, not physical iPhone acceptance.
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            var modernSubscription = false
            while (!modernSubscription && System.nanoTime() < deadline) {
                val frame = phone.recv(100)
                if (frame?.messageId == 0x4154 && frame.body().has(12)) modernSubscription = true
            }
            assertTrue(modernSubscription)
            if (hotEnable) {
                assertTrue("setup must never seed a ringing or active call", os.allIncomingCalls.isEmpty())
                experiment.applyProductionPolicy(true, false) { true }
                assertEquals(ManagedTelecomExperiment.Result.REGISTERED_USER_ENABLE_REQUIRED, experiment.prepareProduction())
                os.enablePhoneAccount(experiment.handle, true)
                assertEquals(ManagedTelecomExperiment.Result.ENABLED_FOR_THIS_PROCESS, experiment.prepareProduction())
            }
            phone.send(Iap2Frame(0x4155, call().payload + field(9, byteArrayOf(0)) +
                field(0, "+15550123456".toByteArray() + byteArrayOf(0)) +
                field(1, "Initial 私密".toByteArray() + byteArrayOf(0))))
            while (os.allIncomingCalls.isEmpty() && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
            }
            assertEquals(1, os.allIncomingCalls.size)
            val connection = os.allowIncomingCall(os.onlyIncomingCall)!!
            assertEquals(Connection.STATE_RINGING, connection.state)
            // Actual native callback path is armed only by the separate supervised-call consent.
            assertEquals("Initial 私密", connection.callerDisplayName)
            assertEquals("+15550123456", connection.address.schemeSpecificPart)
            // Retain private queue access below solely for the pre-existing stale-runtime worker gate.
            val queuePhone = receiver.javaClass.declaredMethods.singleOrNull { it.name == "queuePhoneControl" }
            assertNotNull("bounded off-main phone input seam missing", queuePhone)
            queuePhone!!.isAccessible = true
            receiver.javaClass.getDeclaredField("active").apply { isAccessible = true }.set(receiver, session)
            java.net.ServerSocket(0).use { events -> java.net.Socket("127.0.0.1", events.localPort).use { peer -> events.accept().use { socket ->
                val key = ByteArray(32) { (it+21).toByte() }; val cipher=ControlCipher(key,key)
                session.javaClass.getDeclaredField("eventSocket").apply { isAccessible=true }.set(session,socket)
                session.javaClass.getDeclaredField("eventCipher").apply { isAccessible=true }.set(session,ControlCipher(key,key))
                // Authenticated decoder -> bridge -> actual ConnectionService -> receiver status.
                phone.send(Iap2Frame(0x4155, field(4, "00000000-0000-4000-8000-000000000001".toByteArray() + byteArrayOf(0)) +
                    field(1, "Late 私密".toByteArray() + byteArrayOf(0))))
                val identityDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                while ((!receipts.any { it.contains("stage=native-identity boundary=UPDATE") && it.contains("native=RINGING") } ||
                    connection.callerDisplayName != "Late 私密") && System.nanoTime() < identityDeadline) {
                    shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
                }
                assertEquals(Connection.STATE_RINGING, connection.state)
                assertEquals("Late 私密", connection.callerDisplayName)
                assertTrue("identity receipt dropped before actual receiver status", receipts.any {
                    it.contains("stage=native-identity boundary=UPDATE") && it.contains("native=RINGING") && it.contains("result=MATCH") })
                assertTrue(receipts.none { it.contains("私密") || it.contains("15550123456") })
                if (revocation != null) {
                    val input = receiver.javaClass.getDeclaredField("input").apply { isAccessible=true }.get(receiver) as java.util.concurrent.ExecutorService
                    val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
                    input.submit { entered.countDown();release.await(3,java.util.concurrent.TimeUnit.SECONDS) }
                    assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
                    if (revocation == "rejectPermission") connection.onReject("private not an SMS") else connection.onAnswer()
                    experiment.refresh() // drain own-account read; native command is now behind barrier
                    when(revocation) {
                        "controls" -> experiment.disableNativeControls()
                        "disable" -> experiment.disable()
                        "reconsent" -> { experiment.disableNativeControls(); experiment.enableNativeControls() }
                        "replacement" -> experiment.authenticatedLink { true }
                        "account" -> { os.enablePhoneAccount(experiment.handle,false);experiment.refresh() }
                        "rejectPermission" -> shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(android.Manifest.permission.READ_PHONE_NUMBERS)
                    }
                    release.countDown(); input.submit {}.get(2,java.util.concurrent.TimeUnit.SECONDS)
                    peer.soTimeout=100
                    assertTrue("native stale $revocation command escaped",runCatching {peer.getInputStream().read()}.exceptionOrNull() is java.net.SocketTimeoutException)
                    if (revocation == "rejectPermission") assertNull("queued Reject escaped to iAP2", phone.recv(200))
                    // All no-byte assertions complete before ordinary structured teardown.
                } else {
                connection.onAnswer(); connection.onAnswer(0) // actual OS callbacks, pending dedupe
                peer.soTimeout=2000
                fun receivePair(press: Int) {
                    for (selector in listOf(press,0)) {
                        val input=peer.getInputStream(); val len=input.readNBytes(2)
                        val n=(len[0].toInt() and 255) or ((len[1].toInt() and 255) shl 8)
                        val plain=cipher.decrypt(len+input.readNBytes(n+16)).data
                        val command=BplistCodec.decode(plain.copyOfRange(plain.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")+4,plain.size)) as Map<*,*>
                        assertEquals(AirPlayHid.TELEPHONY_HID_UID.toString(16),command["uuid"])
                        assertArrayEquals(byteArrayOf(selector.toByte()),command["hidReport"] as ByteArray)
                    }
                }
                receivePair(1)
                assertEquals(Connection.STATE_RINGING,connection.state) // writes are not acknowledgement
                phone.send(call(0, id="00000000-0000-4000-8000-000000000099"))
                Thread.sleep(50); shadowOf(Looper.getMainLooper()).idle()
                assertEquals("wrong UUID cannot acknowledge or end Answer",Connection.STATE_RINGING,connection.state)
                phone.send(call(3))
                val transitionDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                while (!receipts.any { it.contains("first=ANSWER_TRANSITION") } && System.nanoTime() < transitionDeadline) {
                    shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
                }
                assertTrue("first-latch cause dropped by actual receiver status", receipts.any { it.contains("first=ANSWER_TRANSITION") })
                assertEquals(Connection.STATE_RINGING, connection.state)
                phone.send(call(4))
                val activeDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                while(connection.state!=Connection.STATE_ACTIVE && System.nanoTime()<activeDeadline) {
                    shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
                }
                assertEquals(Connection.STATE_ACTIVE,connection.state)
                connection.onReject(); connection.onReject(0); connection.onReject("withheld")
                assertTrue(connection.audioModeIsVoip)
                connection.onDisconnect(); connection.onDisconnect()
                receivePair(3)
                assertEquals(Connection.STATE_ACTIVE,connection.state)
                phone.send(call(0))
                val endDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                while(connection.state!=Connection.STATE_DISCONNECTED && System.nanoTime()<endDeadline) {
                    shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
                }
                assertEquals(DisconnectCause.REMOTE,connection.disconnectCause.code)
                // First call now also exercises raw3 and first-latch receipts. Start the second
                // call in a fresh real one-second diagnostic window; keep the production 32/s
                // admission bound and every Reject/no-HID assertion unchanged.
                Thread.sleep(1100)
                // A second actual native call rejects through communications, NEVER HID Drop.
                val rejectedId = "00000000-0000-4000-8000-000000000003"
                phone.send(Iap2Frame(0x4155, call(id = rejectedId).payload + field(9, byteArrayOf(0))))
                val rejectDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                while (os.allIncomingCalls.size < 2 && System.nanoTime() < rejectDeadline) {
                    shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
                }
                val rejected = os.allowIncomingCall(os.lastIncomingCall)!!
                rejected.onReject(); rejected.onReject(0); rejected.onReject("private text must not be sent")
                val rejectFrame = phone.recv(2000)
                assertEquals("actual native Reject not wired to authenticated iAP2", 0x415C, rejectFrame?.messageId)
                assertArrayEquals(byteArrayOf(0, 5, 0, 0, 0), rejectFrame!!.payload)
                assertEquals(Connection.STATE_RINGING, rejected.state)
                assertNull("Reject overloads duplicated command", phone.recv(100))
                peer.soTimeout = 100
                assertTrue("Reject must not alias HID End", runCatching { peer.getInputStream().read() }.exceptionOrNull() is java.net.SocketTimeoutException)
                phone.send(call(0, id = rejectedId))
                val rejectedEnd = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                while (rejected.state != Connection.STATE_DISCONNECTED && System.nanoTime() < rejectedEnd) {
                    shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
                }
                assertEquals(DisconnectCause.REMOTE, rejected.disconnectCause.code)
                val receiptDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
                while (!receipts.contains("media stage=phone-control command=REJECT outcome=WRITTEN") && System.nanoTime() < receiptDeadline) Thread.sleep(10)
                assertTrue("finite Reject receipt was dropped by actual receiver status ingress", receipts.contains("media stage=phone-control command=REJECT outcome=WRITTEN"))
                assertTrue("bridge receipt never reached actual receiver status", receipts.any { it.startsWith("media stage=native-call action=REJECT ") })
                val input = receiver.javaClass.getDeclaredField("input").apply { isAccessible=true }.get(receiver) as java.util.concurrent.ExecutorService
                for (staleOwner in listOf(false,true)) {
                    val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
                    input.submit { entered.countDown();release.await(2,java.util.concurrent.TimeUnit.SECONDS) }
                    assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS))
                    val current=java.util.concurrent.atomic.AtomicBoolean(true)
                    assertEquals(true,queuePhone.invoke(receiver,session,PhoneControl.END,{current.get()}))
                    if(staleOwner) receiver.javaClass.getDeclaredField("runtimeOwner").apply {isAccessible=true}.set(receiver,null)
                    else current.set(false)
                    release.countDown();input.submit {}.get(2,java.util.concurrent.TimeUnit.SECONDS)
                    peer.soTimeout=100
                    assertTrue("queued stale control escaped",runCatching {peer.getInputStream().read()}.exceptionOrNull() is java.net.SocketTimeoutException)
                    if(staleOwner) receiver.javaClass.getDeclaredField("runtimeOwner").apply {isAccessible=true}.set(receiver,session)
                }
                }
            } } }
            if (revocation == null) {
            // Keep the original held-main retirement gate on a distinct display observation.
            phone.send(call(id="00000000-0000-4000-8000-000000000002"))
            val cleanupDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            while(os.allIncomingCalls.size<3 && System.nanoTime()<cleanupDeadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
            }
            assertEquals(3,os.allIncomingCalls.size)
            val cleanupConnection=os.allowIncomingCall(os.lastIncomingCall)!!
            Thread { receiver.close() }.also { it.start(); it.join(2000) } // main deliberately held
            val early = java.util.concurrent.atomic.AtomicBoolean(true)
            Thread { early.set(receiver.awaitClosed(100)) }.also { it.start(); it.join(2000) }
            assertFalse("receiver acknowledged before native cleanup", early.get())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(DisconnectCause.LOCAL, cleanupConnection.disconnectCause.code)
            }
        } finally {
            receiver.close()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(receiver.awaitClosed(5000))
            // EOF is a historical synthetic-peer error; concrete retirement remains mandatory.
            try { phone.close() } catch (failure: java.io.IOException) { println("synthetic peer EOF retained: ${failure.message}") }
            assertTrue(phone.awaitClosed(3000)); service.onDestroy()
            org.robolectric.shadows.ShadowMediaCodecList.reset()
        }
    }
    private fun authenticateSyntheticController(session: AirPlaySession, store: PairingStore) {
        val controller = AirPlayCrypto.ed25519Generate()
        val id = "synthetic-telecom-controller".toByteArray()
        store.save(id.toString(Charsets.UTF_8), controller.publicKey)
        fun tlv(vararg values: Pair<Int, ByteArray>) = Tlv8Codec.encode(values.map { Tlv8Item(it.first, it.second) })
        val ephemeral = AirPlayCrypto.x25519Generate()
        val m2 = Tlv8Codec.decode(session.pairVerify.handle(tlv(6 to byteArrayOf(1), 3 to ephemeral.publicKey)))
        val remote = m2.getValue(3)
        val shared = AirPlayCrypto.x25519Shared(ephemeral.privateKey, remote)
        val key = AirPlayCrypto.hkdfSha512(shared, "Pair-Verify-Encrypt-Salt".toByteArray(), "Pair-Verify-Encrypt-Info".toByteArray())
        val signature = AirPlayCrypto.ed25519Sign(controller.privateKey, ephemeral.publicKey + id + remote)
        session.pairVerify.handle(tlv(6 to byteArrayOf(3), 5 to AirPlayCrypto.chachaSeal(key,
            AirPlayCrypto.nonceLabel("PV-Msg03"), tlv(1 to id, 10 to signature))))
        assertTrue(session.finishControlAuthentication())
        assertTrue(session.controlAuthenticated)
    }
    private class SocketStream(private val socket: java.net.Socket) : com.shilapi.xcertplay.transport.BlockingDuplexByteStream {
        override fun send(data: ByteArray) { socket.getOutputStream().write(data) }
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            socket.soTimeout = timeoutMillis.toInt().coerceAtLeast(1)
            val bytes = ByteArray(maxBytes)
            return try { val size = socket.getInputStream().read(bytes); if (size < 0) byteArrayOf() else bytes.copyOf(size) }
                catch (_: java.net.SocketTimeoutException) { null }
        }
        override fun close() = socket.close()
    }
    @Test fun deniedOsAdmissionCannotLaterCreateFromOldRequest() {
        arm()
        val os = shadowOf(manager)
        val service = Robolectric.buildService(ManagedCarPlayConnectionService::class.java).create().get()
        os.setConnectionService(service)
        val link = experiment.authenticatedLink { true }!!
        link.handle(call()); shadowOf(Looper.getMainLooper()).idle()
        val request = os.onlyIncomingCall
        os.denyIncomingCall(request)
        assertEquals(Connection.STATE_DISCONNECTED,
            service.onCreateIncomingConnection(null, ConnectionRequest(request.phoneAccount, null, request.extras)).state)
        link.handle(call()); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, os.allIncomingCalls.size)
        service.onDestroy()
    }
    @Test fun activeHeldOutgoingInvalidAndUuidlessEndDoNotCreateCalls() {
        arm()
        val link = experiment.authenticatedLink { true }!!
        link.handle(call(4)); link.handle(call(5)); link.handle(call(2, 2))
        link.handle(call(id = "1-1-1-1-1"))
        link.handle(Iap2Frame(0x4155, field(2, byteArrayOf(0))))
        link.handle(Iap2Frame(0x4158, field(10, byteArrayOf(0))))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, shadowOf(manager).allIncomingCalls.size)
        assertEquals(0, shadowOf(manager).allUnknownCalls.size)
    }
}

package com.openautolink.carplaylab.telecom

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], manifest = Config.NONE)
class ManagedIncomingIntegrationTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private fun field(id: Int, bytes: ByteArray) = byteArrayOf(0, (bytes.size + 4).toByte(), 0, id.toByte()) + bytes
    private fun frame(status: Int = 2, direction: Int = 1) = com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4155,
        field(4, id.toByteArray() + byteArrayOf(0)) + field(2, byteArrayOf(status.toByte())) + field(3, byteArrayOf(direction.toByte())))
    @Test fun authenticatedRingCreatesExactlyOneRealRingingConnection() {
        val tokens = mutableListOf<String>()
        val queue = java.util.ArrayDeque<() -> Unit>()
        val bridge = ManagedIncomingBridge({ true }, tokens::add, { queue.add(it); true })
        assertTrue(bridge.handle(frame()))
        assertEquals(0, tokens.size)
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(1, tokens.size)
        val connection = bridge.create(tokens.single())
        assertEquals(android.telecom.Connection.STATE_RINGING, connection.state)
        bridge.handle(frame()); while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(1, tokens.size)
        assertEquals(android.telecom.Connection.STATE_DISCONNECTED, bridge.create(tokens.single()).state)
    }
    @Test fun closeAcknowledgementWaitsForConcreteRetirementAndRejectedPostCannotSucceed() {
        val tokens = mutableListOf<String>()
        val retire = java.util.ArrayDeque<() -> Unit>()
        val bridge = ManagedIncomingBridge({ true }, tokens::add, { it(); true }, retireDispatch = { retire.add(it); true })
        bridge.handle(frame()); val connection = bridge.create(tokens.single())
        val worker = Thread { bridge.close() }; worker.start(); worker.join(1000)
        val await = bridge.javaClass.methods.singleOrNull { it.name == "awaitClosed" }
        assertNotNull("close needs a concrete cleanup acknowledgement", await)
        assertEquals(false, await!!.invoke(bridge, 0L))
        assertEquals(android.telecom.Connection.STATE_RINGING, connection.state)
        retire.removeFirst()()
        assertEquals(true, await.invoke(bridge, 0L))
        assertEquals(android.telecom.Connection.STATE_DISCONNECTED, connection.state)
        val rejected = ManagedIncomingBridge({ true }, {}, { true }, retireDispatch = { false })
        Thread { rejected.close() }.also { it.start(); it.join(1000) }
        assertEquals(false, await.invoke(rejected, 0L))
    }
    @Test fun mainCloseRetiresInlineWithoutWaitingForItsOwnQueue() {
        val tokens = mutableListOf<String>()
        val bridge = ManagedIncomingBridge({ true }, tokens::add, { it(); true }, retireDispatch = { false })
        bridge.handle(frame()); val connection = bridge.create(tokens.single())
        bridge.close()
        assertEquals("main must retire inline", android.telecom.Connection.STATE_DISCONNECTED, connection.state)
    }
    @Test fun failedDestroyWithholdsAckAndRetryDestroysEvenAlreadyDisconnected() {
        val tokens = mutableListOf<String>()
        val bridge = ManagedIncomingBridge({ true }, tokens::add, { it(); true })
        bridge.handle(frame()); bridge.create(tokens.single())
        val records = bridge.javaClass.getDeclaredField("records").apply { isAccessible = true }.get(bridge) as Map<*, *>
        val record = records.values.single()!!
        val connection = org.mockito.Mockito.mock(android.telecom.Connection::class.java)
        org.mockito.Mockito.`when`(connection.state).thenReturn(android.telecom.Connection.STATE_DISCONNECTED)
        org.mockito.Mockito.doThrow(IllegalStateException("synthetic OS failure")).doNothing().`when`(connection).destroy()
        record.javaClass.getDeclaredField("connection").apply { isAccessible = true }.set(record, connection)
        bridge.close()
        assertFalse("destroy must be acknowledged, not just disconnected", bridge.awaitClosed(0))
        bridge.close()
        assertTrue(bridge.awaitClosed(0))
        org.mockito.Mockito.verify(connection, org.mockito.Mockito.times(2)).destroy()
    }
    @Test fun nativeIntentSeamRequiresSingleKnownUnconferencedCallAndDedupeUntilRemoteFact() {
        val tokens = mutableListOf<String>()
        val intents = mutableListOf<Pair<com.shilapi.xcertplay.airplay.PhoneControl, () -> Boolean>>()
        val constructor = ManagedIncomingBridge::class.java.constructors.firstOrNull { c ->
            c.parameterTypes.size == 6 && c.parameterTypes.last() == kotlin.jvm.functions.Function2::class.java
        }
        assertNotNull("native typed intent seam missing", constructor)
        val bridge = constructor!!.newInstance({ true }, { token: String -> tokens.add(token); Unit },
            { action: () -> Unit -> action(); true }, { true }, { action: () -> Unit -> action(); true },
            { control: com.shilapi.xcertplay.airplay.PhoneControl, fence: () -> Boolean -> intents.add(control to fence); true }) as ManagedIncomingBridge
        // Explicit false conference fact, not inferred from missing fields. Controlled lab only.
        val ringing = com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4155, frame().payload + field(9, byteArrayOf(0)))
        bridge.handle(ringing); val connection = bridge.create(tokens.single())
        assertTrue("native call UI lacks Reject warning",connection.statusHints?.label.toString().contains("Reject does nothing"))
        connection.onAnswer(); connection.onAnswer(0); connection.onReject(); connection.onDisconnect()
        assertEquals(listOf(com.shilapi.xcertplay.airplay.PhoneControl.ANSWER), intents.map { it.first })
        assertTrue(intents.single().second())
        assertEquals(android.telecom.Connection.STATE_RINGING, connection.state)
        bridge.handle(frame(4))
        assertFalse("old queued ring command survived status change", intents.first().second())
        connection.onDisconnect(); connection.onDisconnect(); connection.onReject(0)
        assertEquals(listOf(com.shilapi.xcertplay.airplay.PhoneControl.ANSWER, com.shilapi.xcertplay.airplay.PhoneControl.END), intents.map { it.first })
        assertEquals(android.telecom.Connection.STATE_ACTIVE, connection.state)
        bridge.close(); assertFalse(intents.last().second())
    }
    @Test fun enabledControlsHandleTwoSequentialIncomingCallsWithoutRearming() {
        val tokens = mutableListOf<String>()
        val intents = mutableListOf<com.shilapi.xcertplay.airplay.PhoneControl>()
        val bridge = ManagedIncomingBridge({true}, tokens::add, {it();true}, controlIntent={command,_ -> intents.add(command);true})
        for (suffix in listOf("000000000001", "000000000002")) {
            fun event(status: Int) = com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4155,
                field(4, ("00000000-0000-4000-8000-" + suffix).toByteArray() + byteArrayOf(0)) +
                    field(2, byteArrayOf(status.toByte())) + field(3, byteArrayOf(1)) + field(9, byteArrayOf(0)))
            bridge.handle(event(2))
            val connection = bridge.create(tokens.last())
            connection.onAnswer()
            bridge.handle(event(4))
            connection.onDisconnect()
            bridge.handle(event(0))
            assertEquals(android.telecom.Connection.STATE_DISCONNECTED, connection.state)
        }
        assertEquals(listOf(com.shilapi.xcertplay.airplay.PhoneControl.ANSWER, com.shilapi.xcertplay.airplay.PhoneControl.END,
            com.shilapi.xcertplay.airplay.PhoneControl.ANSWER, com.shilapi.xcertplay.airplay.PhoneControl.END), intents)
        bridge.close()
    }
    @Test fun unconfirmedTimeoutRevokesControlsWithoutExpiringThePhoneObservation() {
        val tokens=mutableListOf<String>();val intents=mutableListOf<com.shilapi.xcertplay.airplay.PhoneControl>()
        var fence: (() -> Boolean)? = null
        val bridge=ManagedIncomingBridge({true},tokens::add,{it();true},controlIntent={command,current -> intents.add(command);fence=current;true})
        bridge.handle(com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4155,frame().payload+field(9,byteArrayOf(0))))
        val connection=bridge.create(tokens.single())
        connection.onAnswer()
        assertTrue(fence!!())
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(6))
        assertFalse("unconfirmed queued command survives timeout",fence!!())
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(android.telecom.Connection.STATE_RINGING,connection.state)
        bridge.handle(frame(4)); connection.onDisconnect()
        assertEquals("timeout must revoke End as well, not retry",1,intents.size)
        assertEquals(android.telecom.Connection.STATE_ACTIVE,connection.state)
        bridge.handle(frame(0))
        assertEquals(android.telecom.DisconnectCause.REMOTE,connection.disconnectCause.code)
        val second = com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4155,
            field(4,"00000000-0000-4000-8000-000000000002".toByteArray()+byteArrayOf(0)) +
                field(2,byteArrayOf(2)) + field(3,byteArrayOf(1)) + field(9,byteArrayOf(0)))
        bridge.handle(second)
        bridge.create(tokens.last()).onAnswer()
        assertEquals("failed operation must not expire the enabled experiment for later calls",2,intents.size)
        bridge.close()
    }
    @Test fun observedCompetingCallPermanentlyRevokesThisSingleCallExperiment() {
        val tokens=mutableListOf<String>();val intents=mutableListOf<com.shilapi.xcertplay.airplay.PhoneControl>()
        val bridge=ManagedIncomingBridge({true},tokens::add,{it();true},controlIntent={command,_ -> intents.add(command);true})
        bridge.handle(com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4155,frame().payload+field(9,byteArrayOf(0))))
        val connection=bridge.create(tokens.single());connection.onAnswer();bridge.handle(frame(4))
        fun other(status: Int) = com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4155,
            field(4,"00000000-0000-4000-8000-000000000002".toByteArray()+byteArrayOf(0))+field(2,byteArrayOf(status.toByte())))
        bridge.handle(other(5));bridge.handle(other(0));connection.onDisconnect()
        assertEquals("observed multicall is not repaired by local count returning to one",1,intents.size)
    }
    @Test fun capacityExhaustionIsObservableWithoutEvictionOrInventedExpiry() {
        val tokens = mutableListOf<String>()
        val bridge = ManagedIncomingBridge({ true }, tokens::add, { it(); true })
        repeat(17) { n ->
            val uuid = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
            bridge.handle(com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4155,
                field(4, uuid.toByteArray() + byteArrayOf(0)) + field(2, byteArrayOf(4))))
        }
        val diagnostic = bridge.javaClass.methods.singleOrNull { it.name == "getCapacityDrops" }
        assertNotNull("capacity exhaustion is invisible", diagnostic)
        assertEquals(1, diagnostic!!.invoke(bridge))
        assertEquals(0, tokens.size)
    }
    @Test fun controlsWithholdHeldUnknownWaitingConferenceMultipleAndMissingConferenceFacts() {
        for (hazard in listOf("held", "unknown", "outgoing", "conference", "multiple", "missing-conference", "count-multiple")) {
            val tokens=mutableListOf<String>(); val intents=mutableListOf<com.shilapi.xcertplay.airplay.PhoneControl>()
            val bridge=ManagedIncomingBridge({true},tokens::add,{it();true}, controlIntent={control,_ -> intents.add(control);true})
            val extra=if(hazard=="missing-conference") byteArrayOf() else field(9,byteArrayOf(if(hazard=="conference") 1 else 0))
            bridge.handle(com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4155,frame().payload+extra))
            val connection=bridge.create(tokens.single())
            when(hazard) {
                "held"->bridge.handle(frame(5))
                "unknown"->bridge.handle(frame(3))
                "outgoing"->bridge.handle(frame(2,2))
                "multiple"->bridge.handle(com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4155,
                    field(4,"00000000-0000-4000-8000-000000000002".toByteArray()+byteArrayOf(0))+field(2,byteArrayOf(5))))
                "count-multiple"->bridge.handle(com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4158,field(10,byteArrayOf(2))))
            }
            connection.onAnswer();connection.onDisconnect();connection.onReject()
            assertEquals(hazard,0,intents.size)
            bridge.close()
        }
    }
    @Test fun queuedNewObservationRevokesPriorCommandBeforeMainDelivery() {
        val tokens=mutableListOf<String>();val queue=java.util.ArrayDeque<() -> Unit>()
        var fence: (() -> Boolean)?=null
        val bridge=ManagedIncomingBridge({true},tokens::add,{queue.add(it);true},controlIntent={_,current -> fence=current;true})
        bridge.handle(com.shilapi.xcertplay.iap2.wire.Iap2Frame(0x4155,frame().payload+field(9,byteArrayOf(0))))
        queue.removeFirst()();val connection=bridge.create(tokens.single());connection.onAnswer()
        assertTrue(fence!!())
        bridge.handle(frame(3)) // main is stalled, but unsafe new evidence already arrived
        assertFalse("queued unknown observation left old Answer writable",fence!!())
        bridge.close()
    }
    @Test fun managedServiceAndConsentControllerExist() {
        assertNotNull("managed service missing", runCatching { Class.forName("com.openautolink.carplaylab.telecom.ManagedCarPlayConnectionService") }.getOrNull())
        assertNotNull("explicit consent controller missing", runCatching { Class.forName("com.openautolink.carplaylab.telecom.ManagedTelecomExperiment") }.getOrNull())
    }
    @Test fun authenticatedReceiverDispatchesOptInBridgeAndRetiresIt() {
        val source = java.io.File("src/main/java/com/openautolink/carplaylab/media/ProjectionReceiver.kt").readText()
        assertTrue("runtime does not create an authenticated Telecom link", source.contains("telecomExperiment.authenticatedLink"))
        assertTrue(source.contains("incoming?.handle(frame)"))
        assertTrue(source.contains("incoming?.close()"))
        assertTrue(source.contains("runtimeIncoming.values.forEach { it.close() }"))
        assertTrue(source.indexOf("telecomExperiment.authenticatedLink") > source.indexOf("Iap2MfiAuthenticationClient(mfi).run(channel"))
    }
    @Test fun terminalBeforeAsyncCreationCannotBeResurrectedByRingReplay() {
        val tokens = mutableListOf<String>()
        val bridge = ManagedIncomingBridge({ true }, tokens::add, { it(); true })
        bridge.handle(frame())
        bridge.handle(frame(0))
        bridge.handle(frame())
        assertEquals(1, tokens.size)
        assertEquals(android.telecom.Connection.STATE_DISCONNECTED, bridge.create(tokens.single()).state)
    }
    @Test fun disabledAccountRetiresLocalPresentationWithoutRemoteEnd() {
        var enabled = true
        val tokens = mutableListOf<String>()
        val bridge = ManagedIncomingBridge({ enabled }, tokens::add, { it(); true })
        bridge.handle(frame()); val connection = bridge.create(tokens.single())
        enabled = false; bridge.handle(frame())
        assertEquals(android.telecom.Connection.STATE_DISCONNECTED, connection.state)
        assertEquals(android.telecom.DisconnectCause.LOCAL, connection.disconnectCause.code)
    }
    @Test fun unknownStateBeforeLateCreateFailsClosed() {
        val tokens = mutableListOf<String>()
        val bridge = ManagedIncomingBridge({ true }, tokens::add, { it(); true })
        bridge.handle(frame()); bridge.handle(frame(3))
        assertEquals(android.telecom.Connection.STATE_DISCONNECTED, bridge.create(tokens.single()).state)
    }
    @Test fun managedIncomingVerticalSliceExists() {
        val type = runCatching { Class.forName("com.openautolink.carplaylab.telecom.ManagedIncomingBridge") }.getOrNull()
        assertNotNull("authenticated incoming bridge is missing", type)
    }
}

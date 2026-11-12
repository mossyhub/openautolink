package com.openautolink.companion.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WppStartupIntegrationPolicyTest {

    @Test
    fun `selected BT and service start join one attempt`() {
        val coordinator = coordinatorWithFirstAttempt(71L)
        val selectedByCoordinator = coordinator.selectedTargetConnected()
        val joinedByCoordinator = coordinator.startOrJoin(WppIntegrationTrigger.START)
        val selectedAttempt = WppStartupIntegrationPolicy.routeAttempt(
            trigger = WppIntegrationTrigger.SELECTED_BT,
            activeAttemptId = null,
            candidateAttemptId = 71L,
        )
        val serviceAttempt = WppStartupIntegrationPolicy.routeAttempt(
            trigger = WppIntegrationTrigger.START,
            activeAttemptId = selectedAttempt,
            candidateAttemptId = 72L,
        )

        assertEquals(71L, selectedAttempt)
        assertEquals(selectedAttempt, serviceAttempt)
        assertEquals(selectedByCoordinator, joinedByCoordinator)
    }

    @Test
    fun `start and prewarm join the same active attempt`() {
        val coordinator = coordinatorWithFirstAttempt(81L)
        val startedByCoordinator = coordinator.startOrJoin(WppIntegrationTrigger.START)
        val prewarmedByCoordinator = coordinator.startOrJoin(WppIntegrationTrigger.PREWARM)
        val started = WppStartupIntegrationPolicy.routeAttempt(
            trigger = WppIntegrationTrigger.START,
            activeAttemptId = null,
            candidateAttemptId = 81L,
        )
        val prewarmed = WppStartupIntegrationPolicy.routeAttempt(
            trigger = WppIntegrationTrigger.PREWARM,
            activeAttemptId = started,
            candidateAttemptId = 82L,
        )

        assertEquals(started, prewarmed)
        assertEquals(startedByCoordinator, prewarmedByCoordinator)
    }

    @Test
    fun `selected BT with an existing bridge logs standalone and skips attempt creation`() {
        val events = mutableListOf<String>()
        val coordinator = WppDiagnosticsCoordinator(
            elapsedRealtimeMs = { 100L },
            eventSink = events::add,
            summarySink = {},
            tracker = WppStartupTracker { 91L },
        )

        val selectedAttempt = coordinator.selectedTargetConnected(existingBridgeActive = true)

        assertEquals(null, selectedAttempt)
        assertFalse(coordinator.isActive)
        assertEquals(
            listOf(
                "PHONE WPP EVENT attempt=none stage=TARGET_BT_CONNECTED " +
                    "disposition=existing_bridge elapsed=100",
            ),
            events,
        )

        val started = coordinator.startOrJoin(WppIntegrationTrigger.START)
        val prewarmed = coordinator.startOrJoin(WppIntegrationTrigger.PREWARM)
        assertEquals(91L, started)
        assertEquals(started, prewarmed)
    }

    @Test
    fun `diagnostic-only skipped handoff suppresses service attempt joining`() {
        assertFalse(WppStartupIntegrationPolicy.shouldJoinDispatchedAttempt(0L))
        assertTrue(WppStartupIntegrationPolicy.shouldJoinDispatchedAttempt(null))
        assertTrue(WppStartupIntegrationPolicy.shouldJoinDispatchedAttempt(91L))
    }

    @Test
    fun `passive observation never permits requestNetwork`() {
        assertFalse(
            WppStartupIntegrationPolicy.permitsActiveNetworkRequest(
                WppNetworkObservationMode.PASSIVE,
            ),
        )
    }

    @Test
    fun `probes and sockets are evidence but not an established bridge`() {
        listOf(
            PhoneWppStage.CAR_PROBE,
            PhoneWppStage.CAR_SOCKET,
            PhoneWppStage.AA_SOCKET,
        ).forEach { stage ->
            assertFalse(WppStartupIntegrationPolicy.isBridgeEstablished(stage))
        }
    }

    @Test
    fun `connected outcome is reserved for bridge establishment`() {
        PhoneWppStage.entries.forEach { stage ->
            assertEquals(
                stage == PhoneWppStage.BRIDGE_ESTABLISHED,
                WppStartupIntegrationPolicy.isBridgeEstablished(stage),
            )
        }
    }

    @Test
    fun `bridge establishment completes once before the startup deadline`() {
        var now = 100L
        val summaries = mutableListOf<PhoneWppSummary>()
        val coordinator = WppDiagnosticsCoordinator(
            elapsedRealtimeMs = { now },
            eventSink = {},
            summarySink = summaries::add,
        )
        coordinator.startOrJoin(WppIntegrationTrigger.START)
        coordinator.record(PhoneWppStage.BRIDGE_ESTABLISHED)
        now += 120_001L

        coordinator.timeout()
        coordinator.record(PhoneWppStage.BRIDGE_CLOSED)

        assertEquals(1, summaries.size)
        assertEquals("connected", summaries.single().outcome)
        assertEquals(100L, summaries.single().endedAtMs)
    }

    @Test
    fun `old bridge close cannot alter a newer startup attempt`() {
        var now = 100L
        var nextId = 201L
        val events = mutableListOf<String>()
        val summaries = mutableListOf<PhoneWppSummary>()
        val coordinator = WppDiagnosticsCoordinator(
            elapsedRealtimeMs = { now },
            eventSink = events::add,
            summarySink = summaries::add,
            tracker = WppStartupTracker { nextId++ },
        )
        coordinator.startOrJoin(WppIntegrationTrigger.START)
        val establishedAttemptId = coordinator.bridgeEstablished()

        now = 200L
        val newerAttemptId = coordinator.startOrJoin(WppIntegrationTrigger.START)
        coordinator.record(PhoneWppStage.SERVICE_READY)
        now = 300L
        coordinator.bridgeClosed(establishedAttemptId)
        coordinator.timeout()

        assertEquals(201L, establishedAttemptId)
        assertEquals(202L, newerAttemptId)
        assertEquals(2, summaries.size)
        assertFalse(summaries.last().timeline.any { it.stage == PhoneWppStage.BRIDGE_CLOSED })
        assertTrue(
            events.contains(
                "PHONE WPP EVENT attempt=201 stage=BRIDGE_CLOSED phase=post_startup elapsed=300",
            ),
        )
    }

    @Test
    fun `coordinator timeout reports the first missing stage once`() {
        val summaries = mutableListOf<PhoneWppSummary>()
        val coordinator = WppDiagnosticsCoordinator(
            elapsedRealtimeMs = { 500L },
            eventSink = {},
            summarySink = summaries::add,
        )
        coordinator.selectedTargetConnected()
        coordinator.record(PhoneWppStage.SERVICE_READY)

        coordinator.timeout()
        coordinator.timeout()

        assertEquals(1, summaries.size)
        assertEquals("timeout", summaries.single().outcome)
        assertEquals(PhoneWppStage.TCP_LISTENING, summaries.single().missingStage)
    }

    @Test
    fun `stale completed attempt cleanup cannot claim a newer attempt`() {
        val lifecycle = WppAttemptLifecycleGuard()
        lifecycle.started(101L)
        lifecycle.completed(101L)

        lifecycle.started(102L)

        assertFalse(lifecycle.takeCleanup(101L))
        assertTrue(lifecycle.ownsActiveAttempt(102L))
        lifecycle.completed(102L)
        assertFalse(lifecycle.takeCleanup(101L))
        assertTrue(lifecycle.takeCleanup(102L))
        assertFalse(lifecycle.takeCleanup(102L))
    }

    @Test
    fun `network loss is emitted only for previously accepted networks`() {
        val networks = WppObservedNetworkSet<String>()

        assertFalse(networks.lost("unrelated"))
        assertTrue(networks.accept("wpp"))
        assertFalse(networks.accept("wpp"))
        assertTrue(networks.lost("wpp"))
        assertFalse(networks.lost("wpp"))
    }

    @Test
    fun `proxy reports connected only after both sockets form a bridge`() {
        val source = projectFile(
            "companion/src/main/java/com/openautolink/companion/connection/AaProxy.kt",
        ).readText()
        val acquisition = source.substringAfter("private fun takePending(ticket: Int)")
            .substringBefore("private suspend fun awaitPendingCarSocket")
        val bridgeFunction = source.substringAfter("private fun launchBridge(aaSocket: Socket, ticket: Int)")
            .substringBefore("private suspend fun pump")

        val carSocketAcquired = bridgeFunction.indexOf("lease = takePending(ticket)")
        val bridgeEstablished = bridgeFunction.indexOf("Bridge established: AA <-> Car")
        val pipesReady = bridgeFunction.indexOf("val carOut = car.getOutputStream()")
        val connectedCallback = bridgeFunction.indexOf("listener?.onConnected(car, bridge)")

        assertTrue(acquisition.contains("activeCarSocket = socket"))
        assertTrue(carSocketAcquired >= 0)
        assertTrue(pipesReady > carSocketAcquired)
        assertTrue(bridgeEstablished > pipesReady)
        assertTrue(connectedCallback > bridgeEstablished)
    }

    @Test
    fun `bridge close keeps the generation captured at establishment`() {
        val source = projectFile(
            "companion/src/main/java/com/openautolink/companion/connection/AaProxy.kt",
        ).readText()

        assertTrue(
            source.contains(
                "bridgeAttemptId = PhoneWppDiagnostics.bridgeEstablished()",
            ),
        )
        assertTrue(source.contains("PhoneWppDiagnostics.bridgeClosed(bridgeAttemptId)"))
        assertFalse(source.contains("record(PhoneWppStage.BRIDGE_CLOSED)"))
    }

    @Test
    fun `tcp listen failure is owned by the listener bundle bind`() {
        val source = projectFile(
            "companion/src/main/java/com/openautolink/companion/service/TcpAdvertiser.kt",
        ).readText()
        val replaceFunction = source.substringAfter("private fun replaceCarFacingListeners(")
            .substringBefore("private fun closeCarFacingListeners(")
        val bindCatch = replaceFunction.substringAfter("} catch (e: Exception) {")

        assertEquals(1, "TCP_LISTEN_FAILED".toRegex().findAll(source).count())
        assertTrue(replaceFunction.contains("val listeners = createCarFacingListeners(ticket.target)"))
        assertTrue(bindCatch.contains("PhoneWppDiagnostics.record(PhoneWppStage.TCP_LISTEN_FAILED)"))
        assertTrue(replaceFunction.contains("PhoneWppDiagnostics.record(PhoneWppStage.TCP_LISTENING)"))
    }

    @Test
    fun `network observer source is passive only`() {
        val source = projectFile(
            "companion/src/main/java/com/openautolink/companion/diagnostics/WppNetworkObserver.kt",
        ).readText()

        assertTrue(source.contains("registerNetworkCallback("))
        assertFalse(source.contains("requestNetwork("))
        assertFalse(source.contains("bindProcessTraffic"))
        assertFalse(source.contains("bindProcessToNetwork("))
    }

    private fun projectFile(relativePath: String): File {
        val workingDir = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDir) { it.parentFile }
            .map { root -> File(root, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Could not locate $relativePath from $workingDir")
    }

    private fun coordinatorWithFirstAttempt(attemptId: Long) = WppDiagnosticsCoordinator(
        elapsedRealtimeMs = { 100L },
        eventSink = {},
        summarySink = {},
        tracker = WppStartupTracker { attemptId },
    )
}

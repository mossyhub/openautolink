package com.openautolink.app.session

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleEnergyReconnectContractTest {

    @Test
    fun `vehicle energy subscription immediately replays the current model`() {
        val source = sessionManagerSource()
        val start = source.indexOf("private fun onPhoneSubscribedSensor(sensorType: Int)")
        val end = source.indexOf("private fun seedCurrentUiNightMode", startIndex = start)

        assertTrue("Sensor subscription handler must exist", start >= 0)
        assertTrue("Sensor subscription handler must have a boundary", end > start)
        assertTrue(
            "Vehicle energy model sensor type must remain protocol ordinal 23",
            source.contains("private const val SENSOR_TYPE_VEHICLE_ENERGY_MODEL = 23"),
        )

        val handler = source.substring(start, end)
        val energyDispatch = handler.indexOf(
            "if (sensorType == SENSOR_TYPE_VEHICLE_ENERGY_MODEL)",
        )
        val genericSessionGuard = handler.indexOf("val session = aasdkSession ?: return")
        assertTrue(
            "Type 23 must enter its diagnostic-aware replay before the generic session guard",
            energyDispatch >= 0 && energyDispatch < genericSessionGuard,
        )
        assertTrue(
            "Type 23 subscription must actively replay the current VEM",
            handler.contains("sendCurrentEnergyModel(\"sensor-subscribe\")"),
        )
    }

    @Test
    fun `every restart borrows process VHAL owner for type 23 replay`() {
        val source = sessionManagerSource()
        val start = source.indexOf("private fun prepareNativeSessionStart(session: AasdkSession)")
        val end = source.indexOf("private fun startLocationForwarding", startIndex = start)

        assertTrue("Native dependency preparer must exist", start >= 0)
        assertTrue("Native dependency preparer must have a boundary", end > start)
        val hook = source.substring(start, end)
        val adopt = hook.indexOf("adoptSessionOwnership(session)")
        val collectors = hook.indexOf("bindSessionCollectors(session)")
        assertTrue("Native restart must re-adopt its session", adopt >= 0)
        assertTrue("Native restart must retain collector rebinding", collectors > adopt)
        assertFalse(
            "Native-start callbacks must not create/start a VHAL owner that explicit stop can race",
            hook.contains("ensureVehicleDataForwarder()?.start()"),
        )

        val reconnect = source.substringAfter("private suspend fun doReconnectAfterCancel(")
            .substringBefore("fun onSystemWake()")
        assertFalse("Reconnect must never stop the process VHAL owner",
            reconnect.contains("_vehicleDataForwarder?.stop()"))
        assertTrue("Reconnect must break learner continuity without stopping VHAL",
            reconnect.contains("detachProcessVehicleSession()"))
        assertFalse(
            "Reconnect must retain the cached VHAL snapshot for type-23 replay",
            reconnect.contains("_vehicleDataForwarder = null"),
        )

        val fullStop = source.substringAfter("fun stop() {")
            .substringBefore("fun reconnect(")
        assertFalse(
            "Ignition/full stop must not stop the process VHAL owner",
            fullStop.contains("_vehicleDataForwarder?.stop()"),
        )
        assertFalse(
            "Ignition/full stop must retain the VHAL owner and cached EV snapshot for wake",
            fullStop.contains("_vehicleDataForwarder = null"),
        )
        val revokeSession = fullStop.indexOf("revokeSessionOwnershipLocked()")
        val continuityBoundary = fullStop.indexOf("detachProcessVehicleSession()")
        assertTrue("Explicit stop must fence process observations at a lifecycle boundary",
            continuityBoundary >= 0 && revokeSession >= 0)

        val collectorStart = source.indexOf("private fun bindSessionCollectors(session: AasdkSession)")
        val collectorEnd = source.indexOf("private fun createVideoDecoder", collectorStart)
        assertTrue(collectorStart >= 0 && collectorEnd > collectorStart)
        val collectorsBlock = source.substring(collectorStart, collectorEnd)
        assertTrue(
            "Control messages must carry the exact session that produced them",
            collectorsBlock.contains("handleControlMessage(session, message)"),
        )

        val handlerStart = source.indexOf(
            "private fun handleControlMessage(sourceSession: AasdkSession, message: ControlMessage)",
        )
        val handlerEnd = source.indexOf("// ── EV energy-model tuning", handlerStart)
        assertTrue(handlerStart >= 0 && handlerEnd > handlerStart)
        val handler = source.substring(handlerStart, handlerEnd)
        assertTrue(
            "PhoneConnected effects must reject a stale session owner",
            handler.contains("aasdkSession !== sourceSession"),
        )
        assertTrue(
            "PhoneConnected must use the lock-owned streaming-service chokepoint",
            handler.contains("startStreamingServicesLocked(sourceSession)"),
        )

        val connectionObserverStart = source.indexOf("// Observe session state")
        val connectionObserverEnd = source.indexOf("bindSessionCollectors(session)", connectionObserverStart)
        assertTrue(connectionObserverStart >= 0 && connectionObserverEnd > connectionObserverStart)
        val connectionObserver = source.substring(connectionObserverStart, connectionObserverEnd)
        assertTrue(
            "Connection-state starts must verify exact session ownership",
            connectionObserver.contains("aasdkSession !== session"),
        )
        assertTrue(
            "Connection-state starts must use the same lock-owned chokepoint",
            connectionObserver.contains("startStreamingServicesLocked(session)"),
        )
        assertTrue(
            "SessionManager must never start the process-owned VHAL source",
            !source.contains("_vehicleDataForwarder?.start()") &&
                source.contains("ProcessVehicleDataRuntime.attachSessionConsumer(::forwardVehicleData)"),
        )
        assertTrue(
            "Every control-message effect must remain inside the session ownership lock",
            handler.indexOf("synchronized(sessionStateLock)") in
                0 until handler.indexOf("when (message)"),
        )
        assertTrue(
            "Session ownership must use centralized adopt/revoke helpers",
            source.contains("private fun adoptSessionOwnership(session: AasdkSession)") &&
                source.contains("private fun revokeSessionOwnershipLocked()"),
        )
        assertTrue(
            "No lifecycle path may assign the session outside centralized ownership helpers",
            Regex("aasdkSession = ").findAll(source).count() == 2,
        )
    }

    @Test
    fun `VHAL stop invalidates an in-flight asynchronous start`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/input/VehicleDataForwarderImpl.kt",
        ).readText()
        val stopStart = source.indexOf("override fun stop()")
        val stopEnd = source.indexOf("private fun connectToCar", stopStart)
        assertTrue(stopStart >= 0 && stopEnd > stopStart)
        val stop = source.substring(stopStart, stopEnd)
        assertFalse(
            "stop must not return merely because async start has not set isActive yet",
            stop.contains("if (!isActive) return"),
        )
        assertTrue(stop.contains("desiredActive = false"))
        assertTrue(stop.contains("lifecycleGeneration++"))

        val startStart = source.indexOf("override fun start()")
        assertTrue(startStart >= 0 && stopStart > startStart)
        val start = source.substring(startStart, stopStart)
        assertTrue(
            "Every asynchronous start stage must be fenced by its lifecycle generation",
            start.contains("isStartCurrent(generation)"),
        )
        assertTrue(
            "A stale in-flight attempt must clean up rather than activate after stop",
            start.contains("cleanupAfterStartAttempt(generation, failed)"),
        )
        val cleanup = source.substring(
            source.indexOf("private fun cleanupAfterStartAttempt"),
            source.indexOf("private fun scheduleReconnectAfterCleanup"),
        )
        assertTrue("Failed starts must clean before scheduling one-shot reconnects",
            cleanup.indexOf("startInFlight = false") < cleanup.indexOf("scheduleReconnectAfterCleanup"))
        assertTrue("Retry delay is capped, but total attempts must not be capped",
            source.contains("VhalRetryState()") && source.contains("delay(delayMs)") &&
                !source.contains("reconnectAttempt >= 5") && !source.contains("reconnect attempts exhausted"))
        assertTrue("Car service lifecycle must reset or schedule through the same retry owner",
            source.contains("CarServiceLifecycleListener") && source.contains("onCarServiceLost") &&
                source.contains("onCarServiceReady") && source.contains("retryState.serviceReady()"))
        assertSerializedVhalRetirement(source)
        // Executable stop/start, service-loss, stale-proxy and Binder-retirement races
        // are covered by VehiclePermissionRefreshTest; this guards their source wiring.
        assertTrue("Startup cannot publish active without a meaningful subscription",
            source.contains("VhalSubscriptionReadiness.mayActivate(subscribed)"))
    }

    @Test
    fun `failed current model replay records the rejected precondition`() {
        val source = sessionManagerSource()
        val start = source.indexOf("private fun sendCurrentEnergyModel(reason: String)")
        val end = source.indexOf("fun forceSendEnergyModel()", startIndex = start)

        assertTrue("Current VEM replay helper must exist", start >= 0)
        assertTrue("Current VEM replay helper must have a boundary", end > start)
        val helper = source.substring(start, end)
        assertTrue(helper.contains("return rejectCurrentEnergyModel(reason, \"no-session\")"))
        assertTrue(helper.contains("return rejectCurrentEnergyModel(reason, \"no-forwarder\")"))
        assertTrue(helper.contains("return rejectCurrentEnergyModel(reason, \"no-ev-snapshot\")"))
        assertTrue(
            "Rejected replay must be persisted at warning level with provenance",
            source.contains(
                "DiagnosticLog.w(\"vem\", \"sendCurrentEnergyModel[${'$'}reason] rejected: ${'$'}detail\")",
            ),
        )
    }

    @Test
    fun `native energy send reports every guard and successful queue`() {
        val source = projectFile("app/src/main/cpp/jni_session.cpp").readText()
        val start = source.indexOf("void JniSession::sendEnergyModelSensor")
        val end = source.indexOf("void JniSession::sendAccelerometerSensor", startIndex = start)

        assertTrue("Native VEM sender must exist", start >= 0)
        assertTrue("Native VEM sender must have a boundary", end > start)
        val sender = source.substring(start, end)
        val guardPath = sender.substringBefore("ioService_->post(")
        assertFalse(
            "Native guard paths must not invoke JNI directly",
            guardPath.contains("nativeDiag("),
        )
        assertTrue(guardPath.contains("logEnergyModelDiagOnce("))
        assertTrue(sender.contains("outcome=queued sent=unknown"))
        assertTrue(sender.contains("outcome=sent transport-complete=true phone-accepted=unknown"))
        assertTrue(sender.contains("outcome=failed"))
        assertTrue("Send diagnostics must use the bounded sampler", sender.contains("energyModelSampler_.begin(nowMs)"))
        val helper = source.substringAfter("void JniSession::logEnergyModelDiagOnce(")
            .substringBefore("void JniSession::reportGalStartEnvelope")
        val prePost = helper.substringBefore("ioService_->post(")
        assertFalse("No JNI before the IO post", prePost.contains("nativeDiag("))
        assertTrue("Pre-stream diagnostics stay native-only", prePost.contains("if (!streaming_)"))
        assertTrue(prePost.contains("LOGI("))
        assertTrue("Repeated drops cannot enqueue per-tick work", prePost.contains("energyModelDiagMask_.fetch_or"))
        val posted = helper.substringAfter("ioService_->post(")
        assertTrue(posted.contains("const auto self = weak.lock()"))
        assertTrue(posted.contains("if (!self || self->stopped_) return"))
        assertTrue(posted.contains("self->nativeDiag("))
        val header = projectFile("app/src/main/cpp/jni_session.h").readText()
        assertTrue(header.contains("std::atomic<uint32_t> energyModelDiagMask_{0}"))
        val nativeStart = source.substringAfter("void JniSession::start(")
            .substringBefore("void JniSession::stop()")
        assertTrue(nativeStart.contains("energyModelDiagMask_ = 0"))
    }

    @Test
    fun `retirement source guard rejects unsafe variants retaining lifecycle tokens`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/input/VehicleDataForwarderImpl.kt",
        ).readText()
        assertSerializedVhalRetirement(source)
        val mutations = listOf(
            "failed and stale must each retire" to source.replace(
                "!cleaned && (attemptFailed || stale)", "!cleaned && (attemptFailed && stale)",
            ),
            "lane must remain reserved until cleanup completes" to source.replace(
                "            cleanup()\n            cleaned = true",
                "            startInFlight = false\n            cleanup()\n            cleaned = true",
            ),
            "Binder cleanup must stay outside state monitor" to source.replace(
                "            cleanup()\n            cleaned = true",
                "            synchronized(this) { cleanup() }\n            cleaned = true",
            ),
            "retry must only follow current failed retirement" to source.replace(
                "if (attemptFailed && !stale) retryDelayMs",
                "if (attemptFailed) retryDelayMs",
            ),
            "loss must fail its exact generation" to source.replace(
                "startFailureGeneration = generation", "startFailureGeneration = generation + 1",
            ),
            "loss must not free an occupied mutation lane" to source.replace(
                "if (startInFlight) null else { startInFlight = true; generation }",
                "if (startInFlight) { startInFlight = false; null } else { startInFlight = true; generation }",
            ),
        )
        for ((reason, unsafe) in mutations) {
            assertTrue("Mutation must change source: $reason", unsafe != source)
            assertTrue(
                "Unsafe source must be rejected: $reason",
                runCatching { assertSerializedVhalRetirement(unsafe) }.exceptionOrNull() is AssertionError,
            )
        }
    }

    private fun assertSerializedVhalRetirement(source: String) {
        // Ignore formatting and comments, but assert contiguous control-flow shape,
        // not isolated tokens or log prose. Executable races remain the behavior oracle.
        fun compact(text: String): String = text.replace(Regex("//[^\\n]*"), "")
            .replace(Regex("\\s+"), "")
        val cleanup = compact(source.substringAfter("private fun cleanupAfterStartAttempt")
            .substringBefore("private fun scheduleReconnectAfterCleanup"))
        assertTrue("Failed OR stale attempts must reserve the lane for retirement", cleanup.contains(compact("""
            val mustClean = synchronized(this) {
                val attemptFailed = failed || startFailureGeneration == generation
                val stale = lifecycleGeneration != generation || !desiredActive
                if (!cleaned && (attemptFailed || stale)) true else {
        """)))
        assertTrue("Lane release and retry decision must share the post-cleanup handoff", cleanup.contains(compact("""
            if (startFailureGeneration == generation) startFailureGeneration = null
            startInFlight = false
            if (attemptFailed && !stale) retryDelayMs = retryState.failedAttemptCleaned(generation)
        """)))
        assertTrue("Captured-manager retirement must finish outside the monitor before looping to handoff",
            cleanup.contains(compact("""
                false
                }
                }
                if (!mustClean) break
                cleanup()
                cleaned = true
                }
                nextGeneration?.let(::launchStartAttempt)
                refreshGeneration?.let(::launchRefresh)
                retryDelayMs?.let { scheduleReconnectAfterCleanup(generation, it) }
            """)))
        val loss = compact(source.substringAfter("private fun onCarServiceLost")
            .substringBefore("private fun onCarServiceReady"))
        assertTrue("Service loss must fence the exact generation and retain an occupied lane", loss.contains(compact("""
            val retirement = synchronized(this) {
                if (!desiredActive || (carObject != null && carObject !== lostCar)) return
                val generation = lifecycleGeneration
                if (isActive) retryState.serviceLost(generation)
                isActive = false
                startFailureGeneration = generation
                registrationGeneration++
                publishImmediate()
                reconnectAttempt = 0
                if (startInFlight) null else { startInFlight = true; generation }
            }
        """)))
        assertTrue("Idle-lane service loss must dispatch serialized retirement", loss.contains(compact("""
            retirement?.let { generation -> scope.launch { cleanupAfterStartAttempt(generation, true) } }
        """)))
    }

    private fun sessionManagerSource(): String = projectFile(
        "app/src/main/java/com/openautolink/app/session/SessionManager.kt",
    ).readText()

    private fun projectFile(relativePath: String): File {
        val workingDir = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDir) { it.parentFile }
            .map { root -> File(root, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Could not locate $relativePath from $workingDir")
    }
}

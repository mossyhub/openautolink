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
    fun `transport reconnect retains cached vehicle data without resurrecting its owner`() {
        val source = sessionManagerSource()
        val start = source.indexOf("session.onNativeSessionStarting = {")
        val end = source.indexOf("AaWirelessBtControl.sessionIsStreaming", startIndex = start)

        assertTrue("Native restart hook must exist", start >= 0)
        assertTrue("Native restart hook must have a boundary", end > start)
        val hook = source.substring(start, end)
        val adopt = hook.indexOf("aasdkSession = session")
        val collectors = hook.indexOf("bindSessionCollectors(session)")
        assertTrue("Native restart must re-adopt its session", adopt >= 0)
        assertTrue("Native restart must retain collector rebinding", collectors > adopt)
        assertFalse(
            "Native-start callbacks must not create/start a VHAL owner that explicit stop can race",
            hook.contains("ensureVehicleDataForwarder()?.start()"),
        )

        val reconnect = source.substringAfter("private fun doReconnectAfterCancel(")
            .substringBefore("fun onSystemWake()")
        assertTrue("Reconnect must pause VHAL while replacing the protocol session",
            reconnect.contains("_vehicleDataForwarder?.stop()"))
        assertFalse(
            "Reconnect must retain the cached VHAL snapshot for type-23 replay",
            reconnect.contains("_vehicleDataForwarder = null"),
        )
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
            "Native guard paths run off-strand and must not touch the JNI callback",
            guardPath.contains("nativeDiag(") || guardPath.contains("logEnergyModelDiagOnce("),
        )
        assertTrue(sender.contains("sendEnergyModel queued: level="))
        assertTrue(
            "The queued outcome must be logged at most once per native session",
            sender.contains("logEnergyModelDiagOnce("),
        )
        val helper = source.substringAfter("void JniSession::logEnergyModelDiagOnce(")
            .substringBefore("void JniSession::reportGal6StartEnvelope")
        assertTrue(
            "Do not consume the once bit when no safe Kotlin callback exists",
            helper.indexOf("if (!cbMethods_.onNativeLog || !callbackRef_) return") in
                0 until helper.indexOf("energyModelDiagMask_.fetch_or"),
        )
        val header = projectFile("app/src/main/cpp/jni_session.h").readText()
        assertTrue(header.contains("std::atomic<uint32_t> energyModelDiagMask_{0}"))
        val nativeStart = source.substringAfter("void JniSession::start(")
            .substringBefore("void JniSession::stop()")
        assertTrue(nativeStart.contains("energyModelDiagMask_ = 0"))
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

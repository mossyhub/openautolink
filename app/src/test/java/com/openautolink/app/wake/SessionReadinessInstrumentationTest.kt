package com.openautolink.app.wake

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReadinessInstrumentationTest {

    @Test
    fun `admission readiness follows callback wiring and transport ownership`() {
        val managerSource = projectFile(
            "app/src/main/java/com/openautolink/app/session/SessionManager.kt",
        ).readText()
        val nativeStart = managerSource.indexOf("session.onNativeSessionStarting = {")
        val nativeEnd = managerSource.indexOf(
            "AaWirelessBtControl.sessionIsStreaming",
            startIndex = nativeStart,
        )

        assertTrue("Native session start hook must exist", nativeStart >= 0)
        assertTrue("Native session start block must have a boundary", nativeEnd > nativeStart)
        val nativeStartBlock = managerSource.substring(nativeStart, nativeEnd)
        assertTrue(
            "Every native start must use the complete dependency preparer",
            nativeStartBlock.contains("prepareNativeSessionStart(session)"),
        )
        assertEquals(
            "Pre-native setup must never report session readiness",
            0,
            Regex("""PreWakeMonitor\.reportSessionReady\(""")
                .findAll(nativeStartBlock)
                .count(),
        )

        val sessionStart = managerSource.indexOf("session.start()", startIndex = nativeEnd)
        val installOwner = managerSource.indexOf(
            "installWirelessSessionAdmissionIfCurrent(",
            startIndex = sessionStart,
        )
        val reportAdmission = managerSource.indexOf(
            "PreWakeMonitor.reportSessionReady(\"admission-ready\")",
            startIndex = installOwner,
        )
        assertTrue("Transport must start before admission is installed", sessionStart >= 0)
        assertTrue("Admission owner must be installed after transport start", installOwner > sessionStart)
        assertTrue("Readiness must follow admission ownership", reportAdmission > installOwner)

        val sessionSource = projectFile(
            "app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt",
        ).readText()
        val started = sessionSource.indexOf("\n    override fun onSessionStarted()")
        val stopped = sessionSource.indexOf("override fun onSessionStopped", startIndex = started)
        assertTrue("Native onSessionStarted callback must exist", started >= 0)
        assertTrue("Native callback block must have a boundary", stopped > started)

        val startedBlock = sessionSource.substring(started, stopped)
        val nativeStartedLog = startedBlock.indexOf("AA session started (native)")
        val connectSummary = startedBlock.indexOf("CONNECT SUMMARY")
        val reportReady = startedBlock.indexOf(
            "PreWakeMonitor.reportSessionReady(\"native-session-started\")",
        )
        assertTrue("Native success log must remain", nativeStartedLog >= 0)
        assertTrue("CONNECT SUMMARY must remain", connectSummary >= 0)
        assertTrue("Native success callback must report exact readiness source", reportReady >= 0)
        assertTrue(reportReady > nativeStartedLog)
        assertTrue(reportReady > connectSummary)

        val productionSources = managerSource + sessionSource
        assertEquals(
            "Production must have exactly admission-ready and native-success calls",
            2,
            Regex("""PreWakeMonitor\.reportSessionReady\(""")
                .findAll(productionSources)
                .count(),
        )
        assertEquals(
            "Every readiness call site must provide an explicit source",
            0,
            Regex("""PreWakeMonitor\.reportSessionReady\(\)""")
                .findAll(productionSources)
                .count(),
        )
    }

    @Test
    fun `session readiness callback only timestamps sanitizes and enqueues observation`() {
        val monitorSource = projectFile(
            "app/src/main/java/com/openautolink/app/wake/PreWakeMonitor.kt",
        ).readText()
        val functionStart = monitorSource.indexOf("fun reportSessionReady(source: String) {")
        val functionEnd = monitorSource.indexOf("fun reportSurfaceReady", startIndex = functionStart)

        assertTrue("Session readiness hook must exist", functionStart >= 0)
        assertTrue("Session readiness hook must have a boundary", functionEnd > functionStart)

        val functionBody = monitorSource.substring(functionStart, functionEnd)
        val timestamp = functionBody.indexOf("val elapsedMs = SystemClock.elapsedRealtime()")
        val offer = functionBody.indexOf("sessionReadinessDispatcher.offer(")
        assertTrue("Readiness hook must capture monotonic elapsed time", timestamp >= 0)
        assertTrue("Readiness hook must offer to the bounded dispatcher", offer >= 0)
        assertTrue("Readiness timestamp must be captured before enqueue", timestamp < offer)
        assertTrue("Readiness source must be sanitized before enqueue", functionBody.contains("safe(source)"))
        assertEquals(
            "Readiness hook must not call the reducer directly",
            0,
            Regex("""\brecord\(""").findAll(functionBody).count(),
        )
        assertEquals(
            "Readiness hook must not log synchronously",
            0,
            Regex("""DiagnosticLog""").findAll(functionBody).count(),
        )
        assertEquals(
            "Readiness hook must not perform file operations",
            0,
            Regex("""FileLogWriter|java\.io\.File|\bFile\(|\.write(?:Text|Bytes)?\(|\.append(?:Text)?\(|\.flush\(""")
                .findAll(functionBody)
                .count(),
        )
    }

    private fun projectFile(relativePath: String): File {
        val workingDir = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDir) { it.parentFile }
            .map { root -> File(root, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Could not locate $relativePath from $workingDir")
    }
}

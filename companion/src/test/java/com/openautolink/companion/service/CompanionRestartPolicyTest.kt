package com.openautolink.companion.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionRestartPolicyTest {

    @Test
    fun `null sticky redelivery restores transport when prior owner desired it`() {
        assertTrue(
            CompanionRestartPolicy.shouldRestoreTransport(
                intentWasNull = true,
                transportDesired = true,
            ),
        )
    }

    @Test
    fun `upload-only sticky service does not manufacture transport ownership`() {
        assertFalse(
            CompanionRestartPolicy.shouldRestoreTransport(
                intentWasNull = true,
                transportDesired = false,
            ),
        )
    }

    @Test
    fun `explicit intent without an action does not masquerade as sticky redelivery`() {
        assertFalse(
            CompanionRestartPolicy.shouldRestoreTransport(
                intentWasNull = false,
                transportDesired = true,
            ),
        )
    }

    @Test
    fun `service applies sticky restart policy before resuming persistent logging`() {
        val source = projectFile(
            "companion/src/main/java/com/openautolink/companion/service/CompanionService.kt",
        ).readText()
        val fallbackBranch = source.substringAfter("else -> {")
            .substringBefore("return START_STICKY")

        val policyCall =
            "CompanionRestartPolicy.shouldRestoreTransport(intent == null, transportDesired())"
        val policyCheck = fallbackBranch.indexOf(policyCall)
        val stickyBranch = fallbackBranch.substringAfter("if ($policyCall) {")
            .substringBefore("} else {")
        val unknownActionBranch = fallbackBranch.substringAfter("} else {")

        val runningRestore = stickyBranch.indexOf("_isRunning.value = true")
        val transportRestore = stickyBranch.indexOf("startTcp()")
        val persistentLogging = fallbackBranch.indexOf("maybeStartPersistentLogging()")

        assertTrue(policyCheck >= 0)
        assertTrue(runningRestore >= 0)
        assertTrue(transportRestore > runningRestore)
        assertTrue(persistentLogging > fallbackBranch.indexOf("startTcp()"))
        assertFalse(unknownActionBranch.substringBefore("}").contains("startTcp()"))
        assertTrue(stickyBranch.contains("System sticky restart — restoring TCP transport"))
    }

    @Test
    fun `explicit lifecycle commands persist transport ownership intent`() {
        val source = projectFile(
            "companion/src/main/java/com/openautolink/companion/service/CompanionService.kt",
        ).readText()
        val stopBranch = source.substringAfter("ACTION_STOP -> {").substringBefore("ACTION_START -> {")
        val startBranch = source.substringAfter("ACTION_START -> {").substringBefore("ACTION_PREWARM -> {")
        val prewarmBranch = source.substringAfter("ACTION_PREWARM -> {").substringBefore("ACTION_UPLOAD_LOGS -> {")

        val clearDesired = stopBranch.indexOf("setTransportDesired(false)")
        val stopService = stopBranch.indexOf("stopSelf()")
        assertTrue(clearDesired >= 0)
        assertTrue(stopService > clearDesired)
        assertTrue(startBranch.contains("setTransportDesired(true)"))
        assertTrue(prewarmBranch.contains("setTransportDesired(true)"))
    }

    private fun projectFile(relativePath: String): File {
        val workingDir = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDir) { it.parentFile }
            .map { root -> File(root, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Could not locate $relativePath from $workingDir")
    }
}

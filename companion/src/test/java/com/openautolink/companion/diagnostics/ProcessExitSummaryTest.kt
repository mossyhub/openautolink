package com.openautolink.companion.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessExitSummaryTest {

    @Test
    fun `formats native crash with bounded single-line description`() {
        val summary = ProcessExitSummary.format(
            reason = 5,
            status = 6,
            importance = 100,
            pssKb = 42_000L,
            rssKb = 84_000L,
            timestampMs = 10_000L,
            nowMs = 11_250L,
            description = "native crash\ninside bridge",
        )

        assertEquals(
            "reason=CRASH_NATIVE(5) status=6 importance=100 ageMs=1250 " +
                "pssKb=42000 rssKb=84000 description=native crash inside bridge",
            summary,
        )
    }

    @Test
    fun `unknown reason and future timestamp remain truthful`() {
        val summary = ProcessExitSummary.format(
            reason = 99,
            status = 0,
            importance = 400,
            pssKb = 0L,
            rssKb = 0L,
            timestampMs = 20_000L,
            nowMs = 10_000L,
            description = null,
        )

        assertEquals(
            "reason=UNKNOWN_99(99) status=0 importance=400 ageMs=0 " +
                "pssKb=0 rssKb=0 description=none",
            summary,
        )
    }

    @Test
    fun `description is capped to keep uploaded log records bounded`() {
        val summary = ProcessExitSummary.format(
            reason = 6,
            status = 0,
            importance = 100,
            pssKb = 1L,
            rssKb = 2L,
            timestampMs = 0L,
            nowMs = 1L,
            description = "x".repeat(500),
        )

        val description = summary.substringAfter("description=")
        assertEquals(ProcessExitSummary.MAX_DESCRIPTION_CHARS, description.length)
        assertTrue(summary.contains("reason=ANR(6)"))
    }

    @Test
    fun `service logs previous exit after file logger starts`() {
        val source = projectFile(
            "companion/src/main/java/com/openautolink/companion/service/CompanionService.kt",
        ).readText()
        val onCreate = source.substringAfter("override fun onCreate()")
            .substringBefore("private fun maybeStartPersistentLogging()")

        val persistentLogging = onCreate.indexOf("maybeStartPersistentLogging()")
        val exitLogging = onCreate.indexOf("logPreviousProcessExit()")

        assertTrue(persistentLogging >= 0)
        assertTrue(exitLogging > persistentLogging)
        assertTrue(source.contains("if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)"))
        assertTrue(source.contains("getHistoricalProcessExitReasons(packageName, 0, 1)"))
        assertTrue(source.contains("Previous companion process exit:"))
        assertTrue(source.contains("Previous companion process exit unavailable:"))
    }

    private fun projectFile(relativePath: String): File {
        val workingDir = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDir) { it.parentFile }
            .map { root -> File(root, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Could not locate $relativePath from $workingDir")
    }
}

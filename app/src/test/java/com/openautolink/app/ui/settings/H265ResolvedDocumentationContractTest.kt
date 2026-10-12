package com.openautolink.app.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class H265ResolvedDocumentationContractTest {
    private val h265GreenWarning = Regex(
        "(?i)(h\\.265|hevc).{0,160}green|green.{0,160}(h\\.265|hevc)",
    )

    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun projectFile(relative: String): String {
        val start = File(requireNotNull(System.getProperty("user.dir")))
        val root = generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not locate project root from $start")
        return File(root, relative).readText()
    }

    @Test
    fun `public README no longer lists H265 green startup as a known issue`() {
        val readme = projectFile("README.md")

        assertFalse(readme.contains("H.265 video may appear green-tinted"))
        assertFalse(h265GreenWarning.containsMatchIn(normalized(readme)))
        assertTrue(readme.contains("H.264, H.265 and VP9 video"))
        assertTrue(readme.contains("Per-codec startup-placeholder filtering"))
    }

    @Test
    fun `video settings present H265 as verified instead of risky`() {
        val settings = projectFile(
            "app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt",
        )

        listOf(
            "green-startup risk",
            "H.264 (Recommended)",
            "H.264 is the safe default",
            "legacy GAL can take roughly two minutes",
            "GAL 6.0 remains an implementation attempt",
        ).forEach { stale -> assertFalse("Stale warning remains: $stale", settings.contains(stale)) }
        assertFalse(h265GreenWarning.containsMatchIn(normalized(settings)))

        assertTrue(settings.contains("verified over USB and wireless"))
        assertTrue(settings.contains("one IDR every 120 frames"))
    }

    @Test
    fun `GAL documentation records verified runtime result`() {
        val galDoc = projectFile("docs/experimental-gal6.md")

        assertFalse(galDoc.contains("implementation attempt until the next in-vehicle upload"))
        assertFalse(h265GreenWarning.containsMatchIn(normalized(galDoc)))
        assertTrue(galDoc.contains("Verified in vehicle"))
        assertTrue(galDoc.contains("119 P-frames"))
    }
}

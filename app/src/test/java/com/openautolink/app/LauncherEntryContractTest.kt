package com.openautolink.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Real caller/source guards, not rendered UI or OEM launcher execution proof. */
class LauncherEntryContractTest {
    private val root = File("src/main/java/com/openautolink/app")
    private fun source(path: String) = File(root, path).readText()

    @Test fun `cold launch does not restore an obsolete projection controls gate`() {
        val activity = source("MainActivity.kt")
        assertFalse("Activity must not own launcher-triggered modal state", activity.contains("launcherRecovery"))
        assertFalse("Activity must not restore a retired launcher modal", activity.contains("LauncherRecovery"))
        assertTrue("Normal launch still mounts navigation with the real preview factory",
            activity.contains("AppNavHost(clusterPreviewFactory ="))
    }

    @Test fun `launcher reentry retains intent delivery without opening controls`() {
        val activity = source("MainActivity.kt")
        val reentry = activity.substringAfter("override fun onNewIntent(intent: Intent)")
            .substringBefore("private fun applyDisplayMode")
        assertTrue(reentry.contains("super.onNewIntent(intent)"))
        assertTrue(reentry.contains("setIntent(intent)"))
        assertFalse("MAIN+LAUNCHER must not open a modal", reentry.contains("LauncherRecovery"))
        val resume = activity.substringAfter("override fun onResume()")
            .substringBefore("override fun onPause()")
        assertFalse(resume.contains("launcherRecovery"))
        assertTrue("Keep existing wake handling", resume.contains("onSystemWake()"))
    }

    @Test fun `navigation never gates projection behind launcher controls`() {
        val nav = source("ui/navigation/AppNavHost.kt")
        assertFalse(nav.contains("launcherRecoveryVisible"))
        assertFalse(nav.contains("onLauncherRecoveryDismiss"))
        assertFalse(nav.contains("Projection controls"))
        assertFalse("No obsolete launcher dialog remains", nav.contains("OalAlertDialog"))
        assertTrue(nav.contains("startDestination = AppDestinations.PROJECTION"))
        assertTrue(nav.contains("ProjectionScreen("))
        assertTrue("Explicit settings access remains wired", nav.contains("settingsOverlay ="))
        assertTrue(nav.contains("SettingsScreen("))
    }
}

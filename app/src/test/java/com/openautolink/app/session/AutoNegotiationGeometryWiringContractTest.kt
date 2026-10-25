package com.openautolink.app.session

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoNegotiationGeometryWiringContractTest {
    private fun projectFile(relative: String): String {
        val start = File(requireNotNull(System.getProperty("user.dir")))
        val root = generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not locate project root from $start")
        return File(root, relative).readText()
    }

    @Test
    fun `auto DPI supplies a per-tier layout target to native discovery`() {
        val manager = projectFile(
            "app/src/main/java/com/openautolink/app/session/SessionManager.kt",
        )

        assertTrue(manager.contains("AutoDpiPolicy.layoutWidthDp"))
        assertTrue(manager.contains("videoAutoNegotiate && autoLayoutWidthDp > 0"))
        assertTrue(manager.contains("computedTargetLayoutWidthDp = if (videoAutoNegotiate"))
        assertTrue(manager.contains("autoLayoutWidthDp"))

        val nativeSession = projectFile("app/src/main/cpp/jni_session.cpp")
        assertTrue(nativeSession.contains("int densityWidth = kDims[tier].w"))
        assertTrue(nativeSession.contains("densityWidth = std::max(1, densityWidth - wm)"))
        assertTrue(nativeSession.contains("densityWidth * 160"))
        assertTrue(nativeSession.contains("Auto density: tier="))
    }

    @Test
    fun `touch forwarding uses an atomic selected geometry snapshot not the capability tier`() {
        val projection = projectFile(
            "app/src/main/java/com/openautolink/app/ui/projection/ProjectionViewModel.kt",
        )

        assertTrue(projection.contains("TouchCoordinateSpace.innerForProtocol"))
        assertTrue(projection.contains("sessionManager.captureTouchTarget() ?: return"))
        assertTrue(projection.contains("sessionManager.sendSelectedTouch(message, target)"))
        assertFalse(projection.contains("sessionManager.touchWidth.value"))
        assertFalse(projection.contains("sessionManager.touchHeight.value"))
        assertTrue(projection.contains("Touch mapping active:"))
    }

    @Test
    fun `automatic touchscreen matches the first advertised video tier`() {
        val nativeSession = projectFile("app/src/main/cpp/jni_session.cpp")

        assertTrue(nativeSession.contains("int touchWidth = sdrConfig_.videoWidth"))
        assertTrue(nativeSession.contains("touchWidth = kDims[tiers[0]].w"))
        assertTrue(nativeSession.contains("touchHeight = kDims[tiers[0]].h"))
        assertTrue(nativeSession.contains("ts->set_width(touchWidth)"))
        assertTrue(nativeSession.contains("ts->set_height(touchHeight)"))
        assertTrue(nativeSession.contains("Input touchscreen: %dx%d"))

        val manager = projectFile("app/src/main/java/com/openautolink/app/session/SessionManager.kt")
        assertTrue(manager.contains("AutoVideoTouchPolicy.resolve"))
        assertTrue(manager.contains("_touchWidth.value = protocolTouchW"))
        assertTrue(manager.contains("_touchHeight.value = protocolTouchH"))
        assertTrue(manager.contains("Protocol touchscreen:"))
    }

    @Test
    fun `resolution baseline documents automatic per-tier density`() {
        val baseline = projectFile("docs/oal-resolution-negotiation-baseline.md")

        assertFalse(baseline.contains("this per-tier targeting is normally disabled"))
        assertFalse(baseline.contains("One default density for all tiers"))
        assertTrue(baseline.contains("automatic per-tier density"))
        assertTrue(baseline.contains("4K tier uses density 263"))
        assertTrue(baseline.contains("automatic touchscreen follows the first video tier"))
    }

    @Test
    fun `stats overlay does not mislabel the base density as the selected tier`() {
        val screen = projectFile(
            "app/src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt",
        )

        assertFalse(screen.contains("StatLine(\"DPI sent\""))
        assertTrue(screen.contains("StatLine(\"Auto DPI base\""))
    }
}

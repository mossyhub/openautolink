package com.openautolink.app.secondary

import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.cluster.PixelSize
import org.junit.Assert.*
import org.junit.Test

class SecondaryRuntimeLayoutsTest {
    private val id = SecondaryOutputId("public")
    private val descriptor = SecondaryDisplayDescriptor(id, 17, 4, "External", 800, 480, 160,
        SecondaryRoute.PUBLIC_ACTIVITY, SecondaryReason.NONE, "Unknown")
    private val scope = SecondaryLayoutScope(id, "vehicle", "build", 800, 480, 160)
    private val preference = SecondaryLayoutPreference(scope, SecondaryLayoutBox(.1, .1, .9, .9),
        SecondaryLayoutBox(.2, .2, .8, .8))
    private val benchPanel = PixelSize(1280, 720)
    private val benchDescriptor = descriptor.copy(width = 1280, height = 720, densityDpi = 240)
    private val benchScope = SecondaryLayoutScope(id, "vehicle", "build", 1280, 720, 240)
    private fun benchLayout(top: Int, bottom: Int) = SecondaryLayoutPreference(benchScope,
        SecondaryLayoutBox(0.0, 0.0, 1.0, 1.0),
        SecondaryLayoutBox(0.0, top / 720.0, 1.0, bottom / 720.0))
    private fun benchTarget(layout: SecondaryLayoutPreference? = null,
        safe: PixelRect = benchPanel.bounds, carPlay: Boolean = true) =
        SecondaryRuntimeLayouts.target(benchDescriptor, layout, benchPanel, safe,
            "vehicle", "build", carPlay = carPlay)

    @Test fun automaticCarPlayOnRealBenchPanelAdmitsFittedAlternate() {
        val target = benchTarget()
        val mapping = SecondaryVideoMapping.carPlay(target)
        assertEquals(benchPanel.bounds, target.output)
        assertEquals(PixelRect(0, 120, 1280, 600), target.safe)
        assertEquals(target.safe, mapping.destinationPixels)
        assertEquals(PixelSize(1280, 480), mapping.codedSize)
        assertEquals(mapping.codedSize.bounds, mapping.codecCrop)
        assertEquals(mapping.drawableSize.bounds, mapping.safeInDrawable)
        assertEquals(com.openautolink.app.cluster.PixelInsets.ZERO, mapping.phoneSafeInsets)
    }

    @Test fun automaticCarPlayIntersectsActualHostInsetsWithFittedVideo() {
        val target = benchTarget(safe = PixelRect(20, 140, 1260, 580))
        assertEquals(PixelRect(20, 140, 1260, 580), target.safe)
        assertEquals(PixelRect(20, 20, 1260, 460), SecondaryVideoMapping.carPlay(target).safeInDrawable)
    }

    @Test fun automaticAndroidAutoRetainsFullPanelSafeContract() {
        val target = benchTarget(carPlay = false)
        val mapping = SecondaryVideoMapping.androidAuto(target)
        assertEquals(benchPanel.bounds, target.safe)
        assertEquals(PixelRect(0, 0, 1280, 720), mapping.codecCrop)
        assertEquals(benchPanel.bounds, mapping.destinationPixels)
        assertEquals(com.openautolink.app.cluster.PixelInsets.ZERO, mapping.phoneSafeInsets)
    }

    @Test fun knownWorkingCustomCarPlayIsPreservedWithoutChangingPreference() {
        val layout = benchLayout(120, 600)
        val target = benchTarget(layout)
        assertEquals(PixelRect(0, 120, 1280, 600), target.safe)
        assertEquals(benchPanel.bounds, target.output)
        assertEquals(layout, benchLayout(120, 600))
        assertEquals(com.openautolink.app.cluster.PixelInsets.ZERO,
            SecondaryVideoMapping.carPlay(target).phoneSafeInsets)
    }

    @Test fun customCarPlayInsideVideoRetainsUserInsetsRatherThanExpandingSafe() {
        val target = benchTarget(benchLayout(140, 580))
        assertEquals(PixelRect(0, 140, 1280, 580), target.safe)
        assertEquals(com.openautolink.app.cluster.PixelInsets(0, 20, 0, 20),
            SecondaryVideoMapping.carPlay(target).phoneSafeInsets)
    }

    @Test fun customSafeInBarsFallsBackToAutomaticWithoutClippingSavedIntent() {
        val layout = benchLayout(100, 620)
        val target = benchTarget(layout)
        assertEquals(PixelRect(0, 120, 1280, 600), target.safe)
        assertEquals(layout, benchLayout(100, 620))
        assertEquals(com.openautolink.app.cluster.PixelInsets.ZERO,
            SecondaryVideoMapping.carPlay(target).phoneSafeInsets)
    }

    @Test fun staleCustomScopeUsesProtocolSpecificAutomaticWithoutRewritingRecord() {
        val saved = benchLayout(140, 580)
        val foreign = saved.copy(scope = saved.scope.copy(buildFingerprint = "old"))
        assertEquals(PixelRect(0, 120, 1280, 600), benchTarget(foreign).safe)
        assertEquals("old", foreign.scope.buildFingerprint)
    }

    @Test fun actualSafeEntirelyOutsideVideoFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            benchTarget(safe = PixelRect(0, 0, 1280, 100))
        }
    }

    @Test fun validCustomOutputIsPreservedWithItsOwnVideoFit() {
        val layout = SecondaryLayoutPreference(benchScope,
            SecondaryLayoutBox(.25, .25, .75, .75),
            SecondaryLayoutBox(.25, .375, .75, .625))
        val target = benchTarget(layout)
        val mapping = SecondaryVideoMapping.carPlay(target)
        assertEquals(PixelRect(320, 180, 960, 540), target.output)
        assertEquals(PixelRect(320, 270, 960, 450), target.safe)
        assertEquals(PixelRect(320, 240, 960, 480), mapping.destinationPixels)
        assertEquals(com.openautolink.app.cluster.PixelInsets(0, 60, 0, 60), mapping.phoneSafeInsets)
    }

    @Test fun subpixelCustomVideoFitFallsBackRatherThanThrowingDuringAttach() {
        val box = SecondaryLayoutBox(0.0, 0.0, 1 / 1280.0, 1 / 720.0)
        val target = benchTarget(SecondaryLayoutPreference(benchScope, box, box))
        assertEquals(benchPanel.bounds, target.output)
        assertEquals(PixelRect(0, 120, 1280, 600), target.safe)
        SecondaryVideoMapping.carPlay(target)
    }

    @Test fun customSafeMissingActualHostSafeFallsBackWithoutMovingHostInsets() {
        val target = benchTarget(benchLayout(140, 200), safe = PixelRect(0, 300, 1280, 500))
        assertEquals(benchPanel.bounds, target.output)
        assertEquals(PixelRect(0, 300, 1280, 500), target.safe)
        SecondaryVideoMapping.carPlay(target)
    }

    @Test fun matchingScopeConsumesSavedOutputAndSafeInsideActualInsets() {
        val target = SecondaryRuntimeLayouts.target(descriptor, preference, PixelSize(800, 480),
            PixelRect(0, 0, 800, 480), "vehicle", "build")
        assertEquals(PixelRect(80, 48, 720, 432), target.output)
        assertEquals(PixelRect(160, 96, 640, 384), target.safe)
        assertEquals(4L, target.attachmentEpoch)
    }
    @Test fun foreignScopeCannotMoveCurrentOutput() {
        val target = SecondaryRuntimeLayouts.target(descriptor, preference, PixelSize(800, 480),
            PixelRect(0, 20, 800, 480), "vehicle", "other build")
        assertEquals(PixelRect(0, 0, 800, 480), target.output)
        assertEquals(PixelRect(0, 20, 800, 480), target.safe)
    }
    @Test fun osInsetsCannotBeOverriddenBySavedSafeArea() {
        val target = SecondaryRuntimeLayouts.target(descriptor, preference, PixelSize(800, 480),
            PixelRect(200, 120, 600, 360), "vehicle", "build")
        assertEquals(PixelRect(200, 120, 600, 360), target.safe)
    }
}

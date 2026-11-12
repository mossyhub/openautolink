package com.openautolink.app.secondary

import android.app.Application
import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SecondaryStandardVideoTest {
    @Test fun offIconsAndDisabledOutputNeverQueryVideoCodecOrLegacyHost() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as Application
        val id = SecondaryOutputId("standard-video-disabled")
        val descriptor = SecondaryDisplayDescriptor(id, 17, 1, "Physical target", 2000, 810, 160,
            SecondaryRoute.GM_HOSTED, SecondaryReason.NONE, "Qualified driver cluster")
        val runtime = SecondaryOutputRuntime(app, MutableStateFlow(SecondaryInventorySnapshot(1, listOf(descriptor))),
            { _, _ -> error("Disabled video allocated a renderer") }, { _, _ -> error("Disabled video queried codecs") })
        val receipt = mockk<ClusterDriverDisplayReceipt>()
        every { receipt.resolveReadinessCandidate(any()) } throws AssertionError("Disabled video queried readiness")
        every { receipt.resolveCandidate(any(), any(), any()) } throws AssertionError("Disabled video queried qualification")
        mockkObject(ClusterDriverDisplayReceipt.Companion)
        every { ClusterDriverDisplayReceipt.QUALIFIED_GM } returns receipt
        try {
            for (mode in ClusterDisplayMode.entries) {
                val selection = ClusterOutputSelection(mode, false, false, nativeIconsWithMap = true, directVideoWindow = false)
                // No output entry at all for Off/Icons: even migration must remain lazy.
                val outputs = if (mode == ClusterDisplayMode.MAP_VIDEO) mapOf(id to SecondaryOutputPreference()) else emptyMap()
                val prefs = SecondaryPreferencesSnapshot(0, outputs, ClusterOutputPreferences(selection, ClusterDisplayMode.MAP_VIDEO))
                val aa = runtime.prepareAndroidAuto(1, prefs)
                assertTrue(aa.offers.isEmpty()); assertNull(aa.hostedManager)
                val cp = runtime.prepareCarPlay(2, prefs)
                assertNull(cp.configuration); assertNull(cp.listener)
                assertEquals(selection.mode != ClusterDisplayMode.OFF && selection.mode != ClusterDisplayMode.MAP_VIDEO,
                    ClusterOutputPolicy.shouldPublishNativeIcons(aa.legacySelection, ClusterProjectionProtocol.ANDROID_AUTO, true))
            }
        } finally {
            unmockkObject(ClusterDriverDisplayReceipt.Companion)
            assertTrue(runtime.close().get())
        }
    }

    @Test fun confirmedOwnerDefaultAndResetScaleOnlyInnerSafeAcrossProtocolsAndResolutions() {
        val expected = listOf(
            Triple(PixelSize(2000, 810), PixelRect(558, 70, 1694, 668), PixelRect(192, 71, 1788, 660)),
            Triple(PixelSize(1600, 600), PixelRect(446, 52, 1355, 495), PixelRect(446, 52, 1355, 495)))
        for ((panel, fallbackSafe, aaSafe) in expected) {
            val fallback = SecondaryProjectionActivity.directDefaultSafe(panel.width, panel.height)
            assertEquals("Owner-confirmed fallback normalized from full 2000x810 window", fallbackSafe, fallback)
            val descriptor = SecondaryDisplayDescriptor(SecondaryOutputId("no-oem-database"), 17, 1, "Unknown OEM",
                panel.width, panel.height, 160, SecondaryRoute.PUBLIC_ACTIVITY, SecondaryReason.NONE, "Unknown")
            val scope = SecondaryLayoutScope(descriptor.id, "unknown", "build", panel.width, panel.height, 160)
            val custom = SecondaryLayoutPreference(scope, SecondaryLayoutBox(.1, .1, .9, .9),
                SecondaryLayoutBox(.3, .3, .7, .7), directSafe = SecondaryLayoutBox(.35, .25, .65, .75))
            for (cp in listOf(false, true)) {
                // Only AA's supported 2000x810 default/reset uses the calibrated rectangle.
                // CarPlay and unsupported sizes retain the original direct fallback.
                val safe = if (cp) fallbackSafe else aaSafe
                fun target(layout: SecondaryLayoutPreference?) = SecondaryRuntimeLayouts.target(descriptor, layout,
                    panel, fallback, "unknown", "build", carPlay = cp, safeOnly = true)
                val automatic = target(null)
                val reset = target(custom.copy(directSafe = null, directSafeAutomatic = true))
                assertEquals(panel.bounds, automatic.output); assertEquals(safe, automatic.safe)
                assertEquals(automatic, reset)
                val before = target(custom)
                assertEquals(panel.bounds, before.output); assertNotEquals(safe, before.safe)
                val a = if (cp) SecondaryVideoMapping.carPlay(automatic) else SecondaryVideoMapping.androidAuto(automatic)
                val b = if (cp) SecondaryVideoMapping.carPlay(before) else SecondaryVideoMapping.androidAuto(before)
                assertEquals(a.codecCrop, b.codecCrop); assertEquals(a.codedSize, b.codedSize)
                assertEquals(a.destination, b.destination); assertNotEquals(a.phoneSafeInsets, b.phoneSafeInsets)
                assertEquals(SecondaryLayoutBox(.1,.1,.9,.9), custom.output)
                assertEquals(SecondaryLayoutBox(.35,.25,.65,.75), custom.directSafe)
            }
        }
    }
}

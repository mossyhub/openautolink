package com.openautolink.app.cluster

import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

/** Public production initial/receipt entrypoints with real OS display topology, not toString tokens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
class ClusterAdmissionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(DisplayManager::class.java)
    @Before fun vehicle() {
        ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", "aegean_orange")
        ReflectionHelpers.setStaticField(Build::class.java, "ID", "VCUSR5-163")
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "INCREMENTAL", "239")
    }
    private fun panel() = ShadowDisplayManager.addDisplay("w2000dp-h810dp-mdpi")
    @org.robolectric.annotation.Implements(Display::class)
    class UnavailableMetadata : org.robolectric.shadows.ShadowDisplay() {
        @org.robolectric.annotation.Implementation
        fun getDeviceProductInfo(): android.hardware.display.DeviceProductInfo? = throw SecurityException("private metadata")
    }
    @Test
    @Config(shadows = [UnavailableMetadata::class])
    fun inventoryAndPolicyRetainMossyRouteWhenEdidIsUnavailable() {
        val id = panel()
        ReflectionHelpers.setStaticField(Build::class.java, "ID", "")
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "INCREMENTAL", "unknown")
        com.openautolink.app.displays.SecondaryDisplayInventory(context).use { raw ->
            com.openautolink.app.secondary.SecondaryDisplayPolicyInventory(context, raw).use { policy ->
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                val descriptor = policy.snapshots.value.displays.single()
                assertEquals(id, descriptor.displayId)
                assertEquals(raw.displays.value.single().key, descriptor.id.value)
                assertEquals(com.openautolink.app.secondary.SecondaryRoute.GM_HOSTED, descriptor.route)
                assertNull(org.robolectric.Shadows.shadowOf(context as Application).nextStartedActivity)
            }
        }
    }
    @Test fun missingFirmwareAndPublicMetadataDoNotBlockUniqueMossyPanel() {
        val id = panel()
        ReflectionHelpers.setStaticField(Build::class.java, "ID", "")
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "INCREMENTAL", "unknown")
        val qualified = requireNotNull(ClusterProjectionOutput.qualifiedInitialDisplay(context))
        assertEquals(id, qualified.displayId)
        assertEquals(PixelRect(622,165,1374,669), qualified.safe)
        assertEquals(id, ClusterDriverDisplayReceipt.QUALIFIED_GM.resolve(context, qualified)?.displayId)
    }
    @Test fun changedFirmwareDoesNotRevokeInitialOrDownstreamReceipt() {
        val id = panel()
        val initial = requireNotNull(ClusterProjectionOutput.qualifiedInitialDisplay(context))
        ReflectionHelpers.setStaticField(Build::class.java, "ID", "future-build")
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "INCREMENTAL", "0240-release")
        assertEquals(id, ClusterProjectionOutput.qualifiedInitialDisplay(context)?.displayId)
        assertEquals(id, ClusterDriverDisplayReceipt.QUALIFIED_GM.resolve(context, initial)?.displayId)
    }
    @Test fun unavailableEdidAndDiagnosticStringAreNeverAdmissionPrerequisites() {
        val id = panel()
        val initial = requireNotNull(ClusterProjectionOutput.qualifiedInitialDisplay(context))
        val live = manager.getDisplay(id)
        val display = mockk<Display>()
        every { display.displayId } returns id
        every { display.isValid } returns true
        every { display.state } returns Display.STATE_ON
        every { display.flags } returns 0
        every { display.getRealSize(any()) } answers { firstArg<android.graphics.Point>().set(2000,810) }
        every { display.deviceProductInfo } throws SecurityException("not public")
        every { display.toString() } throws IllegalStateException("not an identity API")
        val displays = mockk<DisplayManager>()
        every { displays.displays } returns arrayOf(display)
        val ctx = mockk<Context>()
        every { ctx.getSystemService(DisplayManager::class.java) } returns displays
        assertNotNull(live)
        assertEquals(id, ClusterDriverDisplayReceipt.QUALIFIED_GM.resolve(ctx, initial)?.displayId)
        verify(exactly = 0) { display.deviceProductInfo; display.toString() }
    }
    @Test fun ambiguousCompatiblePanelsDoNotPickFirstOrPassenger() {
        panel(); panel()
        assertNull(ClusterProjectionOutput.qualifiedInitialDisplay(context))
    }
    @Test fun removedSelectedPanelCannotBeReplacedByAnotherPanel() {
        val id = panel()
        val initial = requireNotNull(ClusterProjectionOutput.qualifiedInitialDisplay(context))
        ShadowDisplayManager.removeDisplay(id)
        panel()
        assertNull(ClusterDriverDisplayReceipt.QUALIFIED_GM.resolve(context, initial))
    }
    @Test fun ambiguousRejectionIsPersistedWithStageAndReasonWithoutReadyClaim() {
        panel(); panel()
        mockkObject(com.openautolink.app.diagnostics.DiagnosticLog)
        try {
            every { com.openautolink.app.diagnostics.DiagnosticLog.w(any(), any()) } just Runs
            assertNull(ClusterProjectionOutput.qualifiedInitialDisplay(context))
            verify { com.openautolink.app.diagnostics.DiagnosticLog.w("cluster",
                match { it.contains("stage=initial") && it.contains("reason=ambiguous_target") &&
                    it.contains("attempt=") && it.contains("output=") }) }
        } finally { unmockkObject(com.openautolink.app.diagnostics.DiagnosticLog) }
    }
    @Test fun wrongGeometryDoesNotGetSourcedDefault() {
        ShadowDisplayManager.addDisplay("w1920dp-h1080dp-mdpi")
        assertNull(ClusterProjectionOutput.qualifiedInitialDisplay(context))
    }
    @Test fun unknownVehicleDoesNotAutoOutputToSimilarlySizedScreen() {
        panel()
        ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", "unknown-passenger")
        assertNull(ClusterProjectionOutput.qualifiedInitialDisplay(context))
    }
    @Test fun offPanelDoesNotQualify() {
        val id = panel()
        org.robolectric.Shadows.shadowOf(manager.getDisplay(id)).setState(Display.STATE_OFF)
        assertNull(ClusterProjectionOutput.qualifiedInitialDisplay(context))
    }
}

package com.openautolink.app.secondary

import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.openautolink.app.displays.SecondaryDisplayInventory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
class SecondaryDisplayPolicyInventoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun ambiguousRawRowsAreRetainedButNeverDerivedOrLaunched() {
        val raw = SecondaryDisplayInventory(context)
        val policy = SecondaryDisplayPolicyInventory(context, raw)
        try {
            ShadowDisplayManager.addDisplay("w800dp-h480dp-mdpi", "Identical")
            ShadowDisplayManager.addDisplay("w800dp-h480dp-mdpi", "Identical")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, raw.displays.value.size)
            assertTrue(raw.displays.value.all { it.key == null && it.identityAmbiguous })
            assertTrue(policy.snapshots.value.displays.isEmpty())
            assertNull(shadowOf(context as Application).nextStartedActivity)
        } finally { policy.close(); raw.close() }
    }
    @Test fun presentationCandidateHasUnknownRoleAndCloseRejectsLateEvents() {
        val raw = SecondaryDisplayInventory(context)
        val policy = SecondaryDisplayPolicyInventory(context, raw)
        try {
            val id = ShadowDisplayManager.addDisplay("w800dp-h480dp-mdpi", "Public fixture")
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(id)
            shadowOf(display).setFlags(android.view.Display.FLAG_PRESENTATION)
            raw.refresh()
            shadowOf(Looper.getMainLooper()).idle()
            val row = policy.snapshots.value.displays.single()
            assertEquals("Unknown", row.physicalRoleLabel)
            assertNotEquals(SecondaryRoute.GM_HOSTED, row.route)
            val revision = policy.snapshots.value.revision
            policy.close()
            ShadowDisplayManager.changeDisplay(id, "w1024dp-h600dp-mdpi")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(revision, policy.snapshots.value.revision)
            assertNull(shadowOf(context as Application).nextStartedActivity)
        } finally { policy.close(); raw.close() }
    }
    @Test fun privateDisplayNeverBecomesPublicRoute() {
        val raw = SecondaryDisplayInventory(context)
        val policy = SecondaryDisplayPolicyInventory(context, raw)
        try {
            val id = ShadowDisplayManager.addDisplay("w800dp-h480dp-mdpi", "Private fixture")
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(id)
            shadowOf(display).setFlags(android.view.Display.FLAG_PRIVATE or android.view.Display.FLAG_PRESENTATION)
            assertTrue(display.flags and android.view.Display.FLAG_PRIVATE != 0)
            raw.refresh()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(SecondaryRoute.INVENTORY_ONLY, policy.snapshots.value.displays.single().route)
        } finally { policy.close(); raw.close() }
    }
    @Test fun unchangedPublicDisplayCallbackPersistsDirtyEpochRatherThanMetadataChange() {
        val raw = SecondaryDisplayInventory(context)
        val policy = SecondaryDisplayPolicyInventory(context, raw)
        val writer = com.openautolink.app.diagnostics.FileLogWriter(context)
        val directory = java.io.File(context.getExternalFilesDir(null), "openautolink/logs")
        org.robolectric.shadows.ShadowStatFs.registerStats(directory, 100000, 100000, 100000)
        val previousWriter = com.openautolink.app.diagnostics.DiagnosticLog.fileLogWriter
        val verbose = com.openautolink.app.diagnostics.DiagnosticLog.isVerbose()
        try {
            // changeDisplay(qualifiers) resets the shadow name to this default; hold identity constant.
            val id = ShadowDisplayManager.addDisplay("w1280dp-h720dp-mdpi", "Built-in screen")
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(id)
            shadowOf(display).setFlags(android.view.Display.FLAG_PRESENTATION)
            raw.refresh()
            shadowOf(Looper.getMainLooper()).idle()
            val first = policy.snapshots.value.displays.single()
            val rawBefore = raw.displays.value
            val factsBefore = PublicDisplayFacts.read(context, id)
            assertEquals(1280, first.width)
            assertEquals(720, first.height)
            com.openautolink.app.diagnostics.DiagnosticLog.setVerbose(false)
            val path = requireNotNull(writer.start())
            com.openautolink.app.diagnostics.DiagnosticLog.fileLogWriter = writer
            ShadowDisplayManager.changeDisplay(id, "w1280dp-h720dp-mdpi")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(rawBefore, raw.displays.value)
            assertEquals(factsBefore, PublicDisplayFacts.read(context, id))
            val changed = policy.snapshots.value.displays.single()
            assertTrue(changed.attachmentEpoch > first.attachmentEpoch)
            writer.stop()
            val saved = java.io.File(path).readText()
            assertTrue(saved, saved.contains("stage=policy_display_event event=changed"))
            assertTrue(saved, saved.contains("stage=policy_epoch physical=$id previous=${first.attachmentEpoch} next=${changed.attachmentEpoch} dirty=true rawChanged=false factsChanged=false"))
            assertNull(shadowOf(context as Application).nextStartedActivity)
        } finally {
            com.openautolink.app.diagnostics.DiagnosticLog.fileLogWriter = previousWriter
            writer.stop(); policy.close(); raw.close()
            com.openautolink.app.diagnostics.DiagnosticLog.setVerbose(verbose)
            org.robolectric.shadows.ShadowStatFs.unregisterStats(directory)
        }
    }

    @Test fun liveInventoryNeverLaunchesAndEpochChangesOnDisplayEvent() {
        val raw = SecondaryDisplayInventory(context)
        val policy = SecondaryDisplayPolicyInventory(context, raw)
        try {
            val id = ShadowDisplayManager.addDisplay("w800dp-h480dp-mdpi", "Unknown")
            shadowOf(Looper.getMainLooper()).idle()
            val first = policy.snapshots.value.displays.single()
            assertEquals(id, first.displayId)
            assertEquals(raw.displays.value.single().key, first.id.value)
            assertEquals("Unknown", first.physicalRoleLabel)
            assertNull(shadowOf(context as Application).nextStartedActivity)
            ShadowDisplayManager.changeDisplay(id, "w800dp-h480dp-hdpi")
            shadowOf(Looper.getMainLooper()).idle()
            val changed = policy.snapshots.value.displays.single()
            assertTrue(changed.attachmentEpoch > first.attachmentEpoch)
            ShadowDisplayManager.removeDisplay(id)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(policy.snapshots.value.displays.isEmpty())
        } finally { policy.close(); raw.close() }
    }
}

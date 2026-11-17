package com.openautolink.app.ui.components

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/** Disk-serialization/restore controls, NOT a PackageManager update or fresh Android process. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32, 34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MenuPlacementDiskRestoreTest {
    private val geometry = OverlayButtonGeometry(1200f, 700f, 572f, 322f, 56f, 56f,
        density = 1.5f, originX = 12f, originY = 24f)
    private val zero = OverlayButtonPoint(0f, 0f)

    @Test fun customizedMenuReloadsSerializedPlacementWithoutRecentringOrRewriting() {
        val app = RuntimeEnvironment.getApplication() as Application
        val name = "menu-restore-${java.util.UUID.randomUUID()}"
        val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().putString("unrelated", "keep").putFloat("overlay_settings_parent_x", .99f).commit()
        OverlayButtonPositionStore(prefs, "overlay_menu").saveOffset(geometry, OverlayButtonPoint(-360f, 170f))
        // A synchronous empty transaction waits for the preceding apply's disk write.
        assertTrue(prefs.edit().commit())
        val before = prefs.all.toMap()
        val disk = File(app.applicationInfo.dataDir, "shared_prefs/$name.xml")
        assertTrue("actual serialized preferences must exist", disk.isFile)
        val restoredName = "$name-reloaded"
        disk.copyTo(File(disk.parentFile, "$restoredName.xml"))
        // Distinct file/name avoids the original SharedPreferences in-memory cache.
        val restored = app.getSharedPreferences(restoredName, Context.MODE_PRIVATE)
        assertEquals(before, restored.all)
        val store = OverlayButtonPositionStore(restored, "overlay_menu")
        for (window in listOf(geometry, geometry.copy(parentWidth = 240f, parentHeight = 180f,
            anchorX = 92f, anchorY = 62f), geometry.copy(density = 2f, buttonWidth = 84f,
            buttonHeight = 84f), geometry)) {
            val actual = store.readOffset(window, zero)
            val expected = window.offsetFromPreferredDp(OverlayButtonPoint(
                prefs.getFloat("overlay_menu_preferred_x_dp", 0f),
                prefs.getFloat("overlay_menu_preferred_y_dp", 0f)))
            assertEquals(expected, actual)
            assertEquals("relayout cannot rewrite preferred position", before, restored.all)
        }
        assertEquals(OverlayButtonPoint(-360f, 170f), store.readOffset(geometry, zero))
        OverlayButtonPositionStore.resetAll(restored)
        assertEquals(zero, store.readOffset(geometry, zero))
        assertEquals("keep", restored.getString("unrelated", null))
    }

    @Test fun menuV2AndLegacyMigrateOnceWithinTheMenuNamespace() {
        val app = RuntimeEnvironment.getApplication() as Application
        for (schema in listOf("v2", "legacy")) {
            val prefs = app.getSharedPreferences("menu-$schema-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)
            val expected = if (schema == "v2") geometry.offsetFromFraction(OverlayButtonPoint(.2f, .8f))
                else OverlayButtonPoint(-120f, 90f)
            prefs.edit().apply {
                if (schema == "v2") putFloat("overlay_menu_parent_x", .2f).putFloat("overlay_menu_parent_y", .8f)
                else putFloat("overlay_menu_x", -120f).putFloat("overlay_menu_y", 90f)
            }.commit()
            val store = OverlayButtonPositionStore(prefs, "overlay_menu")
            assertEquals(expected, store.readOffset(geometry, zero))
            assertTrue(prefs.contains("overlay_menu_preferred_x_dp"))
            assertTrue(prefs.contains("overlay_menu_preferred_y_dp"))
            val migrated = prefs.all.toMap()
            val actual = OverlayButtonPositionStore(prefs, "overlay_menu").readOffset(geometry, zero)
            assertEquals(expected.x, actual.x, .001f)
            assertEquals(expected.y, actual.y, .001f)
            assertEquals(migrated, prefs.all)
        }
    }
}

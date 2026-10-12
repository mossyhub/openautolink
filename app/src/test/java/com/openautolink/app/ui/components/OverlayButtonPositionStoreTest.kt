package com.openautolink.app.ui.components

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [32])
@ConscryptMode(ConscryptMode.Mode.OFF)
class OverlayButtonPositionStoreTest {
    private fun preferences() = RuntimeEnvironment.getApplication().getSharedPreferences("overlay_positions", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }

    @Test fun `enabling Stats keeps untouched Settings in its nonoverlapping default stack slot`() {
        val prefs = preferences()
        val default = OverlayButtonPoint(0f, 0f)
        val settings = OverlayButtonPositionStore(prefs, "overlay_settings")
        val settingsOnly = OverlayButtonGeometry(1200f, 700f, 1120f, 628f, 64f, 56f)
        settings.readOffset(settingsOnly, default)

        // Actual Column layout: inserting Stats adds its 56px height plus 8px spacer.
        val settingsWithStats = settingsOnly.copy(anchorY = 564f)
        val settingsOffset = settings.readOffset(settingsWithStats, default)
        val statsOffset = OverlayButtonPositionStore(prefs, "overlay_stats").readOffset(settingsOnly, default)
        val settingsTop = settingsWithStats.anchorY + settingsOffset.y
        val statsTop = settingsOnly.anchorY + statsOffset.y
        assertTrue("Stats must not cover untouched Settings", settingsTop + settingsWithStats.buttonHeight <= statsTop)
        assertEquals("Settings follows its natural stack anchor", 564f, settingsTop, 0.001f)
        assertTrue("layout alone must not persist a customized position", prefs.all.isEmpty())
    }

    @Test fun `untouched offscreen defaults clamp without saving across all shared controls`() {
        val prefs = preferences()
        val system = OverlayButtonGeometry(1200f, 700f, 1120f, 580f, 64f, 56f)
        val full = OverlayButtonGeometry(1900f, 1100f, 1810f, 740f, 64f, 56f)
        for (key in listOf("overlay_settings", "overlay_switch_phone", "overlay_stats", "overlay_reconnect",
            "overlay_file_log", "overlay_upload_logs", "overlay_simulate_ignition")) {
            val store = OverlayButtonPositionStore(prefs, key)
            for (geometry in listOf(system, full, system)) {
                val offset = store.readOffset(geometry, OverlayButtonPoint(-1500f, -5000f))
                val left = geometry.anchorX + offset.x
                val top = geometry.anchorY + offset.y
                assertTrue(left >= 0f && left + geometry.buttonWidth <= geometry.parentWidth)
                assertTrue(top >= 0f && top + geometry.buttonHeight <= geometry.parentHeight)
            }
        }
        assertTrue("safety clamping must not customize untouched defaults", prefs.all.isEmpty())
    }

    @Test fun `real drag after untouched layout preserves interior fractions across modes and stack changes`() {
        val prefs = preferences()
        val default = OverlayButtonPoint(0f, 0f)
        val system = OverlayButtonGeometry(1200f, 700f, 1120f, 628f, 64f, 56f)
        val fullWithStats = OverlayButtonGeometry(1900f, 1100f, 1810f, 972f, 64f, 56f)
        val store = OverlayButtonPositionStore(prefs, "overlay_settings")
        store.readOffset(system, default)
        assertTrue(prefs.all.isEmpty())
        val drag = OverlayButtonPoint(-550f, -200f)
        store.saveOffset(system, drag)
        val fraction = system.fractionFromOffset(drag)
        val saved = prefs.all.toMap()
        for (geometry in listOf(fullWithStats, system.copy(anchorY = 564f), system)) {
            val restored = OverlayButtonPositionStore(prefs, "overlay_settings").readOffset(geometry, default)
            assertEquals(fraction.x * (geometry.parentWidth - geometry.buttonWidth), geometry.anchorX + restored.x, 0.001f)
            assertEquals(fraction.y * (geometry.parentHeight - geometry.buttonHeight), geometry.anchorY + restored.y, 0.001f)
        }
        assertEquals("mode changes must not rewrite explicit drag fractions", saved, prefs.all)
    }

    @Test fun `legacy traps recover on first layout for settings info and upload without settings access`() {
        val prefs = preferences()
        prefs.edit().putString("unrelated", "preserve me").commit()
        val geometry = OverlayButtonGeometry(1200f, 700f, 1120f, 580f, 64f, 56f)
        for ((key, legacy) in listOf(
            "overlay_settings" to OverlayButtonPoint(1700f, 1400f),
            "overlay_stats" to OverlayButtonPoint(1700f, -1400f),
            "overlay_upload_logs" to OverlayButtonPoint(-1800f, -1400f),
        )) {
            prefs.edit().putFloat("${key}_x", legacy.x).putFloat("${key}_y", legacy.y).commit()
            val recovered = OverlayButtonPositionStore(prefs, key).readOffset(geometry, OverlayButtonPoint(0f, 0f))
            val left = geometry.anchorX + recovered.x
            val top = geometry.anchorY + recovered.y
            assertTrue(left >= 0f && left + geometry.buttonWidth <= geometry.parentWidth)
            assertTrue(top >= 0f && top + geometry.buttonHeight <= geometry.parentHeight)
            assertTrue(prefs.contains("${key}_parent_x"))
            assertTrue(prefs.contains("${key}_parent_y"))
        }
        assertEquals("preserve me", prefs.getString("unrelated", null))
    }

    @Test fun `edge drag remains reachable and stable across repeated mode inset size and anchor changes`() {
        val prefs = preferences()
        val full = OverlayButtonGeometry(1900f, 1100f, 1810f, 740f, 64f, 56f)
        val system = OverlayButtonGeometry(1500f, 850f, 1410f, 480f, 80f, 72f)
        for (key in listOf("overlay_settings", "overlay_stats", "overlay_upload_logs")) {
            val store = OverlayButtonPositionStore(prefs, key)
            store.saveOffset(system, OverlayButtonPoint(5000f, -5000f))
            val saved = prefs.all.toMap()
            repeat(10) {
                for (geometry in listOf(full, system, full.copy(anchorY = 990f))) {
                    val restored = OverlayButtonPositionStore(prefs, key).readOffset(geometry, OverlayButtonPoint(0f, 0f))
                    assertEquals(geometry.parentWidth - geometry.buttonWidth, geometry.anchorX + restored.x, 0.01f)
                    assertEquals(0f, geometry.anchorY + restored.y, 0.01f)
                }
            }
            assertEquals("relayout is not a preference reset or repeated write", saved, prefs.all)
        }
    }

    @Test fun `malformed normalized positions cannot trap controls`() {
        val prefs = preferences()
        prefs.edit().putFloat("overlay_settings_parent_x", Float.NaN)
            .putFloat("overlay_settings_parent_y", Float.NEGATIVE_INFINITY).commit()
        val geometry = OverlayButtonGeometry(1200f, 700f, 1120f, 580f, 64f, 56f)
        val restored = OverlayButtonPositionStore(prefs, "overlay_settings").readOffset(geometry, OverlayButtonPoint(0f, 0f))
        assertTrue(restored.x.isFinite() && restored.y.isFinite())
    }

    @Test fun `reachable legacy custom placement is migrated without a default reset`() {
        val prefs = preferences()
        prefs.edit().putFloat("overlay_settings_x", -550f).putFloat("overlay_settings_y", -200f).commit()
        val geometry = OverlayButtonGeometry(1200f, 700f, 1120f, 580f, 64f, 56f)
        val offset = OverlayButtonPositionStore(prefs, "overlay_settings").readOffset(geometry, OverlayButtonPoint(0f, 0f))
        assertEquals(OverlayButtonPoint(-550f, -200f), offset)
        val reopened = OverlayButtonPositionStore(prefs, "overlay_settings").readOffset(geometry, OverlayButtonPoint(0f, 0f))
        assertEquals(offset.x, reopened.x, 0.001f)
        assertEquals(offset.y, reopened.y, 0.001f)
    }

    @Test fun `persisted drag uses parent basis rather than optional column anchor`() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("overlay_positions", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = OverlayButtonPositionStore(prefs, "overlay_settings")
        val before = OverlayButtonGeometry(1200f, 700f, 1120f, 100f, 64f, 56f)
        store.saveOffset(before, OverlayButtonPoint(-500f, 200f))
        val after = before.copy(anchorY = 300f)
        val reopened = OverlayButtonPositionStore(prefs, "overlay_settings")
        val offset = reopened.readOffset(after, OverlayButtonPoint(0f, 0f))
        assertEquals("optional buttons must not move a saved button", 300f, after.anchorY + offset.y, 0.01f)
    }
}

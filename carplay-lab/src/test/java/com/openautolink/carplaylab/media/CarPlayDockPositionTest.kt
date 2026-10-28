package com.openautolink.carplaylab.media

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test

/** Actual /info binary-plist contracts, not phone-visible placement acceptance. */
class CarPlayDockPositionTest {
    private fun dockClass(): Class<*> {
        val type = runCatching { Class.forName("com.openautolink.carplaylab.media.CarPlayDockPosition") }.getOrNull()
        assertNotNull("A bounded next-session dock position model is required", type)
        return type!!
    }
    private fun positioned(base: CarPlayDisplayPreferences, id: String): CarPlayDisplayPreferences {
        val type = dockClass()
        val value = type.enumConstants.single { type.getMethod("getId").invoke(it) == id }
        val copy = base.javaClass.methods.single { it.name == "copy" }
        val args = copy.parameterTypes.indices.map { base.javaClass.getMethod("component${it + 1}").invoke(base) }.toTypedArray()
        args[copy.parameterTypes.indexOf(type)] = value
        return copy.invoke(base, *args) as CarPlayDisplayPreferences
    }
    private fun info(display: CarPlayDisplayPreferences): Map<*, *> {
        val config = benchReceiverConfig("00:11:22:33:44:55", 41234, display = display)
            .copy(drivingRestrictions = true, cluster = AirPlayDisplayConfig(800, 480))
        return AirPlayInfoPlist.build(config, emptySet())
    }
    private fun bytes(value: Map<*, *>) = BplistCodec.encode(value)

    @Test fun boundedIdsAndUnknownStoredValuesUseAutomaticOnly() {
        val type = dockClass()
        assertEquals(listOf("automatic", "bottom", "driver_side"), type.enumConstants.map { type.getMethod("getId").invoke(it) })
        assertEquals(listOf(null, 1, 2), type.enumConstants.map { type.getMethod("getWireEdge").invoke(it) })
        val companion = type.getField("Companion").get(null)
        val restore = companion.javaClass.getMethod("fromStored", String::class.java)
        for (invalid in listOf(null, "", "BOTTOM", "left", "right", "1", " automatic "))
            assertEquals(type.enumConstants[0], restore.invoke(companion, invalid))
        for (value in type.enumConstants)
            assertEquals(value, restore.invoke(companion, type.getMethod("getId").invoke(value)))
    }

    @Test fun automaticIsByteIdenticalToOriginalDisplayConstructorWithSafeInsets() {
        for (resolution in listOf(CarPlayResolution.FULL_HD1080, CarPlayResolution.PORTRAIT1080)) {
            val base = CarPlayDisplayPreferences(resolution = resolution, fps = 59,
                encodedSafeInsets = AirPlayInsets(12, 24, 36, 48))
            val automatic = positioned(base, "automatic")
            assertNull(automatic.toAirPlayDisplay().viewAreas)
            val size = base.physicalSize
            val original = AirPlayDisplayConfig(base.width, base.height, size.widthMm, size.heightMm,
                fps = base.fps, safeArea = base.encodedSafeInsets, safeAreaDrawOutside = true)
            val config = benchReceiverConfig("00:11:22:33:44:55", 41234, display = automatic)
            assertArrayEquals(BplistCodec.encode(AirPlayInfoPlist.build(config.copy(main = original), emptySet())),
                BplistCodec.encode(AirPlayInfoPlist.build(config, emptySet())))
        }
    }

    @Test fun fixedEdgesOnlyAddOneNestedIntegerWithoutChangingDisplayHidOrSafeGeometry() {
        for (resolution in listOf(CarPlayResolution.FULL_HD1080, CarPlayResolution.PORTRAIT1080))
            for (rhd in listOf(false, true)) for ((id, edge) in listOf("bottom" to 1, "driver_side" to 2)) {
                val base = CarPlayDisplayPreferences(resolution = resolution, fps = 59, rightHandDrive = rhd,
                    preferHevc = true, encodedSafeInsets = AirPlayInsets(12, 24, 36, 48))
                val fixed = positioned(base, id)
                val display = fixed.toAirPlayDisplay()
                assertEquals(listOf(AirPlayViewArea(base.width, base.height, dockEdge = edge)), display.viewAreas)
                assertEquals(0, display.initialViewArea)
                assertEquals(base.encodedSafeInsets, display.safeArea)
                assertEquals(true, display.safeAreaDrawOutside)
                assertEquals(base.codec, fixed.codec)
                assertEquals(base.preferHevc, fixed.preferHevc)
                val decoded = BplistCodec.decode(bytes(info(fixed))) as Map<*, *>
                val original = BplistCodec.decode(bytes(info(base))) as Map<*, *>
                val displays = decoded["displays"] as List<*>
                assertEquals(2, displays.size) // existing alternate screen is unchanged; no added screens
                val main = displays.first() as Map<*, *>
                assertEquals(0L, main["initialViewArea"])
                assertFalse(main.containsKey("viewAreaTransitionControl"))
                assertFalse(main.containsKey("viewAreaStatusBarEdge"))
                val area = (main["viewAreas"] as List<*>).single() as Map<*, *>
                assertEquals(edge.toLong(), area["viewAreaStatusBarEdge"])
                assertEquals(base.width.toLong(), area["widthPixels"])
                assertEquals(base.height.toLong(), area["heightPixels"])
                assertEquals(0L, area["originXPixels"]); assertEquals(0L, area["originYPixels"])
                val normalizedArea = area.toMutableMap().apply { remove("viewAreaStatusBarEdge") }
                val normalizedMain = main.toMutableMap().apply { put("viewAreas", listOf(normalizedArea)) }
                val normalized = decoded.toMutableMap().apply { put("displays", listOf(normalizedMain, displays[1])) }
                assertArrayEquals("Only nested status bar edge changes, including physical/FPS/HID/limitedUI/alt", bytes(original), bytes(normalized))
                assertEquals(rhd, decoded["rightHandDrive"])
            }
    }

    @Test fun immutableCopyAndQualificationFallbackRetainDockPosition() {
        val requested = positioned(CarPlayDisplayPreferences(resolution = CarPlayResolution.FULL_HD1080), "driver_side")
        val selected = CarPlayDisplayPolicy.select(requested, AvcDecoderProbe {
            if (it.width == 1280 && it.height == 720 && it.fps == 30) "baseline" else null
        })
        assertTrue(selected.usedFallback)
        assertEquals(2, selected.display.toAirPlayDisplay().viewAreas!!.single().dockEdge)
        assertEquals(2, requested.copy(fps = 35).toAirPlayDisplay().viewAreas!!.single().dockEdge)
        assertEquals(1920, requested.width)
    }
}

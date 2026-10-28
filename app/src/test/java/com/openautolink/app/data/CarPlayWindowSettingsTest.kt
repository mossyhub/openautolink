package com.openautolink.app.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancelAndJoin

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [32, 34], application = android.app.Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class CarPlayWindowSettingsTest {
    @Test fun bootstrapTimeoutPassesMissingViewportRatherThanFabricatingDisplayMetrics() {
        val vm = java.io.File("src/main/java/com/openautolink/app/ui/projection/ProjectionViewModel.kt").readText()
        assertFalse("Explicit Auto carries missing viewport without a synthetic Input",
            vm.contains("0, 0, 0, 0, Double.NaN, Double.NaN, 0)"))
    }

    @Test fun finalQualifiedFallbackFreezesTheSamePhysicalScaleForWireAndDiagnostics() {
        val selected = CarPlaySettings(autoResolution = false, resolution = "1440p", fitToWindow = true)
            .qualifiedDisplay(viewport(2914,1134), com.shilapi.xcertplay.media.VideoDecoderProbe {
                if (it.width == 1280 && it.height == 720 && it.fps == 30) "baseline" else null
            })
        val scale = selected.resolvedScale!!
        assertSame(scale, selected.scaleReceipt)
        assertEquals(1280, selected.width)
        assertEquals(720, selected.height)
        assertEquals(selected.toAirPlayDisplay().widthPhysicalMm, scale.effectiveWidthMm)
        assertEquals(selected.toAirPlayDisplay().heightPhysicalMm, scale.effectiveHeightMm)
        assertTrue(com.openautolink.carplaylab.media.CarPlayDisplayPolicy.scaleDiagnostic(selected)
            .contains("physical=${scale.effectiveWidthMm}x${scale.effectiveHeightMm} coded=1280x720"))
        assertSame(scale, selected.resolvedScale)
    }

    @Test fun independentCalibrationDraftCommitsToRealDataStoreWithoutChangingAaOrConcurrentFields() = kotlinx.coroutines.runBlocking {
        val file = java.io.File("build/window-calibration-${java.util.UUID.randomUUID()}.preferences_pb")
        file.parentFile.mkdirs()
        val job = kotlinx.coroutines.SupervisorJob()
        val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(job + kotlinx.coroutines.Dispatchers.IO), produceFile = { file })
        try {
            val preferences = AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
                .apply { isAccessible = true }.newInstance(store)
            preferences.setSafeAreaRight(319)
            preferences.setAaResolution("1440p")
            preferences.setCarPlaySettings(CarPlaySettings(autoResolution = false, resolution = "1440p", enabled = false))
            val draft = com.openautolink.app.ui.settings.CarPlaySettingsDraft()
            draft.update { it.copy(fitToWindow = true, safeRight = 1100, safeTop = 150) }
            // A separate tab's saved changes must not be lost by calibration Apply.
            preferences.updateCarPlaySettings({ it.copy(fps = 59, widthPhysicalMm = 225) })
            assertTrue(draft.commit { merge, secret ->
                assertNull(secret)
                preferences.updateCarPlaySettings(merge)
            })
            val restored = preferences.carPlaySettings.first()
            assertTrue(restored.fitToWindow)
            assertEquals(1100, restored.safeRight)
            assertEquals(150, restored.safeTop)
            assertEquals(59, restored.fps)
            assertEquals(225, restored.widthPhysicalMm)
            assertFalse(restored.enabled)
            assertEquals("1440p", restored.resolution)
            assertEquals(319, preferences.safeAreaRight.first())
            assertEquals("1440p", preferences.aaResolution.first())
        } finally { job.cancelAndJoin(); file.delete() }
    }

    private val probe = com.shilapi.xcertplay.media.VideoDecoderProbe { "qualified-${it.codec}" }
    private fun viewport(w: Int, h: Int) = com.openautolink.carplaylab.media.CarPlayAutoScalePolicy.Input(
        w, h, w, h, 200.0, 200.0, 200)

    @Test fun explicitExactManualChoicesMigrateWithoutChangingGeometryOrEnabledState() {
        for (id in listOf("720p", "1080p", "1440p", "4k", "custom", "1920x720")) {
            val old = """{"enabled":false,"resolution":"$id","fitToWindow":false,"widthPixels":2400,"heightPixels":960,"fps":59,"autoScale":false,"videoCodec":"hevc","widthPhysicalMm":225}"""
            val saved = CarPlaySettings.decode(old)
            assertFalse(saved.fitToWindow)
            assertFalse(saved.usesWindowGeometry)
            val expected = saved.displayRequest()
            val selected = saved.qualifiedDisplay(viewport(2914,1134), probe)
            assertEquals(expected.width, selected.width)
            assertEquals(expected.height, selected.height)
            assertEquals(59, selected.fps)
            assertEquals(225, selected.widthPhysicalMm)
            assertFalse(saved.enabled)
            assertEquals(saved, CarPlaySettings.decode(CarPlaySettings.encode(saved)))
        }
    }

    @Test fun fitFlagNeverOverridesCustomOrGeometryNamedPreset() {
        for (saved in listOf(
            CarPlaySettings(autoResolution = false, resolution = "custom", widthPixels = 2400, heightPixels = 960, fitToWindow = true),
            CarPlaySettings(autoResolution = false, resolution = "1920x720", fitToWindow = true))) {
            val selected = saved.qualifiedDisplay(viewport(2914,1134), probe)
            assertFalse(saved.usesWindowGeometry)
            assertEquals(saved.displayRequest().width, selected.width)
            assertEquals(saved.displayRequest().height, selected.height)
        }
    }

    @Test fun selectedDetailAreaDoesNotSilentlyBecomeAutomatic1440() {
        val areas = listOf("720p", "1080p", "1440p", "4k").map { id ->
            val selected = CarPlaySettings(autoResolution = false, resolution = id, fitToWindow = true).qualifiedDisplay(viewport(2400,960), probe)
            assertEquals(2.5, selected.width.toDouble() / selected.height, .005)
            selected.width.toLong() * selected.height
        }
        assertTrue(areas.zipWithNext().all { (smaller, larger) -> smaller < larger })
    }

    @Test fun onlyOneExactQualifiedReceiptIsConsumedAfterWindowSelection() {
        val selected = CarPlaySettings(autoResolution = false, resolution = "1440p", fitToWindow = true, videoCodec = "hevc")
            .qualifiedDisplay(viewport(2914,1134), probe)
        assertSame(selected.qualifiedVideoSelection,
            com.openautolink.carplaylab.media.CarPlayDisplayPolicy.videoSelection(selected,
                com.shilapi.xcertplay.media.VideoDecoderProbe { error("Must not probe again") }))
    }

    @Test fun safeEdgesUseLetterboxOriginRatherThanShrinkingTouchCanvas() {
        val selected = CarPlaySettings(autoResolution = false, resolution = "1080p", fitToWindow = false, autoScale = false, safeRight = 3000)
            .qualifiedDisplay(viewport(3000,1000), probe)
        assertEquals(312, selected.toAirPlayDisplay().safeArea!!.right)
        assertEquals(1920, selected.width)
        assertEquals(1080, selected.height)
        val fit = com.openautolink.carplaylab.media.CarPlayDisplayPolicy.fitRect(selected,3000,1000)!!
        assertEquals(1000f, fit.height, .01f)
        assertTrue(fit.left > 0)
    }

    @Test fun alreadyChromeFreeParentHasNoSecondBarDeduction() {
        val saved = CarPlaySettings(autoResolution = false, resolution = "custom", widthPixels = 2400, heightPixels = 960,
            autoScale = false, safeRight = 1000)
        val fullscreen = saved.qualifiedDisplay(viewport(1200,480), probe)
        val barsVisible = saved.qualifiedDisplay(viewport(1200,400), probe)
        assertEquals(240, fullscreen.toAirPlayDisplay().safeArea!!.right)
        assertEquals(48, barsVisible.toAirPlayDisplay().safeArea!!.right)
        assertEquals(saved, CarPlaySettings.decode(CarPlaySettings.encode(saved)))
    }

    @Test fun zeroCalibrationKeepsWireViewAndSafeAreaIdenticalToOldDefaults() {
        val display = CarPlaySettings(autoScale = false).qualifiedDisplay(null, probe)
        val main = display.toAirPlayDisplay()
        assertNull(main.safeArea)
        val config = com.shilapi.xcertplay.airplay.AirPlayConfig("synthetic", "00:11:22:33:44:55", "00:11:22:33:44:55", "950.7.1", main)
        val info = com.shilapi.xcertplay.airplay.AirPlayInfoPlist.build(config, emptySet())
        val old = com.shilapi.xcertplay.airplay.AirPlayInfoPlist.build(config.copy(main = main.copy(safeAreaDrawOutside = null)), emptySet())
        assertEquals(old["displays"], info["displays"])
    }

    @Test fun serializerEmitsBackgroundOutsideSafeAreaWithFullHidCoordinates() {
        val display = CarPlaySettings(autoResolution = false, resolution = "custom", widthPixels = 2400, heightPixels = 960,
            safeTop = 500, safeLeft = 500, safeRight = 1000).qualifiedDisplay(viewport(1200,480), probe)
        val config = com.shilapi.xcertplay.airplay.AirPlayConfig("synthetic", "00:11:22:33:44:55", "00:11:22:33:44:55", "950.7.1", display.toAirPlayDisplay())
        val info = com.shilapi.xcertplay.airplay.AirPlayInfoPlist.build(config, emptySet())
        val main = (info["displays"] as List<*>).single() as Map<*,*>
        val view = (main["viewAreas"] as List<*>).single() as Map<*,*>
        val safe = view["safeArea"] as Map<*,*>
        assertEquals(2400, view["widthPixels"])
        assertEquals(960, view["heightPixels"])
        assertEquals(120, safe["originXPixels"])
        assertEquals(48, safe["originYPixels"])
        assertEquals(2040, safe["widthPixels"])
        assertEquals(912, safe["heightPixels"])
        assertEquals(true, safe["drawUIOutsideSafeArea"])
        val hid = ((info["hidDevices"] as List<*>).first() as Map<*,*>)["hidDescriptor"] as ByteArray
        assertTrue(hid.toList().windowed(3).contains(listOf(0x26.toByte(), 0x60.toByte(), 0x09.toByte())))
        assertTrue(hid.toList().windowed(3).contains(listOf(0x26.toByte(), 0xC0.toByte(), 0x03.toByte())))
    }

    @Test fun nonzeroSafeAreaFailsClosedWithoutMeasuredParentOrForInvalidEdges() {
        assertThrows(IllegalArgumentException::class.java) {
            CarPlaySettings(safeRight = 1000).qualifiedDisplay(null, probe)
        }
        for (edge in listOf(-1, 5000, Int.MAX_VALUE)) {
            val invalid = CarPlaySettings(safeRight = edge)
            assertNotNull(invalid.videoError())
            assertThrows(IllegalArgumentException::class.java) { invalid.qualifiedDisplay(viewport(1200,480), probe) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            com.openautolink.carplaylab.media.CarPlayDisplayPreferences(
                encodedSafeInsets = com.shilapi.xcertplay.airplay.AirPlayInsets(left = 1280))
        }
    }

    @Test fun failedWideGeometryFallsBackOnlyToQualifiedBaselineWithoutRepeatingFailedProbe() {
        val checked = mutableListOf<com.shilapi.xcertplay.media.VideoDecoderRequest>()
        val selected = CarPlaySettings(autoResolution = false, resolution = "1440p", fitToWindow = true, fps = 60)
            .qualifiedDisplay(viewport(2914,1134), com.shilapi.xcertplay.media.VideoDecoderProbe {
                checked += it
                if (it.width == 1280 && it.height == 720 && it.fps == 30) "baseline" else null
            })
        assertTrue(selected.qualifiedVideoSelection!!.usedFallback)
        assertEquals(1280, selected.width)
        assertEquals(720, selected.height)
        assertEquals(30, selected.fps)
        assertEquals(checked.size, checked.distinct().size)
        assertThrows(IllegalStateException::class.java) {
            CarPlaySettings(autoResolution = false, resolution = "1440p", fitToWindow = true).qualifiedDisplay(viewport(2914,1134),
                com.shilapi.xcertplay.media.VideoDecoderProbe { null })
        }
    }

    @Test fun windowAndCalibrationUiUseParentWithoutAaSettingsMutation() {
        val root = java.io.File("src/main/java/com/openautolink/app")
        val ui = java.io.File(root,"ui/settings/CarPlaySettings.kt").readText()
        val editor = java.io.File(root,"ui/settings/CarPlaySafeEditor.kt").readText()
        val layer = java.io.File(root,"carplay/CarPlayProjectionLayer.kt").readText()
        assertTrue("Expose an independent fit toggle", ui.contains("carPlayFitToWindow"))
        assertTrue(ui.contains("CarPlay safe area"))
        assertTrue(editor.contains("coordinateWidth = 10000"))
        assertFalse("Calibration must not create an OEM separate window", editor.contains("Dialog("))
        assertTrue(editor.contains("safeTop = top"))
        assertFalse(ui.contains("updateSafeArea"))
        assertTrue("Parent owns snapshot geometry", layer.contains("parentSize.width, parentSize.height"))
        assertFalse("Fitted view cannot drive parent receipt", layer.contains("this@apply.width, this@apply.height"))
    }

    @Test fun manualSafeEdgesReachTheExactOfferWithoutShrinkingCanvas() {
        val settings = CarPlaySettings(autoResolution = false, resolution = "custom", widthPixels = 2400, heightPixels = 960,
            autoScale = false, safeRight = 1000)
        val input = com.openautolink.carplaylab.media.CarPlayAutoScalePolicy.Input(1200,480,2400,960,200.0,200.0,200)
        val display = settings.qualifiedDisplay(input, com.shilapi.xcertplay.media.VideoDecoderProbe { "qualified" })
        val config = display.toAirPlayDisplay()
        assertNotNull("Safe calibration must reach the frozen display offer", config.safeArea)
        assertEquals(240, config.safeArea!!.right)
        assertEquals(2400, config.widthPixels)
        assertEquals(960, config.heightPixels)
        assertNull(config.viewArea)
        assertEquals(true, config.safeAreaDrawOutside)
    }

    @Test fun selected1440DetailFillsMeasuredWideParent() {
        val settings = CarPlaySettings(autoResolution = false, resolution = "1440p", fps = 60, videoCodec = "hevc", fitToWindow = true)
        val input = com.openautolink.carplaylab.media.CarPlayAutoScalePolicy.Input(2914,1134,2914,1134,200.0,200.0,200)
        val probe = com.shilapi.xcertplay.media.VideoDecoderProbe { "qualified-${it.codec}" }
        val display = settings.qualifiedDisplay(input, probe)
        assertTrue(display.width > 2560)
        assertEquals(2914.0 / 1134, display.width.toDouble() / display.height, .005)
        assertEquals(60, display.fps)
        assertTrue(display.qualifiedVideoSelection!!.hevcOffered)
        assertEquals("1440p", settings.resolution)
    }

    @Test fun explicitWindowAndSafeCalibrationSurviveStorage() {
        val json = """{"enabled":false,"resolution":"1440p","fps":60,"videoCodec":"hevc","fitToWindow":true,"safeTop":100,"safeBottom":200,"safeLeft":300,"safeRight":1100}"""
        val restored = CarPlaySettings.decode(json)
        val encoded = CarPlaySettings.encode(restored)
        assertTrue("Independent window presentation must persist", encoded.contains("\"fitToWindow\":true"))
        assertTrue("Independent safe calibration must persist", encoded.contains("\"safeRight\":1100"))
        assertFalse(restored.enabled)
        assertEquals("1440p", restored.resolution)
        assertEquals("hevc", restored.videoCodec)
    }
}

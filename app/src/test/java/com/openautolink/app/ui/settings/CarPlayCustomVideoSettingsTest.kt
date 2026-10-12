package com.openautolink.app.ui.settings

import com.openautolink.app.data.CarPlaySettings
import com.openautolink.app.data.CarPlaySettingsPolicy
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CarPlayCustomVideoSettingsTest {
    @Test fun customJsonDimensionsAndIntegerRateRoundTripWithoutPresetSubstitution() {
        val json = """{"enabled":true,"resolution":"custom","widthPixels":2400,"heightPixels":960,"fps":31,"ssid":"keep"}"""
        val restored = CarPlaySettings.decode(json)
        assertEquals("custom", restored.resolution)
        assertEquals(31, restored.fps)
        val encoded = CarPlaySettings.encode(restored)
        assertTrue(encoded.contains("\"widthPixels\":2400"))
        assertTrue(encoded.contains("\"heightPixels\":960"))
        assertEquals(restored, CarPlaySettings.decode(encoded))
        assertNull(carPlayEditError(restored))
    }

    @Test fun runtimeBootstrapUsesStrictExactRequestNotStoredPresetOnlyFallback() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        val vm = File(root, "app/src/main/java/com/openautolink/app/ui/projection/ProjectionViewModel.kt").readText()
        val bootstrap = vm.substringAfter("private fun createCarPlayRuntime").substringBefore("fun reconnectCarPlay")
        assertTrue(bootstrap.contains("saved.qualifiedDisplay(viewport)"))
        assertFalse(bootstrap.contains("CarPlayDisplayPreferences.fromStored"))
        val runtime = File(root, "app/src/main/java/com/openautolink/app/carplay/OalCarPlayRuntime.kt").readText()
        assertTrue(runtime.contains("CarPlayDisplayPolicy.scaleDiagnostic(projection.displayConfiguration)"))
        assertTrue(runtime.contains("DiagnosticLog.i(\"carplay-scale\", scaleSnapshot)"))
    }

    @Test fun partialTextDraftFailsSaveAndRetainsAllEnteredTextUntilCorrection() = kotlinx.coroutines.runBlocking {
        for (raw in listOf("", "-", "2.", "2147483648", "2401", "0")) {
            val draft = CarPlaySettingsDraft()
            draft.update { it.copy(resolution = "custom", widthPixels = raw.toIntOrNull() ?: 0,
                widthDraft = raw, heightPixels = 960, fps = 31, fpsDraft = "31") }
            var writes = 0
            val saved = draft.commit { merge, _ ->
                val next = merge(CarPlaySettings())
                check(carPlayEditError(next) == null)
                writes++
            }
            assertFalse(saved); assertEquals(0, writes)
            assertEquals(raw, draft.preview(CarPlaySettings()).widthDraft)
            draft.update { it.copy(widthPixels = 2400, widthDraft = "2400") }
            assertTrue(draft.commit { merge, _ ->
                val next = merge(CarPlaySettings())
                assertNull(carPlayEditError(next))
                assertEquals(2400, next.displayRequest().width)
                assertEquals(31, next.displayRequest().fps)
                assertFalse(CarPlaySettings.encode(next).contains("Draft"))
                writes++
            })
            assertEquals(1, writes)
        }
    }

    @Test fun integerFpsDraftRejectsFractionalEmptyOverflowAndOutOfRangeWithoutLosingText() {
        for (raw in listOf("", "-", "29.97", "2147483648", "0", "61")) {
            val settings = CarPlaySettings(fps = raw.toIntOrNull() ?: 0, fpsDraft = raw)
            assertNotNull(carPlayEditError(settings))
            assertEquals(raw, settings.fpsDraft)
        }
        for (fps in 1..60) {
            val settings = CarPlaySettings(fps = fps, fpsDraft = fps.toString())
            assertNull(carPlayEditError(settings))
            assertEquals(fps, settings.displayRequest().fps)
        }
    }

    @Test fun sizingUsesContinuousReversedSliderAndDisablesItUnderAuto() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        val display = File(root, "app/src/main/java/com/openautolink/app/ui/settings/CarPlaySettings.kt").readText()
            .substringAfter("fun CarPlayDisplaySettingsSection(").substringBefore("fun CarPlayVideoSettingsSection(")
        assertTrue(display.contains("Slider("))
        assertTrue(display.contains("value = (500 - sliderWidthMm).toFloat()"))
        assertTrue(display.contains("steps = 0"))
        assertTrue(display.contains("enabled = !saving && !settings.autoScale"))
        assertTrue(display.contains("onValueChangeFinished"))
        assertTrue(display.contains("it.copy(widthPhysicalMm = sliderWidthMm)"))
        assertTrue(display.contains("Text(\"Smaller UI\""))
        assertTrue(display.contains("Text(\"Larger UI\""))
        assertFalse(display.contains("DropdownMenu"))
        for (width in 100..400) {
            val settings = CarPlaySettings(widthPhysicalMm = width)
            assertNull(carPlayEditError(settings))
            assertEquals(width, CarPlaySettings.decode(CarPlaySettings.encode(settings)).widthPhysicalMm)
        }
    }

    @Test fun videoUiUsesPresetDropdownIntegerDraftAndNoExperimentalOrConstantFpsPromise() {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        val ui = File(root, "app/src/main/java/com/openautolink/app/ui/settings/CarPlaySettings.kt").readText()
            .substringAfter("fun CarPlayVideoSettingsSection(").substringBefore("private fun CarPlayField")
        assertTrue(ui.contains("carPlayResolutionMenu"))
        assertTrue(ui.contains("DropdownMenu(expanded = resolutionMenu"))
        assertTrue(ui.contains("Maximum FPS"))
        assertTrue(ui.contains("carPlayCustomWidth"))
        assertTrue(ui.contains("carPlayCustomHeight"))
        assertTrue(ui.contains("carPlayCustomFps"))
        assertFalse(ui.contains("experimental"))
        assertFalse(ui.contains("FilterChip"))
    }
}

package com.openautolink.app.ui.projection

import com.openautolink.app.data.CarPlaySettings
import com.openautolink.carplaylab.media.CarPlayAutoScalePolicy
import org.junit.Assert.*
import org.junit.Test

/** Synthetic measured-window geometry, not claimed OEM/Compose render evidence. */
class CarPlayCalibrationGeometryTest {
    private val bars = ProjectionChrome(left = 8, top = 72, right = 16, bottom = 108)
    private val cutout = ProjectionChrome(left = 20, top = 24, right = 40, bottom = 12)
    private val probe = com.shilapi.xcertplay.media.VideoDecoderProbe { "qualified" }

    @Test fun fullscreenUsesEntireOwningAppRectangleDespiteSettingsRailAndHeaderOffsets() {
        val padding = ProjectionChrome.forMode("fullscreen_immersive", bars, cutout)
        assertEquals(ProjectionChrome(), padding)
        // The app window can itself have an OEM origin; calibration never means full panel.
        val appLeft = 180
        val appTop = 70
        val width = 3000
        val height = 1200
        val safe = CarPlaySettings(resolution = "1440p", safeTop = 1000, safeBottom = 2000,
            safeLeft = 500, safeRight = 1500)
        val display = safe.qualifiedDisplay(CarPlayAutoScalePolicy.Input(width,height,width,height,200.0,200.0,200), probe)
        val fit = com.openautolink.carplaylab.media.CarPlayDisplayPolicy.fitRect(display,width,height)!!
        assertEquals(width.toDouble() / height, display.width.toDouble() / display.height, .005)
        val offered = display.encodedSafeInsets!!
        assertEquals(appTop + height * .1, (appTop + fit.top + offered.top * fit.height / display.height).toDouble(), 2.0)
        assertEquals(appLeft + width * .05, (appLeft + fit.left + offered.left * fit.width / display.width).toDouble(), 2.0)
        // Reusing the settings area (rail/header/footer) produces a different physical boundary.
        assertNotEquals(appTop + height * .1, appTop + 72 + (height - 144) * .1, 1.0)
        assertNotEquals(appLeft + width * .05, appLeft + 64 + (width - 64) * .05, 1.0)
    }

    @Test fun systemUiVisibleExcludesMaxChromeOnceAndOffersItsOwnParentRatio() {
        val chrome = ProjectionChrome.forMode("system_ui_visible", bars, cutout)
        assertEquals(ProjectionChrome(20,72,40,108), chrome)
        val width = 3000 - chrome.left - chrome.right
        val height = 1200 - chrome.top - chrome.bottom
        val settings = CarPlaySettings(resolution = "1440p", safeTop = 1000, safeRight = 1200)
        val full = settings.qualifiedDisplay(CarPlayAutoScalePolicy.Input(3000,1200,3000,1200,200.0,200.0,200), probe)
        val visible = settings.qualifiedDisplay(CarPlayAutoScalePolicy.Input(width,height,3000,1200,200.0,200.0,200), probe)
        assertEquals(width.toDouble()/height, visible.width.toDouble()/visible.height, .005)
        assertNotEquals(full.width.toDouble()/full.height, visible.width.toDouble()/visible.height, .05)
        assertNotEquals(16.0/9, visible.width.toDouble()/visible.height, .05)
        val fit = com.openautolink.carplaylab.media.CarPlayDisplayPolicy.fitRect(visible,width,height)!!
        val offered = visible.encodedSafeInsets!!
        assertEquals(chrome.top + height * .1, (chrome.top + fit.top + offered.top * fit.height / visible.height).toDouble(), 2.0)
        assertEquals(chrome.left + width * .88, (chrome.left + fit.left + fit.width - offered.right * fit.width / visible.width).toDouble(), 2.0)
        // Editor receives this already padded parent and no second status/nav bar subtraction.
        assertEquals(1020, height)
        assertNotEquals(height, height - chrome.top - chrome.bottom)
    }
}

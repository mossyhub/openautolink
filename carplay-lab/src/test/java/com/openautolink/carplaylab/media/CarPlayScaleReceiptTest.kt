package com.openautolink.carplaylab.media

import com.openautolink.carplaylab.media.CarPlayScaleReceipt.Mode.AUTOMATIC
import com.openautolink.carplaylab.media.CarPlayScaleReceipt.Mode.MANUAL as MANUAL_MODE
import com.openautolink.carplaylab.media.CarPlayScaleReceipt.Reason.*
import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test

class CarPlayScaleReceiptTest {
    private val good = CarPlayAutoScalePolicy.Input(960, 540, 1920, 1080, 240.0, 240.0, 160)
    private fun receipt(input: CarPlayAutoScalePolicy.Input?, mode: CarPlayScaleReceipt.Mode = AUTOMATIC,
        w: Int = 1920, h: Int = 1080, reference: Int = 200) =
        CarPlayAutoScalePolicy.resolveReceipt(w, h, reference, mode, input)

    @Test fun reasonsAreOrderedWithoutChangingTheLegacyAcceptancePredicate() {
        val cases = listOf(
            null to MISSING_VIEWPORT,
            good.copy(viewportWidthPx = 0, xdpi = Double.NaN) to INVALID_GEOMETRY,
            good.copy(xdpi = Double.NaN, ydpi = 0.0) to NONFINITE_METRICS,
            good.copy(xdpi = 0.0) to METRIC_RANGE,
            good.copy(xdpi = 160.0, ydpi = 160.0) to BASELINE_ALIAS,
            good.copy(densityDpi = 240) to LOGICAL_ALIAS,
            good.copy(xdpi = 240.0, ydpi = 480.0) to AXIS_DISAGREEMENT,
            good.copy(viewportWidthPx = 20, viewportHeightPx = 20, displayWidthPx = 20, displayHeightPx = 20) to PHYSICAL_BOUNDS,
            good to HARDWARE_ESTIMATE)
        for ((input, reason) in cases) {
            val decision = receipt(input)
            assertEquals("input=$input", reason, decision.reason)
            val old = CarPlayAutoScalePolicy.resolve(1920, 1080, 200, input)
            assertEquals(old.widthMm, decision.effectiveWidthMm)
            assertEquals(old.heightMm, decision.effectiveHeightMm)
            if (input != null) assertEquals(old, decision.physicalSize)
            assertEquals(MANUAL, receipt(input, MANUAL_MODE).reason)
        }
        assertEquals(960.0, receipt(good).fittedWidthPx, 0.0)
        assertEquals(540.0, receipt(good).fittedHeightPx, 0.0)
        assertEquals(160, receipt(good).logicalDensityDpi)
        assertEquals(240.0, receipt(good).xdpi!!, 0.0)
        assertNull(receipt(null).xdpi)
        assertNull(receipt(null).logicalDensityDpi)
        assertEquals(MANUAL, receipt(null, MANUAL_MODE).reason)
    }

    @Test fun exactDecoderSelectionFreezesScaleOnlyAfterItsFinalFallbackCanvas() {
        val selected = CarPlayDisplayPolicy.select(CarPlayDisplayPreferences(
            resolution = CarPlayResolution.QHD1440, autoScaleInput = good), AvcDecoderProbe {
                if (it.width == 1280 && it.height == 720) "baseline" else null
            }).display
        assertNotNull("Exact final decoder canvas must freeze scale", selected.resolvedScale)
        assertEquals(102, selected.resolvedScale!!.effectiveWidthMm)
    }

    @Test fun frozenReceiptSurvivesOnlyAnExactlyMatchingScaleContext() {
        val requested = CarPlayDisplayPreferences(autoScaleInput = good)
        assertNull(requested.resolvedScale)
        val frozen = requested.freezeScale()
        val decision = frozen.resolvedScale!!
        assertSame(decision, frozen.scaleReceipt)
        assertSame(decision, frozen.copy(fps = 59, preferHevc = true).resolvedScale)
        for (changed in listOf(frozen.copy(resolution = CarPlayResolution.FULL_HD1080),
            frozen.copy(widthPhysicalMm = 225), frozen.copy(scaleMode = MANUAL_MODE),
            frozen.copy(autoScaleInput = null), frozen.copy(autoScaleInput = good.copy(ydpi = 241.0)))) {
            assertNull(changed.resolvedScale)
            assertNotSame(decision, changed.scaleReceipt)
            assertNotSame(decision, changed.freezeScale().resolvedScale)
        }
        assertEquals(AUTOMATIC, requested.scaleMode)
        assertEquals(MANUAL_MODE, CarPlayDisplayPreferences().scaleMode)
        assertEquals(AUTOMATIC, frozen.copy(autoScaleInput = null).scaleMode)
    }

    @Test fun binaryPlistVectorsPreserveScaleWithoutChangingCodedHidViewSafeCodecOrFps() {
        data class Vector(val w: Int, val h: Int, val ref: Int, val mode: CarPlayScaleReceipt.Mode,
            val input: CarPlayAutoScalePolicy.Input?, val mmW: Int, val mmH: Int)
        // Captured vendor metrics are a fixture, not independent evidence of panel size.
        val captured = CarPlayAutoScalePolicy.Input(2914,1134,2914,1134,200.042,200.025,200)
        val vectors = listOf(
            Vector(1920,1080,225,MANUAL_MODE,null,225,127),
            Vector(1280,720,200,AUTOMATIC,good,102,57),
            Vector(2560,1440,200,AUTOMATIC,good,102,57),
            Vector(1920,1080,200,AUTOMATIC,good.copy(xdpi=160.0,ydpi=160.0),100,56),
            Vector(1920,720,200,AUTOMATIC,good.copy(xdpi=160.0,ydpi=160.0),100,38),
            Vector(1080,1920,200,AUTOMATIC,good.copy(viewportWidthPx=540,viewportHeightPx=960,
                displayWidthPx=1080,displayHeightPx=1920),57,102),
            Vector(3076,1196,400,AUTOMATIC,captured,400,156),
            Vector(3076,1196,200,AUTOMATIC,null,200,78))
        for (v in vectors) {
            val selected = CarPlayDisplayPreferences.custom(v.w,v.h,59).copy(widthPhysicalMm=v.ref,
                autoScaleInput=v.input,scaleMode=v.mode).freezeScale()
            val baseline = CarPlayDisplayPreferences.custom(v.w,v.h,59)
            fun info(p: CarPlayDisplayPreferences): Map<*,*> = BplistCodec.decode(BplistCodec.encode(
                AirPlayInfoPlist.build(benchReceiverConfig("00:11:22:33:44:55",41234,display=p),emptySet()))) as Map<*,*>
            val actual = info(selected)
            val original = info(baseline)
            val display = (actual["displays"] as List<*>).single() as Map<*,*>
            val old = (original["displays"] as List<*>).single() as Map<*,*>
            assertEquals(v.mmW, (display["widthPhysical"] as Number).toInt())
            assertEquals(v.mmH, (display["heightPhysical"] as Number).toInt())
            assertEquals(old.filterKeys { it !in setOf("widthPhysical","heightPhysical") },
                display.filterKeys { it !in setOf("widthPhysical","heightPhysical") })
            val hid = (actual["hidDevices"] as List<*>).first() as Map<*,*>
            val oldHid = (original["hidDevices"] as List<*>).first() as Map<*,*>
            assertArrayEquals(oldHid["hidDescriptor"] as ByteArray,hid["hidDescriptor"] as ByteArray)
            assertEquals(59,selected.fps)
            assertEquals("H264",selected.codec)
            assertEquals(v.mmW,selected.resolvedScale!!.effectiveWidthMm)
            assertTrue(CarPlayDisplayPolicy.scaleDiagnostic(selected).contains("physical=${v.mmW}x${v.mmH}"))
        }
        assertEquals(LOGICAL_ALIAS,receipt(captured,w=3076,h=1196,reference=400).reason)
    }
}

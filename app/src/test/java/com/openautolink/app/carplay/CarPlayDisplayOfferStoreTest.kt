package com.openautolink.app.carplay

import com.openautolink.carplaylab.media.*
import org.junit.Assert.*
import org.junit.Test

class CarPlayDisplayOfferStoreTest {
    private fun actual(width: Int = 200) = CarPlayDisplayPreferences(widthPhysicalMm = width,
        scaleMode = CarPlayScaleReceipt.Mode.AUTOMATIC).freezeScale()

    @Test fun staleOwnerCannotPublishOrRetireSuccessorAndEndedOfferIsRetained() {
        val store = CarPlayDisplayOfferStore()
        assertNull(store.state.value)
        val a = store.begin()
        assertTrue(store.publish(a, actual()))
        val b = store.begin()
        assertEquals(CarPlayDisplayOfferStore.Phase.ENDED, store.state.value!!.phase)
        assertFalse(store.publish(a, actual(100)))
        assertTrue(store.publish(b, actual(225)))
        val offer = store.state.value
        store.retire(a)
        assertSame(offer, store.state.value)
        assertFalse(store.publish(b, actual(400)))
        store.retire(b)
        assertEquals(CarPlayDisplayOfferStore.Phase.ENDED, store.state.value!!.phase)
        assertEquals(225, store.state.value!!.referenceWidthMm)
        assertFalse(store.publish(b, actual(100)))
    }

    @Test fun nonfiniteCoreMetricsAreNormalizedAtObservationAndExportBoundaries() {
        val display = CarPlayDisplayPreferences(autoScaleInput = CarPlayAutoScalePolicy.Input(
            1920, 750, 1920, 750, Double.NaN, Double.POSITIVE_INFINITY, Int.MAX_VALUE)).freezeScale()
        assertTrue(display.scaleReceipt.xdpi!!.isNaN()) // Core retains original evidence privately.
        val store = CarPlayDisplayOfferStore()
        assertTrue(store.publish(store.begin(), display))
        val offer = store.state.value!!
        assertEquals(CarPlayScaleReceipt.Reason.NONFINITE_METRICS, offer.reason)
        assertNull(offer.xdpi); assertNull(offer.ydpi); assertNull(offer.logicalDensityDpi)
        val message = CarPlayDisplayPolicy.scaleDiagnosticV2(display)
        assertFalse(message.contains("NaN")); assertFalse(message.contains("Infinity"))
        assertNotNull(CarPlayAudioDiagnostics.sanitize(1, message))
        for (invalid in listOf(message + " displayId=private", message.replace("xdpi=0.0", "xdpi=NaN"),
            message.replace("xdpi=0.0", "xdpi=Infinity"), message.replace("logical=0", "logical=99999999999999999999"))) {
            assertNull(CarPlayAudioDiagnostics.sanitize(1, invalid))
        }
    }

    @Test fun validSubpixelFitStillPassesFiniteExportSchema() {
        val display = CarPlayDisplayPreferences(resolution = CarPlayResolution.CUSTOM, customWidth = 65534, customHeight = 2,
            autoScaleInput = CarPlayAutoScalePolicy.Input(1, 1, 1, 1, 160.0, 160.0, 160)).freezeScale()
        val message = CarPlayDisplayPolicy.scaleDiagnosticV2(display)
        assertNotNull("bounded finite fitted values must survive export: $message", CarPlayAudioDiagnostics.sanitize(1, message))
    }

    @Test fun exactScaleOfferSurvivesOrdinaryStatusFloodButRemainsOncePerReporter() {
        val lines = mutableListOf<String>()
        val reporter = CarPlayAudioDiagnosticReporter(1, { 0L }, lines::add)
        repeat(100) { reporter.record("media stage=record-active; rendering unverified") }
        val message = CarPlayDisplayPolicy.scaleDiagnosticV2(actual())
        reporter.record(message); reporter.record(message)
        assertEquals(1, lines.count { it.contains("displayScaleV2 ") })
    }
}

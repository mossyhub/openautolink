package com.openautolink.app.carplay

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Pure ownership/validation tests plus source wiring guards, not rendered device proof. */
class CarPlaySurfaceReceiptTest {
    @Test fun destroyedOrReplacedOwnerCannotPublishAStaleReceipt() {
        val gate = CarPlaySurfaceReceiptGate()
        val oldView = Any()
        val newView = Any()
        val oldGeneration = gate.created(oldView)
        assertTrue(gate.isCurrent(oldView, oldGeneration))
        val newGeneration = gate.created(newView)
        assertFalse(gate.isCurrent(oldView, oldGeneration))
        assertFalse(gate.destroyed(oldView, oldGeneration))
        assertTrue(gate.isCurrent(newView, newGeneration))
        assertTrue(gate.destroyed(newView, newGeneration))
        assertFalse(gate.isCurrent(newView, newGeneration))
    }

    @Test fun recreatedSameOwnerRejectsThePreviousGeneration() {
        val gate = CarPlaySurfaceReceiptGate()
        val owner = Any()
        val old = gate.created(owner)
        val replacement = gate.created(owner)
        assertFalse(gate.destroyed(owner, old))
        assertFalse(gate.isCurrent(owner, old))
        assertTrue(gate.isCurrent(owner, replacement))
    }

    @Test fun viewportValidationIsBoundedAndRejectsUnmeasuredOrOversizedViews() {
        val gate = CarPlaySurfaceReceiptGate()
        assertTrue(gate.validViewport(2560, 1440))
        assertTrue(gate.validViewport(16384, 16384))
        assertFalse(gate.validViewport(0, 1440))
        assertFalse(gate.validViewport(2560, -1))
        assertFalse(gate.validViewport(16385, 1440))
        assertFalse(gate.validViewport(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun surfaceChangedPublishesMeasuredParentAndOwningDisplayBeforeMakingSurfaceAvailable() {
        val source = File("src/main/java/com/openautolink/app/carplay/CarPlayProjectionLayer.kt").readText()
        val changed = source.substringAfter("override fun surfaceChanged(").substringBefore("override fun surfaceDestroyed(")
        assertTrue("Missing production parent receipt", changed.contains("publishParent(this@apply)"))
        val publisher = source.substringAfter("fun publishParent(").substringBefore("    Box(Modifier")
        assertTrue(publisher.contains("parentSize.width, parentSize.height, view.display, receipt.second"))
        assertFalse(changed.contains("this@apply.width"))
        assertTrue(changed.contains("receiptGate.isCurrent(this@apply, generation)"))
        assertTrue(changed.indexOf("publishParent(this@apply)") < changed.indexOf("viewModel.onCarPlaySurfaceAvailable("))
        assertFalse(changed.contains("setRenderRect"))
        assertFalse(changed.contains("reconnect"))
    }
}

package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayOverlayPolicyTest {
    @Test fun settingsAndDiagnosticsBlockTouchWithoutChangingSurfaceOwner() {
        fun forward(settings: Boolean, diagnostics: Boolean) = CarPlayOverlayPolicy.forwardTouch(settings, diagnostics)
        assertTrue(forward(false, false))
        assertFalse(forward(true, false))
        assertFalse(forward(false, true))
        assertFalse(forward(true, true))
        // Closing overlays restores input; no reconnect/Surface API is involved.
        assertTrue(forward(false, false))
    }
}

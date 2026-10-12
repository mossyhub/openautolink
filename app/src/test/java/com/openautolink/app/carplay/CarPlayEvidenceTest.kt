package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayEvidenceTest {
    @Test fun recordAndAuthenticationAreNotRenderedVideo() {
        var state = CarPlayEvidence()
        state = state.accept("media stage=record-active; rendering unverified")
        state = state.accept("media stage=runtime-authentication-accepted")
        assertTrue(state.runtimeAuthenticated)
        assertFalse(state.videoRendered)
        assertEquals(0, state.touchSent)
    }
    @Test fun onlyRealSinkRenderAndSuccessfulTouchMarkersPromoteEvidence() {
        var state = CarPlayEvidence()
        state = state.accept("media stage=video-first-frame-confirmed count=1")
        state = state.accept("media stage=touch-sent count=1")
        assertTrue(state.videoRendered)
        assertEquals(1, state.touchSent)
        state = state.accept("media stage=session-ended")
        assertFalse(state.videoRendered)
        assertFalse(state.runtimeAuthenticated)
    }
}

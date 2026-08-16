package com.openautolink.app.transport.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvertiserLivenessTest {

    @Test
    fun staleRunFlagIsNotLiveAfterAcceptLoopExits() {
        assertFalse(
            AdvertiserLiveness.isLive(
                startRequested = true,
                acceptLoopActive = false,
            )
        )
    }

    @Test
    fun activeAcceptLoopIsLiveAfterStart() {
        assertTrue(
            AdvertiserLiveness.isLive(
                startRequested = true,
                acceptLoopActive = true,
            )
        )
    }

    @Test
    fun stoppedAdvertiserIsNotLiveEvenIfJobHasNotExitedYet() {
        assertFalse(
            AdvertiserLiveness.isLive(
                startRequested = false,
                acceptLoopActive = true,
            )
        )
    }

    @Test
    fun deadSocketExitRequestsRecovery() {
        assertTrue(
            AdvertiserLiveness.shouldRecoverAfterExit(
                startRequested = true,
                socketDied = true,
            )
        )
    }

    @Test
    fun intentionalStopNeverRequestsRecovery() {
        assertFalse(
            AdvertiserLiveness.shouldRecoverAfterExit(
                startRequested = false,
                socketDied = true,
            )
        )
    }

    @Test
    fun ordinaryLoopCompletionDoesNotRequestRecovery() {
        assertFalse(
            AdvertiserLiveness.shouldRecoverAfterExit(
                startRequested = true,
                socketDied = false,
            )
        )
    }
}

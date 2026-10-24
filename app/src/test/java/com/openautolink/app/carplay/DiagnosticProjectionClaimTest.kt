package com.openautolink.app.carplay

import com.shilapi.xcertplay.media.MicrophoneCaptureFence
import org.junit.Assert.*
import org.junit.Test

class DiagnosticProjectionClaimTest {
    @Test fun diagnosticStopRetiresAdvertiserWithoutInventedCarPlayOwnership() = kotlinx.coroutines.runBlocking {
        MicrophoneCaptureFence.pauseProjection()
        try {
            com.openautolink.app.transport.bluetooth.AaWirelessBtControl.retireForMicrophoneDiagnostic()
        } finally { MicrophoneCaptureFence.resumeProjection() }
    }
    @Test fun explicitDiagnosticPauseBlocksProjectionButNotDiagnosticsAndReturnWaitsForRetirement() {
        val pause = MicrophoneCaptureFence.javaClass.methods.singleOrNull { it.name == "pauseProjection" }
        assertNotNull("diagnostic panel needs atomic projection pause", pause)
        pause!!.invoke(MicrophoneCaptureFence)
        var lease: MicrophoneCaptureFence.Lease? = null
        try {
            lease = MicrophoneCaptureFence.tryDiagnostic()!!
            assertNull(MicrophoneCaptureFence.tryProjectionClaim())
            val resume = MicrophoneCaptureFence.javaClass.methods.single { it.name == "resumeProjection" }
            assertEquals(false, resume.invoke(MicrophoneCaptureFence))
            lease.release()
            assertEquals(true, resume.invoke(MicrophoneCaptureFence))
            MicrophoneCaptureFence.tryProjectionClaim()!!.release()
        } finally {
            lease?.release()
            MicrophoneCaptureFence.javaClass.methods.single { it.name == "resumeProjection" }.invoke(MicrophoneCaptureFence)
        }
    }

    @Test fun selectedStoppedCarPlayRetainsCaptureClaimUntilExactReturn() {
        val ownership = CarPlayOwnership()
        val token = ownership.reserve(true)!!
        var diagnostic: MicrophoneCaptureFence.Lease? = null
        try {
            diagnostic = MicrophoneCaptureFence.tryDiagnostic()
            assertNull("selected CP is a real pending capture claim", diagnostic)
            ownership.acknowledgeAaRetired(token)
            ownership.acknowledgeCarPlayRetired(token)
            assertNull(MicrophoneCaptureFence.tryDiagnostic())
            assertTrue(ownership.release(token))
            diagnostic = MicrophoneCaptureFence.tryDiagnostic()
            assertNotNull(diagnostic)
            assertNull("CP must lose before any retirement/bootstrap", ownership.reserve(true))
        } finally {
            diagnostic?.release()
            ownership.acknowledgeAaRetired(token); ownership.acknowledgeCarPlayRetired(token); ownership.release(token)
        }
    }
}

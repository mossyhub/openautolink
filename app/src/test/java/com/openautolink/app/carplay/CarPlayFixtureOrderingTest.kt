package com.openautolink.app.carplay

import com.shilapi.xcertplay.media.MicrophoneCaptureFence
import org.junit.Assert.*
import org.junit.Test

/** Keep the real process fence: execute the historical predecessor and successor in one JVM. */
class CarPlayFixtureOrderingTest {
    @Test fun failedRuntimeCloseFixtureRetiresExactOwnerBeforeDiagnosticSuccessor() {
        CarPlayFailurePresentationTest().explicitDismissalClearsFailureDuringFailedRetirement()
        DiagnosticProjectionClaimTest().explicitDiagnosticPauseBlocksProjectionButNotDiagnosticsAndReturnWaitsForRetirement()
        assertDiagnosticAdmission()
    }

    @Test fun failedAaRetirementFixtureRetriesRealRetirementBeforeDiagnosticSuccessor() {
        CarPlayCoordinatorTest().failedAaRetirementNeverBootstrapsOrReleasesAdmission()
        DiagnosticProjectionClaimTest().explicitDiagnosticPauseBlocksProjectionButNotDiagnosticsAndReturnWaitsForRetirement()
        assertDiagnosticAdmission()
    }

    @Test fun retirementFixtureReleasesCaptureBeforeDiagnosticSuccessor() {
        CarPlayOwnershipTest().retirementMustCompleteBeforeBootstrapAndBlocksSuccessorAA()
        DiagnosticProjectionClaimTest().explicitDiagnosticPauseBlocksProjectionButNotDiagnosticsAndReturnWaitsForRetirement()
        assertDiagnosticAdmission()
    }

    @Test fun staleAcknowledgmentFixtureReleasesSuccessorBeforeDiagnosticSuccessor() {
        CarPlayOwnershipTest().staleAcknowledgmentsCannotReleaseSuccessor()
        DiagnosticProjectionClaimTest().explicitDiagnosticPauseBlocksProjectionButNotDiagnosticsAndReturnWaitsForRetirement()
        assertDiagnosticAdmission()
    }

    @Test fun failedDiagnosticAdmissionStillResumesProjection() {
        val claim = MicrophoneCaptureFence.tryProjectionClaim()!!
        try {
            assertThrows(NullPointerException::class.java) {
                DiagnosticProjectionClaimTest().explicitDiagnosticPauseBlocksProjectionButNotDiagnosticsAndReturnWaitsForRetirement()
            }
            assertFalse("failed fixture must not leave the process paused", MicrophoneCaptureFence.projectionPaused())
        } finally { claim.release() }
        DiagnosticProjectionClaimTest().explicitDiagnosticPauseBlocksProjectionButNotDiagnosticsAndReturnWaitsForRetirement()
        assertDiagnosticAdmission()
    }

    private fun assertDiagnosticAdmission() {
        val lease = MicrophoneCaptureFence.tryDiagnostic()
        try { assertNotNull("no predecessor normal/diagnostic lease may remain", lease) }
        finally { lease?.release() }
    }
}

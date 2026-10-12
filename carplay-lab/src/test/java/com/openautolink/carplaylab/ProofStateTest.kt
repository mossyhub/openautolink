package com.openautolink.carplaylab
import org.junit.Assert.*
import org.junit.Test
class ProofStateTest {
    @Test fun localLoadDoesNotProvePhoneTrust() {
        val state = ProofState()
        assertFalse(state.identified)
        assertFalse(state.authenticated)
    }
    @Test fun identificationIsNotAuthentication() {
        val state = ProofState()
        state.identificationAccepted()
        assertTrue(state.identified)
        assertFalse(state.authenticated)
    }
    @Test fun phoneAuthenticationAfterIdentificationIsProof() {
        val state = ProofState()
        state.identificationAccepted()
        state.authenticationAccepted()
        assertTrue(state.authenticated)
    }
    @Test(expected = IllegalStateException::class) fun canceledAttemptCannotReportPhoneAuthentication() {
        val state = ProofState()
        state.identificationAccepted()
        state.cancel()
        state.authenticationAccepted()
    }
    @Test(expected = IllegalStateException::class) fun canceledAttemptCannotBeginIdentification() {
        val state = ProofState()
        state.cancel()
        state.identificationAccepted()
    }
    @Test(expected = IllegalStateException::class) fun authenticationCannotSkipIdentification() {
        ProofState().authenticationAccepted()
    }
}

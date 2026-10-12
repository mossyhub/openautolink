package com.openautolink.app.ui.projection

import org.junit.Assert.*
import org.junit.Test

class ProjectionLaunchPolicyTest {
    private fun request(policy: ProjectionLaunchPolicy, enabled: Boolean, ready: Boolean = true,
        off: Boolean = false): List<String> {
        val events = mutableListOf<String>()
        policy.request(enabled, ready, off, { events += "aa" }, { events += "cp" }, { events += "setup" })
        return events
    }
    @Test fun configuredEnabledLaunchStartsCarPlayInsteadOfAndroidAuto() {
        assertEquals(listOf("cp"), request(ProjectionLaunchPolicy(), true))
    }
    @Test fun disabledLaunchPreservesAndroidAuto() {
        assertEquals(listOf("aa"), request(ProjectionLaunchPolicy(), false))
    }
    @Test fun recompositionDoesNotStartAnotherAttempt() {
        val policy = ProjectionLaunchPolicy()
        request(policy, true)
        assertEquals(emptyList<String>(), request(policy, true))
    }
    @Test fun incompleteSetupShowsGuidanceWithoutEitherProtocolStart() {
        assertEquals(listOf("setup"), request(ProjectionLaunchPolicy(), true, false))
    }
    @Test fun incompleteSetupDoesNotAutomaticallyRetryWhenItBecomesReady() {
        val policy = ProjectionLaunchPolicy()
        request(policy, true, false)
        assertEquals(emptyList<String>(), request(policy, true, true))
    }
    @Test fun explicitSaveSuppressesAnAutomaticLaunchStillWaitingForPreferences() {
        val policy = ProjectionLaunchPolicy()
        policy.suppress()
        assertEquals(emptyList<String>(), request(policy, true))
    }
    @Test fun ignitionOffDoesNotBootstrap() {
        assertEquals(listOf("setup"), request(ProjectionLaunchPolicy(), true, true, true))
    }
}

package com.openautolink.app.transport

import org.junit.Assert.*
import org.junit.Test

class ProjectionConnectionPolicyTest {
    @Test fun failedRetirementKeepsOffClosedUntilExactRevisionAcknowledges() {
        val policy = ProjectionConnectionPolicy()
        assertFalse(policy.wirelessAllowed)
        policy.observe(false)
        val oldTicket = policy.wirelessTicket()
        assertNotNull(oldTicket)
        policy.observe(true)
        val on = policy.state.value
        assertFalse(policy.admits(oldTicket))
        policy.observe(false)
        assertFalse("OFF cannot admit a successor before previous wireless owners retire", policy.wirelessAllowed)
        fun retired(revision: Long) { policy.javaClass.getMethod("retired", Long::class.javaPrimitiveType).invoke(policy, revision) }
        retired(on.revision)
        assertFalse("Stale acknowledgement cannot clear current transition", policy.wirelessAllowed)
        retired(policy.state.value.revision)
        assertTrue(policy.wirelessAllowed)
        assertFalse(policy.admits(oldTicket))
        assertTrue(policy.permits("usb"))
    }
}

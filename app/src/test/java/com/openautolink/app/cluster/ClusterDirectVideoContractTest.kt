package com.openautolink.app.cluster

import org.junit.Assert.*
import org.junit.Test

class ClusterDirectVideoContractTest {
    @Test fun productionActivityExistsForExplicitPilotWithoutReplacingAndroidX() {
        val activity = runCatching { Class.forName("com.openautolink.app.cluster.ClusterDirectVideoActivity") }.getOrNull()
        assertNotNull("Released566 has no app-owned projection window lifecycle", activity)
        assertTrue(android.app.Activity::class.java.isAssignableFrom(activity!!))
    }
}

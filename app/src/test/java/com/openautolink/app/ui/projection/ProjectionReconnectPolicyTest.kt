package com.openautolink.app.ui.projection

import org.junit.Assert.*
import org.junit.Test

class ProjectionReconnectPolicyTest {
    private fun dispatched(enabled: Boolean, selected: Boolean): List<String> {
        val events = mutableListOf<String>()
        dispatchSavedProjectionReconnect(enabled, selected,
            { events += "connectCarPlay" }, { events += "reconnectCarPlay" },
            { events += "reconnectAndroidAuto" }, { events += "returnToAndroidAuto" })
        return events
    }

    @Test fun savedEnabledUnselectedCarPlayReservesTheRealCoordinatorOwner() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val owner = com.openautolink.app.carplay.CarPlayOwnership()
        val state = java.util.concurrent.atomic.AtomicReference(com.openautolink.app.carplay.CarPlayUiState())
        val coordinator = com.openautolink.app.carplay.CarPlayCoordinator(owner, {}, state::set)
        val factory: ((String) -> Unit) -> com.openautolink.app.carplay.CarPlayRuntime = {
            object : com.openautolink.app.carplay.CarPlayRuntime {
                override fun run() { started.countDown(); check(release.await(3, java.util.concurrent.TimeUnit.SECONDS)) }
                override fun cancel() { release.countDown() }
                override fun close() { release.countDown() }
            }
        }
        try {
            dispatchSavedProjectionReconnect(true, state.get().selected,
                { coordinator.connect(true, factory) }, { coordinator.reconnect(true, factory) },
                { fail("Save started Android Auto") }, { fail("Save returned to Android Auto") })
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(state.get().selected)
            assertTrue(owner.blocksAndroidAuto)
        } finally { release.countDown(); coordinator.close() }
    }

    @Test fun enabledButUnselectedCarPlayStartsCarPlayNeverAndroidAuto() {
        assertEquals(listOf("connectCarPlay"), dispatched(true, false))
    }

    @Test fun enabledSelectedCarPlayReconnects() {
        assertEquals(listOf("reconnectCarPlay"), dispatched(true, true))
    }

    @Test fun disabledSelectedCarPlayReleasesOwnershipBeforeAndroidAutoReconnect() {
        assertEquals(listOf("returnToAndroidAuto"), dispatched(false, true))
    }

    @Test fun disabledUnselectedCarPlayPreservesNormalAndroidAutoReconnect() {
        assertEquals(listOf("reconnectAndroidAuto"), dispatched(false, false))
    }
}

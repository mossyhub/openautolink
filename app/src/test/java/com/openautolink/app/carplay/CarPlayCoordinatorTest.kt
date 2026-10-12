package com.openautolink.app.carplay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CarPlayCoordinatorTest {
    @Test fun receiverDoesNotStartUntilAaRetirementReturns() {
        val retiring = CountDownLatch(1)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val owner = CarPlayOwnership()
        val coordinator = CarPlayCoordinator(owner, { retiring.countDown(); check(release.await(3, TimeUnit.SECONDS)) }) { }
        try {
            assertTrue(coordinator.connect(true) { object : CarPlayRuntime {
                override fun run() { started.countDown() }
                override fun cancel() {}
                override fun close() {}
            } })
            assertTrue(retiring.await(3, TimeUnit.SECONDS))
            assertTrue(owner.blocksAndroidAuto)
            assertFalse(started.await(50, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(started.await(3, TimeUnit.SECONDS))
        } finally { release.countDown(); coordinator.close() }
    }
    @Test fun stopDuringBlockedAaRetirementNeverBootstraps() {
        val retiring = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        var constructed = false
        val owner = CarPlayOwnership()
        val coordinator = CarPlayCoordinator(owner, { retiring.countDown(); check(release.await(3, TimeUnit.SECONDS)) }) { }
        assertTrue(coordinator.connect(true) { constructed = true; error("must not construct") })
        assertTrue(retiring.await(3, TimeUnit.SECONDS))
        coordinator.returnToAndroidAuto { returned.countDown() }
        release.countDown()
        assertTrue(returned.await(3, TimeUnit.SECONDS))
        assertFalse(constructed)
        assertFalse(owner.blocksAndroidAuto)
        coordinator.close()
    }
    @Test fun returnCancelsQueuedReconnectWhileOldRuntimeIsRetiring() {
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val owner = CarPlayOwnership()
        var starts = 0
        val coordinator = CarPlayCoordinator(owner, {}) { }
        coordinator.connect(true) { object : CarPlayRuntime {
            override fun run() { starts++; running.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            override fun cancel() {}
            override fun close() {}
        } }
        assertTrue(running.await(3, TimeUnit.SECONDS))
        coordinator.reconnect(true) { starts++; error("queued restart must be revoked") }
        coordinator.returnToAndroidAuto { returned.countDown() }
        release.countDown()
        assertTrue(returned.await(3, TimeUnit.SECONDS))
        assertEquals(1, starts)
        assertFalse(owner.blocksAndroidAuto)
        coordinator.close()
    }

    @Test fun failedAaRetirementNeverBootstrapsOrReleasesAdmission() {
        val ended = CountDownLatch(1)
        val owner = CarPlayOwnership()
        val coordinator = CarPlayCoordinator(owner, { error("native stop failed") }) { if (!it.busy && it.selected) ended.countDown() }
        coordinator.connect(true) { error("must not create runtime") }
        assertTrue(ended.await(3, TimeUnit.SECONDS))
        assertTrue(owner.blocksAndroidAuto)
        coordinator.close()
    }

    @Test fun disabledDoesNotInvokeRetirementOrRuntimeFactory() {
        val coordinator = CarPlayCoordinator(CarPlayOwnership(), { error("disabled retirement") }) { }
        assertFalse(coordinator.connect(false) { error("disabled bootstrap") })
        coordinator.close()
    }
}

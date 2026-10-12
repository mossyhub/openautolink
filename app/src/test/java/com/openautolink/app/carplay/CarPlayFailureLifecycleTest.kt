package com.openautolink.app.carplay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class CarPlayFailureLifecycleTest {
    @Test fun everyTypedKindKeepsItsGuidanceAfterStopAndClearsOnReturn() {
        for (kind in CarPlayFailureKind.entries) {
            val ended = CountDownLatch(1)
            val returned = CountDownLatch(1)
            val latest = AtomicReference(CarPlayUiState())
            val coordinator = CarPlayCoordinator(CarPlayOwnership(), {}) {
                latest.set(it); if (it.selected && !it.busy) ended.countDown()
            }
            try {
                val failure = CarPlayConnectionFailure(kind, true, Exception("secret"))
                assertTrue(coordinator.connect(true) { throw failure })
                assertTrue(ended.await(3, TimeUnit.SECONDS))
                assertEquals(CarPlayFailureUi(kind, true, failure.userGuidance), latest.get().failure)
                coordinator.stop()
                assertEquals(kind, latest.get().failure!!.kind)
                coordinator.returnToAndroidAuto { returned.countDown() }
                assertNull(latest.get().failure)
                assertTrue(returned.await(3, TimeUnit.SECONDS))
                assertFalse(latest.get().selected)
            } finally { coordinator.close() }
        }
    }

    @Test fun acceptedNewExplicitAttemptClearsFailureAndSuccessKeepsItClear() {
        val ended = CountDownLatch(1)
        val authenticated = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val latest = AtomicReference(CarPlayUiState())
        val coordinator = CarPlayCoordinator(CarPlayOwnership(), {}) {
            latest.set(it)
            if (it.selected && !it.busy) ended.countDown()
            if (it.evidence.runtimeAuthenticated) authenticated.countDown()
        }
        try {
            coordinator.connect(true) { throw CarPlayConnectionFailure(CarPlayFailureKind.RUNTIME_AUTHENTICATION, false) }
            assertTrue(ended.await(3, TimeUnit.SECONDS))
            assertNotNull(latest.get().failure)
            assertFalse(coordinator.connect(false) { error("disabled") })
            assertNotNull(latest.get().failure)
            assertTrue(coordinator.connect(true) { report -> object : CarPlayRuntime {
                override fun run() {
                    report("media stage=runtime-authentication-accepted")
                    check(finish.await(3, TimeUnit.SECONDS))
                }
                override fun cancel() { finish.countDown() }
                override fun close() {}
            } })
            assertNull(latest.get().failure)
            assertTrue(authenticated.await(3, TimeUnit.SECONDS))
            assertNull(latest.get().failure)
        } finally { finish.countDown(); coordinator.close() }
    }

    @Test fun diagnosticStatusCannotManufactureTypedFailure() {
        val ended = CountDownLatch(1)
        val latest = AtomicReference(CarPlayUiState())
        val coordinator = CarPlayCoordinator(CarPlayOwnership(), {}) {
            latest.set(it); if (it.selected && !it.busy) ended.countDown()
        }
        try {
            coordinator.connect(true) { report -> object : CarPlayRuntime {
                override fun run() { report("CarPlayConnectionFailure RUNTIME_STARTUP_TIMEOUT manualPeer=true") }
                override fun cancel() {}
                override fun close() {}
            } }
            assertTrue(ended.await(3, TimeUnit.SECONDS))
            assertNull(latest.get().failure)
        } finally { coordinator.close() }
    }
}

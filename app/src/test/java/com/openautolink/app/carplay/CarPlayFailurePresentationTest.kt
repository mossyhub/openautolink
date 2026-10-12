package com.openautolink.app.carplay

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class CarPlayFailurePresentationTest {
    @Test fun explicitDismissalClearsFailureDuringFailedRetirement() {
        val latest = AtomicReference(CarPlayUiState())
        val failed = CountDownLatch(1)
        val coordinator = CarPlayCoordinator(CarPlayOwnership(), {}) {
            latest.set(it)
            if (it.status.contains("retirement failed")) failed.countDown()
        }
        try {
            coordinator.connect(true) { object : CarPlayRuntime {
                override fun run() { throw CarPlayConnectionFailure(CarPlayFailureKind.LISTENER_BIND, true) }
                override fun cancel() {}
                override fun close() { error("retirement not yet acknowledged") }
            } }
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            assertNotNull(latest.get().failure)
            val dismiss = coordinator.javaClass.methods.firstOrNull { it.name == "dismissFailure" }
            assertNotNull("Explicit dismissal must clear the DTO", dismiss)
            dismiss!!.invoke(coordinator)
            assertNull(latest.get().failure)
        } finally { coordinator.close() }
    }

    @Test fun automaticTimeoutPresentationWarnsThatManualIpv4IsNotGuaranteed() {
        val ended = CountDownLatch(1)
        val latest = AtomicReference(CarPlayUiState())
        val coordinator = CarPlayCoordinator(CarPlayOwnership(), {}) {
            latest.set(it); if (it.selected && !it.busy) ended.countDown()
        }
        try {
            coordinator.connect(true) { throw CarPlayConnectionFailure(CarPlayFailureKind.RUNTIME_STARTUP_TIMEOUT, false) }
            assertTrue(ended.await(3, TimeUnit.SECONDS))
            val guidance = latest.get().failure!!.userGuidance
            assertTrue(guidance.contains("automatic endpoint setup is preferred"))
            assertTrue("Fallback must not promise inbound IPv4 works", guidance.contains("inbound IPv4 may be blocked"))
        } finally { coordinator.close() }
    }

    /** Source wiring guard, not rendered Compose/device proof. */
    @Test fun projectionPresentsTypedFailureOutsideSettingsAndStats() {
        val screen = File("src/main/java/com/openautolink/app/ui/projection/ProjectionScreen.kt").readText()
        assertTrue("Actionable guidance needs a persistent projection presentation", screen.contains("carPlayState.failure"))
        assertTrue(screen.contains("Text(failure.userGuidance)"))
        assertTrue(screen.contains("viewModel::dismissCarPlayFailure"))
        assertTrue(screen.contains("viewModel::returnToAndroidAuto"))
        assertFalse(screen.contains("failure.cause"))
    }
}

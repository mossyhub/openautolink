package com.openautolink.app.ui.testing

import android.os.Looper
import com.openautolink.app.carplay.CarPlayCallController
import com.openautolink.carplaylab.telecom.HfpCallGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ScheduledExecutorService

/**
 * Test-owned process teardown, while this controller's Android sandbox and mocks still exist.
 * Production close cancels, but does not join, its IO children or HFP census worker. Read only
 * those exact lifetime handles; never clear capture, account, lease or backend ownership fences.
 * Main must keep draining because local presentation retirement can acknowledge there.
 */
fun closeCarPlayCallControllerAndAwait(
    controller: CarPlayCallController,
    expectedLocalCleanupFailure: Boolean = false,
    timeoutMillis: Long = 5_000,
) {
    require(timeoutMillis > 0)
    val scope = ReflectionHelpers.getField<CoroutineScope>(controller, "scope")
    val job = requireNotNull(scope.coroutineContext[Job])
    val guard = ReflectionHelpers.getField<HfpCallGuard>(controller, "guard")
    val worker = ReflectionHelpers.getField<ScheduledExecutorService>(guard, "worker")
    controller.close()
    try {
        runBlocking {
            withTimeout(timeoutMillis) {
                while (!job.isCompleted || !worker.isTerminated || controller.state.value.localCleanupPending) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
                // Parent completion includes all children, including an already-running poller.
                job.join()
                // The repair callback publishes pending=false before its final receipt/future
                // completion. Its backend retires on Main; finish that callback as well.
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        throw AssertionError(
            "Controller teardown timed out: jobCompleted=${job.isCompleted}, " +
                "hfpTerminated=${worker.isTerminated}, state=${controller.state.value}", timeout,
        )
    }
    check(controller.state.value.localCleanupFailed == expectedLocalCleanupFailure) {
        "Unexpected local retirement result: expected failure=$expectedLocalCleanupFailure, state=${controller.state.value}"
    }
}

/** Never call get(): a mocked companion can return a mock or create a real owner while recording.
 * Forget only the exact successfully awaited controller, not an unacknowledged or replacement owner.
 */
fun closeCarPlayCallControllerTestProcessAndAwait() {
    val controller = ReflectionHelpers.getStaticField<CarPlayCallController?>(CarPlayCallController::class.java, "instance")
        ?: return
    closeCarPlayCallControllerAndAwait(controller)
    synchronized(CarPlayCallController.Companion) {
        check(ReflectionHelpers.getStaticField<CarPlayCallController?>(CarPlayCallController::class.java, "instance") === controller) {
            "Controller singleton changed during fixture teardown"
        }
        ReflectionHelpers.setStaticField(CarPlayCallController::class.java, "instance", null)
    }
}

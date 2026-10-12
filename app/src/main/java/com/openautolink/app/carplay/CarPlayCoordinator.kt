package com.openautolink.app.carplay

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

interface CarPlayRuntime : Closeable {
    /** Blocking attempt; all OS/protocol work lives on the coordinator's owned worker. */
    fun run()
    /** Nonblocking revocation; subsequent resource installation/effects must fail closed. */
    fun cancel()
    /** Constant-space observed receiver counters, never inferred from status strings. */
    fun telemetrySnapshot(): com.openautolink.carplaylab.media.ReceiverTelemetrySnapshot? = null
}

/** Safe memory-only UI receipt: never retain an exception, cause or diagnostic message. */
data class CarPlayFailureUi(
    val kind: CarPlayFailureKind,
    val manualPeer: Boolean,
    val userGuidance: String,
)

data class CarPlayUiState(
    val selected: Boolean = false,
    val busy: Boolean = false,
    val status: String = "CarPlay ready",
    val evidence: CarPlayEvidence = CarPlayEvidence(),
    val failure: CarPlayFailureUi? = null,
)

/** One worker serializes complete AA retirement, CarPlay setup, and full receiver retirement.
 * No preference or Bluetooth-friendly name starts this owner. Stop keeps CP selected; only explicit
 * Return releases AA admission, so legacy auto-reconnect cannot undo a user Stop or failed start.
 */
class CarPlayCoordinator(
    private val ownership: CarPlayOwnership = CarPlayOwnership.process,
    private val retireAndroidAuto: () -> Unit,
    private val onState: (CarPlayUiState) -> Unit,
) : Closeable {
    private class Attempt(val token: CarPlayOwnership.Token) {
        val canceled = AtomicBoolean()
        @Volatile var runtime: CarPlayRuntime? = null
    }
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "oal-carplay-owner").apply { isDaemon = true } }
    private val interrupter = Executors.newSingleThreadExecutor { Thread(it, "oal-carplay-revoke").apply { isDaemon = true } }
    private var token: CarPlayOwnership.Token? = null
    private var current: Attempt? = null
    private var state = CarPlayUiState()
    private var closed = false
    private var commandEpoch = 0L

    fun connect(enabled: Boolean, factory: ((String) -> Unit) -> CarPlayRuntime): Boolean = synchronized(lock) {
        if (closed || !enabled || current != null) return false
        val lease = token ?: ownership.reserve(enabled)?.also { token = it } ?: return false
        val attempt = Attempt(lease)
        current = attempt
        publish(CarPlayUiState(selected = true, busy = true, status = "Retiring Android Auto before CarPlay"))
        worker.execute { run(attempt, factory) }
        true
    }

    private fun run(attempt: Attempt, factory: ((String) -> Unit) -> CarPlayRuntime) {
        var failedStatus: String? = null
        try {
            // Return means real stop completion, never an IDLE snapshot.
            retireAndroidAuto()
            ownership.acknowledgeAaRetired(attempt.token)
            if (attempt.canceled.get() || !ownership.canBootstrap(attempt.token)) return
            val runtime = factory { message -> record(attempt, message) }
            attempt.runtime = runtime
            if (attempt.canceled.get()) runtime.cancel() else runtime.run()
        } catch (failure: CarPlayConnectionFailure) {
            failedStatus = "CarPlay connection failed · reconnect or Return to Android Auto"
            synchronized(lock) {
                if (current === attempt && !attempt.canceled.get()) {
                    publish(state.copy(status = failedStatus, failure = CarPlayFailureUi(
                        failure.kind, failure.manualPeer, failure.userGuidance +
                            if (!failure.manualPeer && failure.kind in setOf(
                                CarPlayFailureKind.RUNTIME_STARTUP_TIMEOUT,
                                CarPlayFailureKind.WIRELESS_REQUEST_TIMEOUT,
                            )) " Manual fallback is optional; inbound IPv4 may be blocked on the car AP." else "",
                    )))
                }
            }
        } catch (failure: Exception) {
            failedStatus = "CarPlay failed type=${failure.javaClass.simpleName}; check setup / reconnect"
            if (!attempt.canceled.get()) record(attempt, failedStatus)
        } finally {
            try {
                attempt.runtime?.cancel()
                attempt.runtime?.close()
                attempt.runtime = null
                ownership.acknowledgeCarPlayRetired(attempt.token)
                synchronized(lock) {
                    if (current === attempt) {
                        current = null
                        publish(state.copy(busy = false, evidence = CarPlayEvidence(),
                            status = if (attempt.canceled.get()) "CarPlay stopped · reconnect or Return to Android Auto" else failedStatus ?: "CarPlay ended · reconnect or Return to Android Auto"))
                    }
                }
            } catch (_: Exception) {
                // Keep exact retiring owner/runtime for a Stop retry; never admit a successor.
                synchronized(lock) { if (current === attempt) publish(state.copy(status = "CarPlay retirement failed · Stop again to retry")) }
            }
        }
    }

    private fun record(attempt: Attempt, message: String) = synchronized(lock) {
        if (current === attempt && !attempt.canceled.get()) {
            publish(state.copy(status = message.take(220), evidence = state.evidence.accept(message)))
        }
    }
    /** Called at UI sampling cadence, not from media callbacks. Exact attempt gates retirement. */
    fun refreshTelemetry() = synchronized(lock) {
        val attempt = current ?: return@synchronized
        if (attempt.canceled.get()) return@synchronized
        val snapshot = attempt.runtime?.telemetrySnapshot() ?: return@synchronized
        if (state.evidence.telemetry != snapshot)
            publish(state.copy(evidence = state.evidence.copy(telemetry = snapshot)))
    }
    private fun publish(next: CarPlayUiState) { state = next; runCatching { onState(next) } }

    fun dismissFailure() = synchronized(lock) {
        if (state.failure != null) publish(state.copy(failure = null))
    }

    fun stop() = synchronized(lock) {
        commandEpoch++ // revoke already-queued reconnects even if no runtime exists yet.
        val attempt = current ?: return@synchronized
        attempt.canceled.set(true)
        attempt.runtime?.cancel()
        publish(state.copy(status = "Stopping CarPlay; waiting for exact receiver/control retirement"))
        // Socket closure breaks blocking connect/recv. Never close/join MediaCodec on UI thread.
        interrupter.execute { runCatching { attempt.runtime?.close() } }
        // Also retries a previously failed close after the original worker returned.
        worker.execute {
            if (current === attempt && attempt.runtime != null) {
                runCatching { attempt.runtime?.close(); attempt.runtime = null }.onSuccess {
                    ownership.acknowledgeCarPlayRetired(attempt.token)
                    synchronized(lock) { if (current === attempt) { current = null; publish(state.copy(busy = false, evidence = CarPlayEvidence(), status = "CarPlay stopped")) } }
                }
            }
        }
    }

    fun reconnect(enabled: Boolean, factory: ((String) -> Unit) -> CarPlayRuntime) {
        synchronized(lock) {
            if (closed || !enabled || token == null) return
            stop()
            val requestEpoch = commandEpoch
            worker.execute {
                synchronized(lock) {
                    if (!closed && requestEpoch == commandEpoch) connect(enabled, factory)
                }
            }
        }
    }
    fun returnToAndroidAuto(onReturned: () -> Unit = {}) = synchronized(lock) {
        if (closed) return@synchronized
        dismissFailure()
        stop()
        worker.execute {
            val lease = synchronized(lock) { token } ?: return@execute
            try {
                // Also retry AA retirement if the initial handoff failed.
                retireAndroidAuto()
                ownership.acknowledgeAaRetired(lease)
                synchronized(lock) {
                    if (current != null) return@synchronized
                    ownership.acknowledgeCarPlayRetired(lease)
                    if (ownership.release(lease)) {
                        token = null
                        publish(CarPlayUiState())
                        onReturned()
                    }
                }
            } catch (_: Exception) {
                synchronized(lock) { publish(state.copy(status = "AA retirement failed · Return again to retry")) }
            }
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        returnToAndroidAuto()
        closed = true
        worker.shutdown() // queued retirement is deliberately not canceled.
        interrupter.shutdown()
    }
}

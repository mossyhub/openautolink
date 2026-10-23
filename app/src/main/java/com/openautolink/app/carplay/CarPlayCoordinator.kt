package com.openautolink.app.carplay

import android.view.Surface
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

interface CarPlayRuntime : Closeable {
    /** Blocking attempt; all OS/protocol work lives on the coordinator's owned worker. */
    fun run()
    /** Nonblocking revocation; subsequent resource installation/effects must fail closed. */
    fun cancel()
    /** Presentation only; never cancel transport/audio or wait for decoder retirement. */
    fun attachSurface(surface: Surface) {}
    /** Ignore stale Surface callbacks; only the exact current presentation can detach. */
    fun detachSurface(surface: Surface) {}
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
    /** Revoked by Stop/failure, independently of the worker's retirement-in-progress busy flag. */
    val videoWaitActive: Boolean = true,
) {
    val waitingForVideo: Boolean get() = selected && busy && videoWaitActive && failure == null &&
        evidence.telemetry?.presentationRendered != true
}

/** One worker serializes complete AA retirement, CarPlay setup, and full receiver retirement.
 * No preference or Bluetooth-friendly name starts this owner. Stop keeps CP selected; only explicit
 * Return releases AA admission, so legacy auto-reconnect cannot undo a user Stop or failed start.
 */
class CarPlayCoordinator(
    private val ownership: CarPlayOwnership = CarPlayOwnership.process,
    private val retireAndroidAuto: () -> Unit,
    private val sessionLeases: CarPlaySessionLeaseSource = CarPlaySessionLeaseSource.NONE,
    private val mediaLeases: CarPlayHostMediaLeaseSource = CarPlayHostMediaLeaseSource.NONE,
    private val onState: (CarPlayUiState) -> Unit,
) : Closeable {
    // Preserve positional and trailing-onState callers as well as existing named arguments.
    constructor(ownership: CarPlayOwnership, retireAndroidAuto: () -> Unit, onState: (CarPlayUiState) -> Unit) :
        this(ownership, retireAndroidAuto, CarPlaySessionLeaseSource.NONE, onState)

    constructor(ownership: CarPlayOwnership, retireAndroidAuto: () -> Unit,
                sessionLeases: CarPlaySessionLeaseSource, onState: (CarPlayUiState) -> Unit) :
        this(ownership, retireAndroidAuto, sessionLeases, CarPlayHostMediaLeaseSource.NONE, onState)

    private class Attempt(val token: CarPlayOwnership.Token, val initialSurface: Surface?, val sessionLease: CarPlaySessionLease) {
        val context = CarPlayAttemptContext(CarPlayHostMediaLease(
            CarPlayPlayingPublicationExperiment.snapshotMode()))
        var mediaBegun = false
        var runtimeClosed = false
        var protectionReleased = false
        val canceled = AtomicBoolean()
        @Volatile var runtime: CarPlayRuntime? = null
    }
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "oal-carplay-owner").apply { isDaemon = true } }
    private val interrupter = Executors.newSingleThreadExecutor { Thread(it, "oal-carplay-revoke").apply { isDaemon = true } }
    private var token: CarPlayOwnership.Token? = null
    private var current: Attempt? = null
    private var presentation: Surface? = null
    private var state = CarPlayUiState()
    private var closed = false
    private var commandEpoch = 0L

    fun connect(enabled: Boolean, factory: ((String) -> Unit) -> CarPlayRuntime): Boolean =
        connectWithContext(enabled) { _, report -> factory(report) }

    fun connectWithContext(enabled: Boolean, factory: (CarPlayAttemptContext, (String) -> Unit) -> CarPlayRuntime): Boolean = synchronized(lock) {
        if (closed || !enabled || current != null) return false
        val lease = token ?: ownership.reserve(enabled)?.also { token = it } ?: return false
        val sessionLease = try { sessionLeases.acquire() } catch (_: Exception) {
            publish(CarPlayUiState(selected = true, status = "CarPlay protection unavailable · reconnect while app is visible"))
            return false
        }
        val attempt = Attempt(lease, presentation, sessionLease)
        current = attempt
        publish(CarPlayUiState(selected = true, busy = true, status = "Retiring Android Auto before CarPlay"))
        worker.execute { run(attempt, factory) }
        true
    }

    private fun run(attempt: Attempt, factory: (CarPlayAttemptContext, (String) -> Unit) -> CarPlayRuntime) {
        var failedStatus: String? = null
        try {
            // OS acceptance is not promotion. Never retire AA/bootstrap transport unprotected.
            attempt.sessionLease.awaitReady()
            // Return means real stop completion, never an IDLE snapshot.
            retireAndroidAuto()
            ownership.acknowledgeAaRetired(attempt.token)
            if (attempt.canceled.get() || !ownership.canBootstrap(attempt.token)) return
            attempt.mediaBegun = true // even partial apply owns retryable cleanup debt.
            mediaLeases.begin(attempt.context.hostMedia)
            if (attempt.canceled.get() || !ownership.canBootstrap(attempt.token)) return
            val runtime = factory(attempt.context) { message -> record(attempt, message) }
            synchronized(lock) {
                attempt.runtime = runtime
                if (current === attempt && !attempt.canceled.get()) {
                    // A destroy/replacement may arrive while the factory is blocked. Never
                    // revive its original target when installing this exact runtime.
                    attempt.initialSurface?.takeIf { it !== presentation }?.let(runtime::detachSurface)
                    presentation?.let(runtime::attachSurface)
                }
            }
            if (attempt.canceled.get()) runtime.cancel() else runtime.run()
        } catch (failure: CarPlayConnectionFailure) {
            failedStatus = "CarPlay connection failed · reconnect or Return to Android Auto"
            synchronized(lock) {
                if (current === attempt && !attempt.canceled.get()) {
                    publish(state.copy(status = failedStatus, videoWaitActive = false, failure = CarPlayFailureUi(
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
            synchronized(lock) { if (current === attempt) publish(state.copy(videoWaitActive = false)) }
        } finally {
            try {
                finishAttempt(attempt)
                synchronized(lock) {
                    if (current === attempt) {
                        current = null
                        publish(state.copy(busy = false, evidence = CarPlayEvidence(),
                            status = if (attempt.canceled.get()) "CarPlay stopped · reconnect or Return to Android Auto" else failedStatus ?: "CarPlay ended · reconnect or Return to Android Auto"))
                    }
                }
            } catch (_: Exception) {
                if (attempt.mediaBegun) attempt.context.hostMedia.record("failed", "unknown", "failed")
                // Keep exact retiring owner/runtime for a Stop retry; never admit a successor.
                synchronized(lock) { if (current === attempt) publish(state.copy(status = "CarPlay retirement failed · Stop again to retry")) }
            }
        }
    }

    /** One retryable finalizer, including failed factory/null-runtime and partial-apply debt. */
    private fun finishAttempt(attempt: Attempt) {
        if (attempt.mediaBegun) attempt.context.hostMedia.record("pending", "unknown", "pending")
        if (!attempt.runtimeClosed) {
            attempt.runtime?.cancel()
            attempt.runtime?.close()
            attempt.runtimeClosed = true
            attempt.runtime = null
        }
        if (!attempt.protectionReleased) {
            attempt.sessionLease.release()
            attempt.protectionReleased = true
        }
        if (attempt.mediaBegun) mediaLeases.finish(attempt.context.hostMedia)
        ownership.acknowledgeCarPlayRetired(attempt.token)
    }

    private fun record(attempt: Attempt, message: String) = synchronized(lock) {
        if (current === attempt && !attempt.canceled.get()) {
            publish(state.copy(status = message.take(220), evidence = state.evidence.accept(message)))
        }
    }
    fun attachSurface(surface: Surface) = synchronized(lock) {
        if (closed || presentation === surface) return@synchronized
        presentation = surface
        current?.takeUnless { it.canceled.get() }?.runtime?.attachSurface(surface)
        if (current?.canceled?.get() == false)
            publish(state.copy(evidence = state.evidence.copy(telemetry = null)))
    }

    fun detachSurface(surface: Surface) = synchronized(lock) {
        if (presentation !== surface) return@synchronized
        presentation = null
        current?.takeUnless { it.canceled.get() }?.runtime?.detachSurface(surface)
        if (current?.canceled?.get() == false)
            publish(state.copy(evidence = state.evidence.copy(telemetry = null)))
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

    /** Presentation only: rejecting a fenced USB candidate must not retire a healthy owner. */
    internal fun presentFailure(failure: CarPlayFailureUi) = synchronized(lock) {
        if (!closed) publish(state.copy(failure = failure))
    }

    fun stop() = synchronized(lock) {
        commandEpoch++ // revoke already-queued reconnects even if no runtime exists yet.
        val attempt = current ?: return@synchronized
        attempt.canceled.set(true)
        attempt.runtime?.cancel()
        publish(state.copy(videoWaitActive = false, status = "Stopping CarPlay; waiting for exact receiver/control retirement"))
        // Socket closure breaks blocking connect/recv. Never close/join MediaCodec on UI thread.
        interrupter.execute { runCatching { attempt.runtime?.close() } }
        // Also retries a previously failed close after the original worker returned.
        worker.execute {
            if (synchronized(lock) { current === attempt }) {
                runCatching { finishAttempt(attempt) }.onSuccess {
                    synchronized(lock) { if (current === attempt) { current = null; publish(state.copy(busy = false, evidence = CarPlayEvidence(), status = "CarPlay stopped")) } }
                }.onFailure {
                    if (attempt.mediaBegun) attempt.context.hostMedia.record("failed", "unknown", "failed")
                    synchronized(lock) { if (current === attempt) publish(state.copy(status = "CarPlay retirement failed · Stop again to retry")) }
                }
            }
        }
    }

    fun reconnect(enabled: Boolean, factory: ((String) -> Unit) -> CarPlayRuntime) =
        reconnectWithContext(enabled) { _, report -> factory(report) }

    fun reconnectWithContext(enabled: Boolean, factory: (CarPlayAttemptContext, (String) -> Unit) -> CarPlayRuntime) {
        synchronized(lock) {
            if (closed || !enabled || token == null) return
            stop()
            val requestEpoch = commandEpoch
            worker.execute {
                synchronized(lock) {
                    if (!closed && requestEpoch == commandEpoch) connectWithContext(enabled, factory)
                }
            }
        }
    }
    fun returnToAndroidAuto(preserveFailure: Boolean = false, onReturned: () -> Unit = {}) = synchronized(lock) {
        if (worker.isShutdown) return@synchronized
        // Automatic cable retirement releases ownership, not the user's failure receipt.
        if (!preserveFailure) dismissFailure()
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
                        publish(CarPlayUiState(failure = state.failure.takeIf { preserveFailure }))
                        onReturned()
                    }
                }
            } catch (_: Exception) {
                synchronized(lock) { publish(state.copy(status = "AA retirement failed · Return again to retry")) }
            }
        }
    }

    override fun close() = synchronized(lock) {
        if (worker.isShutdown) return@synchronized
        returnToAndroidAuto()
        closed = true // Admission stays revoked even if retirement needs an explicit close retry.
        worker.execute {
            synchronized(lock) {
                if (current == null && token == null) {
                    worker.shutdown()
                    interrupter.shutdown()
                }
            }
        }
    }
}

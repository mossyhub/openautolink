package com.openautolink.app.carplay

import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.input.ProcessVehicleDataRuntime
import com.shilapi.xcertplay.airplay.AirPlaySession
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Read-only CarPlay subscriber to the existing application-owned VHAL snapshot. Construct after
 * ProcessVehicleDataRuntime.initialize; an absent runtime remains silent (no second producer).
 *
 * Parent hook, serialized by its receiver lifecycle coroutine:
 * ```
 * val appearance = CarPlayAppearanceBridge() // one per receiver lifecycle
 * val owner = appearance.replace(exactAirPlaySession, exactSessionEventReady)
 * // On this session's retirement, BEFORE closing its AirPlaySession:
 * appearance.retire(owner)
 * // On receiver shutdown, BEFORE receiver/session socket teardown:
 * appearance.close()
 * ```
 * exactSessionEventReady must be a StateFlow owned by that exact session, initially false and
 * true only after encrypted event readiness AND the parent's required runtime authentication.
 * Publish readiness from actual lifecycle/transport callbacks, never media/status-string guesses.
 * A successor gets a new readiness flow and owner; never look up a mutable 'current session' from
 * the writer. Late retire(oldOwner) cannot retire a successor. Do not runBlocking these suspending
 * hooks inside an AirPlay writer/listener callback; dispatch them to the lifecycle coroutine.
 *
 * All transport calls run on IO. StateFlow/combine retain bounded latest state, not a command list.
 * The ownership mutex linearizes writes with retirement: an already admitted blocking write may
 * finish, but retire/close/replacement acknowledgement fences every old pending write. Suspension
 * waits without blocking the caller/UI thread; there is deliberately no misleading timeout that
 * could acknowledge retirement while an admitted network write is still running.
 *
 * NIGHT_MODE's observation is already exported under evObservationMetadata by the forwarder's
 * ordinary property path. Require available status, active matching VHAL registration and known
 * monotonic sample/receipt age; never stamp unrelated batch arrival as a fresh night observation.
 * Five seconds is this bridge's configurable freshness policy, not a CarPlay protocol requirement.
 * Boolean false is a real observation; null/missing/stale authority is not a day-mode command.
 *
 * AirPlaySession.setNightMode already owns eventWriteLock, retained state and the sourced wire
 * dictionary {type:setNightMode, params:{nightMode:Boolean}}. Reuse it without another serializer,
 * socket, polling transport or protocol queue. Hold stale-prone state here until actual readiness
 * so its retained transport cannot later replay a value that expired before readiness. A false
 * return is not deduplicated; retry only on a subsequent source/readiness change, never a loop.
 * A successful local write is not evidence of iPhone reception or appearance change.
 */
class CarPlayAppearanceBridge internal constructor(
    private val source: StateFlow<ControlMessage.VehicleData>?,
    private val dispatcher: CoroutineDispatcher,
    private val elapsedRealtime: () -> Long,
    private val maxAgeMillis: Long,
) {
    constructor(maxAgeMillis: Long = 5_000L) : this(
        ProcessVehicleDataRuntime.latestVehicleData(), Dispatchers.IO, SystemClock::elapsedRealtime, maxAgeMillis,
    )
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val gate = Mutex()
    private var active: Owner? = null
    private var closed = false
    class Owner internal constructor() { internal var job: Job? = null }
    suspend fun replace(session: AirPlaySession, eventReady: StateFlow<Boolean>): Owner =
        replace(eventReady) { night -> session.controlAuthenticated && session.setNightMode(night) }
    internal suspend fun replace(ready: StateFlow<Boolean>, send: (Boolean) -> Boolean): Owner = gate.withLock {
        check(!closed) { "Appearance bridge is closed" }
        active?.job?.cancel()
        val owner = Owner()
        active = owner
        owner.job = scope.launch {
            var delivered: Boolean? = null
            source?.let { data ->
                combine(data, ready) { _, _ -> Unit }.collect {
                    gate.withLock {
                        if (active === owner && ready.value) eligibleNightMode(data.value)?.let { night ->
                            if (night != delivered && send(night)) delivered = night
                        }
                    }
                }
            }
        }
        owner
    }
    private fun eligibleNightMode(data: ControlMessage.VehicleData): Boolean? {
        val value = data.nightMode ?: return null
        val observation = data.evObservationMetadata["NIGHT_MODE"] ?: return null
        if (observation.source != "vhal" || observation.propertyId != 0x11200407 ||
            observation.status != 0 || !observation.subscriptionActive ||
            data.vhalRegistrationGeneration == null ||
            observation.registrationGeneration != data.vhalRegistrationGeneration) return null
        val at = observation.timestampElapsedNanos?.takeIf { it > 0 }?.div(1_000_000) ?: return null
        val now = elapsedRealtime()
        if (at > now || observation.receivedElapsedMs > now || observation.receivedElapsedMs < 0 ||
            now - at > maxAgeMillis || now - observation.receivedElapsedMs > maxAgeMillis) return null
        return value
    }
    suspend fun retire(owner: Owner) = gate.withLock {
        if (active === owner) {
            active = null
            owner.job?.cancel()
        }
    }
    suspend fun close() = gate.withLock {
        closed = true
        active = null
        scope.cancel()
    }
}

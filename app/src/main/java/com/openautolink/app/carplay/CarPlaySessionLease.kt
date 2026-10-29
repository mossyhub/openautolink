package com.openautolink.app.carplay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Readiness is historical base protection; capture and loss are ongoing exact-owner authority. */
interface CarPlaySessionLease {
    fun awaitReady() {}
    /** Current base foreground-service protection, independent of microphone permission/type. */
    fun playbackAllowed(): Boolean = false
    fun captureAllowed(): Boolean = false
    /** Worker-only: return only after exact capture resources have actually retired. */
    fun onCaptureRetirement(retire: () -> Unit) {}
    fun inputAdvertised(available: Boolean) {}
    fun revokeCapture() {}
    fun onProtectionLost(callback: () -> Unit) {}
    fun release() {}
    /** Worker-only: posting removal is not an acknowledgement. */
    fun releaseAndAwait() { release() }
}
fun interface CarPlaySessionLeaseSource {
    fun acquire(): CarPlaySessionLease
    companion object { val NONE = CarPlaySessionLeaseSource { object : CarPlaySessionLease {} } }
}
class CarPlaySessionLeases(private val context: Context) : CarPlaySessionLeaseSource {
    override fun acquire(): CarPlaySessionLease = Registry.acquire(context)
    companion object {
        val microphoneState: kotlinx.coroutines.flow.StateFlow<CarPlayMicrophoneSnapshot> get() = CarPlayMicrophoneSnapshots.state
        fun status(): CarPlayMicrophoneSnapshot = microphoneState.value
        /** Called only by explicit contextual UI; no background prompting or automatic reconnect. */
        fun requestMicrophone(context: Context, requestPermission: () -> Unit) {
            if (!CarPlayForegroundVisibility.isResumed()) return
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermission()
            else reconcile(context)
        }
        fun forContext(context: Context): CarPlaySessionLeaseSource = CarPlaySessionLeases(context.applicationContext)
        fun reconcile(context: Context) = Registry.reconcile(context.applicationContext)
        fun refreshNotification() = Registry.refreshNotification()
    }

    /** Android operations/main-owned state never execute under a registry/coordinator monitor. */
    internal object Registry {
        private class Lease(val id: Long, val context: Context) : CarPlaySessionLease {
            val ready = CompletableFuture<Unit>()
            val retired = AtomicBoolean()
            val revoked = AtomicBoolean()
            val lost = AtomicBoolean()
            val lossCallback = AtomicReference<(() -> Unit)?>(null)
            @Volatile var epoch: CarPlaySessionService? = null
            @Volatile var epochDestroyed = false
            @Volatile var base = false
            @Volatile var mic = false
            @Volatile var delivered = false
            @Volatile var lastReconcile: Pair<Long?, Boolean>? = null
            @Volatile var removal = CompletableFuture<Unit>()
            @Volatile var permission = false
            @Volatile var advertised: Boolean? = null
            val captureRetirement = AtomicReference<(() -> Unit)?>(null)
            val captureRetiring = AtomicBoolean()
            val permissionRevoked = AtomicBoolean()
            override fun onCaptureRetirement(retire: () -> Unit) {
                check(captureRetirement.compareAndSet(null,retire)) { "Capture retirement owner already installed" }
                if (permissionRevoked.get()) retireCapture(this)
            }
            override fun inputAdvertised(available: Boolean) { onMain {
                if (leases[id] === this && !retired.get() && !lost.get()) { advertised=available; publishSnapshot() }
            } }
            override fun awaitReady() { ready.get(10, TimeUnit.SECONDS) }
            // revoked is capture-only (also set by microphone permission loss). The coordinator
            // independently fences explicit attempt cancellation; keep ordinary playback eligible.
            override fun playbackAllowed(): Boolean = base && !epochDestroyed && !retired.get() && !lost.get()
            override fun captureAllowed(): Boolean = base && mic && !retired.get() && !revoked.get() && !lost.get() && permission
            override fun revokeCapture() { revoked.set(true) }
            override fun onProtectionLost(callback: () -> Unit) {
                lossCallback.set(callback)
                if (lost.get()) lossCallback.getAndSet(null)?.invoke()
            }
            fun loss(failure: Exception) {
                base = false; mic = false; lost.set(true); revoked.set(true)
                ready.completeExceptionally(failure)
                lossCallback.getAndSet(null)?.let { runCatching(it) }
            }
            override fun release() { releaseExact(this) }
            override fun releaseAndAwait() {
                check(Looper.myLooper() != Looper.getMainLooper()) { "Foreground retirement cannot wait on main" }
                release()
                removal.get(10, TimeUnit.SECONDS)
            }
        }
        private data class Request(val lease: Lease, val visible: Long?, var epoch: CarPlaySessionService?)
        private val retirementWorker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it,"oal-carplay-capture-retirement").apply { isDaemon=true } }
        private val main = Handler(Looper.getMainLooper())
        private val sequence = AtomicLong()
        private val leases = ConcurrentHashMap<Long, Lease>()
        private val requests = ConcurrentHashMap<Long, Request>()
        @Volatile private var service: CarPlaySessionService? = null
        private var startId = 0

        private fun granted(context: Context) = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        private fun onMain(action: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) action()
            else check(main.post(action)) { "CarPlay foreground dispatch unavailable" }
        }
        fun acquire(context: Context): CarPlaySessionLease {
            val lease = Lease(sequence.incrementAndGet(), context)
            leases[lease.id] = lease
            try { check(main.post {
                if (lease.retired.get()) { finishRemoval(lease); return@post }
                requestStart(lease)
            }) { "CarPlay foreground dispatch unavailable" } } catch (failure: Exception) { lease.loss(failure); leases.remove(lease.id,lease); lease.removal.complete(Unit) }
            return lease
        }
        private fun requestStart(lease: Lease) {
            val id = sequence.incrementAndGet()
            lease.permission=granted(lease.context)
            val request = Request(lease,CarPlayForegroundVisibility.generation(),service?.takeIf { owner ->
                leases.values.any { it.epoch === owner && it.base && !it.retired.get() && !it.lost.get() }
            })
            requests[id] = request
            publishSnapshot()
            CarPlayForegroundReceipts.emit(CarPlayForegroundStage.START,CarPlayForegroundStatus.REQUESTED)
            try {
                lease.context.startForegroundService(Intent(lease.context,CarPlaySessionService::class.java)
                    .putExtra("attempt",lease.id).putExtra("request",id)) ?: error("CarPlay foreground service unavailable")
            } catch (failure: Exception) {
                CarPlayForegroundReceipts.emit(CarPlayForegroundStage.START,CarPlayForegroundStatus.DENIED)
                requests.remove(id)
                if (!lease.base) {
                    lease.loss(failure)
                    leases.remove(lease.id,lease)
                    lease.removal.complete(Unit)
                }
                // Optional refresh failure leaves the positively acknowledged base intact.
            }
        }
        fun created(owner: CarPlaySessionService) {
            val previous = service
            service = owner; startId = 0
            if (previous != null && previous !== owner) loseEpoch(previous, acknowledgeRetirement = false)
            requests.values.filter { it.epoch == null }.forEach { it.epoch=owner }
            publishSnapshot()
        }
        fun started(owner: CarPlaySessionService, id: Int, attempt: Long, requestId: Long) {
            if (service !== owner) return
            startId = id
            val request = requests[requestId]?.takeIf { it.lease.id == attempt }
            if (request != null) {
                requests.remove(requestId,request)
                // A canceled but delivered OS start still owns this exact lifecycle epoch.
                request.lease.epoch = owner
            }
            val lease = request?.lease?.takeIf { leases[it.id] === it && !it.retired.get() && !it.lost.get() }
            try {
                // Every accepted canceled OS start still meets its foreground obligation promptly.
                owner.promote(maskFor(owner))
                if (service !== owner) return
                if (lease != null) {
                    lease.epoch = owner; lease.base = true; lease.delivered = true
                    val visible = request.visible
                    if (!lease.mic && granted(lease.context) && visible != null && CarPlayForegroundVisibility.isCurrent(visible)) {
                        try {
                            owner.promote(maskFor(owner) or CarPlayForegroundPolicy.MIC)
                            if (service === owner && !lease.retired.get() && !lease.revoked.get() &&
                                granted(lease.context) && CarPlayForegroundVisibility.isCurrent(visible)) lease.mic = true
                            else owner.promote(maskFor(owner))
                        } catch (_: Exception) {
                            // Readback, not a catch-and-retry assumption, establishes retained base.
                            check(owner.foregroundServiceType and CarPlayForegroundPolicy.BASE == CarPlayForegroundPolicy.BASE)
                        }
                    }
                    lease.permission=granted(lease.context)
                    lease.lastReconcile = CarPlayForegroundVisibility.generation() to lease.permission
                    if (!lease.retired.get()) lease.ready.complete(Unit)
                    publishSnapshot()
                }
                reconcileRemoval(owner)
            } catch (failure: Exception) {
                leases.values.filter { it.epoch === owner || it === lease }.forEach { it.loss(failure) }
                runCatching { owner.removeProtection() }
                owner.stopSelfResult(id)
            }
        }
        private fun maskFor(owner: CarPlaySessionService): Int = CarPlayForegroundPolicy.BASE or
            (if (leases.values.any { it.epoch === owner && it.mic }) CarPlayForegroundPolicy.MIC else 0)

        fun refreshNotification() = onMain {
            val owner = service ?: return@onMain
            if (leases.values.any { it.epoch === owner && it.base && !it.retired.get() })
                runCatching { owner.refreshNotification() }
        }
        fun reconcile(context: Context) = onMain {
            val visible = CarPlayForegroundVisibility.generation()
            val grant = granted(context)
            leases.values.filter { it.delivered && !it.retired.get() && !it.lost.get() }.forEach { lease ->
                lease.permission=grant
                if ((!grant || lease.permissionRevoked.get()) && lease.mic) {
                    lease.revoked.set(true)
                    lease.permissionRevoked.set(true)
                    retireCapture(lease)
                    return@forEach
                }
                val tuple = visible to grant
                if (!lease.revoked.get() && !lease.mic && visible != null && lease.lastReconcile != tuple) {
                    lease.lastReconcile = tuple
                    // Fresh same-service visible start refreshes API32 while-in-use admission too.
                    requestStart(lease)
                }
            }
            publishSnapshot()
        }
        private fun retireCapture(lease: Lease) {
            val retire = lease.captureRetirement.get() ?: return
            if (!lease.captureRetiring.compareAndSet(false,true)) return
            CarPlayForegroundReceipts.emit(CarPlayForegroundStage.MICROPHONE,CarPlayForegroundStatus.PENDING)
            retirementWorker.execute {
                try {
                    retire() // exact recorder/effects/encoder owner, never a posted-close acknowledgement.
                    onMain {
                        if (leases[lease.id] !== lease || lease.lost.get()) return@onMain
                        lease.mic=false
                        lease.captureRetiring.set(false)
                        service?.takeIf { it === lease.epoch }?.let { owner ->
                            try { owner.promote(maskFor(owner)) }
                            catch (_: Exception) {
                                lease.loss(IllegalStateException("CarPlay microphone demotion failed"))
                                CarPlayForegroundReceipts.emit(CarPlayForegroundStage.MICROPHONE,CarPlayForegroundStatus.FAILED)
                            }
                        }
                        publishSnapshot()
                    }
                } catch (_: Exception) {
                    lease.captureRetiring.set(false)
                    CarPlayForegroundReceipts.emit(CarPlayForegroundStage.MICROPHONE,CarPlayForegroundStatus.FAILED)
                    // Keep the OS MIC purpose and retirement debt; next reconcile retries exact close.
                }
            }
        }
        private fun publishSnapshot() {
            val lease=leases.values.filter { !it.retired.get() && !it.lost.get() }.maxByOrNull { it.id }
            CarPlayMicrophoneSnapshots.mutable.value=lease?.let {
                CarPlayMicrophoneSnapshot(it.base,it.captureAllowed(),it.permission,it.advertised,
                    retiring = it.permissionRevoked.get() && it.mic,
                    reconnectRequired = it.permissionRevoked.get())
            } ?: CarPlayMicrophoneSnapshot()
        }
        private fun releaseExact(lease: Lease) {
            lease.revoked.set(true); lease.retired.set(true)
            lease.ready.completeExceptionally(IllegalStateException("CarPlay attempt retired"))
            if (lease.removal.isDone && !lease.removal.isCompletedExceptionally) return
            if (lease.removal.isCompletedExceptionally) lease.removal = CompletableFuture()
            try { onMain {
                val owner = service
                if (lease.epochDestroyed || (owner == null && lease.epoch == null)) {
                    if (requests.values.none { it.lease === lease }) finishRemoval(lease)
                } else if (lease.epoch == null || lease.epoch === owner) owner?.let(::reconcileRemoval)
            } } catch (failure: Exception) { lease.removal.completeExceptionally(failure) }
        }
        private fun finishRemoval(lease: Lease) {
            leases.remove(lease.id,lease); lease.base = false; lease.mic = false; lease.removal.complete(Unit)
            publishSnapshot()
        }
        private fun reconcileRemoval(owner: CarPlaySessionService) {
            if (service !== owner) return
            val retiring = leases.values.filter { it.retired.get() && it.epoch === owner && requests.values.none { request -> request.lease === it } }
            val live = leases.values.filter { !it.retired.get() && !it.lost.get() && it.delivered && it.epoch === owner }
            try {
                if (live.isNotEmpty()) {
                    val mask = CarPlayForegroundPolicy.BASE or if (live.any { it.mic }) CarPlayForegroundPolicy.MIC else 0
                    if (owner.foregroundServiceType != mask) owner.promote(mask)
                    retiring.forEach(::finishRemoval)
                } else if (requests.isEmpty() && startId != 0) {
                    CarPlayForegroundReceipts.emit(CarPlayForegroundStage.PROTECTION_STOP,CarPlayForegroundStatus.REQUESTED,
                        runCatching { owner.foregroundServiceType }.getOrNull())
                    owner.removeProtection()
                    CarPlayForegroundReceipts.emit(CarPlayForegroundStage.PROTECTION_READBACK,CarPlayForegroundStatus.PENDING,
                        runCatching { owner.foregroundServiceType }.getOrNull())
                    // Android 12L can retain its stored type after this call. Only the exact
                    // onDestroy acknowledges removal; false means an unseen newer OS start.
                    val accepted = owner.stopSelfResult(startId)
                    CarPlayForegroundReceipts.emit(CarPlayForegroundStage.SERVICE_STOP,
                        if (accepted) CarPlayForegroundStatus.REQUESTED else CarPlayForegroundStatus.PENDING)
                }
            } catch (failure: Exception) { retiring.forEach { it.removal.completeExceptionally(failure) } }
        }
        fun destroyed(owner: CarPlaySessionService) {
            if (service === owner) service = null
            loseEpoch(owner, acknowledgeRetirement = true)
        }
        private fun loseEpoch(owner: CarPlaySessionService, acknowledgeRetirement: Boolean) {
            val pending=requests.entries.filter { it.value.epoch === owner }
            pending.forEach { requests.remove(it.key,it.value) }
            val affected=leases.values.filter { it.epoch === owner || pending.any { request -> request.value.lease === it } }
            affected.forEach {
                it.epoch = owner
                if (acknowledgeRetirement) it.epochDestroyed = true
                it.loss(IllegalStateException("CarPlay foreground service lost"))
                if (it.retired.get() && acknowledgeRetirement) {
                    CarPlayForegroundReceipts.emit(CarPlayForegroundStage.RETIREMENT,CarPlayForegroundStatus.ACKNOWLEDGED)
                    finishRemoval(it)
                }
            }
            CarPlayForegroundReceipts.emit(CarPlayForegroundStage.SERVICE,CarPlayForegroundStatus.LOST)
            publishSnapshot()
        }
    }
}

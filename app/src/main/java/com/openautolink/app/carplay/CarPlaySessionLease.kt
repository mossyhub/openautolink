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
    fun captureAllowed(): Boolean = false
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
            @Volatile var base = false
            @Volatile var mic = false
            @Volatile var delivered = false
            @Volatile var lastReconcile: Pair<Long?, Boolean>? = null
            @Volatile var removal = CompletableFuture<Unit>()
            override fun awaitReady() { ready.get(10, TimeUnit.SECONDS) }
            override fun captureAllowed(): Boolean = base && mic && !retired.get() && !revoked.get() && !lost.get() && granted(context)
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
        private data class Request(val lease: Lease, val visible: Long?)
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
            val request = Request(lease,CarPlayForegroundVisibility.generation())
            requests[id] = request
            try {
                lease.context.startForegroundService(Intent(lease.context,CarPlaySessionService::class.java)
                    .putExtra("attempt",lease.id).putExtra("request",id)) ?: error("CarPlay foreground service unavailable")
            } catch (failure: Exception) {
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
            if (previous != null && previous !== owner) {
                leases.values.filter { it.epoch === previous }.forEach { it.loss(IllegalStateException("CarPlay service replaced")) }
            }
        }
        fun started(owner: CarPlaySessionService, id: Int, attempt: Long, requestId: Long) {
            if (service !== owner) return
            startId = id
            val request = requests[requestId]?.takeIf { it.lease.id == attempt }
            if (request != null) requests.remove(requestId,request)
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
                    lease.lastReconcile = CarPlayForegroundVisibility.generation() to granted(lease.context)
                    if (!lease.retired.get()) lease.ready.complete(Unit)
                }
                reconcileRemoval(owner)
            } catch (failure: Exception) {
                leases.values.filter { it.epoch === owner || it === lease }.forEach { it.loss(failure) }
                runCatching { owner.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE) }
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
            leases.values.filter { it.delivered && !it.retired.get() && !it.revoked.get() && !it.lost.get() }.forEach { lease ->
                if (!grant) { lease.mic = false; return@forEach }
                val tuple = visible to grant
                if (!lease.mic && visible != null && lease.lastReconcile != tuple) {
                    lease.lastReconcile = tuple
                    // Fresh same-service visible start refreshes API32 while-in-use admission too.
                    requestStart(lease)
                }
            }
        }
        private fun releaseExact(lease: Lease) {
            lease.revoked.set(true); lease.retired.set(true)
            lease.ready.completeExceptionally(IllegalStateException("CarPlay attempt retired"))
            if (lease.removal.isDone && !lease.removal.isCompletedExceptionally) return
            if (lease.removal.isCompletedExceptionally) lease.removal = CompletableFuture()
            try { onMain {
                val owner = service
                if (owner == null || lease.epoch != null && lease.epoch !== owner) {
                    if (requests.values.none { it.lease === lease }) finishRemoval(lease)
                } else reconcileRemoval(owner)
            } } catch (failure: Exception) { lease.removal.completeExceptionally(failure) }
        }
        private fun finishRemoval(lease: Lease) {
            leases.remove(lease.id,lease); lease.base = false; lease.mic = false; lease.removal.complete(Unit)
        }
        private fun reconcileRemoval(owner: CarPlaySessionService) {
            if (service !== owner) return
            val retiring = leases.values.filter { it.retired.get() && requests.values.none { request -> request.lease === it } }
            val live = leases.values.filter { !it.retired.get() && it.delivered && it.epoch === owner }
            try {
                if (live.isNotEmpty()) {
                    val mask = CarPlayForegroundPolicy.BASE or if (live.any { it.mic }) CarPlayForegroundPolicy.MIC else 0
                    if (owner.foregroundServiceType != mask) owner.promote(mask)
                } else if (requests.isEmpty() && startId != 0) {
                    owner.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
                    check(owner.foregroundServiceType == 0) { "CarPlay foreground removal not acknowledged" }
                    owner.stopSelfResult(startId) // false means a newer start; never unconditional stop.
                }
                retiring.forEach(::finishRemoval)
            } catch (failure: Exception) { retiring.forEach { it.removal.completeExceptionally(failure) } }
        }
        fun destroyed(owner: CarPlaySessionService) {
            if (service !== owner) return
            service = null
            leases.values.filter { it.epoch === owner }.forEach {
                it.loss(IllegalStateException("CarPlay foreground service destroyed"))
                if (it.retired.get() && requests.values.none { request -> request.lease === it }) finishRemoval(it)
            }
        }
    }
}

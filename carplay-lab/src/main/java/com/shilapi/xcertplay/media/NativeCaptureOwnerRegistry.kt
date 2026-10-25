// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import java.util.concurrent.Executor

/** Pure lifecycle adapter, NOT a native entry point. Retain once at process/service lifetime.
 * [executor] must be a dedicated serialized non-main executor, never a UI-owned scope.
 * Process scheduler must poll pending owners independently of screen lifetime. No destructor.
 */
class NativeCaptureOwnerRegistry(
    private val executor: Executor,
    private val isMainThread: () -> Boolean,
) {
    interface NativeRun {
        fun begin()
        /** Atomic cancellation only; never closes, joins or destroys a stream. */
        fun cancel()
        /** null means pending; non-null certifies worker joined and close acknowledged. */
        fun pollClosedJoin(): Receipt?
        /** Destroy the exact joined owner, off main. Never retry an arbitrary close failure. */
        fun disposeJoined()
    }
    data class Receipt(val termination: Int, val error: Int, val stopError: Int,
                       val closeError: Int, val recoveryRequiresRestart: Boolean)
    enum class State { ADMITTED, RUNNING, STOPPING, RETIRED, RESTART_REQUIRED }
    class Owner internal constructor(
        internal val lease: MicrophoneCaptureFence.Lease,
        internal val factory: () -> NativeRun,
    ) {
        @Volatile var state = State.ADMITTED
            internal set
        @Volatile var receipt: Receipt? = null
            internal set
        internal var native: NativeRun? = null
        internal val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        internal val queued = java.util.concurrent.atomic.AtomicBoolean()
    }
    private val lock = Any()
    private var current: Owner? = null

    fun start(factory: () -> NativeRun): Owner? {
        val owner = synchronized(lock) {
            if (current != null) return null
            val lease = MicrophoneCaptureFence.tryDiagnostic() ?: return null
            Owner(lease, factory).also { current = it }
        }
        enqueue(owner)
        return owner
    }

    /** Policy/UI callers only revoke and enqueue; no native call or join inline. */
    fun requestCancel(owner: Owner) {
        synchronized(lock) {
            if (current !== owner || owner.state == State.RESTART_REQUIRED) return
            owner.cancelled.set(true)
            owner.state = State.STOPPING
        }
        poll(owner)
    }

    fun poll(owner: Owner) {
        if (synchronized(lock) { current !== owner || owner.state == State.RESTART_REQUIRED }) return
        enqueue(owner)
    }

    private fun failed(owner: Owner) = synchronized(lock) {
        if (current === owner) owner.state = State.RESTART_REQUIRED
    }

    private fun enqueue(owner: Owner) {
        if (!owner.queued.compareAndSet(false, true)) return
        try { executor.execute { try { service(owner) } finally { owner.queued.set(false) } } }
        catch (_: Exception) { failed(owner) } // rejected executor cannot acknowledge retirement
    }

    private fun service(owner: Owner) {
        if (synchronized(lock) { current !== owner || owner.state == State.RESTART_REQUIRED }) return
        try {
            check(!isMainThread()) { "native lifecycle must never run on main" }
            if (owner.native == null) {
                if (owner.cancelled.get()) {
                    synchronized(lock) {
                        if (current === owner) {
                            owner.state = State.RETIRED
                            owner.lease.release()
                            current = null
                        }
                    }
                    return
                }
                owner.native = owner.factory()
                owner.native!!.begin()
                synchronized(lock) {
                    owner.state = if (owner.cancelled.get()) State.STOPPING else State.RUNNING
                }
            }
            if (owner.cancelled.get()) owner.native!!.cancel()
            val receipt = owner.native!!.pollClosedJoin() ?: return
            owner.receipt = receipt
            if (receipt.closeError != 0 || receipt.recoveryRequiresRestart) {
                failed(owner)
                return // retain owner and lease; no dispose, handle retry or reset
            }
            owner.native!!.disposeJoined()
            owner.native = null
            synchronized(lock) {
                if (current === owner) {
                    owner.state = State.RETIRED
                    owner.lease.release()
                    current = null
                }
            }
        } catch (_: Throwable) {
            failed(owner) // unknown lifetime: retain the exact adapter, never retry its handle
        }
    }
}

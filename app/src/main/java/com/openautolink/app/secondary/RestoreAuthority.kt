package com.openautolink.app.secondary

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CompletableFuture

/** Ephemeral exact-owner authority. The monitor is also RuntimeOutput.guard. No preferences,
 * task lookup, renderer waits or future completion may run under it. The sole OS write is
 * linearized at moveTaskToFront dispatch initiation; an already initiated Binder write cannot
 * be undone. Revocation received before admission always wins, including same-output ABA. */
class RestoreAuthority {
    internal val monitor = Any()
    private val main = Handler(Looper.getMainLooper())
    internal class Identity(val runtime: Any, val session: Any?, val selection: Any, val stream: Any, val generation: Long, val policyGeneration:Long?)
    private var generation = 0L
    private var policyGeneration:Long?=null
    private var identity: Identity? = Identity(this, null, this, this, generation,policyGeneration)
    private var active: CompletableFuture<DriverVideoRestoreResult>? = null
    internal fun snapshot(): Identity? = synchronized(monitor) { identity }
    internal fun matches(exact: Identity): Boolean = identity === exact && generation == exact.generation
    internal fun install(runtime: Any, session: Any?, selection: Any?, stream: Any?) = synchronized(monitor) {
        invalidate()
        identity = if (selection != null && stream != null) Identity(runtime, session, selection, stream, generation,policyGeneration) else null
    }
    internal fun observePolicy(revision:Long?):Boolean = synchronized(monitor) {
        if(policyGeneration==revision)false else { policyGeneration=revision;invalidate();true }
    }
    internal fun invalidate() = synchronized(monitor) {
        generation++
        identity = identity?.let { Identity(it.runtime, it.session, it.selection, it.stream, generation,policyGeneration) }
        val old = active; active = null
        // Always enqueue: callers may already own the shared monitor. Never invoke UI/coordinator
        // continuations under it, even when a renderer acknowledges synchronously.
        if (old != null) main.post { old.complete(DriverVideoRestoreResult.BLOCKED) }
    }
    internal fun result(exact: Identity): CompletableFuture<DriverVideoRestoreResult> {
        val result = object : CompletableFuture<DriverVideoRestoreResult>() {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                synchronized(monitor) { if (active === this) { active = null; invalidate() } }
                return super.cancel(mayInterruptIfRunning)
            }
        }
        return result
    }
    internal fun activate(exact:Identity,result:CompletableFuture<DriverVideoRestoreResult>):Boolean = synchronized(monitor) {
        if(!matches(exact) || result.isDone || active?.isDone==false)false else { active=result;true }
    }
    internal fun owns(exact: Identity, result: CompletableFuture<DriverVideoRestoreResult>): Boolean =
        matches(exact) && active === result && !result.isDone
    internal fun finish(exact: Identity, result: CompletableFuture<DriverVideoRestoreResult>, value: DriverVideoRestoreResult) {
        val admitted = synchronized(monitor) { if (owns(exact, result)) { active = null; true } else false }
        result.complete(if (admitted) value else DriverVideoRestoreResult.BLOCKED)
    }
}

package com.openautolink.app.cluster

import android.app.Activity
import java.lang.ref.WeakReference

/** Actual Activity placement, never the AndroidX CarContext's synthetic display or desired ID.
 * One receipt belongs to one generation and Activity. Replacement/destruction revokes its borrower. */
internal object ClusterPhysicalHostState {
    class Receipt internal constructor(val generation: Long, activity: Activity, val displayId: Int) {
        internal val activity = WeakReference(activity)
        internal var owner: Any? = null
        internal var onRevoked: (() -> Unit)? = null
    }
    private var receipt: Receipt? = null

    fun record(generation: Long, activity: Activity) {
        val actual = activity.display?.displayId ?: return
        val revoked = synchronized(ClusterBindingLifecycle.lock) {
            if (!ClusterBindingState.isManagerCurrent(ClusterBindingRegistry.ManagerLease(generation))) return
            if (receipt?.let { it.generation == generation && it.activity.get() === activity && it.displayId == actual } == true) return
            val old = receipt
            receipt = Receipt(generation, activity, actual)
            old?.onRevoked.also { old?.onRevoked = null; old?.owner = null }
        }
        revoked?.let { clusterLifecycleEffect(it) }
    }
    fun current(generation: Long): Receipt? = synchronized(ClusterBindingLifecycle.lock) {
        receipt?.takeIf { it.generation == generation && isCurrent(it) }
    }
    fun isCurrent(exact: Receipt): Boolean = synchronized(ClusterBindingLifecycle.lock) {
        if (receipt !== exact || !ClusterBindingState.isManagerCurrent(ClusterBindingRegistry.ManagerLease(exact.generation))) return@synchronized false
        val activity = exact.activity.get() ?: return@synchronized false
        !activity.isFinishing && !activity.isDestroyed && activity.display?.displayId == exact.displayId
    }
    fun borrow(exact: Receipt, owner: Any, onRevoked: () -> Unit): Boolean = synchronized(ClusterBindingLifecycle.lock) {
        if (!isCurrent(exact)) false else {
            exact.owner = owner; exact.onRevoked = onRevoked; true
        }
    }
    fun release(exact: Receipt?, owner: Any) = synchronized(ClusterBindingLifecycle.lock) {
        if (exact?.owner === owner) { exact.owner = null; exact.onRevoked = null }
    }
    fun destroy(activity: Activity) {
        val revoked = synchronized(ClusterBindingLifecycle.lock) {
            val exact = receipt?.takeIf { it.activity.get() === activity } ?: return
            receipt = null
            exact.onRevoked.also { exact.onRevoked = null; exact.owner = null }
        }
        revoked?.let { clusterLifecycleEffect(it) }
    }
}

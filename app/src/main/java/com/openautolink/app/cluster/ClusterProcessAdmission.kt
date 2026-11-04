package com.openautolink.app.cluster

/**
 * One AndroidX owner (map, guidance or preview). Direct video has a separate exact slot only
 * when its frozen user choice explicitly allows native guidance alongside it. Direct video
 * never lends its Surface or permits a second AndroidX map/preview owner.
 * All operations share the binding lifecycle lock; stale releases cannot clear successors.
 */
internal object ClusterProcessAdmission {
    class Claim internal constructor(val owner: Any, internal val nativeAlongsideDirect: Boolean = false)
    private var current: Claim? = null
    private var direct: Claim? = null

    fun acquire(owner: Any): Claim? = synchronized(ClusterBindingLifecycle.lock) {
        if (current != null || direct != null) null else Claim(owner).also { current = it }
    }
    fun acquireGuidance(owner: Any): Claim? = synchronized(ClusterBindingLifecycle.lock) {
        if (current != null || direct?.nativeAlongsideDirect == false) null
        else Claim(owner).also { current = it }
    }
    /** Direct preparation runs before guidance admission; never inherit a predecessor's host. */
    fun acquireDirect(owner: Any, allowNativeGuidance: Boolean): Claim? = synchronized(ClusterBindingLifecycle.lock) {
        if (current != null || direct != null) null
        else Claim(owner, allowNativeGuidance).also { direct = it }
    }
    fun owns(claim: Claim): Boolean = synchronized(ClusterBindingLifecycle.lock) { current === claim || direct === claim }
    fun exclusive(claim: Claim): Boolean = synchronized(ClusterBindingLifecycle.lock) { current === claim && direct == null }
    fun release(claim: Claim): Boolean = synchronized(ClusterBindingLifecycle.lock) {
        when {
            current === claim -> { current = null; true }
            direct === claim -> { direct = null; true }
            else -> false
        }
    }
}

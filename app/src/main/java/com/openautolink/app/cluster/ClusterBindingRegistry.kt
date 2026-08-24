package com.openautolink.app.cluster

internal const val CLUSTER_BINDING_GENERATION_EXTRA =
    "com.openautolink.app.cluster.BINDING_GENERATION"

/** Serializes manager transitions with AndroidX cluster session initialization. */
internal object ClusterBindingLifecycle {
    val lock = Any()
}

/**
 * Generation-owned lifecycle registry for the cluster host binding.
 *
 * AndroidX may unbind [androidx.car.app.activity.CarAppActivity] without delivering the
 * cluster Session's lifecycle destroy callback. Manager generations therefore own liveness;
 * a session callback can only affect the generation under which it registered.
 */
internal class ClusterBindingRegistry {

    class ManagerLease internal constructor(val generation: Long)

    class SessionLease internal constructor(
        val generation: Long,
        val sessionId: Long,
    )

    private var nextGeneration = 0L
    private var nextSessionId = 0L
    private var activeGeneration: Long? = null
    private val liveSessionIds = linkedSetOf<Long>()
    private var primarySessionId: Long? = null

    @Synchronized
    fun openManager(): ManagerLease {
        val generation = ++nextGeneration
        activeGeneration = generation
        liveSessionIds.clear()
        primarySessionId = null
        return ManagerLease(generation)
    }

    @Synchronized
    fun closeManager(lease: ManagerLease): Boolean {
        if (activeGeneration != lease.generation) return false
        activeGeneration = null
        liveSessionIds.clear()
        primarySessionId = null
        return true
    }

    @Synchronized
    fun registerSession(expectedGeneration: Long? = null): SessionLease? {
        val generation = activeGeneration ?: return null
        if (expectedGeneration != null && expectedGeneration != generation) return null
        val sessionId = ++nextSessionId
        liveSessionIds += sessionId
        return SessionLease(generation, sessionId)
    }

    @Synchronized
    fun markPrimary(lease: SessionLease): Boolean {
        if (activeGeneration != lease.generation || lease.sessionId !in liveSessionIds) {
            return false
        }
        primarySessionId = lease.sessionId
        return true
    }

    @Synchronized
    fun unregisterSession(lease: SessionLease) {
        if (activeGeneration != lease.generation) return
        liveSessionIds -= lease.sessionId
        if (primarySessionId == lease.sessionId) primarySessionId = null
    }

    @Synchronized
    fun isManagerCurrent(lease: ManagerLease): Boolean =
        activeGeneration == lease.generation

    @Synchronized
    fun hasLiveSession(lease: ManagerLease): Boolean =
        activeGeneration == lease.generation && primarySessionId in liveSessionIds

    @Synchronized
    fun isSessionCurrent(lease: SessionLease): Boolean =
        activeGeneration == lease.generation && lease.sessionId in liveSessionIds
}

/** Identity-safe primary-owner slot scoped by a cluster binding generation. */
internal class GenerationOwnedSlot<T : Any> {
    class Claim<T : Any>(
        val accepted: Boolean,
        val displaced: T?,
    )

    private var generation: Long? = null
    private var owner: T? = null

    @Synchronized
    fun claim(candidateGeneration: Long, candidate: T): Claim<T> {
        val currentGeneration = generation
        if (currentGeneration == null || candidateGeneration > currentGeneration) {
            val displaced = owner
            generation = candidateGeneration
            owner = candidate
            return Claim(accepted = true, displaced = displaced)
        }
        if (candidateGeneration == currentGeneration && owner === candidate) {
            return Claim(accepted = true, displaced = null)
        }
        return Claim(accepted = false, displaced = null)
    }

    @Synchronized
    fun release(candidateGeneration: Long, candidate: T): Boolean {
        if (generation != candidateGeneration || owner !== candidate) return false
        generation = null
        owner = null
        return true
    }

    @Synchronized
    fun invalidate(candidateGeneration: Long): T? {
        if (generation != candidateGeneration) return null
        val displaced = owner
        generation = null
        owner = null
        return displaced
    }

    @Synchronized
    fun isOwner(candidateGeneration: Long, candidate: T): Boolean =
        generation == candidateGeneration && owner === candidate

    @Synchronized
    fun currentOwner(): T? = owner
}

/** Process-wide facade used by ClusterManager and AndroidX cluster Sessions. */
internal object ClusterBindingState {
    private val registry = ClusterBindingRegistry()

    fun openManager(): ClusterBindingRegistry.ManagerLease = registry.openManager()

    fun closeManager(lease: ClusterBindingRegistry.ManagerLease): Boolean =
        registry.closeManager(lease)

    fun registerSession(expectedGeneration: Long? = null): ClusterBindingRegistry.SessionLease? =
        registry.registerSession(expectedGeneration)

    fun markPrimary(lease: ClusterBindingRegistry.SessionLease): Boolean =
        registry.markPrimary(lease)

    fun unregisterSession(lease: ClusterBindingRegistry.SessionLease) =
        registry.unregisterSession(lease)

    fun isManagerCurrent(lease: ClusterBindingRegistry.ManagerLease): Boolean =
        registry.isManagerCurrent(lease)

    fun hasLiveSession(lease: ClusterBindingRegistry.ManagerLease): Boolean =
        registry.hasLiveSession(lease)

    fun isSessionCurrent(lease: ClusterBindingRegistry.SessionLease): Boolean =
        registry.isSessionCurrent(lease)
}

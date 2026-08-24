package com.openautolink.app.cluster

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterBindingRegistryTest {

    @Test
    fun closingManagerInvalidatesSessionEvenWhenHostNeverDestroysIt() {
        val registry = ClusterBindingRegistry()
        val manager = registry.openManager()
        val session = registry.registerSession()

        assertNotNull(session)
        registry.markPrimary(session!!)
        assertTrue(registry.hasLiveSession(manager))

        assertTrue(registry.closeManager(manager))

        assertFalse(registry.hasLiveSession(manager))
        assertFalse(registry.isSessionCurrent(session))
        assertNull(registry.registerSession())
    }

    @Test
    fun staleSessionDestroyCannotClearReplacementGeneration() {
        val registry = ClusterBindingRegistry()
        val oldManager = registry.openManager()
        val oldSession = registry.registerSession()!!
        val newManager = registry.openManager()
        val newSession = registry.registerSession()!!
        registry.markPrimary(newSession)

        registry.unregisterSession(oldSession)
        assertFalse(registry.closeManager(oldManager))

        assertTrue(registry.isManagerCurrent(newManager))
        assertTrue(registry.isSessionCurrent(newSession))
        assertTrue(registry.hasLiveSession(newManager))
    }

    @Test
    fun destroyingOneOfMultipleCurrentSessionsLeavesGenerationAlive() {
        val registry = ClusterBindingRegistry()
        val manager = registry.openManager()
        val primary = registry.registerSession()!!
        val secondary = registry.registerSession()!!
        registry.markPrimary(primary)

        registry.unregisterSession(secondary)

        assertTrue(registry.isSessionCurrent(primary))
        assertTrue(registry.hasLiveSession(manager))

        registry.unregisterSession(primary)

        assertFalse(registry.hasLiveSession(manager))
    }

    @Test
    fun passiveSecondaryCannotKeepBindingAliveAfterPrimaryDies() {
        val registry = ClusterBindingRegistry()
        val manager = registry.openManager()
        val primary = registry.registerSession()!!
        val secondary = registry.registerSession()!!
        registry.markPrimary(primary)

        registry.unregisterSession(primary)

        assertTrue(registry.isSessionCurrent(secondary))
        assertFalse(registry.hasLiveSession(manager))
    }

    @Test
    fun lifecycleLockSerializesSessionInitializationAgainstRetirement() {
        val registry = ClusterBindingRegistry()
        val manager = registry.openManager()
        val initializationEntered = CountDownLatch(1)
        val allowInitializationToFinish = CountDownLatch(1)
        val retirementFinished = CountDownLatch(1)
        val retired = AtomicBoolean(false)

        val initialization = thread {
            synchronized(ClusterBindingLifecycle.lock) {
                val session = registry.registerSession(manager.generation)!!
                registry.markPrimary(session)
                initializationEntered.countDown()
                assertTrue(allowInitializationToFinish.await(2, TimeUnit.SECONDS))
            }
        }
        assertTrue(initializationEntered.await(2, TimeUnit.SECONDS))

        val retirement = thread {
            synchronized(ClusterBindingLifecycle.lock) {
                retired.set(registry.closeManager(manager))
            }
            retirementFinished.countDown()
        }

        assertFalse(retirementFinished.await(100, TimeUnit.MILLISECONDS))
        assertFalse(retired.get())
        allowInitializationToFinish.countDown()
        initialization.join(2_000)
        retirement.join(2_000)

        assertTrue(retirementFinished.await(0, TimeUnit.MILLISECONDS))
        assertTrue(retired.get())
    }

    @Test
    fun openingReplacementGenerationImmediatelyRejectsOldLiveness() {
        val registry = ClusterBindingRegistry()
        val oldManager = registry.openManager()
        val oldSession = registry.registerSession()!!

        val replacement = registry.openManager()

        assertFalse(registry.isManagerCurrent(oldManager))
        assertFalse(registry.isSessionCurrent(oldSession))
        assertFalse(registry.hasLiveSession(replacement))
    }

    @Test
    fun staleActivityGenerationCannotRegisterIntoReplacementManager() {
        val registry = ClusterBindingRegistry()
        val oldManager = registry.openManager()
        val replacement = registry.openManager()

        assertNull(registry.registerSession(oldManager.generation))
        assertNotNull(registry.registerSession(replacement.generation))
    }

    @Test
    fun newerGenerationDisplacesStalePrimaryOwner() {
        val slot = GenerationOwnedSlot<Any>()
        val oldPrimary = Any()
        val newPrimary = Any()

        assertTrue(slot.claim(1, oldPrimary).accepted)
        val replacement = slot.claim(2, newPrimary)

        assertTrue(replacement.accepted)
        assertTrue(replacement.displaced === oldPrimary)
        assertTrue(slot.isOwner(2, newPrimary))
        assertFalse(slot.isOwner(1, oldPrimary))
    }

    @Test
    fun lateOldPrimaryReleaseCannotClearNewOwner() {
        val slot = GenerationOwnedSlot<Any>()
        val oldPrimary = Any()
        val newPrimary = Any()
        slot.claim(1, oldPrimary)
        slot.claim(2, newPrimary)

        assertFalse(slot.release(1, oldPrimary))
        assertTrue(slot.isOwner(2, newPrimary))
    }

    @Test
    fun secondSessionInSameGenerationRemainsPassive() {
        val slot = GenerationOwnedSlot<Any>()
        val primary = Any()
        val secondary = Any()
        slot.claim(4, primary)

        val claim = slot.claim(4, secondary)

        assertFalse(claim.accepted)
        assertNull(claim.displaced)
        assertTrue(slot.isOwner(4, primary))
    }
}

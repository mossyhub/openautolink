package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayOwnershipTest {
    @Test fun disabledNeverRetiresOrBootstraps() {
        val owner = CarPlayOwnership()
        assertNull(owner.reserve(enabled = false))
        assertFalse(owner.blocksAndroidAuto)
    }

    @Test fun retirementMustCompleteBeforeBootstrapAndBlocksSuccessorAA() {
        val owner = CarPlayOwnership()
        val token = owner.reserve(true)!!
        assertTrue(owner.blocksAndroidAuto)
        assertFalse(owner.canBootstrap(token))
        owner.acknowledgeAaRetired(token)
        assertTrue(owner.canBootstrap(token))
        assertNull(owner.reserve(true))
    }

    @Test fun canceledRetirementCannotAuthorizeBootstrap() {
        val owner = CarPlayOwnership()
        val token = owner.reserve(true)!!
        owner.cancel(token)
        owner.acknowledgeAaRetired(token)
        assertFalse(owner.canBootstrap(token))
        assertTrue(owner.blocksAndroidAuto)
        assertFalse(owner.release(token)) // No receiver/control retirement acknowledgment yet.
        owner.acknowledgeCarPlayRetired(token)
        assertTrue(owner.release(token))
        assertFalse(owner.blocksAndroidAuto)
    }

    @Test fun staleAcknowledgmentsCannotReleaseSuccessor() {
        val owner = CarPlayOwnership()
        val a = owner.reserve(true)!!
        owner.acknowledgeAaRetired(a)
        owner.cancel(a)
        owner.acknowledgeCarPlayRetired(a)
        assertTrue(owner.release(a))
        val b = owner.reserve(true)!!
        owner.acknowledgeAaRetired(a)
        owner.acknowledgeCarPlayRetired(a)
        assertFalse(owner.release(a))
        assertFalse(owner.canBootstrap(b))
        assertTrue(owner.blocksAndroidAuto)
    }
}

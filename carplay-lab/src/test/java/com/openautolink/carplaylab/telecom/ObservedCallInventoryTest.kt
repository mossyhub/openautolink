package com.openautolink.carplaylab.telecom

import org.junit.Assert.*
import org.junit.Test

/** Offline semantic fixtures only: GM CommunicationManager.java:611-680 and
 * CarPlayConnection.java:55-59. NOT captured packets or a claim of a wire schema. */
class ObservedCallInventoryTest {
    @Test fun expiryRevokesWaitingObservationWithoutClaimingRemoteHangup() {
        val calls = ObservedCallInventory(2, 100)
        val session = calls.beginSession()
        calls.update(session, "offline-a", RemoteCallState.ACTIVE, 0)
        calls.update(session, "offline-b", RemoteCallState.RINGING, 10)
        calls.update(session, "offline-a", RemoteCallState.ACTIVE, 90)
        assertTrue(calls.expire(session, 109).isEmpty())
        assertEquals(setOf("offline-b"), calls.expire(session, 110))
        assertEquals(mapOf("offline-a" to RemoteCallState.ACTIVE), calls.snapshot(session))
        assertEquals(setOf("offline-a"), calls.transportLost(session))
        assertTrue(calls.snapshot(session).isEmpty())
        assertFalse(calls.update(session, "offline-a", RemoteCallState.ACTIVE, 111))
        val next = calls.beginSession()
        calls.update(next, "offline-new", RemoteCallState.HELD, 0)
        assertTrue(calls.transportLost(session).isEmpty())
        assertTrue(calls.expire(session, 999).isEmpty())
        assertEquals(mapOf("offline-new" to RemoteCallState.HELD), calls.snapshot(next))
    }

    @Test fun boundedInventoryKeepsWaitingAndHeldCallsDistinctUntilRemoteEnd() {
        val calls = ObservedCallInventory(2, 100)
        val session = calls.beginSession()
        assertTrue(calls.update(session, "offline-a", RemoteCallState.ACTIVE, 0))
        assertTrue(calls.update(session, "offline-b", RemoteCallState.RINGING, 1))
        assertFalse(calls.update(session, "offline-c", RemoteCallState.RINGING, 2))
        assertTrue(calls.update(session, "offline-a", RemoteCallState.HELD, 3))
        assertEquals(RemoteCallState.HELD, calls.snapshot(session)["offline-a"])
        assertEquals(RemoteCallState.RINGING, calls.snapshot(session)["offline-b"])
        assertTrue(calls.update(session, "offline-b", RemoteCallState.ENDED, 4))
        assertEquals(mapOf("offline-a" to RemoteCallState.HELD), calls.snapshot(session))
        assertFalse(calls.update(session, "", RemoteCallState.ACTIVE, 5))
        assertFalse(calls.update(session, "x".repeat(257), RemoteCallState.ACTIVE, 5))
        assertFalse(calls.update(session, "offline-a", RemoteCallState.ACTIVE, 2))
    }

    @Test fun replacementSessionCannotReceiveOldSessionUpdates() {
        val calls = ObservedCallInventory(2, 100)
        val old = calls.beginSession()
        calls.update(old, "offline-a", RemoteCallState.ACTIVE, 0)
        val current = calls.beginSession()
        assertFalse(calls.update(old, "offline-a", RemoteCallState.RINGING, 1))
        assertTrue(calls.snapshot(current).isEmpty())
        assertTrue(calls.snapshot(old).isEmpty())
    }

    @Test fun authoritativeRingingCreatesOnePrivateSessionOwnedRecord() {
        val calls = ObservedCallInventory(2, 100)
        val session = calls.beginSession()
        assertTrue(calls.update(session, "offline-a", RemoteCallState.RINGING, 0))
        assertEquals(mapOf("offline-a" to RemoteCallState.RINGING), calls.snapshot(session))
    }
}

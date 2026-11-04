package com.openautolink.app.secondary

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture

class SecondaryOutputReservationTest {
    private class Owner(val id: String) {
        var revoked = false
        val detached = CompletableFuture<Boolean>()
        fun revoke() { revoked = true }
    }
    @Test fun cancellingPreparationRevokesExactLateOwnerWithoutInstallingIt() {
        val reservation = SecondaryOutputReservation<Owner>({ it.revoke() }, { it.detached })
        val first = Owner("A")
        assertTrue(reservation.install(first))
        val retirement = reservation.retire()
        assertTrue(first.revoked)
        val late = Owner("B")
        assertFalse(reservation.install(late))
        assertTrue(late.revoked)
        first.detached.complete(true)
        assertTrue(retirement.get())
        assertTrue(reservation.snapshot().isEmpty())
    }
    @Test fun negativeDebtDoesNotPreventIndependentOwnersBeingRevoked() {
        val reservation = SecondaryOutputReservation<Owner>({ it.revoke() }, { it.detached })
        val cluster = Owner("GM"); val aux = Owner("aux")
        reservation.install(cluster); reservation.install(aux)
        val retirement = reservation.retire()
        assertTrue(cluster.revoked); assertTrue(aux.revoked)
        aux.detached.complete(true)
        assertFalse(retirement.isDone)
        cluster.detached.complete(false)
        assertFalse(retirement.get())
        assertEquals(listOf(cluster), reservation.snapshot())
    }
}

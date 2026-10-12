package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MetadataHostOwnershipTest {
    private fun gate(): Any {
        val type = runCatching { Class.forName("com.openautolink.app.carplay.MetadataHostOwnership") }.getOrNull()
        assertNotNull("A shared host mutation gate must fence AA and exact CP retirement", type)
        return type!!.getDeclaredConstructor().newInstance()
    }
    @Test fun staleRetirementCannotClearSuccessor() {
        val gate = gate()
        val type = gate.javaClass
        val first = Any(); val second = Any()
        assertEquals(true, type.getMethod("claim", Any::class.java).invoke(gate, first))
        assertEquals(false, type.getMethod("claim", Any::class.java).invoke(gate, second))
        var clears = 0
        val retire = type.getMethod("retire", Any::class.java, kotlin.jvm.functions.Function0::class.java)
        retire.invoke(gate, first, { clears++ })
        assertEquals(true, type.getMethod("claim", Any::class.java).invoke(gate, second))
        retire.invoke(gate, first, { clears++ })
        assertEquals(1, clears)
    }
    @Test fun legacyEffectsAreRejectedUntilAwaitedRetirementCompletes() {
        val gate = gate(); val owner = Any(); val type = gate.javaClass
        type.getMethod("claim", Any::class.java).invoke(gate, owner)
        var aaWrites = 0
        val legacy = type.getMethod("legacy", kotlin.jvm.functions.Function0::class.java)
        legacy.invoke(gate, { aaWrites++ })
        assertEquals(0, aaWrites)
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
        val thread = Thread { type.getMethod("retire", Any::class.java, kotlin.jvm.functions.Function0::class.java)
            .invoke(gate, owner, { entered.countDown(); unblock.await(2, TimeUnit.SECONDS) }) }
        thread.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
        val returned = CountDownLatch(1)
        val aa = Thread { legacy.invoke(gate, { aaWrites++ }); returned.countDown() }
        aa.start(); assertFalse(returned.await(100, TimeUnit.MILLISECONDS))
        unblock.countDown(); thread.join(2000); aa.join(2000)
        assertEquals(1, aaWrites)
    }
}

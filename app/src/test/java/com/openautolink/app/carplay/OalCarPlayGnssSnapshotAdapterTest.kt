package com.openautolink.app.carplay

import com.openautolink.app.input.GnssForwarder
import com.openautolink.app.transport.ControlMessage
import org.junit.Assert.*
import org.junit.Test

class OalCarPlayGnssSnapshotAdapterTest {
    @Test fun failedHostStopStillPermanentlyRetiresAdapter() {
        var starts=0
        val adapter = OalCarPlayGnssSnapshotAdapter({1000}) {
            object : GnssForwarder {
                override val isActive = true
                override fun start() { starts++ }
                override fun stop() { throw IllegalStateException("host failure") }
            }
        }
        adapter.setActive(true)
        runCatching {adapter.close()}
        assertTrue(runCatching {adapter.setActive(true)}.isSuccess)
        assertEquals(1,starts)
        assertNull(adapter.latest(setOf(1)))
    }

    @Test fun reusesHostSourceOnlyDuringRequestAndRejectsStoppedGenerationCallbacks() {
        val sinks = mutableListOf<(ControlMessage.Gnss) -> Unit>()
        var starts=0; var stops=0
        val adapter = OalCarPlayGnssSnapshotAdapter({1000}) { sink ->
            sinks += sink
            object : GnssForwarder {
                override var isActive = false
                override fun start() { starts++; isActive=true }
                override fun stop() { stops++; isActive=false }
            }
        }
        val body="GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
        val sentence="$" + body + "*" + "%02X".format(body.fold(0) { a,c -> a xor c.code })
        assertNull(adapter.latest(setOf(1)))
        adapter.setActive(true); adapter.setActive(true)
        assertEquals(1,starts)
        sinks[0](ControlMessage.Gnss(sentence))
        assertEquals(sentence+"\r\n",adapter.latest(setOf(1)))
        adapter.setActive(false); assertEquals(1,stops)
        sinks[0](ControlMessage.Gnss(sentence)); assertNull(adapter.latest(setOf(1)))
        adapter.setActive(true); sinks[0](ControlMessage.Gnss(sentence))
        assertNull(adapter.latest(setOf(1)))
        sinks[1](ControlMessage.Gnss(sentence)); assertNotNull(adapter.latest(setOf(1)))
        adapter.close(); adapter.setActive(true)
        assertEquals(2,starts); assertNull(adapter.latest(setOf(1)))
    }
}

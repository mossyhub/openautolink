// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.app.carplay

import com.openautolink.carplaylab.media.ReceiverPorts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class CarHotspotPortsTest {
    @After fun tearDown() { ReceiverPorts.plan = null; ReceiverPorts.onStatus = {} }

    @Test fun autoMeansIpv4OnTheCarHotspotOnly() {
        assertEquals("ipv4", CarHotspotPorts.family("auto", sharedWifi = false))
        assertEquals("ipv6", CarHotspotPorts.family("ipv6", sharedWifi = false))
        assertEquals("auto", CarHotspotPorts.family("auto", sharedWifi = true))
    }

    @Test fun poolsAreAppPortsAndNeverRepeatTheControlPort() {
        for (pool in listOf(CarHotspotPorts.POOL_IPV4, CarHotspotPorts.POOL_IPV6, CarHotspotPorts.AUDIO_IPV4)) {
            assertTrue(pool.all { it > 1024 })
            assertTrue(CarHotspotPorts.CONTROL !in pool)
        }
    }

    @Test fun theCarHotspotGetsAPlanWithBluetoothAudioAndSharedWifiGetsNone() {
        val lines = mutableListOf<String>()
        val local = InetAddress.getByName("192.168.1.1")
        CarHotspotPorts.apply(local, sharedWifi = false) { lines += it }
        val plan = checkNotNull(ReceiverPorts.plan)
        assertEquals(CarHotspotPorts.CONTROL, plan.control)
        assertEquals(CarHotspotPorts.AUDIO_IPV4, plan.audio)
        assertTrue(plan.audioOverBluetooth)
        assertEquals(CarHotspotPorts.POOL_IPV6, CarHotspotPorts.plan(InetAddress.getByName("fe80::1")).pool)
        CarHotspotPorts.apply(local, sharedWifi = true) { lines += it }
        assertNull(ReceiverPorts.plan)
        assertTrue(lines.none { "192.168" in it })
    }
}

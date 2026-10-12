package com.openautolink.carplaylab.network

import java.net.InetAddress
import java.net.Inet6Address
import org.junit.Assert.*
import org.junit.Test

class CarPlayNetworkPolicyTest {
    private fun v6(ip: String, scope: Int = 0) = Inet6Address.getByAddress(null, InetAddress.getByName(ip).address, scope)
    @Test fun unavailableDownAndVpnInterfacesFailClosed() {
        val good = CarPlayInterface("ap_br_swlan0", 7, true, false, listOf(v6("fe80::1", 7)))
        for (inventory in listOf(emptyList(), listOf(good.copy(up = false)), listOf(good.copy(pointToPoint = true)),
            listOf(good.copy(index = 0)), listOf(good.copy(name = "wlan0")))) {
            assertThrows(IllegalArgumentException::class.java) { CarPlayNetworkPolicy.select(CarPlayNetworkConfig(), inventory) }
        }
    }
    @Test fun overridesCannotEscapeSelectedInterfaceOrFamily() {
        val nic = CarPlayInterface("ap_br_swlan0", 7, true, false, listOf(v6("fe80::1", 7), InetAddress.getByName("192.0.2.1")))
        for (config in listOf(CarPlayNetworkConfig(localAddress = "fe80::2"),
            CarPlayNetworkConfig(localAddress = "fe80::1%8"), CarPlayNetworkConfig(peerAddress = "fe80::2%0"), CarPlayNetworkConfig(family = "bogus"),
            CarPlayNetworkConfig(family = "ipv6", localAddress = "192.0.2.1"),
            CarPlayNetworkConfig(peerAddress = "fe80::2%8"), CarPlayNetworkConfig(peerAddress = "example.com"),
            CarPlayNetworkConfig(peerAddress = "192.0.2.2"))) {
            assertThrows(IllegalArgumentException::class.java) { CarPlayNetworkPolicy.select(config, listOf(nic)) }
        }
        val selected = CarPlayNetworkPolicy.select(CarPlayNetworkConfig(peerAddress = "fe80::2"), listOf(nic))
        assertEquals(7, (selected.manualPeer as Inet6Address).scopeId)
        val v4 = CarPlayNetworkPolicy.select(CarPlayNetworkConfig(sharedWifi = true, peerAddress = "192.0.2.2"), listOf(nic))
        assertEquals("192.0.2.1", v4.local.hostAddress)
        assertEquals("192.0.2.2", v4.manualPeer?.hostAddress)
        assertThrows(IllegalArgumentException::class.java) { CarPlayNetworkPolicy.select(CarPlayNetworkConfig(sharedWifi = true), listOf(nic)) }
    }
    @Test fun explicitLocalScopeNameMustBeTheConfiguredInterface() {
        val nic = CarPlayInterface("ap_br_swlan0", 7, true, false, listOf(v6("fe80::1", 7)))
        val selected = CarPlayNetworkPolicy.select(CarPlayNetworkConfig(
            localAddress = "fe80::1%ap_br_swlan0", peerAddress = "fe80::2%ap_br_swlan0"), listOf(nic))
        assertEquals(7, (selected.manualPeer as Inet6Address).scopeId)
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayNetworkPolicy.select(CarPlayNetworkConfig(peerAddress = "fe80::2%wlan0"), listOf(nic))
        }
    }
    @Test fun apAutoSelectsOnlyOwnedLinkLocalAndAdvertisesWithoutScope() {
        val nic = CarPlayInterface("ap_br_swlan0", 7, true, false,
            listOf(InetAddress.getByName("192.0.2.1"), v6("fe80::1", 7)))
        val selected = CarPlayNetworkPolicy.select(CarPlayNetworkConfig(), listOf(nic))
        assertEquals(v6("fe80::1", 7).hostAddress, selected.local.hostAddress)
        assertEquals(7, (selected.local as Inet6Address).scopeId)
        assertEquals(listOf("fe80:0:0:0:0:0:0:1"), selected.advertisedAddresses)
        assertNull(selected.manualPeer)
    }
}

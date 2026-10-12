// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.network

import java.net.InetAddress
import java.net.Inet6Address

/** Non-secret next-session policy. No network creation, DNS, scanning or default route fallback. */
data class CarPlayNetworkConfig(
    val sharedWifi: Boolean = false,
    val family: String = "auto",
    val interfaceName: String = "ap_br_swlan0",
    val localAddress: String = "",
    val peerAddress: String = "",
)
data class CarPlayInterface(val name: String, val index: Int, val up: Boolean,
    val pointToPoint: Boolean, val addresses: List<InetAddress>)
data class CarPlayNetworkSelection(val local: InetAddress, val interfaceIndex: Int,
    val manualPeer: InetAddress?) {
    val advertisedAddresses: List<String> get() = listOf(checkNotNull(local.hostAddress).substringBefore('%'))
}
object CarPlayNetworkPolicy {
    fun select(config: CarPlayNetworkConfig, interfaces: List<CarPlayInterface>): CarPlayNetworkSelection {
        val nic = interfaces.singleOrNull { it.name == config.interfaceName }
        require(nic != null && nic.up && !nic.pointToPoint && nic.index > 0) { "Selected interface unavailable, down or unsupported" }
        require(!nic.name.startsWith("tun") && !nic.name.startsWith("tap") && !nic.name.startsWith("ppp")) { "VPN interface forbidden" }
        require(config.family in setOf("auto", "ipv4", "ipv6")) { "Unsupported network family" }
        val ipv6 = config.family == "ipv6" || config.family == "auto" && !config.sharedWifi
        fun parseOnSelectedInterface(value: String): InetAddress {
            val scope = value.substringAfter('%', "")
            require('%' !in value || scope == nic.name || scope.toIntOrNull() == nic.index) { "Explicit IPv6 scope differs from selected interface" }
            // A named zone is valid only when it is literally the selected interface, never a DNS name.
            val numeric = if (scope == nic.name) value.substringBefore('%') + "%${nic.index}" else value
            return numericAddress(numeric)
        }
        fun scoped(value: InetAddress): InetAddress {
            require((value is Inet6Address) == ipv6) { "Selected address family mismatch" }
            require(!value.isAnyLocalAddress && !value.isLoopbackAddress && !value.isMulticastAddress) { "Unicast endpoint required" }
            if (value is Inet6Address) {
                require(value.isLinkLocalAddress) { "AP IPv6 must be link-local" }
                require(value.scopeId == 0 || value.scopeId == nic.index) { "IPv6 scope does not match selected interface" }
                return Inet6Address.getByAddress(null, value.address, nic.index)
            }
            require(!value.isLinkLocalAddress) { "Usable IPv4 endpoint required" }
            return value
        }
        val candidates = nic.addresses.filter { (it is Inet6Address) == ipv6 && !it.isLoopbackAddress &&
            !it.isAnyLocalAddress && !it.isMulticastAddress && (if (ipv6) it.isLinkLocalAddress else !it.isLinkLocalAddress) }
        val address = if (config.localAddress.isEmpty()) candidates.minByOrNull { checkNotNull(it.hostAddress).substringBefore('%') }
        else scoped(parseOnSelectedInterface(config.localAddress)).let { requested -> candidates.firstOrNull { it.address.contentEquals(requested.address) } }
        require(address != null) { "Selected interface does not own a usable requested-family address" }
        val local = scoped(address)
        require(!config.sharedWifi || config.peerAddress.isNotEmpty()) { "Shared Wi-Fi requires explicit numeric phone IP" }
        val peer = config.peerAddress.takeIf { it.isNotEmpty() }?.let { scoped(parseOnSelectedInterface(it)) }
        require(peer == null || !peer.address.contentEquals(local.address)) { "Phone IP cannot equal receiver IP" }
        return CarPlayNetworkSelection(local, nic.index, peer)
    }
    /** A strict numeric grammar prevents getByName DNS lookup. Scope is local numeric index only. */
    fun numericAddress(value: String): InetAddress {
        require(value.length in 1..128 && value.none { it.isWhitespace() }) { "Numeric endpoint required" }
        val base = value.substringBefore('%')
        if (':' in base) {
            require(base.matches(Regex("[0-9a-fA-F:.]+")) &&
                ('%' !in value || value.substringAfter('%').matches(Regex("[0-9]{1,10}")))) { "Numeric IPv6 address and scope required" }
            return try { InetAddress.getByName(value) } catch (_: Exception) { throw IllegalArgumentException("Invalid numeric IPv6 endpoint") }
        }
        require('%' !in value && base.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) { "Numeric IPv4 endpoint required" }
        val octets = base.split('.').map(String::toInt)
        require(octets.all { it in 0..255 }) { "Invalid IPv4 endpoint" }
        return InetAddress.getByAddress(octets.map(Int::toByte).toByteArray())
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.pairing

import java.net.InetAddress

/** Numeric IP allowlist, not a claim that IP ownership proves a physical phone identity. */
internal class PeerPolicy {
    @Volatile private var allowed = emptySet<String>()
    fun set(addresses: Set<String>) {
        require(addresses.size <= 16) { "Peer address limit" }
        val next = addresses.map { value ->
            val address = value.substringBefore('%')
            require(address.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) ||
                (':' in address && address.matches(Regex("[0-9a-fA-F:.]+")))) { "Numeric peer address required" }
            require(value.length <= 128 && value.none { it.isWhitespace() })
            val parsed = InetAddress.getByName(value)
            require(!parsed.isAnyLocalAddress && !parsed.isMulticastAddress)
            key(parsed)
        }.toSet()
        allowed = next
    }
    fun allows(address: InetAddress): Boolean = key(address) in allowed
    private fun key(address: InetAddress): String = address.address.joinToString(":") { (it.toInt() and 255).toString(16) }
}

package com.openautolink.app.data

import java.net.Inet6Address
import java.net.InetAddress

/** Android's bond list is display-only; only the exact saved address is selected. */
data class CarPlayPairedPhone(val address: String, val name: String)

object CarPlaySettingsPolicy {
    /** Pure immutable mapping; the runtime still owns real interface and persisted-trust admission. */
    fun runtimeConfig(settings: CarPlaySettings, display: com.openautolink.carplaylab.media.CarPlayDisplayPreferences):
        com.openautolink.app.carplay.CarPlayRuntimeConfig {
        require(settings.enabled) { "CarPlay disabled before bootstrap" }
        return com.openautolink.app.carplay.CarPlayRuntimeConfig(
            settings.phoneAddress, settings.peerAddress, settings.interfaceName, settings.sharedWifiOverride,
            settings.ssid, CarPlayReceiverAddressPolicy.runtimeAddress(settings.receiverAddress), settings.apFrequencyMhz, display,
            networkFamily = settings.networkFamily, apSecurity = settings.apSecurity,
            localAddress = settings.localAddress,
            restrictionPolicy = com.openautolink.app.carplay.CarPlayRestrictionPolicy.from(settings),
        )
    }

    fun selectedPhone(address: String, phones: List<CarPlayPairedPhone>): CarPlayPairedPhone? =
        phones.firstOrNull { address.isNotEmpty() && it.address == address }

    fun requiredAction(settings: CarPlaySettings, phones: List<CarPlayPairedPhone>, identityReady: Boolean,
        passwordSaved: Boolean): String = when {
        !settings.enabled -> "CarPlay is off"
        selectedPhone(settings.phoneAddress, phones) == null -> "Select a paired phone"
        !identityReady -> "Provision authentication identity on this head unit"
        settings.sharedWifiOverride && settings.peerAddress.isEmpty() -> "Enter the selected phone's numeric IP address"
        settings.peerAddress.isNotEmpty() && !configuredEndpoint(settings.peerAddress, settings) ->
            "Enter a numeric phone IP matching the selected family and interface"
        settings.interfaceName.isBlank() -> "Choose the network interface"
        settings.localAddress.isNotEmpty() && !configuredEndpoint(settings.localAddress, settings) ->
            "Enter an exact numeric local address matching the selected family and interface"
        CarPlayReceiverAddressPolicy.canonicalOrNull(settings.receiverAddress).isNullOrEmpty() ->
            "Enter this head unit's real Bluetooth MAC address; not the AP BSSID or phone MAC"
        settings.ssid.isEmpty() -> "Enter the actual Wi-Fi SSID"
        !passwordSaved -> "Save a Wi-Fi passphrase"
        !settings.sharedWifiOverride && settings.peerAddress.isEmpty() ->
            "AP connection requires one persisted trusted controller; fresh provisioning is unavailable here"
        else -> "Ready for an explicit connection; phone acceptance is not verified"
    }

    /** Scope omission is resolved only by runtime against the actual configured interface. */
    fun configuredEndpoint(value: String, settings: CarPlaySettings): Boolean {
        if (!numericPeer(value, requireLinkLocalScope = false)) return false
        val ipv6 = settings.networkFamily == "ipv6" || settings.networkFamily == "auto" && !settings.sharedWifiOverride
        if ((':' in value.substringBefore('%')) != ipv6) return false
        if ('%' in value) {
            val scope = value.substringAfter('%')
            if (scope != settings.interfaceName && (scope.toIntOrNull() ?: 0) <= 0) return false
        }
        val parsed = runCatching { InetAddress.getByName(value.substringBefore('%')) }.getOrNull() ?: return false
        return if (ipv6) parsed is Inet6Address && parsed.isLinkLocalAddress else !parsed.isLinkLocalAddress
    }

    /** Validate numeric IPs without DNS or dropping an IPv6 interface scope. */
    fun numericPeer(value: String, requireLinkLocalScope: Boolean = true): Boolean {
        if (value.isEmpty() || value.length > 128 || value.any { it.isWhitespace() }) return false
        val address = value.substringBefore('%')
        val parsed = if (':' in address) {
            if (!address.matches(Regex("[0-9a-fA-F:.]+"))) return false
            if ('%' in value && !value.substringAfter('%').matches(Regex("[a-zA-Z0-9_.-]+"))) return false
            runCatching { InetAddress.getByName(address) }.getOrNull() as? Inet6Address ?: return false
        } else {
            if ('%' in value || !address.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) return false
            val octets = address.split('.').map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray())
        }
        return !(requireLinkLocalScope && parsed.isLinkLocalAddress && parsed is Inet6Address && '%' !in value) &&
            !parsed.isAnyLocalAddress && !parsed.isLoopbackAddress && !parsed.isMulticastAddress &&
            !(parsed.address.all { (it.toInt() and 255) == 255 })
    }
}

package com.openautolink.companion.ui

internal data class WppVisibleNetwork(
    val ssid: String,
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
) {
    val bandKey: String
        get() = when {
            is5Ghz(frequencyMhz) -> "5ghz"
            is24Ghz(frequencyMhz) -> "2.4ghz"
            else -> "other"
        }

    val bandLabel: String
        get() = when {
            is5Ghz(frequencyMhz) -> "5 GHz"
            is24Ghz(frequencyMhz) -> "2.4 GHz"
            else -> "${frequencyMhz} MHz"
        }

    val channelLabel: String
        get() = wifiChannelLabel(frequencyMhz)
}

private fun is5Ghz(frequencyMhz: Int): Boolean = frequencyMhz in 5000..5999

private fun is24Ghz(frequencyMhz: Int): Boolean = frequencyMhz in 2400..2499

private fun wifiChannelLabel(frequencyMhz: Int): String = when {
    frequencyMhz == 2484 -> "14"
    frequencyMhz in 2412..2472 -> (((frequencyMhz - 2412) / 5) + 1).toString()
    frequencyMhz in 5000..5895 -> ((frequencyMhz - 5000) / 5).toString()
    frequencyMhz > 0 -> "?"
    else -> "-"
}

/** Keep SSIDs verbatim: case and whitespace identify different networks. */
internal fun selectWppNetworks(scans: List<WppVisibleNetwork>): List<WppVisibleNetwork> =
    scans.filter { it.ssid.isNotEmpty() && it.bssid.isNotBlank() }
        .groupBy { it.ssid to it.bandKey }
        .values
        .map { networks ->
            networks.minWithOrNull(
                compareByDescending<WppVisibleNetwork> { it.rssiDbm }
                    .thenBy { it.bssid }
                    .thenBy { it.frequencyMhz }
            )!!
        }
        .sortedWith(
            compareBy<WppVisibleNetwork> { !is5Ghz(it.frequencyMhz) }
                .thenByDescending { it.rssiDbm }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.ssid }
                .thenBy { it.ssid }
                .thenBy { it.bssid }
                .thenBy { it.frequencyMhz }
        )

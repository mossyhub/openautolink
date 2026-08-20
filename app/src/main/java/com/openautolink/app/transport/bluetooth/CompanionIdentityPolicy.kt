package com.openautolink.app.transport.bluetooth

import java.util.Locale

/** Identity returned by the companion's dedicated OAL probe endpoint. */
internal data class CompanionProbe(
    val phoneId: String?,
    val friendlyName: String?,
    val proxyPort: Int,
)

/** Pure parsing and ownership policy for Bluetooth-phone → companion matching. */
internal object CompanionIdentityPolicy {
    private const val PREFIX = "OAL!"

    fun parseProbe(reply: String): CompanionProbe? {
        if (!reply.startsWith(PREFIX)) return null
        val parts = reply.removePrefix(PREFIX).split('\t')
        val port = parts.firstOrNull { it.startsWith("wpp=") }
            ?.removePrefix("wpp=")
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: return null
        return CompanionProbe(
            phoneId = parts.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() },
            friendlyName = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() },
            proxyPort = port,
        )
    }

    fun matches(
        expectedPhoneId: String?,
        bluetoothDeviceName: String?,
        probe: CompanionProbe,
    ): Boolean {
        val expectedId = expectedPhoneId?.trim()?.takeIf { it.isNotEmpty() }
        if (expectedId != null) {
            return expectedId == probe.phoneId
        }
        val btName = normalizeName(bluetoothDeviceName) ?: return false
        val companionName = normalizeName(probe.friendlyName) ?: return false
        return btName == companionName
    }

    private fun normalizeName(value: String?): String? = value
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotEmpty() }
}

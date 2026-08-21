package com.openautolink.app.transport.bluetooth

import java.util.Locale

/** Identity returned by the companion's dedicated OAL probe endpoint. */
internal data class CompanionProbe(
    val phoneId: String?,
    val friendlyName: String?,
    val proxyPort: Int,
    val bluetoothName: String? = null,
    val bluetoothNameReported: Boolean = bluetoothName != null,
)

internal enum class CompanionIdentityMatch {
    PHONE_ID,
    BLUETOOTH_NAME,
    FRIENDLY_NAME,
    NONE,
}

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
        val bluetoothNameField = parts.firstOrNull { it.startsWith("bt_name=") }
        return CompanionProbe(
            phoneId = parts.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() },
            friendlyName = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() },
            proxyPort = port,
            bluetoothName = bluetoothNameField
                ?.removePrefix("bt_name=")
                ?.trim()
                ?.takeIf { it.isNotEmpty() },
            bluetoothNameReported = bluetoothNameField != null,
        )
    }

    fun matches(
        expectedPhoneId: String?,
        bluetoothDeviceName: String?,
        probe: CompanionProbe,
    ): Boolean = matchBasis(expectedPhoneId, bluetoothDeviceName, probe) !=
        CompanionIdentityMatch.NONE

    fun matchBasis(
        expectedPhoneId: String?,
        bluetoothDeviceName: String?,
        probe: CompanionProbe,
    ): CompanionIdentityMatch {
        val expectedId = expectedPhoneId?.trim()?.takeIf { it.isNotEmpty() }
        if (expectedId != null) {
            return if (expectedId == probe.phoneId) {
                CompanionIdentityMatch.PHONE_ID
            } else {
                CompanionIdentityMatch.NONE
            }
        }
        val btName = normalizeName(bluetoothDeviceName)
            ?: return CompanionIdentityMatch.NONE
        if (probe.bluetoothNameReported) {
            val reportedBluetoothName = normalizeName(probe.bluetoothName)
                ?: return CompanionIdentityMatch.NONE
            return if (btName == reportedBluetoothName) {
                CompanionIdentityMatch.BLUETOOTH_NAME
            } else {
                CompanionIdentityMatch.NONE
            }
        }
        val companionName = normalizeName(probe.friendlyName)
            ?: return CompanionIdentityMatch.NONE
        return if (btName == companionName) {
            CompanionIdentityMatch.FRIENDLY_NAME
        } else {
            CompanionIdentityMatch.NONE
        }
    }

    private fun normalizeName(value: String?): String? = value
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotEmpty() }
}

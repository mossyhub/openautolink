package com.openautolink.companion.service

/** Builds the backward-compatible identity reply consumed by the car app. */
internal object CompanionIdentityPayload {
    fun encode(
        phoneId: String,
        friendlyName: String,
        bluetoothName: String?,
        proxyPort: Int,
    ): String {
        require(proxyPort in 1..65535) { "proxyPort must be live" }
        val bluetoothField = "\tbt_name=${wireValue(bluetoothName.orEmpty())}"
        return "OAL!${wireValue(phoneId)}\t${wireValue(friendlyName)}" +
            "\twpp=$proxyPort$bluetoothField\n"
    }

    private fun wireValue(value: String): String = value
        .replace(Regex("[\\t\\r\\n]+"), " ")
        .trim()
}

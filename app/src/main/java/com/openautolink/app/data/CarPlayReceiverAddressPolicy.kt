package com.openautolink.app.data

/** User entry only: validate the entire trimmed token before canonicalizing accepted delimiters. */
object CarPlayReceiverAddressPolicy {
    private val colon = Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}")
    private val hyphen = Regex("[0-9a-fA-F]{2}(-[0-9a-fA-F]{2}){5}")
    private val denied = setOf("00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "02:00:00:00:00:00")

    /** Empty stays optional; null means invalid, never an instruction to fall back. */
    fun canonicalOrNull(input: String): String? {
        val token = input.trim()
        if (token.isEmpty()) return ""
        if (!colon.matches(token) && !hyphen.matches(token)) return null
        val canonical = token.replace('-', ':').uppercase(java.util.Locale.ROOT)
        return canonical.takeUnless { it in denied }
    }

    /** Fixed error text must never expose the private user entry. */
    fun runtimeAddress(input: String): String = requireNotNull(canonicalOrNull(input)) {
        "Enter the head unit's real Bluetooth address in CarPlay settings."
    }
}

package com.openautolink.companion.diagnostics

/** Formats ApplicationExitInfo fields without depending on Android framework classes. */
internal object ProcessExitSummary {
    const val MAX_DESCRIPTION_CHARS = 160

    fun format(
        reason: Int,
        status: Int,
        importance: Int,
        pssKb: Long,
        rssKb: Long,
        timestampMs: Long,
        nowMs: Long,
        description: String?,
    ): String {
        val ageMs = (nowMs - timestampMs).coerceAtLeast(0L)
        val safeDescription = description
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(MAX_DESCRIPTION_CHARS)
            ?.ifBlank { null }
            ?: "none"
        return "reason=${reasonName(reason)}($reason) status=$status importance=$importance " +
            "ageMs=$ageMs pssKb=$pssKb rssKb=$rssKb description=$safeDescription"
    }

    private fun reasonName(reason: Int): String = when (reason) {
        0 -> "UNKNOWN"
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "UNKNOWN_$reason"
    }
}

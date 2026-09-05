package com.openautolink.app.ui.components

/**
 * Per-composition gate: an explicit update applies once, after its persisted echo.
 * A remounted field ignores retained events that no longer match saved state.
 */
internal class LocalEchoExternalUpdateGate {
    private var consumedVersion = 0L

    fun consume(upstreamValue: String, externalValue: String?, version: Long): Boolean {
        if (version <= consumedVersion || externalValue == null || externalValue != upstreamValue) {
            return false
        }
        consumedVersion = version
        return true
    }
}

package com.shilapi.xcertplay.airplay

/**
 * Session-owned startup policy, after RECORD's reply and encrypted event readiness.
 *
 * DiPlay e2fd8ead's RECORD/onSessionActive path does not send requestUI. Keep that reference
 * behavior by default; the opt-in retains the independently sourced receiver policy solely
 * as a startup discriminator, not an established audio fix. Call readiness hooks on the
 * control/event workers, never the Android main thread: send is synchronous under [lock].
 */
internal class CarPlayStartup(
    private val lock: Any = Any(),
    private val requestUiOnStartup: Boolean = false,
    private val send: (Map<String, Any?>) -> Boolean,
) {
    private var recorded = false
    private var ready = false
    private var attempted = false
    private var retired = false
    fun recordReplied() = synchronized(lock) { recorded = true; startIfReady() }
    fun eventReady() = synchronized(lock) { ready = true; startIfReady() }
    fun close() = synchronized(lock) { retired = true }
    private fun startIfReady() {
        if (!requestUiOnStartup || retired || attempted || !recorded || !ready) return
        attempted = true
        send(mapOf("type" to "requestUI"))
    }
}

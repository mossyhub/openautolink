package com.openautolink.app.voice

import com.openautolink.app.diagnostics.DiagnosticLog
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Process-local handoff from AAOS's selected assistant session to the exact live projection owner.
 *
 * Selecting OAL in the car's Digital assistant settings is the opt-in. Until then Android binds none
 * of these services. The router never starts a projection, reconnects a phone, or retains a closed
 * Android Auto/CarPlay session: owners explicitly install and retire exact-capability callbacks.
 */
object ProjectionVoiceAssistantRouter {
    private data class Route(val owner: Any, val invoke: () -> Boolean)

    private val lock = Any()
    private var current: Route? = null
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "oal-projection-voice-assistant").apply { isDaemon = true }
    }

    /** Replace the currently routable projection with one exact owner. */
    fun install(owner: Any, invoke: () -> Boolean) = synchronized(lock) {
        current = Route(owner, invoke)
    }

    /** A retired predecessor must never clear its successor. */
    fun clear(owner: Any) = synchronized(lock) {
        if (current?.owner === owner) current = null
    }

    /** Queue the route off AAOS's assistant callback; the captured owner is rechecked at execution. */
    fun request(source: String): Boolean {
        val route = synchronized(lock) { current } ?: run {
            DiagnosticLog.w("voice", "assistant gesture source=$source refused: no live projection route")
            return false
        }
        return try {
            executor.execute {
                if (synchronized(lock) { current !== route }) {
                    DiagnosticLog.i("voice", "assistant gesture source=$source dropped: projection owner changed")
                    return@execute
                }
                val sent = runCatching(route.invoke)
                    .onFailure { DiagnosticLog.w("voice", "assistant gesture source=$source delivery failed type=${it.javaClass.simpleName}") }
                    .getOrDefault(false)
                DiagnosticLog.i("voice", "assistant gesture source=$source delivered=$sent")
            }
            true
        } catch (_: RejectedExecutionException) {
            DiagnosticLog.w("voice", "assistant gesture source=$source refused: delivery worker unavailable")
            false
        }
    }
}

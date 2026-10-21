package com.openautolink.app.carplay

/** Process memory only. Arming affects future exact metadata owners, never an active owner. */
internal class PlayingPublicationArm(private val now: () -> Long) {
    data class Status(val armed: Boolean, val remainingMillis: Long)
    private var deadline: Long? = null
    @Synchronized fun arm() { deadline = now() + DURATION_MILLIS }
    @Synchronized fun disarm() { deadline = null }
    @Synchronized fun status(): Status {
        val remaining = deadline?.let { it - now() } ?: 0L
        if (remaining <= 0) { deadline = null; return Status(false, 0) }
        return Status(true, remaining.coerceAtMost(DURATION_MILLIS))
    }
    @Synchronized fun snapshot(): Boolean = status().armed
    companion object { const val DURATION_MILLIS = 15 * 60 * 1000L }
}

internal object CarPlayPlayingPublicationExperiment {
    private val arm = PlayingPublicationArm(android.os.SystemClock::elapsedRealtime)
    fun arm() = arm.arm()
    fun disarm() = arm.disarm()
    fun status() = arm.status()
    fun snapshot(): Boolean = arm.snapshot()
}

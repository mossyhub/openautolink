package com.openautolink.app.carplay

/** Process memory only. Arming affects future exact metadata owners, never an active owner. */
internal class PlayingPublicationArm(private val now: () -> Long) {
    data class Status(val armed: Boolean, val remainingMillis: Long, val mode: CarPlayHostMediaMode = CarPlayHostMediaMode.NORMAL)
    private var deadline: Long? = null
    private var mode = CarPlayHostMediaMode.NORMAL
    @Synchronized fun arm(mode: CarPlayHostMediaMode = CarPlayHostMediaMode.SUPPRESS_PLAYING) {
        require(mode != CarPlayHostMediaMode.NORMAL)
        this.mode = mode
        deadline = now() + DURATION_MILLIS
    }
    @Synchronized fun disarm() { deadline = null }
    @Synchronized fun status(): Status {
        val remaining = deadline?.let { it - now() } ?: 0L
        if (remaining <= 0) { deadline = null; return Status(false, 0) }
        return Status(true, remaining.coerceAtMost(DURATION_MILLIS), mode)
    }
    @Synchronized fun snapshot(): Boolean = snapshotMode() == CarPlayHostMediaMode.SUPPRESS_PLAYING
    @Synchronized fun snapshotMode(): CarPlayHostMediaMode = status().mode
    companion object { const val DURATION_MILLIS = 15 * 60 * 1000L }
}

internal object CarPlayPlayingPublicationExperiment {
    private val arm = PlayingPublicationArm(android.os.SystemClock::elapsedRealtime)
    fun arm(mode: CarPlayHostMediaMode = CarPlayHostMediaMode.SUPPRESS_PLAYING) = arm.arm(mode)
    fun disarm() = arm.disarm()
    fun status() = arm.status()
    fun snapshot(): Boolean = arm.snapshot()
    fun snapshotMode(): CarPlayHostMediaMode = arm.snapshotMode()
}

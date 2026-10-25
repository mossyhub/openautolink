package com.openautolink.app.carplay

/** Process selection for future exact owners; an active owner's mode is immutable. */
internal class PlayingPublicationArm {
    data class Status(val armed: Boolean, val mode: CarPlayHostMediaMode = CarPlayHostMediaMode.NORMAL)
    private var mode = CarPlayHostMediaMode.NORMAL
    @Synchronized fun arm(mode: CarPlayHostMediaMode = CarPlayHostMediaMode.SUPPRESS_PLAYING) {
        require(mode != CarPlayHostMediaMode.NORMAL)
        this.mode = mode
    }
    @Synchronized fun disarm() { mode = CarPlayHostMediaMode.NORMAL }
    @Synchronized fun status(): Status = Status(mode != CarPlayHostMediaMode.NORMAL, mode)
    @Synchronized fun snapshot(): Boolean = snapshotMode() == CarPlayHostMediaMode.SUPPRESS_PLAYING
    @Synchronized fun snapshotMode(): CarPlayHostMediaMode = mode
}

internal object CarPlayPlayingPublicationExperiment {
    private val arm = PlayingPublicationArm()
    fun arm(mode: CarPlayHostMediaMode = CarPlayHostMediaMode.SUPPRESS_PLAYING) = arm.arm(mode)
    fun disarm() = arm.disarm()
    fun status() = arm.status()
    fun snapshot(): Boolean = arm.snapshot()
    fun snapshotMode(): CarPlayHostMediaMode = arm.snapshotMode()
}

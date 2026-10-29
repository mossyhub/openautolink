package com.openautolink.app.carplay

import com.openautolink.app.media.OalMediaSessionManager
import com.openautolink.carplaylab.media.ProjectionReceiver
import com.shilapi.xcertplay.airplay.AirPlaySession

/** Normal production policy. Owner accepts that phone autoplay can interrupt another source.
 * This is connection admission, NOT OEM selected-source readback or Activity visibility. */
object PhonePlaybackResumeRecovery {
    data class Selection(val enabled:Boolean=true,val epoch:Long=0)
    private val selection=Selection()
    fun isEnabled():Boolean=selection.enabled
    fun snapshot():Selection=selection
    fun isCurrent(value:Selection):Boolean=value.enabled && value===selection

    /** Shared wired/wireless producer binding. Capture original authority before parse/queues. */
    internal fun capture(receiver:ProjectionReceiver,session:AirPlaySession,
        attempt:CarPlayAttemptContext?,manager:OalMediaSessionManager):((Boolean?)->Unit)? {
        val selected=snapshot()
        val currentAttempt=attempt ?: return null
        val lease=currentAttempt.hostMedia
        fun admitted()=isCurrent(selected) && currentAttempt.playbackAdmission() &&
            lease.mode==CarPlayHostMediaMode.NORMAL && manager.acceptsCarPlayHost(lease)
        val stamp=receiver.captureMediaRecoveryStamp(session,selected.epoch,::admitted) ?: return null
        return { playing -> receiver.offerPhonePlaybackObservation(session,playing,stamp); Unit }
    }
}

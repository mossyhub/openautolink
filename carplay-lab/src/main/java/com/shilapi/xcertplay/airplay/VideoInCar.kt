package com.shilapi.xcertplay.airplay

import java.math.BigInteger
import java.util.Base64

/** Whether the latest video availability update was sent now or retained for a later AirPlay stage. */
internal enum class VideoPlaybackDelivery { SENT, QUEUED, UNCHANGED }

/**
 * Keeps the latest parked-state decision until both SETUP has enabled video playback and the
 * encrypted AirPlay event channel is ready. AirPlay establishes those asynchronously, so dropping
 * an early update can leave the iPhone in audio-only mode for the rest of the session.
 */
internal class VideoPlaybackAvailability(
    private val send: (Boolean) -> Boolean,
) {
    private var desired: Boolean? = null
    private var featureEnabled = false
    private var eventReady = false
    private var lastSent: Boolean? = null

    @Synchronized
    fun setDesired(allowed: Boolean): VideoPlaybackDelivery {
        desired = allowed
        return flush()
    }

    @Synchronized
    fun setFeatureEnabled(enabled: Boolean): VideoPlaybackDelivery {
        if (featureEnabled != enabled) {
            featureEnabled = enabled
            if (!enabled) lastSent = null
        }
        return flush()
    }

    @Synchronized
    fun setEventReady(ready: Boolean): VideoPlaybackDelivery {
        if (eventReady != ready) {
            eventReady = ready
            if (!ready) lastSent = null
        }
        return flush()
    }

    private fun flush(): VideoPlaybackDelivery {
        val next = desired ?: return VideoPlaybackDelivery.QUEUED
        if (!featureEnabled || !eventReady) return VideoPlaybackDelivery.QUEUED
        if (lastSent == next) return VideoPlaybackDelivery.UNCHANGED
        return if (send(next)) {
            lastSent = next
            VideoPlaybackDelivery.SENT
        } else {
            VideoPlaybackDelivery.QUEUED
        }
    }
}

/** Video-in-car constants retained for upstream session routing; never enabled by lab config. */
object VideoInCar { const val FEATURE = "videoPlayback"; const val UI_URL = "videoplayback:"; @Volatile var allowed = false }

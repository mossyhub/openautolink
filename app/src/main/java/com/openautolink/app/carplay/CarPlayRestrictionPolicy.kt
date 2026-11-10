package com.openautolink.app.carplay

import com.openautolink.app.BuildConfig
import com.openautolink.app.data.CarPlaySettings

/** Frozen next-session offer AND writer policy. Never an Android Auto or parked-video input. */
data class CarPlayRestrictionPolicy(val enabled: Boolean, val alwaysInPark: Boolean) {
    companion object {
        fun from(settings: CarPlaySettings, buildOptIn: Boolean = BuildConfig.CARPLAY_DRIVING_RESTRICTIONS): CarPlayRestrictionPolicy {
            val enabled = buildOptIn || settings.drivingRestrictionsEnabled || settings.alwaysInPark
            return CarPlayRestrictionPolicy(enabled, settings.alwaysInPark)
        }
    }
}

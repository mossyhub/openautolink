package com.openautolink.app.ui.projection

/** A ViewModel launch request, not a recomposition/reconnect timer. */
internal class ProjectionLaunchPolicy {
    private var requested = false

    @Synchronized fun suppress() { requested = true }

    @Synchronized fun request(
        carPlayEnabled: Boolean,
        configured: Boolean,
        ignitionOff: Boolean,
        connectAndroidAuto: () -> Unit,
        connectCarPlay: () -> Unit,
        showSetup: () -> Unit,
    ) {
        if (requested) return
        requested = true
        when {
            !carPlayEnabled -> connectAndroidAuto()
            !configured || ignitionOff -> showSetup()
            else -> connectCarPlay()
        }
    }
}

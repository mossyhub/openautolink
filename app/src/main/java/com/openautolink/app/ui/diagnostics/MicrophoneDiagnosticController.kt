package com.openautolink.app.ui.diagnostics

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.shilapi.xcertplay.media.CarPlayMicrophoneExperimentProfile
import com.shilapi.xcertplay.media.CarPlayMicrophoneExperiments
import com.shilapi.xcertplay.media.CarPlayMicrophoneIndependentSelection
import com.shilapi.xcertplay.media.CarPlayMicrophoneQualificationHistory
import com.shilapi.xcertplay.media.CarPlayMicrophoneSpeechCapture

class MicrophoneDiagnosticController(
    val backend: MicrophoneDiagnosticBackend? = null,
    val automotive: Boolean = true,
    private val eligibility: (() -> Boolean)? = null,
    private val freshPark: (() -> Boolean)? = null,
) {
    var consent by androidx.compose.runtime.mutableStateOf(false)
        private set
    var stationary by androidx.compose.runtime.mutableStateOf(false)
        private set
    var state by androidx.compose.runtime.mutableStateOf(backend?.state ?: MicrophoneDiagnosticState())
        private set
    private var cancelPending = false
    var summaryStored by androidx.compose.runtime.mutableStateOf<Boolean?>(null)
        internal set
    @Volatile private var foreground = false
    @Volatile private var consentSnapshot = false
    @Volatile private var stationarySnapshot = false
    private var admission by androidx.compose.runtime.mutableStateOf(false)
    var custom by androidx.compose.runtime.mutableStateOf(MicrophoneDiagnosticCustom())
        private set
    var liveProfile by androidx.compose.runtime.mutableStateOf(CarPlayMicrophoneExperimentProfile())
        private set
    var independentSelection by androidx.compose.runtime.mutableStateOf(CarPlayMicrophoneIndependentSelection())
        private set
    val independentSupported: Boolean get() = independentSelection.history != CarPlayMicrophoneQualificationHistory.MANUAL_VR48_TWO ||
        independentSelection.speechCapture == CarPlayMicrophoneSpeechCapture.VR48_TWO
    val canArmIndependent: Boolean get() = canArmLive && independentSupported
    fun updateIndependentSelection(value: CarPlayMicrophoneIndependentSelection) { if (!busy) independentSelection = value }
    fun armIndependentLive() {
        refresh()
        if (!canArmIndependent) return
        CarPlayMicrophoneExperiments.arm(CarPlayMicrophoneExperimentProfile(independent = independentSelection))
        liveExpired = false
        refresh()
    }
    var liveConsent by androidx.compose.runtime.mutableStateOf(false)
        private set
    var liveStatus by androidx.compose.runtime.mutableStateOf(CarPlayMicrophoneExperiments.status())
        private set
    var liveExpired by androidx.compose.runtime.mutableStateOf(false)
        private set
    private var liveAdmission by androidx.compose.runtime.mutableStateOf(false)

    // Separate consent and future-owner memory arm; never changes microphone settings.
    internal var playingConsent by androidx.compose.runtime.mutableStateOf(false)
        private set
    internal var playingStatus by androidx.compose.runtime.mutableStateOf(com.openautolink.app.carplay.CarPlayPlayingPublicationExperiment.status())
        private set
    internal var playingExpired by androidx.compose.runtime.mutableStateOf(false)
        private set
    internal var currentHostMediaStatus by androidx.compose.runtime.mutableStateOf("No current CarPlay host-media lease")
        private set
    internal val canArmPlaying: Boolean get() = playingConsent && !busy && liveEligibleNow()
    internal fun updatePlayingConsent(value: Boolean) { playingConsent = value; refresh() }
    internal fun armInactive() = armHostMedia(com.openautolink.app.carplay.CarPlayHostMediaMode.INACTIVE)
    internal fun armPlaying() = armHostMedia(com.openautolink.app.carplay.CarPlayHostMediaMode.SUPPRESS_PLAYING)
    private fun armHostMedia(mode: com.openautolink.app.carplay.CarPlayHostMediaMode) {
        refresh()
        if (!canArmPlaying) return
        com.openautolink.app.carplay.CarPlayPlayingPublicationExperiment.arm(mode)
        playingExpired = false
        refresh()
    }
    internal fun disarmPlaying() {
        com.openautolink.app.carplay.CarPlayPlayingPublicationExperiment.disarm()
        playingStatus = com.openautolink.app.carplay.CarPlayPlayingPublicationExperiment.status()
        playingExpired = false
        refresh()
    }

    // Actual Park is mandatory even on a test tablet; local stationary consent is not a substitute.
    private fun liveEligibleNow(): Boolean = foreground && runCatching {
        eligibility?.invoke() == true && freshPark?.invoke() == true
    }.getOrDefault(false)
    val canArmLive: Boolean get() = liveAdmission && liveConsent && !busy && liveEligibleNow()

    fun updateCustom(value: MicrophoneDiagnosticCustom) { if (!busy) custom = value }
    fun updateLiveProfile(value: CarPlayMicrophoneExperimentProfile) { if (!busy) liveProfile = value }
    fun updateLiveConsent(value: Boolean) { liveConsent = value; refresh() }
    fun armLive() {
        refresh()
        if (!canArmLive) return
        CarPlayMicrophoneExperiments.arm(liveProfile)
        liveExpired = false
        refresh()
    }
    /** Future owners only. Never stop or modify an active projection recorder. */
    fun disarmLive() {
        CarPlayMicrophoneExperiments.disarm()
        liveStatus = CarPlayMicrophoneExperiments.status()
        liveExpired = false
        refresh()
    }
    fun resetLive() {
        if (busy) return
        disarmLive()
        liveProfile = CarPlayMicrophoneExperimentProfile()
        independentSelection = CarPlayMicrophoneIndependentSelection()
        liveConsent = false
    }

    /** Safe for the backend worker: callbacks must only read current in-memory snapshots. */
    fun isEligibleNow(): Boolean = foreground && consentSnapshot &&
        runCatching {
            eligibility?.invoke() == true &&
                (if (automotive) freshPark?.invoke() == true else stationarySnapshot)
        }.getOrDefault(false)

    fun updateForeground(value: Boolean) {
        foreground = value
        if (!value) {
            consentSnapshot = false
            stationarySnapshot = false
            consent = false
            stationary = false
            liveConsent = false
            playingConsent = false
        }
        refresh()
    }
    val busy: Boolean get() = state.phase in setOf(MicrophoneDiagnosticPhase.RUNNING,
        MicrophoneDiagnosticPhase.INSPECTING, MicrophoneDiagnosticPhase.STOPPING)
    val canStart: Boolean get() = admission && !busy && backend != null && isEligibleNow()

    fun refresh() {
        admission = isEligibleNow()
        liveAdmission = liveEligibleNow()
        currentHostMediaStatus = com.openautolink.app.media.OalMediaSessionManager.instanceOrNull()?.carPlayHostMediaStatus()
            ?: "No current CarPlay host-media lease"
        val playing = com.openautolink.app.carplay.CarPlayPlayingPublicationExperiment.status()
        if (playingStatus.armed && !playing.armed) playingExpired = true
        playingStatus = playing
        val status = CarPlayMicrophoneExperiments.status()
        if (liveStatus.armed && !status.armed) liveExpired = true
        liveStatus = status
        val next = backend?.state ?: return
        if (next.phase == MicrophoneDiagnosticPhase.COMPLETE || next.phase == MicrophoneDiagnosticPhase.IDLE) cancelPending = false
        state = if (cancelPending) next.copy(phase = MicrophoneDiagnosticPhase.STOPPING, result = null) else next
        if (!admission) cancel()
    }
    fun updateConsent(value: Boolean) { consentSnapshot = value; consent = value; refresh() }
    fun updateStationary(value: Boolean) { stationarySnapshot = value; stationary = value; refresh() }
    fun start(comparison: MicrophoneDiagnosticComparison) = submit { it.start(comparison) }
    fun startCustom() = submit { it.startCustom(custom) }
    fun inspect() = submit { it.inspect() }
    private fun submit(action: (MicrophoneDiagnosticBackend) -> Boolean) {
        refresh()
        if (!canStart) return
        val accepted = action(backend ?: return)
        refresh()
        if (!accepted) state = state.copy(reason = MicrophoneDiagnosticReason.BUSY)
    }
    fun cancel() {
        if (!busy || cancelPending || state.phase == MicrophoneDiagnosticPhase.STOPPING) return
        cancelPending = true
        state = state.copy(phase = MicrophoneDiagnosticPhase.STOPPING, result = null)
        backend?.cancel()
    }
}

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
) {
    var state by androidx.compose.runtime.mutableStateOf(backend?.state ?: MicrophoneDiagnosticState())
        private set
    internal var parkedComparison: ParkedCaptureComparison? = null
    internal var parkedEligibility: () -> Boolean = { false }
    internal var stopProjection: () -> Unit = {}
    internal var returnProjection: () -> Unit = {}
    internal var projectionStatus: () -> String = { "Stop projection before comparing" }
    var parkedBusy by androidx.compose.runtime.mutableStateOf(false)
        private set
    var parkedStatus by androidx.compose.runtime.mutableStateOf("Ready")
        private set
    var parkedResults by androidx.compose.runtime.mutableStateOf<List<List<Long>>>(emptyList())
        private set
    var parkedProjectionStatus by androidx.compose.runtime.mutableStateOf("")
        private set
    private fun parkedEligibleNow():Boolean = foreground &&
        runCatching { parkedEligibility() }.getOrDefault(false)
    private var parkedAdmission by androidx.compose.runtime.mutableStateOf(false)
    val canStartParked:Boolean get() = parkedAdmission && !busy && parkedComparison!=null && parkedEligibleNow()
    fun startParked() { refresh();if(canStartParked)parkedComparison?.start(::parkedEligibleNow);refresh() }
    fun stopProjectionForDiagnostic() { if(!busy)stopProjection();refresh() }
    fun returnToProjection() { parkedComparison?.cancel();returnProjection();refresh() }
    private var cancelPending = false
    var summaryStored by androidx.compose.runtime.mutableStateOf<Boolean?>(null)
        internal set
    @Volatile private var foreground = false
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
        if (!canArmIndependent || liveStatus.profile?.aaStyle == true) return
        CarPlayMicrophoneExperiments.arm(CarPlayMicrophoneExperimentProfile(independent = independentSelection))
        refresh()
    }
    var liveStatus by androidx.compose.runtime.mutableStateOf(CarPlayMicrophoneExperiments.status())
        private set
    private var liveAdmission by androidx.compose.runtime.mutableStateOf(false)

    // Process-only future-owner selection; never changes an active owner.
    internal var playingStatus by androidx.compose.runtime.mutableStateOf(com.openautolink.app.carplay.CarPlayPlayingPublicationExperiment.status())
        private set
    internal var currentHostMediaStatus by androidx.compose.runtime.mutableStateOf("No current CarPlay host-media lease")
        private set
    internal val canArmPlaying: Boolean get() = liveAdmission && !busy && liveEligibleNow()
    internal fun armInactive() = armHostMedia(com.openautolink.app.carplay.CarPlayHostMediaMode.INACTIVE)
    internal fun armPlaying() = armHostMedia(com.openautolink.app.carplay.CarPlayHostMediaMode.SUPPRESS_PLAYING)
    private fun armHostMedia(mode: com.openautolink.app.carplay.CarPlayHostMediaMode) {
        refresh()
        if (!canArmPlaying) return
        com.openautolink.app.carplay.CarPlayPlayingPublicationExperiment.arm(mode)
        refresh()
    }
    internal fun disarmPlaying() {
        com.openautolink.app.carplay.CarPlayPlayingPublicationExperiment.disarm()
        playingStatus = com.openautolink.app.carplay.CarPlayPlayingPublicationExperiment.status()
        refresh()
    }

    // Future-owner changes still require a foreground, idle diagnostic panel.
    private fun liveEligibleNow(): Boolean = foreground && runCatching {
        eligibility?.invoke() == true
    }.getOrDefault(false)
    val canArmLive: Boolean get() = liveAdmission && !busy && liveEligibleNow()

    fun updateCustom(value: MicrophoneDiagnosticCustom) { if (!busy) custom = value }
    fun updateLiveProfile(value: CarPlayMicrophoneExperimentProfile) { if (!busy) liveProfile = value }
    fun armAaStyle() {
        refresh()
        if (!canArmLive || liveStatus.armed) return
        CarPlayMicrophoneExperiments.arm(CarPlayMicrophoneExperimentProfile(
            source = com.shilapi.xcertplay.media.MicrophoneSource.VOICE_RECOGNITION,
            rate = com.shilapi.xcertplay.media.CarPlayMicrophoneCaptureRate.HZ_16000, aaStyle = true))
        refresh()
    }
    fun armLive() {
        refresh()
        if (!canArmLive || liveStatus.profile?.aaStyle == true) return
        CarPlayMicrophoneExperiments.arm(liveProfile)
        refresh()
    }
    /** Future owners only. Never stop or modify an active projection recorder. */
    fun disarmLive() {
        CarPlayMicrophoneExperiments.disarm()
        liveStatus = CarPlayMicrophoneExperiments.status()
        refresh()
    }
    fun resetLive() {
        if (busy) return
        disarmLive()
        liveProfile = CarPlayMicrophoneExperimentProfile()
        independentSelection = CarPlayMicrophoneIndependentSelection()
    }

    /** Safe for the backend worker: callbacks must only read current in-memory snapshots. */
    fun isEligibleNow(): Boolean = foreground &&
        runCatching { eligibility?.invoke() == true }.getOrDefault(false)

    fun updateForeground(value: Boolean) {
        foreground = value
        if (!value) {
            parkedComparison?.cancel()
        }
        refresh()
    }
    val busy: Boolean get() = parkedBusy || parkedComparison?.busy == true || state.phase in setOf(MicrophoneDiagnosticPhase.RUNNING,
        MicrophoneDiagnosticPhase.INSPECTING, MicrophoneDiagnosticPhase.STOPPING)
    val canStart: Boolean get() = admission && !busy && backend != null && isEligibleNow()

    fun refresh() {
        parkedAdmission=parkedEligibleNow()
        parkedComparison?.let {
            parkedBusy=it.busy
            parkedStatus=it.status;parkedResults=it.results
            if(!parkedEligibleNow() && it.busy)it.cancel()
        }
        parkedProjectionStatus=projectionStatus()
        admission = isEligibleNow()
        liveAdmission = liveEligibleNow()
        currentHostMediaStatus = com.openautolink.app.media.OalMediaSessionManager.instanceOrNull()?.carPlayHostMediaStatus()
            ?: "No current CarPlay host-media lease"
        val playing = com.openautolink.app.carplay.CarPlayPlayingPublicationExperiment.status()
        playingStatus = playing
        val status = CarPlayMicrophoneExperiments.status()
        liveStatus = status
        val next = backend?.state ?: return
        if (next.phase == MicrophoneDiagnosticPhase.COMPLETE || next.phase == MicrophoneDiagnosticPhase.IDLE) cancelPending = false
        state = if (cancelPending) next.copy(phase = MicrophoneDiagnosticPhase.STOPPING, result = null) else next
        if (!admission) cancel()
    }
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
        parkedComparison?.cancel()
        if (state.phase !in setOf(MicrophoneDiagnosticPhase.RUNNING,MicrophoneDiagnosticPhase.INSPECTING)) return
        if (!busy || cancelPending || state.phase == MicrophoneDiagnosticPhase.STOPPING) return
        cancelPending = true
        state = state.copy(phase = MicrophoneDiagnosticPhase.STOPPING, result = null)
        backend?.cancel()
    }
}

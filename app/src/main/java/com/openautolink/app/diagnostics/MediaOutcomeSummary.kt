package com.openautolink.app.diagnostics

import com.openautolink.app.carplay.CarPlayForegroundReceipt
import com.openautolink.app.carplay.CarPlayForegroundStage
import com.openautolink.app.carplay.CarPlayForegroundStatus
import com.openautolink.app.carplay.CarPlayForegroundMask

/** Receipts describe our process boundaries, never OEM rendering/audibility. No content parameters. */
class MediaOutcomeSummary {
    enum class Outcome { NONE, CONTROL_RECEIVED, PLAYBACK_SUBMITTED, METADATA_SUBMITTED, OWNER_RETIRED, LOSS,
        SUBMISSION_FAILED, RECOVERY_GRANTED, RECOVERY_DENIED, RECOVERY_CLOSED, RECOVERY_PERMITTED, RECOVERY_STALE }
    enum class ForegroundOutcome { NONE, START_REQUESTED, STARTED, TYPE_CHANGED, START_FAILED, STOPPED, DENIED, LOST, PENDING }
    data class Snapshot(val controlReceived: Long = 0, val playbackSubmitted: Long = 0,
        val metadataSubmitted: Long = 0, val losses: Long = 0, val rejected: Long = 0,
        val last: Outcome = Outcome.NONE, val beforeProcess: MediaTruth = MediaTruth.UNKNOWN,
        val submissionFailed: Long = 0, val recoveries: Long = 0, val lastRecovery: Outcome = Outcome.NONE,
        val foregroundEvents: Long = 0, val foregroundRejected: Long = 0,
        val lastForeground: ForegroundOutcome = ForegroundOutcome.NONE,
        val foregroundStage: CarPlayForegroundStage? = null,
        val foregroundStatus: CarPlayForegroundStatus? = null,
        val foregroundMask: CarPlayForegroundMask = CarPlayForegroundMask.UNKNOWN)
    private var owner: Any? = null
    private var foregroundOwner: Any? = null
    private var value = Snapshot()
    private fun Long.next() = if (this == Long.MAX_VALUE) this else this + 1
    @Synchronized fun admit(owner: Any) { this.owner = owner }
    @Synchronized fun retire(owner: Any) { if (this.owner === owner) { this.owner = null } }
    @Synchronized fun admitForeground(owner: Any) { foregroundOwner = owner }
    @Synchronized fun retireForeground(owner: Any) { if (foregroundOwner === owner) foregroundOwner = null }
    @Synchronized fun recordForeground(owner: Any, outcome: ForegroundOutcome) {
        if (foregroundOwner !== owner) { value = value.copy(foregroundRejected = value.foregroundRejected.next()); return }
        if (outcome != ForegroundOutcome.NONE) value = value.copy(foregroundEvents = value.foregroundEvents.next(), lastForeground = outcome)
    }
    @Synchronized fun recordForeground(owner: Any, receipt: CarPlayForegroundReceipt) {
        if (foregroundOwner !== owner) { value = value.copy(foregroundRejected = value.foregroundRejected.next()); return }
        val outcome = when(receipt.status) {
            CarPlayForegroundStatus.REQUESTED -> ForegroundOutcome.START_REQUESTED
            CarPlayForegroundStatus.ACKNOWLEDGED -> when(receipt.stage) {
                CarPlayForegroundStage.RETIREMENT -> ForegroundOutcome.STOPPED
                CarPlayForegroundStage.MICROPHONE -> ForegroundOutcome.TYPE_CHANGED
                else -> ForegroundOutcome.STARTED
            }
            CarPlayForegroundStatus.DENIED -> ForegroundOutcome.DENIED
            CarPlayForegroundStatus.LOST -> ForegroundOutcome.LOST
            CarPlayForegroundStatus.PENDING -> ForegroundOutcome.PENDING
            CarPlayForegroundStatus.FAILED -> ForegroundOutcome.START_FAILED
        }
        value = value.copy(foregroundEvents = value.foregroundEvents.next(), lastForeground = outcome,
            foregroundStage = receipt.stage, foregroundStatus = receipt.status, foregroundMask = receipt.mask)
    }
    @Synchronized fun record(owner: Any, outcome: Outcome) {
        if (this.owner !== owner) { value = value.copy(rejected = value.rejected.next()); return }
        val recovery = outcome in recoveryOutcomes
        value = value.copy(
            controlReceived = if (outcome == Outcome.CONTROL_RECEIVED) value.controlReceived.next() else value.controlReceived,
            playbackSubmitted = if (outcome == Outcome.PLAYBACK_SUBMITTED) value.playbackSubmitted.next() else value.playbackSubmitted,
            metadataSubmitted = if (outcome == Outcome.METADATA_SUBMITTED) value.metadataSubmitted.next() else value.metadataSubmitted,
            losses = if (outcome == Outcome.LOSS) value.losses.next() else value.losses,
            submissionFailed = if (outcome == Outcome.SUBMISSION_FAILED) value.submissionFailed.next() else value.submissionFailed,
            recoveries = if (recovery) value.recoveries.next() else value.recoveries,
            lastRecovery = if (recovery) outcome else value.lastRecovery,
            last = outcome)
    }
    @Synchronized fun snapshot(): Snapshot = value
    companion object {
        val process = MediaOutcomeSummary()
        private val recoveryOutcomes = setOf(Outcome.RECOVERY_GRANTED, Outcome.RECOVERY_DENIED, Outcome.RECOVERY_CLOSED,
            Outcome.RECOVERY_PERMITTED, Outcome.RECOVERY_STALE)
    }
}

enum class MediaTruth { TRUE, FALSE, UNKNOWN }
enum class MediaSubmittedState { NONE, PAUSED, PLAYING, OTHER, UNKNOWN }
enum class MediaExperiment { NORMAL, SUPPRESS_PLAYING, INACTIVE, UNKNOWN }
enum class MediaOwnerKind { AA, CARPLAY, NONE, UNKNOWN }
enum class MediaAdmission { CURRENT, RETIRED, UNKNOWN }
data class MediaOwnSnapshot(val initialized: Boolean = false, val tokenPresent: Boolean = false,
    val active: MediaTruth = MediaTruth.UNKNOWN, val phonePlaying: MediaTruth = MediaTruth.UNKNOWN,
    val submitted: MediaSubmittedState = MediaSubmittedState.UNKNOWN,
    val cached: MediaSubmittedState = MediaSubmittedState.UNKNOWN,
    val mode: MediaExperiment = MediaExperiment.UNKNOWN, val owner: MediaOwnerKind = MediaOwnerKind.UNKNOWN,
    val admission: MediaAdmission = MediaAdmission.UNKNOWN)
fun interface MediaSnapshotProvider { fun read(): MediaOwnSnapshot }

/** Reads only an already-created manager. Never constructs a session to inspect it. */
object MediaDiagnosticHooks {
    private val managerProvider = MediaSnapshotProvider {
        com.openautolink.app.media.OalMediaSessionManager.instanceOrNull()?.diagnosticSnapshot()?.let { s ->
            fun truth(value: Boolean?) = when(value) { true -> MediaTruth.TRUE; false -> MediaTruth.FALSE; null -> MediaTruth.UNKNOWN }
            fun state(value: Int?) = when(value) {
                null -> MediaSubmittedState.UNKNOWN
                android.support.v4.media.session.PlaybackStateCompat.STATE_NONE -> MediaSubmittedState.NONE
                android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED -> MediaSubmittedState.PAUSED
                android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING -> MediaSubmittedState.PLAYING
                else -> MediaSubmittedState.OTHER
            }
            MediaOwnSnapshot(s.initialized, s.tokenPresent,
                truth(s.sessionActive.takeIf { s.activeKnown }), truth(s.phonePlaying), state(s.submittedState),
                state(s.cachedState), when(s.source.publication) {
                    com.openautolink.app.media.PhoneMediaBrowseState.Publication.NORMAL -> MediaExperiment.NORMAL
                    com.openautolink.app.media.PhoneMediaBrowseState.Publication.SUPPRESS_PLAYING -> MediaExperiment.SUPPRESS_PLAYING
                    com.openautolink.app.media.PhoneMediaBrowseState.Publication.INACTIVE -> MediaExperiment.INACTIVE
                }, when(s.source.protocol) {
                    com.openautolink.app.media.PhoneMediaBrowseState.Protocol.CARPLAY -> MediaOwnerKind.CARPLAY
                    com.openautolink.app.media.PhoneMediaBrowseState.Protocol.ANDROID_AUTO -> MediaOwnerKind.AA
                    com.openautolink.app.media.PhoneMediaBrowseState.Protocol.NONE -> MediaOwnerKind.NONE
                }, when(s.source.phase) {
                    com.openautolink.app.media.PhoneMediaBrowseState.Phase.READY,
                    com.openautolink.app.media.PhoneMediaBrowseState.Phase.CONNECTING -> MediaAdmission.CURRENT
                    com.openautolink.app.media.PhoneMediaBrowseState.Phase.RETIRING -> MediaAdmission.RETIRED
                    else -> MediaAdmission.UNKNOWN
                })
        } ?: MediaOwnSnapshot()
    }
    @Volatile var snapshotProvider: MediaSnapshotProvider = managerProvider
    private val foregroundOwner = Any()
    private var foregroundObservation: AutoCloseable? = null
    @Volatile private var diagnosticContext: android.content.Context? = null
    @Synchronized fun install(context: android.content.Context? = null) {
        snapshotProvider = managerProvider
        if (context == null) return
        diagnosticContext = context.applicationContext
        if (foregroundObservation != null) return
        MediaOutcomeSummary.process.admitForeground(foregroundOwner)
        foregroundObservation = com.openautolink.app.carplay.CarPlayForegroundReceipts.observe { receipt ->
            val application = diagnosticContext ?: return@observe
            MediaOutcomeSummary.process.recordForeground(foregroundOwner, receipt)
            if (receipt.status in setOf(CarPlayForegroundStatus.DENIED, CarPlayForegroundStatus.FAILED,
                    CarPlayForegroundStatus.LOST) || (receipt.stage == CarPlayForegroundStage.RETIREMENT &&
                    receipt.status == CarPlayForegroundStatus.ACKNOWLEDGED)) {
                // Emitter may hold registry locks. Only enqueue; manager read and disk run off-main.
                MediaDiagnosticStore.retainForegroundTerminal(application, MediaOutcomeSummary.process.snapshot())
            }
        }
    }
    fun own(): MediaOwnSnapshot = try { snapshotProvider.read() } catch (_: Exception) { MediaOwnSnapshot() }
    /** Runtime status callback supplies its exact attempt lease, never an inferred/current owner. */
    fun recordReceiverReceipt(lease: com.openautolink.app.carplay.CarPlayHostMediaLease, message: String) {
        com.openautolink.app.media.OalMediaSessionManager.instanceOrNull()?.recordDiagnosticReceipt(lease, message)
    }
}

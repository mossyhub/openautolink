package com.openautolink.app.diagnostics

/** Receipts describe our process boundaries, never OEM rendering/audibility. No content parameters. */
class MediaOutcomeSummary {
    enum class Outcome { NONE, CONTROL_RECEIVED, PLAYBACK_SUBMITTED, METADATA_SUBMITTED, OWNER_RETIRED, LOSS }
    data class Snapshot(val controlReceived: Long = 0, val playbackSubmitted: Long = 0,
        val metadataSubmitted: Long = 0, val losses: Long = 0, val rejected: Long = 0,
        val last: Outcome = Outcome.NONE, val beforeProcess: MediaTruth = MediaTruth.UNKNOWN)
    private var owner: Any? = null
    private var value = Snapshot()
    @Synchronized fun admit(owner: Any) { this.owner = owner }
    @Synchronized fun retire(owner: Any) { if (this.owner === owner) { this.owner = null } }
    @Synchronized fun record(owner: Any, outcome: Outcome) {
        fun Long.next() = if (this == Long.MAX_VALUE) this else this + 1
        if (this.owner !== owner) { value = value.copy(rejected = value.rejected.next()); return }
        value = value.copy(
            controlReceived = value.controlReceived + if (outcome == Outcome.CONTROL_RECEIVED && value.controlReceived < Long.MAX_VALUE) 1 else 0,
            playbackSubmitted = value.playbackSubmitted + if (outcome == Outcome.PLAYBACK_SUBMITTED && value.playbackSubmitted < Long.MAX_VALUE) 1 else 0,
            metadataSubmitted = value.metadataSubmitted + if (outcome == Outcome.METADATA_SUBMITTED && value.metadataSubmitted < Long.MAX_VALUE) 1 else 0,
            losses = value.losses + if (outcome == Outcome.LOSS && value.losses < Long.MAX_VALUE) 1 else 0,
            last = outcome)
    }
    @Synchronized fun snapshot(): Snapshot = value
    companion object { val process = MediaOutcomeSummary() }
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

/** Parent may replace with the manager's immutable lock-qualified snapshot adapter. Never creates it. */
object MediaDiagnosticHooks {
    @Volatile var snapshotProvider: MediaSnapshotProvider = MediaSnapshotProvider {
        MediaOwnSnapshot(initialized = com.openautolink.app.media.OalMediaSessionManager.instanceOrNull() != null)
    }
    fun own(): MediaOwnSnapshot = try { snapshotProvider.read() } catch (_: Exception) { MediaOwnSnapshot() }
}

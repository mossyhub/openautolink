package com.openautolink.app.ui.settings

import android.content.Context
import com.openautolink.app.cluster.*
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.ClusterLayoutScope
import com.openautolink.app.data.SavedClusterLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Parent publishes actual current-owner observations. Saved selection/initial qualification are NOT
 * host/stream/surface observations. No CarPlay implementation dependency or synthetic readiness. */
data class ClusterSettingsObservation(
    val source: String? = null,
    val host: String? = null,
    val output: ClusterProjectionStatus? = null,
    val actualSurface: PixelSize? = null,
    val liveDisplay: QualifiedClusterDisplay? = null,
)

/** Runtime binding with an independent next-session draft. Never edits center-display preferences,
 * projection geometry, session selection, pause consent, or reconnects a phone during Save/Reset.
 * Parent collects [bindings], and must call [close] when this settings owner is disposed.
 */
class ClusterSettingsRuntimeAdapter private constructor(
    private var display: QualifiedClusterDisplay?,
    private val observations: StateFlow<ClusterSettingsObservation>,
    private val storage: LayoutStorage,
    private val preview: ClusterCalibrationPreview?,
    scope: CoroutineScope,
    private var automatic: ClusterLayoutModel?,
    restored: ClusterLayoutModel?,
    private val directCalibration: Boolean = false,
    migrationPending: Boolean = false,
) {
    /** Internal seam only: public factories always install the actual AppPreferences implementation. */
    internal interface LayoutStorage {
        suspend fun read(automatic: ClusterLayoutModel, profileId: String): ClusterLayoutModel?
        suspend fun write(automatic: ClusterLayoutModel, profileId: String, custom: ClusterLayoutModel?): Boolean
    }
    private val lock = Any()
    private val commitLock = Mutex()
    private val seededDisplay = display
    private val seededAutomatic = automatic
    private fun observeLiveDisplay() {
        // A direct normal window uses the sourced physical envelope, not the phone host.
        // Host loss still clears actualSurface/status; never cache a retired video host here.
        if (directCalibration) return
        val live = observations.value.liveDisplay
        val next = live ?: seededDisplay
        if (next?.host == display?.host && next?.profile == display?.profile) return
        display = next
        automatic = if (live != null) layoutModel(live) else seededAutomatic
        // Preserve valid custom intent exactly; geometry loss never clips or rewrites it.
        revision++
    }
    private var custom: ClusterLayoutModel? = restored
    private var dirty = migrationPending
    private var revision = 0L
    private var closed = false
    private var previewToken: Any = Any()
    private var previewStarted = false
    private var ownedPreview: Any? = null // retain exact identity through false/pending retirement
    private var startingPreview: Any? = null
    private var stoppingPreview: Any? = null
    private var observedPreviewOwner: Any? = null
    private var observationRevision = 0L
    private val state = MutableStateFlow(snapshot())
    val bindings: StateFlow<ClusterSettingsBindings> = state.asStateFlow()
    private val statusJob: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        observations.collect {
            synchronized(lock) { observeLiveDisplay() }
            publish()
        }
    }
    private val previewJob: Job? = preview?.let { backend -> scope.launch(start = CoroutineStart.UNDISPATCHED) {
        backend.owner.collect { active ->
            val retire = synchronized(lock) {
                observedPreviewOwner = active; observationRevision++
                val exact = ownedPreview
                val stop = if (previewStarted && startingPreview == null && active !== exact)
                    reserveStop(requireNotNull(exact)) else null
                stop
            }
            publish()
            retire?.let(::invokeStop)
        }
    } }

    private fun snapshot(): ClusterSettingsBindings {
        val observed = observations.value
        val output = observed.output
        val token = previewToken
        val viewRevision = revision
        return ClusterSettingsBindings(
            status = ClusterSettingsStatus(observed.source, display?.profile?.id,
                observed.host ?: if (output?.hostAttached == true) "Attached — host acknowledged" else null,
                output?.let { "${it.received} packet admissions; ${it.configurations} configurations; ${it.keyframes} keyframes${it.error?.let { error -> "; error: $error" } ?: ""}" },
                output?.let { "${it.decoded} decoded / ${it.presented} presented; ${it.dropped} dropped (visibility not confirmed)" }),
            layout = (custom ?: automatic)?.takeIf { model -> automatic?.let { current ->
                model.key == current.key && (model.canvas == current.canvas || model.canvas == current.essentialCanvas) &&
                    current.essentialCanvas.contains(model.safe)
            } == true }, automaticLayout = automatic, actualSurface = observed.actualSurface,
            isCustomLayout = custom != null,
            previewActive = previewStarted && observedPreviewOwner === token,
            previewOwner = token,
            onStageLayout = { model ->
                val retire = synchronized(lock) {
                    check(!closed); validate(model)
                    val stop = reserveStop(ownedPreview ?: previewToken)
                    custom = model; dirty = true; revision++
                    stop
                }
                publish()
                retire?.let(::invokeStop)
            },
            onResetLayout = {
                val retire = synchronized(lock) {
                    check(!closed && automatic != null)
                    val stop = reserveStop(ownedPreview ?: previewToken)
                    custom = null; dirty = true; revision++
                    com.openautolink.app.diagnostics.DiagnosticLog.i("cluster", "OPTIONAL_LAYOUT_V1 stage=reset_staged revision=$revision apply=next_session")
                    stop
                }
                publish()
                retire?.let(::invokeStop)
            },
            onStartPreview = { model, seconds -> startPreview(token, viewRevision, model, seconds) },
            onStopPreview = {
                val retire = synchronized(lock) {
                    reserveStop(if (previewToken === token) ownedPreview ?: token else token)
                }
                publish()
                retire?.let(::invokeStop)
            },
            commitLayoutDraft = { commit() },
        )
    }
    private fun validate(model: ClusterLayoutModel) {
        val qualified = requireNotNull(automatic) { "Qualified driver cluster unavailable" }
        require(model.key == qualified.key && (model.canvas == qualified.canvas || model.canvas == qualified.essentialCanvas) &&
            qualified.essentialCanvas.contains(model.safe)) { "Layout scope/canvas mismatch" }
    }
    /** Reserve state only. No foreign call or future callback registration under [lock]. */
    private fun reserveStop(token: Any): Any? {
        if (previewToken === token) {
            previewStarted = false
            previewToken = Any()
        }
        if (ownedPreview !== token || startingPreview === token || stoppingPreview != null) return null
        stoppingPreview = token
        return token
    }

    private fun startPreview(token: Any, viewRevision: Long, model: ClusterLayoutModel, seconds: Int) {
        fun validateStart() {
            check(!closed && previewToken === token && revision == viewRevision) { "Preview owner replaced" }
            validate(model)
            check(model == (custom ?: automatic)) { "Preview draft replaced" }
            check(!previewStarted && startingPreview == null) { "Preview already started" }
        }
        val backend = checkNotNull(preview) { "Preview unavailable" }
        val retained = synchronized(lock) { validateStart(); ownedPreview?.let(::reserveStop) }
        retained?.let(::invokeStop)
        synchronized(lock) {
            validateStart()
            check(ownedPreview == null) { "Preview retirement pending; retry after exact clear acknowledgement" }
            ownedPreview = token; startingPreview = token; previewStarted = true
        }
        publish()
        var failure: Exception? = null
        try { backend.start(requireNotNull(display), model.output, model.safe, seconds, token) }
        catch (caught: Exception) { failure = caught }
        // Sample only outside our monitor. A concurrent edit/close has already revoked this token;
        // it cannot retire a not-yet-installed run, so the returning starter owns that cleanup.
        val observation = synchronized(lock) { observationRevision }
        val observed = backend.owner.value
        val retire = synchronized(lock) {
            if (startingPreview === token) startingPreview = null
            if (observationRevision == observation) observedPreviewOwner = observed
            val stop = if (failure != null || closed || previewToken !== token || revision != viewRevision || observedPreviewOwner !== token)
                reserveStop(token) else null
            stop
        }
        publish()
        retire?.let(::invokeStop)
        failure?.let { throw it }
    }

    private fun invokeStop(token: Any) {
        val receipt = try { preview?.stop(token) }
        catch (_: Exception) { null }
        // Registration can synchronously invoke the callback: it too must be outside the monitor.
        if (receipt == null) {
            synchronized(lock) { if (stoppingPreview === token) stoppingPreview = null }
            publish()
            return
        }
        receipt.whenComplete { acknowledged, failure ->
            synchronized(lock) {
                if (stoppingPreview === token) stoppingPreview = null
                if (failure == null && acknowledged == true && ownedPreview === token) ownedPreview = null
            }
            publish()
        }
    }
    private fun publish() {
        // CAS against the value sampled BEFORE snapshotting: a competing publisher either wins
        // first (and forces a fresh snapshot) or follows this publication. No stale draft can win.
        while (true) {
            val previous = state.value
            val next = synchronized(lock) { if (closed) return; snapshot() }
            if (state.compareAndSet(previous, next)) return
        }
    }

    private suspend fun commit(): Boolean = commitLock.withLock {
        data class Pending(val automatic: ClusterLayoutModel, val custom: ClusterLayoutModel?, val revision: Long)
        val receipt = kotlinx.coroutines.currentCoroutineContext()[SettingsSaveTransaction]
        val observed = synchronized(lock) {
            Triple(closed, dirty, automatic?.let { Pending(it, custom, revision) })
        }
        receipt?.event("adapter", "observed", "revision=${observed.third?.revision} dirty=${observed.second} closed=${observed.first}")
        if (observed.first) { receipt?.event("adapter", "closed"); return@withLock false }
        if (!observed.second) { receipt?.event("adapter", "clean"); return@withLock true }
        val pending = observed.third ?: return@withLock false
        val selectedForWrite = pending.custom ?: pending.automatic
        receipt?.event("adapter", "dirty", "revision=${pending.revision} mode=${if (pending.custom == null) "Auto" else "Custom"} canvas=${selectedForWrite.canvas} output=${selectedForWrite.output} safe=${selectedForWrite.safe} essential=${selectedForWrite.essentialCanvas}")
        val acknowledged = try { storage.write(pending.automatic, requireNotNull(display).profile.id, pending.custom) }
        catch (cancelled: CancellationException) { receipt?.event("durable", "cancelled", "revision=${pending.revision}"); throw cancelled }
        catch (_: Exception) { false }
        receipt?.event("durable", if (acknowledged) "success" else "failed", "revision=${pending.revision} boundary=write_readback")
        com.openautolink.app.diagnostics.DiagnosticLog.i("cluster",
            "OPTIONAL_LAYOUT_V1 stage=${if (pending.custom == null) "reset" else "save"} revision=${pending.revision} acknowledged=$acknowledged apply=next_session role=${pending.automatic.key.role} panel=${pending.automatic.key.panel}")
        val selected = pending.custom ?: pending.automatic
        com.openautolink.app.diagnostics.DiagnosticLog.i("cluster",
            "OPTIONAL_LAYOUT_V1 stage=persisted_geometry revision=${pending.revision} acknowledged=$acknowledged mode=${if (pending.custom == null) "Auto" else "Custom"} canvas=${selected.canvas} output=${selected.output} safe=${selected.safe}")
        if (acknowledged) synchronized(lock) {
            // A newer edit arriving during a suspended real store write remains dirty and visible.
            if (revision == pending.revision) dirty = false
        }
        if (acknowledged) publish()
        acknowledged
    }

    fun close() {
        val retire = synchronized(lock) {
            val stop = reserveStop(ownedPreview ?: previewToken)
            closed = true
            stop
        }
        statusJob.cancel(); previewJob?.cancel()
        retire?.let(::invokeStop)
    }

    companion object {
        private fun layoutModel(display: QualifiedClusterDisplay): ClusterLayoutModel {
            val essential = requireNotNull(display.host.essentialCanvas)
            val background = requireNotNull(display.host.backgroundCanvas(display.profile))
            // Preserve the sourced legacy675 canvas unless CURRENT host callbacks expose more height.
            val canvas = if (background.top < essential.top) background else essential
            return ClusterLayoutModel(ClusterLayoutKey(display.profile.vehicle, display.profile.role, display.profile.panel),
                canvas, display.effectiveOutput, display.safe, essential)
        }
        /** Initial display qualification is NOT an assertion of an attached/visible host. */
        suspend fun create(context: Context, observations: StateFlow<ClusterSettingsObservation>,
                           preferences: AppPreferences, preview: ClusterCalibrationPreview,
                           scope: CoroutineScope): ClusterSettingsRuntimeAdapter =
            create(ClusterProjectionOutput.qualifiedInitialDisplay(context), observations, PreferenceLayouts(preferences), preview, scope, directCalibration = true)

        suspend fun create(display: QualifiedClusterDisplay?, observations: StateFlow<ClusterSettingsObservation>,
                           preferences: AppPreferences, preview: ClusterCalibrationPreview,
                           scope: CoroutineScope): ClusterSettingsRuntimeAdapter =
            create(display, observations, PreferenceLayouts(preferences), preview, scope)

        internal suspend fun create(display: QualifiedClusterDisplay?, observations: StateFlow<ClusterSettingsObservation>,
                                    storage: LayoutStorage, preview: ClusterCalibrationPreview?,
                                    scope: CoroutineScope, directCalibration: Boolean = false): ClusterSettingsRuntimeAdapter {
            val direct = directCalibration && display?.profile == ClusterDisplayProfiles.AEGEAN_ORANGE_239
            val automatic = display?.let { if (direct) ClusterLayoutModel(
                ClusterLayoutKey(it.profile.vehicle, it.profile.role, it.profile.panel),
                it.profile.output, it.profile.output, it.profile.safe, requireNotNull(it.host.essentialCanvas))
                else layoutModel(it) }
            // A failed read is not silently treated as a successful Automatic restore. Parent can
            // present retry/unavailable; no editable model is published from an uncompleted read.
            val restored = automatic?.let { storage.read(it, requireNotNull(display).profile.id) }
                ?.takeIf { it.key == automatic.key && (it.canvas == automatic.canvas || it.canvas == automatic.essentialCanvas) }
            // Correct only the identifiable old maximum preset. Preserve arbitrary custom output
            // and safe exactly, including custom rectangles wider than the sourced default.
            val migrated = if (direct && restored != null && automatic != null &&
                restored.canvas == automatic.essentialCanvas && restored.output == restored.canvas &&
                automatic.output.contains(restored.safe)) restored.copy(canvas = automatic.canvas, output = automatic.output,
                    essentialCanvas = automatic.essentialCanvas) else restored
            return ClusterSettingsRuntimeAdapter(display, observations, storage, preview, scope, automatic, migrated,
                direct, migrationPending = migrated != restored)
        }
    }
    private class PreferenceLayouts(private val preferences: AppPreferences) : LayoutStorage {
        private fun scope(model: ClusterLayoutModel, profileId: String) = ClusterLayoutScope(
            model.key.vehicle, profileId, model.key.role, model.key.panel)
        override suspend fun read(automatic: ClusterLayoutModel, profileId: String): ClusterLayoutModel? {
            val namespace = scope(automatic, profileId)
            val current = preferences.getClusterLayout(namespace, automatic.canvas)
            val receipt = if (current.unavailableReason == com.openautolink.app.data.ClusterLayoutUnavailableReason.CANVAS_CHANGED &&
                automatic.canvas != automatic.essentialCanvas) preferences.getClusterLayout(namespace, automatic.essentialCanvas) else current
            return receipt.custom?.let { ClusterLayoutModel(automatic.key, it.canvas, it.output, it.safe, automatic.essentialCanvas) }
        }
        override suspend fun write(automatic: ClusterLayoutModel, profileId: String, custom: ClusterLayoutModel?): Boolean {
            val namespace = scope(automatic, profileId)
            if (custom == null) {
                preferences.resetClusterLayout(namespace, automatic.canvas)
                return preferences.getClusterLayout(namespace, automatic.canvas).custom == null &&
                    preferences.getClusterLayout(namespace, automatic.essentialCanvas).custom == null
            }
            require(custom.key == automatic.key &&
                (custom.canvas == automatic.canvas || custom.canvas == automatic.essentialCanvas) &&
                automatic.essentialCanvas.contains(custom.safe))
            val record = SavedClusterLayout(namespace, custom.canvas, custom.output, custom.safe)
            val written = preferences.saveClusterLayout(record).custom == record
            return written && preferences.getClusterLayout(namespace, custom.canvas).custom == record
        }
    }
}

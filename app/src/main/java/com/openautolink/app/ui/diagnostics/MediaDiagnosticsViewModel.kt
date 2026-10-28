package com.openautolink.app.ui.diagnostics

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.openautolink.app.diagnostics.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MediaExportPhase { IDLE, AWAITING_PICKER, AWAITING_CANCELED_PICKER, VALIDATING, WRITING, CLOSING, SAVED, FAILED, CANCELED, INTERRUPTED }
data class MediaDiagnosticsState(val localText: String = "No comparison collected; Refresh.", val refreshing: Boolean = false,
    val exportPhase: MediaExportPhase = MediaExportPhase.IDLE, val pickerId: String? = null,
    val bytes: Long = 0, val files: Int = 0, val partialWarning: Boolean = false, val result: String = "") {
    val exportBusy: Boolean get() = exportPhase in listOf(MediaExportPhase.AWAITING_PICKER, MediaExportPhase.AWAITING_CANCELED_PICKER,
        MediaExportPhase.VALIDATING, MediaExportPhase.WRITING, MediaExportPhase.CLOSING)
}
internal class MediaDiagnosticsDependencies(
    val collect: (() -> MediaIntegrationResult)? = null,
    val export: (Uri, MediaExportCancellation, (Long, Int) -> Unit) -> MediaExportReceipt = { _, _, _ -> MediaExportReceipt(MediaExportCode.UNAVAILABLE) },
    val delete: (Uri) -> Boolean = { false },
) {
    companion object {
        fun production(app: Application) = MediaDiagnosticsDependencies(
            collect = { MediaIntegrationInspection(app).collect() },
            export = { uri, cancel, progress -> MediaHostApkExporter(app).export(uri, cancel, progress) },
            delete = { uri -> try { DocumentsContract.deleteDocument(app.contentResolver, uri) } catch (_: Exception) { false } })
    }
}

/** Activity owner only. No Activity, provider URI, comparison or descriptor in SavedStateHandle. */
class MediaDiagnosticsViewModel internal constructor(application: Application, private val saved: SavedStateHandle,
    private val dependencies: MediaDiagnosticsDependencies) : AndroidViewModel(application) {
    constructor(application: Application, saved: SavedStateHandle) : this(application, saved, MediaDiagnosticsDependencies.production(application))
    private val _state = MutableStateFlow(MediaDiagnosticsState())
    val state = _state.asStateFlow()
    private var picker: String? = saved["media_export_picker"]
    private var claimed: Boolean = saved["media_export_claimed"] ?: false
    private var cancelOnly = picker != null
    private var interrupted = picker != null
    private var cancellation: MediaExportCancellation? = null
    @Volatile private var revoked = false
    @Volatile private var cleared = false
    private val refreshGeneration = AtomicLong()
    init {
        if (picker != null) { claimed = true; _state.value = _state.value.copy(exportPhase = MediaExportPhase.AWAITING_CANCELED_PICKER, pickerId = picker, result = "Interrupted; waiting for the old picker result.") }
        else if (saved.get<Boolean>("media_export_writing") == true) {
            saved["media_export_writing"] = false
            _state.value = _state.value.copy(exportPhase = MediaExportPhase.INTERRUPTED, partialWarning = true, result = "Interrupted; no export was resumed.")
        }
    }
    fun refresh() {
        if (_state.value.refreshing || cleared) return
        val generation = refreshGeneration.incrementAndGet()
        _state.value = _state.value.copy(refreshing = true)
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val report = dependencies.collect?.invoke() ?: return@withContext "Inspection unavailable."
                    if (generation != refreshGeneration.get()) return@withContext null
                    val coverage = report.toUploadSummary()
                    val run = MediaDiagnosticStore.forContext(getApplication()).save(report.own, coverage,
                        accepted = { generation == refreshGeneration.get() })
                    if (generation != refreshGeneration.get()) return@withContext null
                    MediaDiagnosticStore.latestCoverage = coverage.copy(inspectionRun = run ?: 0L)
                    report.renderLocalText() + "summary_storage=${if (run == null) "UNAVAILABLE" else "AVAILABLE run=$run"}\n"
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { "Inspection failed; previous comparison retained. No private error text collected." }
            }
            if (generation == refreshGeneration.get() && text != null) _state.value = _state.value.copy(refreshing = false, localText = text)
        }
    }
    fun requestExport() {
        if (cleared || _state.value.exportBusy) return
        picker = UUID.randomUUID().toString(); claimed = false; cancelOnly = false; interrupted = false; revoked = false
        saved["media_export_picker"] = picker; saved["media_export_claimed"] = false
        _state.value = _state.value.copy(exportPhase = MediaExportPhase.AWAITING_PICKER, pickerId = picker, bytes = 0, files = 0, partialWarning = false, result = "Choose a destination first.")
    }
    /** Main-thread synchronous claim and registry launch must not suspend in between. */
    fun claimPicker(): String? {
        val id = picker ?: return null
        if (claimed || cancelOnly || cleared) return null
        claimed = true; saved["media_export_claimed"] = true
        return id
    }
    fun pickerLaunchFailed(id: String) {
        if (picker != id) return
        consumePicker(); terminal(MediaExportPhase.FAILED, "Document picker unavailable.", false)
    }
    private fun consumePicker() { picker = null; saved.remove<String>("media_export_picker"); saved.remove<Boolean>("media_export_claimed") }
    fun onPickerResult(uri: Uri?) {
        if (picker == null) return // no successor can exist while an earlier callback remains outstanding
        val discard = cancelOnly || cleared
        val wasInterrupted = interrupted
        consumePicker()
        if (uri == null) { terminal(if (wasInterrupted) MediaExportPhase.INTERRUPTED else MediaExportPhase.CANCELED, "Save canceled.", false); return }
        saved["media_export_writing"] = true
        val exact = MediaExportCancellation(); cancellation = exact
        _state.value = _state.value.copy(exportPhase = if (discard) MediaExportPhase.CLOSING else MediaExportPhase.VALIDATING, pickerId = null, result = "")
        // Undispatched admission ensures cancellation-before-IO still has an owning finally/cleanup path.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val completion = withContext(NonCancellable + Dispatchers.IO) {
                var receipt = MediaExportReceipt(if (wasInterrupted) MediaExportCode.INTERRUPTED else MediaExportCode.CANCELED)
                if (!discard) {
                    try { receipt = dependencies.export(uri, exact) { bytes, files ->
                        _state.value = _state.value.copy(bytes = bytes, files = files,
                            exportPhase = if (revoked) MediaExportPhase.CLOSING else MediaExportPhase.WRITING)
                    } } catch (e: CancellationException) { exact.revoke(); receipt = MediaExportReceipt(MediaExportCode.CANCELED) }
                    catch (_: Exception) { receipt = MediaExportReceipt(MediaExportCode.FAILED) }
                }
                exact.awaitClose()
                if (revoked && receipt.code == MediaExportCode.SAVED) receipt = receipt.copy(code = MediaExportCode.CANCELED)
                val partial = if (receipt.code != MediaExportCode.SAVED) try { !dependencies.delete(uri) } catch (_: Exception) { true } else false
                try { MediaDiagnosticStore.forContext(getApplication()).save(MediaDiagnosticHooks.own(), MediaDiagnosticStore.latestCoverage) } catch (_: Exception) { }
                receipt to partial
            }
            cancellation = null; saved["media_export_writing"] = false
            val (receipt, partial) = completion
            val phase = when (receipt.code) {
                MediaExportCode.SAVED -> MediaExportPhase.SAVED
                MediaExportCode.CANCELED -> MediaExportPhase.CANCELED
                MediaExportCode.INTERRUPTED -> MediaExportPhase.INTERRUPTED
                else -> MediaExportPhase.FAILED
            }
            terminal(phase, when (receipt.code) {
                MediaExportCode.SAVED -> "Saved; selected provider accepted and closed the bytes (not an atomic install snapshot)."
                MediaExportCode.UNAVAILABLE -> "Fixed system media host is not available or accessible."
                MediaExportCode.PACKAGE_CHANGED -> "Media host changed during export; retry with a new document."
                MediaExportCode.LIMIT -> "Export exceeds a safety limit."
                MediaExportCode.CANCELED -> "Save canceled."
                MediaExportCode.INTERRUPTED -> "Interrupted; no export was resumed."
                else -> "Save failed."
            }, partial)
        }
    }
    fun cancelExport() {
        if (!_state.value.exportBusy) return
        if (picker == null && cancellation?.revoke() == false) return
        revoked = true
        if (picker != null) {
            cancelOnly = true
            _state.value = _state.value.copy(exportPhase = MediaExportPhase.AWAITING_CANCELED_PICKER, result = "Canceled; waiting for the picker result before another save.")
        } else {
            _state.value = _state.value.copy(exportPhase = MediaExportPhase.CLOSING, result = "Closing; waiting for writer and provider acknowledgement.")
        }
    }
    private fun terminal(phase: MediaExportPhase, text: String, partial: Boolean) {
        _state.value = _state.value.copy(exportPhase = phase, pickerId = null, result = text, partialWarning = partial)
    }
    override fun onCleared() {
        cleared = true; refreshGeneration.incrementAndGet(); cancelExport(); super.onCleared()
    }
}

/** Dedicated stable registry key, registered before STARTED on every Activity creation. */
fun bindMediaDiagnosticsExport(activity: ComponentActivity, vm: MediaDiagnosticsViewModel) {
    val launcher = activity.activityResultRegistry.register("oal.media_host_zip.v1", activity,
        ActivityResultContracts.CreateDocument("application/zip"), vm::onPickerResult)
    activity.lifecycleScope.launch {
        activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.state.collect {
                val id = vm.claimPicker()
                if (id != null) try { launcher.launch("OAL-media-host.zip") } catch (_: Exception) { vm.pickerLaunchFailed(id) }
            }
        }
    }
}

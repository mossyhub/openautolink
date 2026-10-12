package com.openautolink.app.carplay

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.view.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.openautolink.app.ui.components.ProjectionOverlayControls
import com.openautolink.app.ui.components.StatsForNerdsOverlay
import com.openautolink.app.ui.components.StatLine
import androidx.compose.ui.unit.dp
import com.openautolink.app.ui.projection.ProjectionUiState
import com.openautolink.app.ui.projection.ProjectionViewModel

/** Main-thread callback ownership; a retired view/generation cannot overwrite a newer receipt. */
internal class CarPlaySurfaceReceiptGate {
    private var owner: Any? = null
    private var generation = 0L

    fun created(owner: Any): Long {
        this.owner = owner
        generation += 1
        return generation
    }

    fun isCurrent(owner: Any, generation: Long): Boolean =
        this.owner === owner && this.generation == generation

    fun destroyed(owner: Any, generation: Long): Boolean {
        if (!isCurrent(owner, generation)) return false
        this.owner = null
        return true
    }

    fun validViewport(width: Int, height: Int): Boolean =
        width in 1..16384 && height in 1..16384
}

/** Settings/diagnostics are overlays, never owners of the live projection Surface. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun CarPlayProjectionLayer(
    viewModel: ProjectionViewModel,
    state: CarPlayUiState,
    enabled: Boolean,
    controls: ProjectionUiState,
    onReconnect: () -> Unit,
    showSettings: Boolean,
    showDiagnostics: Boolean,
    onSettings: () -> Unit,
) {
    val receiptGate = remember(viewModel) { CarPlaySurfaceReceiptGate() }
    val currentDisplayMode = androidx.compose.runtime.rememberUpdatedState(controls.displayMode)
    val display by viewModel.carPlayDisplay.collectAsStateWithLifecycle()
    val automaticResolution by viewModel.carPlayAutomaticResolution.collectAsStateWithLifecycle()
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var parentReceipt by remember { mutableStateOf<Pair<androidx.compose.ui.unit.IntSize, String>?>(null) }
    fun publishParent(view: SurfaceView) {
        val receipt = parentReceipt ?: return
        val parentSize = receipt.first
        if (receiptGate.validViewport(parentSize.width, parentSize.height)) {
            viewModel.onCarPlayViewportChanged(parentSize.width, parentSize.height, view.display, receipt.second)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged {
        parentReceipt = null
        viewModel.onCarPlayViewportChanged(0, 0, null)
    }.onGloballyPositioned { coordinates ->
        // This already chrome-free parent is independent of any aspect-fitted video island.
        parentReceipt = coordinates.size to currentDisplayMode.value
        surfaceView?.takeIf { it.holder.surface?.isValid == true }?.let(::publishParent)
    }.testTag("carPlayProjection")) {
        BoxWithConstraints(Modifier.align(Alignment.Center).then(
            if (automaticResolution && display.qualifiedVideoSelection == null) Modifier.fillMaxSize()
            else Modifier.aspectRatio(display.width.toFloat() / display.height))) {
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            AndroidView(
                factory = { context -> SurfaceView(context).apply {
                    surfaceView = this
                    holder.addCallback(object : SurfaceHolder.Callback {
                        private var generation = 0L
                        private var createdSurface: Surface? = null
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            surfaceView = this@apply
                            generation = receiptGate.created(this@apply)
                            createdSurface = holder.surface
                            // Never let a replacement receiver consume the previous viewport.
                            viewModel.onCarPlayViewportChanged(0, 0, null)
                            // SurfaceChanged supplies the measured receipt before availability.
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            if (!receiptGate.isCurrent(this@apply, generation)) return
                            if (createdSurface !== holder.surface) return
                            // Parent layout is authoritative; neither fitted View nor decoder buffer.
                            publishParent(this@apply)
                            viewModel.onCarPlaySurfaceAvailable(holder.surface)
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            if (!receiptGate.destroyed(this@apply, generation)) return
                            createdSurface?.let(viewModel::onCarPlaySurfaceDestroyed)
                            createdSurface = null
                            if (surfaceView === this@apply) surfaceView = null
                        }
                    })
                } },
                update = { view -> view.requestLayout() },
                modifier = Modifier.fillMaxSize().testTag("carPlaySurface"),
            )
            Box(Modifier.fillMaxSize().pointerInteropFilter { event ->
                if (CarPlayOverlayPolicy.forwardTouch(showSettings, showDiagnostics)) viewModel.onCarPlayTouch(event, width, height)
                true
            }.testTag("carPlayTouch"))
        }
        ProjectionOverlayControls(
            uiState = controls,
            onSettings = onSettings,
            onStats = viewModel::toggleStats,
            onReconnect = if (enabled) onReconnect else null,
            modifier = Modifier.align(Alignment.BottomEnd),
            onFileLogging = viewModel::toggleFileLogging,
            onUpload = viewModel::uploadLogsNow,
        )
        if (controls.showStats) {
            StatsForNerdsOverlay(modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                CarPlayStatsPresentation.rows(state).forEach { (label, value) -> StatLine(label, value) }
            }
        }
    }
}

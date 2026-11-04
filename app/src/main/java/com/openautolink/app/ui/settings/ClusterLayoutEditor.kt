package com.openautolink.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openautolink.app.cluster.*
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Durable namespace deliberately excludes ephemeral Android physical display IDs. */
data class ClusterLayoutKey(val vehicle: ClusterVehicleIdentity, val role: ClusterVehicleRole, val panel: PixelSize) {
    init { require(role == ClusterVehicleRole.DRIVER_CLUSTER) }
}

enum class ClusterLayoutTarget { OUTPUT, SAFE }

/** A validated next-session editor model, not an output/offer qualification receipt. */
data class ClusterLayoutModel(val key: ClusterLayoutKey, val canvas: PixelRect, val output: PixelRect, val safe: PixelRect,
    val essentialCanvas: PixelRect = canvas) {
    init { require(key.panel.bounds.contains(canvas) && key.panel.bounds.contains(essentialCanvas) &&
        canvas.contains(output) && output.contains(safe) && essentialCanvas.contains(safe)) }

    fun adjust(target: ClusterLayoutTarget, x: Int? = null, y: Int? = null, width: Int? = null, height: Int? = null): ClusterLayoutModel {
        val current = if (target == ClusterLayoutTarget.OUTPUT) output else safe
        val bounds = if (target == ClusterLayoutTarget.OUTPUT) canvas else requireNotNull(output.intersect(essentialCanvas))
        val left = (x ?: current.left).coerceIn(bounds.left, if (target == ClusterLayoutTarget.OUTPUT) safe.left else bounds.right - 1)
        val top = (y ?: current.top).coerceIn(bounds.top, if (target == ClusterLayoutTarget.OUTPUT) safe.top else bounds.bottom - 1)
        val minWidth = if (target == ClusterLayoutTarget.OUTPUT) safe.right - left else 1
        val minHeight = if (target == ClusterLayoutTarget.OUTPUT) safe.bottom - top else 1
        val w = (width ?: current.width).coerceIn(minWidth, bounds.right - left)
        val h = (height ?: current.height).coerceIn(minHeight, bounds.bottom - top)
        val result = PixelRect(left, top, left + w, top + h)
        return if (target == ClusterLayoutTarget.OUTPUT) copy(output = result) else copy(safe = result)
    }
}

/** Captures a stop handle for one owner/geometry; never follows a successor binding. */
private class EditorPreview(active: Boolean, stop: () -> Unit) {
    var active by mutableStateOf(active)
    var stopHandle = stop
    fun stop() {
        if (active) {
            stopHandle()
            active = false
        }
    }
}

/** Local schematic only. Runtime preview uses a separate required bounded owner callback. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClusterLayoutEditor(bindings: ClusterSettingsBindings, onBack: () -> Unit) {
    val initial = bindings.layout
    if (bindings.useLiveGridEditor && initial != null) {
        LiveGridEditor("Cluster layout", initial.gridGeometry(), true, bindings.liveGrid,
            onStage = { next -> bindings.onStageLayout(initial.copy(output = next.output, safe = next.safe)) },
            onReset = bindings.onResetLayout, onBack = onBack,
            automatic = bindings.automaticLayout?.gridGeometry() ?: initial.gridGeometry())
        return
    }
    var draft by remember(initial) { mutableStateOf(initial) }
    var target by rememberSaveable { mutableStateOf(ClusterLayoutTarget.SAFE) }
    var resize by rememberSaveable { mutableStateOf(false) }
    val preview = remember(bindings.previewOwner, initial) {
        EditorPreview(bindings.previewActive, bindings.onStopPreview)
    }
    var previewError by remember(bindings.previewOwner, initial) { mutableStateOf<String?>(null) }
    fun stop() = preview.stop()
    BackHandler { stop(); onBack() }
    DisposableEffect(preview) { onDispose { preview.stop() } }
    LaunchedEffect(preview, preview.active) {
        if (preview.active) { delay(CLUSTER_PREVIEW_SECONDS * 1000L); preview.stop() }
    }
    val stageCallback by rememberUpdatedState(bindings.onStageLayout)
    fun stage(next: ClusterLayoutModel) { stop(); draft = next; stageCallback(next) }
    // The pointer coroutine survives owner/geometry replacement; refresh the whole action,
    // including its exact preview owner, rather than only the downstream stage callback.
    val currentStage by rememberUpdatedState<(ClusterLayoutModel) -> Unit> { next -> stage(next) }

    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
            val wide = maxWidth >= 760.dp
            val narrowPreviewHeight = (maxHeight * .32f).coerceIn(96.dp, 220.dp)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { stop(); onBack() }, modifier = Modifier.heightIn(min = 48.dp).testTag("clusterEditorBack")) { Text("Back") }
                    Column {
                        Text("Cluster layout", style = MaterialTheme.typography.titleMedium)
                        Text("Adjust only if content is cut off.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (draft == null) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Text("Cluster unavailable — main display continues", style = MaterialTheme.typography.titleMedium)
                        Text("A qualified vehicle profile and actual cluster host canvas are required. No display is guessed.")
                    }
                } else {
                    val model = requireNotNull(draft)
                    val latest by rememberUpdatedState(model)
                    val schematic: @Composable (Modifier) -> Unit = { modifier ->
                        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Panel ${model.key.panel.width} × ${model.key.panel.height} · Output ${model.output.width} × ${model.output.height}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Surface: ${bindings.actualSurface?.let { "${it.width} × ${it.height}" } ?: "Not reported by editor"}",
                                style = MaterialTheme.typography.bodySmall)
                            // Only the available editor slot sizes the preview. Panel pixels supply
                            // its aspect ratio, never a requested layout height or minimum size.
                            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                val ratio = model.key.panel.width.toFloat() / model.key.panel.height
                                val width = minOf(maxWidth, maxHeight * ratio)
                                Canvas(Modifier.size(width, width / ratio).testTag("clusterLayoutCanvas")
                                    .pointerInput(model.key, target, resize) {
                                        var gestureModel = latest
                                        detectDragGestures(onDragStart = { gestureModel = latest }) { change, delta ->
                                            change.consume()
                                            val rect = if (target == ClusterLayoutTarget.OUTPUT) gestureModel.output else gestureModel.safe
                                            val dx = (delta.x * gestureModel.key.panel.width / size.width).roundToInt()
                                            val dy = (delta.y * gestureModel.key.panel.height / size.height).roundToInt()
                                            gestureModel = if (resize) gestureModel.adjust(target, width = rect.width + dx, height = rect.height + dy)
                                                else gestureModel.adjust(target, x = rect.left + dx, y = rect.top + dy)
                                            currentStage(gestureModel)
                                        }
                                    }) {
                                    val scale = size.width / model.key.panel.width
                                    fun rect(r: PixelRect, color: Color, stroke: Boolean) {
                                        val position = Offset(r.left * scale, r.top * scale)
                                        val dimensions = Size(r.width * scale, r.height * scale)
                                        if (stroke) drawRect(color, position, dimensions, style = Stroke(2.dp.toPx()))
                                        else drawRect(color, position, dimensions)
                                    }
                                    rect(model.key.panel.bounds, Color.Gray, true)
                                    rect(model.canvas, Color.LightGray, true)
                                    rect(model.output, Color(0x553F8CFF), false)
                                    rect(model.output, Color(0xFF3F8CFF), true)
                                    rect(model.safe, Color(0xFF50D890), true)
                                }
                            }
                            Text("Blue: map · Green: important content · Outline: host canvas", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    val controls: @Composable (Modifier) -> Unit = { modifier ->
                        Column(modifier) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = target == ClusterLayoutTarget.OUTPUT, onClick = { target = ClusterLayoutTarget.OUTPUT },
                                    label = { Text("Map output") }, modifier = Modifier.heightIn(min = 48.dp))
                                FilterChip(selected = target == ClusterLayoutTarget.SAFE, onClick = { target = ClusterLayoutTarget.SAFE },
                                    label = { Text("Important content") }, modifier = Modifier.heightIn(min = 48.dp))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = !resize, onClick = { resize = false }, label = { Text("Move") }, modifier = Modifier.heightIn(min = 48.dp).testTag("clusterDragMove"))
                                FilterChip(selected = resize, onClick = { resize = true }, label = { Text("Resize") }, modifier = Modifier.heightIn(min = 48.dp).testTag("clusterDragResize"))
                            }
                            val rect = if (target == ClusterLayoutTarget.OUTPUT) model.output else model.safe
                            // Two rows instead of four; precision actions keep full 48dp targets.
                            listOf(listOf("X" to rect.left, "Y" to rect.top), listOf("W" to rect.width, "H" to rect.height)).forEach { axes ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    axes.forEach { (axis, value) ->
                                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                            Text("$axis $value", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                            for (step in listOf(-1, 1)) OutlinedButton(
                                                onClick = {
                                                    stage(model.adjust(target, x = if (axis == "X") value + step else null,
                                                        y = if (axis == "Y") value + step else null,
                                                        width = if (axis == "W") value + step else null,
                                                        height = if (axis == "H") value + step else null))
                                                }, contentPadding = PaddingValues(0.dp),
                                                modifier = Modifier.size(48.dp).testTag("clusterNudge_${target.name}_${axis}_${if (step > 0) "plus" else "minus"}")
                                                    .semantics { contentDescription = "${if (target == ClusterLayoutTarget.OUTPUT) "Map output" else "Important content"} $axis ${if (step > 0) "+1" else "−1"}" },
                                            ) { Text(if (step > 0) "+1" else "−1") }
                                        }
                                    }
                                }
                            }
                            Text("Drag the selection; buttons adjust by 1 px.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (wide) {
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            schematic(Modifier.weight(1f).fillMaxHeight())
                            controls(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()))
                        }
                    } else {
                        // One scroll owner on narrow windows; the canvas never owns scrolling.
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            schematic(Modifier.fillMaxWidth().height(narrowPreviewHeight))
                            controls(Modifier.fillMaxWidth())
                        }
                    }
                    previewError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = {
                            stop()
                            bindings.automaticLayout?.let { draft = it; bindings.onResetLayout() }
                        }, enabled = bindings.automaticLayout?.key == model.key,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("clusterResetAutomatic")) { Text("Reset to automatic") }
                        if (preview.active) {
                            Button(onClick = { stop() }, modifier = Modifier.heightIn(min = 48.dp).testTag("clusterPreviewStop")) { Text("Stop preview") }
                        } else Button(onClick = {
                            previewError = null
                            preview.stopHandle = bindings.onStopPreview
                            preview.active = true
                            try { bindings.onStartPreview(model, CLUSTER_PREVIEW_SECONDS) }
                            catch (_: Exception) {
                                previewError = "Preview unavailable — main display continues"
                                runCatching { preview.stop() }
                            }
                        }, modifier = Modifier.heightIn(min = 48.dp).testTag("clusterPreviewStart")) { Text("Preview grid · 15 s") }
                        Text("Local grid only · Instruments untouched\nSave changes applies on next connection", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }
    }
}

const val CLUSTER_PREVIEW_SECONDS = 15

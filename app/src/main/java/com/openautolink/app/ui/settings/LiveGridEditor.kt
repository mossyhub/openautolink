package com.openautolink.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openautolink.app.cluster.PixelRect
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Bounds-preserving geometry: moving/resizing output carries essential content proportionally. */
internal fun LiveGridGeometry.adjustGrid(safeTarget: Boolean, resize: Boolean, dx: Int, dy: Int): LiveGridGeometry {
    val rect = if (safeTarget) safe else output
    val bounds = if (safeTarget) requireNotNull(output.intersect(essentialCanvas)) else canvas
    if (!resize) {
        val x = dx.coerceIn(maxOf(bounds.left - rect.left, essentialCanvas.left - safe.left),
            minOf(bounds.right - rect.right, essentialCanvas.right - safe.right))
        val y = dy.coerceIn(maxOf(bounds.top - rect.top, essentialCanvas.top - safe.top),
            minOf(bounds.bottom - rect.bottom, essentialCanvas.bottom - safe.bottom))
        fun translate(r: PixelRect) = PixelRect(r.left + x, r.top + y, r.right + x, r.bottom + y)
        return if (safeTarget) copy(safe = translate(safe)) else copy(output = translate(output), safe = translate(safe))
    }
    val right = (rect.right + dx).coerceIn(rect.left + 1, bounds.right)
    val bottom = (rect.bottom + dy).coerceIn(rect.top + 1, bounds.bottom)
    val next = PixelRect(rect.left, rect.top, right, bottom)
    if (safeTarget) return copy(safe = next)
    // Resize output as one layout, carrying the essential region's relative proportions.
    // Full-canvas defaults must be resizable without first performing a hidden safe-area ritual.
    fun x(value: Int) = (next.left + (value - output.left).toLong() * next.width / output.width).toInt()
    fun y(value: Int) = (next.top + (value - output.top).toLong() * next.height / output.height).toInt()
    val safeBounds = next.intersect(essentialCanvas) ?: return this
    val left = x(safe.left).coerceIn(safeBounds.left, safeBounds.right - 1)
    val top = y(safe.top).coerceIn(safeBounds.top, safeBounds.bottom - 1)
    val essential = PixelRect(left, top, x(safe.right).coerceIn(left + 1, safeBounds.right), y(safe.bottom).coerceIn(top + 1, safeBounds.bottom))
    return copy(output = next, safe = essential)
}

/** Shared usable editor for public outputs and an explicitly local tablet demonstration. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LiveGridEditor(
    title: String,
    initial: LiveGridGeometry,
    enabled: Boolean,
    external: LiveGridPreviewBinding?,
    onStage: (LiveGridGeometry) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    localOnly: Boolean = false,
    automatic: LiveGridGeometry = initial,
    safeOnly: Boolean = false,
    previewUnavailableReason: String? = null,
) {
    var geometry by remember(external?.owner, initial.panel, initial.canvas) { mutableStateOf(initial) }
    var safeTarget by remember(safeOnly) { mutableStateOf(safeOnly) }
    var resize by remember { mutableStateOf(false) }
    var active by remember(external?.owner, initial.panel, initial.canvas) { mutableStateOf(false) }
    var error by remember(external?.owner) { mutableStateOf<String?>(null) }
    val previewSeconds = if (safeOnly) 30 else 15
    val localRun = remember(safeOnly) { LiveGridDraftRun(maxSeconds = previewSeconds) }
    val localOwner = remember(external?.owner, initial.panel, initial.canvas) { Any() }
    // Capture exact old binding, never latest, for A→B disposal.
    val stopExact = remember(external?.owner, localOwner) { { localRun.stop(localOwner); external?.stop?.invoke(); active = false } }
    DisposableEffect(stopExact) { onDispose { stopExact() } }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, stopExact) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) stopExact()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler { stopExact(); onBack() }
    LaunchedEffect(active, localOwner) { if (active) { delay(previewSeconds * 1000L); stopExact() } }
    LaunchedEffect(enabled) { if (!enabled) stopExact() }
    LaunchedEffect(previewUnavailableReason) { if (safeOnly && previewUnavailableReason != null) stopExact() }
    val currentStage by rememberUpdatedState<(LiveGridGeometry) -> Unit> { next ->
        if (enabled) {
            geometry = next; onStage(next)
            if (active) {
                try {
                    localRun.update(localOwner, next)
                    if (external != null && !external.update(next)) { stopExact(); error = if (safeOnly) "Preview ended. Check the display and try again." else "Grid stopped: display changed or deadline reached." }
                } catch (failure: Exception) { stopExact(); error = failure.message ?: if (safeOnly) "Preview unavailable. Check the display and try again." else "Grid update failed." }
            }
        }
    }
    val latest by rememberUpdatedState(geometry)
    if (safeOnly) {
        DirectionsAreaEditor(geometry = geometry, enabled = enabled, active = active,
            unavailableReason = when {
                !enabled -> "Display disconnected. Return to settings and choose a display."
                previewUnavailableReason != null -> previewUnavailableReason
                external == null -> "Connect a driver display to preview."
                else -> null
            }, error = error,
            onStage = currentStage,
            onReset = {
                // Only the inner rectangle is reset. Legacy outer/calibration data stays intact.
                val reset = geometry.copy(safe = automatic.safe)
                geometry = reset; onReset()
                if (active) {
                    try {
                        localRun.update(localOwner, reset)
                        if (external != null && !external.update(reset)) { stopExact(); error = "Preview ended. Check the display and try again." }
                    } catch (failure: Exception) { stopExact(); error = failure.message ?: "Preview unavailable. Check the display and try again." }
                }
            },
            onPreview = {
                if (active) stopExact() else if (enabled && external != null && previewUnavailableReason == null) {
                    error = null
                    try {
                        external.start(geometry, previewSeconds)
                        localRun.start(localOwner, geometry, previewSeconds) { if (it == null) active = false }
                        active = true
                    } catch (failure: Exception) { stopExact(); error = failure.message ?: "Preview unavailable. Check the display and try again." }
                }
            }, onDone = { stopExact(); onBack() })
        return
    }
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
            val wide = maxWidth >= 760.dp
            val previewHeight = (maxHeight * .36f).coerceIn(96.dp, 220.dp)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { stopExact(); onBack() }, modifier = Modifier.heightIn(min = 48.dp).testTag("liveGridBack")) { Text("Back") }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(if (localOnly) "This screen only · not vehicle output" else "Transparent grid · no phone video", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!localOnly && external == null) Text("No admitted physical display for a direct grid. Local preview only.",
                    style = MaterialTheme.typography.bodySmall)
                if (!enabled) Text("Output changed or disconnected. Return to displays to refresh.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                val preview: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier) {
                        Text("${geometry.panel.width} × ${geometry.panel.height} reference pixels", style = MaterialTheme.typography.bodySmall)
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            val ratio = geometry.panel.width.toFloat() / geometry.panel.height
                            val width = minOf(maxWidth, maxHeight * ratio)
                            Box(Modifier.size(width, width / ratio).testTag("liveGridCanvas")
                                .pointerInput(initial.panel, safeTarget, resize, enabled) {
                                    var gesture = latest
                                    detectDragGestures(onDragStart = { gesture = latest }) { change, delta ->
                                        change.consume()
                                        if (enabled) {
                                            gesture = gesture.adjustGrid(safeTarget, resize,
                                                (delta.x * gesture.panel.width / size.width).roundToInt(),
                                                (delta.y * gesture.panel.height / size.height).roundToInt())
                                            currentStage(gesture)
                                        }
                                    }
                                }) {
                                Canvas(Modifier.fillMaxSize()) {
                                    val scale = size.width / geometry.panel.width
                                    fun outline(r: PixelRect, color: Color) = drawRect(color,
                                        Offset(r.left * scale, r.top * scale), Size(r.width * scale, r.height * scale), style = Stroke(2.dp.toPx()))
                                    outline(geometry.canvas, Color.Gray)
                                    outline(geometry.output, Color.Cyan)
                                    outline(geometry.safe, Color(0xFF50D890))
                                }
                                AndroidView(factory = { LiveTransparentGridView(it) }, modifier = Modifier.fillMaxSize(),
                                    update = { it.geometry = geometry; it.active = active })
                            }
                        }
                        Text(if (safeOnly) "Cyan: Auto video background (read-only) · Green: Important content" else "Cyan: output · Green: essential content", style = MaterialTheme.typography.bodySmall)
                    }
                }
                val controls: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!safeOnly) FilterChip(!safeTarget, { safeTarget = false }, label = { Text("Output") }, modifier = Modifier.heightIn(min = 48.dp).testTag("liveGridOutput"))
                            FilterChip(safeTarget, { safeTarget = true }, label = { Text(if (safeOnly) "Important content" else "Essential content") }, modifier = Modifier.heightIn(min = 48.dp).testTag("liveGridSafe"))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!resize, { resize = false }, label = { Text("Move") }, modifier = Modifier.heightIn(min = 48.dp).testTag("liveGridMove"))
                            FilterChip(resize, { resize = true }, label = { Text("Resize") }, modifier = Modifier.heightIn(min = 48.dp).testTag("liveGridResize"))
                        }
                        val rect = if (safeTarget) geometry.safe else geometry.output
                        listOf(listOf("X" to rect.left, "Y" to rect.top), listOf("Width" to rect.width, "Height" to rect.height)).forEach { axes ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                axes.forEach { (axis, value) ->
                                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Text("$axis $value", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                        listOf(-1, 1).forEach { step ->
                                            OutlinedButton(onClick = {
                                                val isResize = axis == "Width" || axis == "Height"
                                                currentStage(geometry.adjustGrid(safeTarget, isResize,
                                                    if (axis == "X" || axis == "Width") step else 0,
                                                    if (axis == "Y" || axis == "Height") step else 0))
                                            }, enabled = enabled, contentPadding = PaddingValues(0.dp),
                                                modifier = Modifier.size(48.dp).testTag("liveGrid_${axis}_${if (step > 0) "plus" else "minus"}")
                                                    .semantics { contentDescription = "${if (safeTarget) "Essential content" else "Output"} $axis ${if (step > 0) "increase" else "decrease"} one pixel" }) { Text(if (step > 0) "+1" else "−1") }
                                        }
                                    }
                                }
                            }
                        }
                        Text(if (safeOnly) "Drag important content to move or resize. Background stays automatic." else "Drag to move or resize. Edits update the running grid immediately.", style = MaterialTheme.typography.bodySmall)
                        if (!localOnly) Text("Save changes after returning. Video layout applies next connection.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (wide) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    preview(Modifier.weight(1f).fillMaxHeight())
                    controls(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()))
                } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    preview(Modifier.fillMaxWidth().height(previewHeight)); controls(Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { geometry = automatic; onReset(); if (active) {
                        localRun.update(localOwner, automatic)
                        if (external != null && !external.update(automatic)) stopExact()
                    } }, enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("liveGridReset")) { Text("Reset") }
                    Button(onClick = {
                        if (active) stopExact() else {
                            error = null
                            try {
                                external?.start?.invoke(geometry, 15)
                                localRun.start(localOwner, geometry, 15) { if (it == null) active = false }
                                active = true
                            } catch (failure: Exception) { stopExact(); error = failure.message ?: "Grid unavailable. Return and retry." }
                        }
                    }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag(if (active) "liveGridStop" else "liveGridStart")) {
                        Text(if (active) "Stop grid" else if (external == null) "Start local grid · 15 s" else "Start grid · 15 s")
                    }
                    Text(if (active) if (external == null) "Grid visible here" else "Direct window requested · visibility unverified"
                        else if (external == null) "No external window starts" else "${external.destination}\n15 s maximum · Instruments untouched",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp).testTag("liveGridStatus"))
                }
            }
        }
    }
}

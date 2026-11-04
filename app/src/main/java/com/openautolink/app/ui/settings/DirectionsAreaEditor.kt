package com.openautolink.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.openautolink.app.cluster.PixelRect
import kotlin.math.roundToInt

internal enum class DirectionsHandle(val label: String) {
    MOVE("Move directions area"),
    TOP_LEFT("Resize directions area top left"), TOP_RIGHT("Resize directions area top right"),
    BOTTOM_LEFT("Resize directions area bottom left"), BOTTOM_RIGHT("Resize directions area bottom right");
    fun corner(rect: PixelRect): Offset = when (this) {
        TOP_LEFT -> Offset(rect.left.toFloat(), rect.top.toFloat())
        TOP_RIGHT -> Offset(rect.right.toFloat(), rect.top.toFloat())
        BOTTOM_LEFT -> Offset(rect.left.toFloat(), rect.bottom.toFloat())
        BOTTOM_RIGHT -> Offset(rect.right.toFloat(), rect.bottom.toFloat())
        MOVE -> Offset((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
    }
}

/** Only safe is mutable. Neither a gesture nor accessibility can change the video window. */
internal fun LiveGridGeometry.adjustDirections(handle: DirectionsHandle, dx: Int, dy: Int): LiveGridGeometry {
    if (handle == DirectionsHandle.MOVE) return adjustGrid(true, false, dx, dy)
    val bounds = requireNotNull(output.intersect(essentialCanvas))
    val left = handle == DirectionsHandle.TOP_LEFT || handle == DirectionsHandle.BOTTOM_LEFT
    val top = handle == DirectionsHandle.TOP_LEFT || handle == DirectionsHandle.TOP_RIGHT
    return copy(safe = PixelRect(
        if (left) (safe.left + dx).coerceIn(bounds.left, safe.right - 1) else safe.left,
        if (top) (safe.top + dy).coerceIn(bounds.top, safe.bottom - 1) else safe.top,
        if (!left) (safe.right + dx).coerceIn(safe.left + 1, bounds.right) else safe.right,
        if (!top) (safe.bottom + dy).coerceIn(safe.top + 1, bounds.bottom) else safe.bottom))
}

/** Same clamped 48dp square for drawing semantics and touch; nearest corner wins overlaps. */
internal fun directionsHit(model: LiveGridGeometry, point: Offset, viewport: Size, target: Float): DirectionsHandle? {
    val sx = viewport.width / model.panel.width; val sy = viewport.height / model.panel.height
    fun center(handle: DirectionsHandle): Offset = handle.corner(model.safe).let { Offset(it.x * sx, it.y * sy) }
    val corners = DirectionsHandle.entries.filter { it != DirectionsHandle.MOVE }
    val corner = corners.filter { handle ->
        val c = center(handle)
        val x = (c.x - target / 2).coerceIn(0f, (viewport.width - target).coerceAtLeast(0f))
        val y = (c.y - target / 2).coerceIn(0f, (viewport.height - target).coerceAtLeast(0f))
        point.x in x..(x + target) && point.y in y..(y + target)
    }.minByOrNull { (center(it) - point).getDistanceSquared() }
    if (corner != null) return corner
    val r = model.safe
    return DirectionsHandle.MOVE.takeIf { point.x in r.left * sx..r.right * sx && point.y in r.top * sy..r.bottom * sy }
}

/** Ordinary task UI; the legacy diagnostic editor remains separate and unreachable here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DirectionsAreaEditor(
    geometry: LiveGridGeometry, enabled: Boolean, active: Boolean,
    unavailableReason: String?, error: String?, onStage: (LiveGridGeometry) -> Unit,
    onReset: () -> Unit, onPreview: () -> Unit, onDone: () -> Unit,
) {
    val latest by rememberUpdatedState(geometry)
    val stage by rememberUpdatedState(onStage)
    val density = LocalDensity.current
    val target = with(density) { 48.dp.toPx() }
    val green = Color(0xFF50D890)
    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
            val wide = maxWidth >= 760.dp
            val availableHeight = maxHeight
            // Pin completion/preview. Only the body can scroll on a short or large-text window.
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Directions area", style = MaterialTheme.typography.titleLarge)
                val diagram: @Composable (Modifier) -> Unit = { modifier ->
                    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
                        val ratio = geometry.panel.width.toFloat() / geometry.panel.height
                        // Leave room for complete 48dp handles even on a short viewport.
                        val fittedWidth = minOf(maxWidth - 16.dp, (maxHeight - 16.dp) * ratio).coerceAtLeast(1.dp)
                        Box(Modifier.size(fittedWidth, fittedWidth / ratio).testTag("liveGridCanvas")
                            .pointerInput(geometry.panel, enabled, target) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val initial = latest
                                    val handle = directionsHit(initial, down.position,
                                        Size(size.width.toFloat(), size.height.toFloat()), target)
                                    var total = Offset.Zero
                                    if (enabled && handle != null) {
                                        down.consume()
                                        drag(down.id) { change ->
                                            total += change.positionChange(); change.consume()
                                            stage(initial.adjustDirections(handle,
                                                (total.x * initial.panel.width / size.width).roundToInt(),
                                                (total.y * initial.panel.height / size.height).roundToInt()))
                                        }
                                    }
                                }
                            }) {
                            Canvas(Modifier.fillMaxSize()) {
                                val sx = size.width / geometry.panel.width; val sy = size.height / geometry.panel.height
                                // Fixed full-window context, never a second selectable/editable rectangle.
                                drawRect(Color.White.copy(alpha = .55f), style = Stroke(1.dp.toPx()))
                                val r = geometry.safe
                                val origin = Offset(r.left * sx, r.top * sy)
                                val extent = Size(r.width * sx, r.height * sy)
                                drawRect(green.copy(alpha = .15f), origin, extent)
                                drawRect(green, origin, extent, style = Stroke(3.dp.toPx()))
                                DirectionsHandle.entries.filter { it != DirectionsHandle.MOVE }.forEach { handle ->
                                    val corner = handle.corner(r).let { Offset(it.x * sx, it.y * sy) }
                                    drawCircle(Color.White, 7.dp.toPx(), corner)
                                    drawCircle(green, 7.dp.toPx(), corner, style = Stroke(3.dp.toPx()))
                                }
                            }
                            val scaleX = with(density) { fittedWidth.toPx() } / geometry.panel.width
                            val scaleY = scaleX
                            val viewportW = with(density) { fittedWidth.toPx() }
                            val viewportH = with(density) { (fittedWidth / ratio).toPx() }
                            DirectionsHandle.entries.forEach { handle ->
                                val corner = handle.corner(geometry.safe)
                                val isMove = handle == DirectionsHandle.MOVE
                                // The move accessibility node stays central; corners get independent 48dp nodes.
                                val x = (corner.x * scaleX - target / 2).coerceIn(0f, (viewportW - target).coerceAtLeast(0f))
                                val y = (corner.y * scaleY - target / 2).coerceIn(0f, (viewportH - target).coerceAtLeast(0f))
                                Box(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                                    .size(48.dp).testTag("directions_${handle.name}").semantics {
                                        contentDescription = handle.label
                                        customActions = listOf(
                                            Triple(if (isMove) "Move left" else "Resize left", -1, 0),
                                            Triple(if (isMove) "Move right" else "Resize right", 1, 0),
                                            Triple(if (isMove) "Move up" else "Resize up", 0, -1),
                                            Triple(if (isMove) "Move down" else "Resize down", 0, 1),
                                        ).map { (label, dx, dy) -> CustomAccessibilityAction(label) {
                                            if (enabled) stage(latest.adjustDirections(handle, dx, dy))
                                            enabled
                                        } } + if (isMove) listOf(
                                            Triple("Increase width", 1, 0), Triple("Decrease width", -1, 0),
                                            Triple("Increase height", 0, 1), Triple("Decrease height", 0, -1),
                                        ).map { (label, dx, dy) -> CustomAccessibilityAction(label) {
                                            if (enabled) stage(latest.adjustDirections(DirectionsHandle.BOTTOM_RIGHT, dx, dy))
                                            enabled
                                        } } else emptyList()
                                    })
                            }
                        }
                    }
                }
                val guidance: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Move and resize the green box to keep directions visible.", style = MaterialTheme.typography.bodyMedium)
                        unavailableReason?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                if (wide) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    diagram(Modifier.weight(1f).fillMaxHeight())
                    guidance(Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState()))
                } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    guidance(Modifier.fillMaxWidth())
                    diagram(Modifier.fillMaxWidth().height((availableHeight * .4f).coerceIn(128.dp, 280.dp)))
                }
                Text("After Done, tap Save changes. USB: unplug and reconnect to apply.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("directionsSaveGuidance"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onReset, enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("liveGridReset")) { Text("Restore default") }
                    Button(onClick = onPreview, enabled = active || enabled && unavailableReason == null,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(if (active) "liveGridStop" else "liveGridStart")) {
                        Text(if (active) "Stop preview" else "Show on driver display · 30s")
                    }
                    OutlinedButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp).testTag("liveGridBack")) { Text("Done") }
                }
            }
        }
    }
}

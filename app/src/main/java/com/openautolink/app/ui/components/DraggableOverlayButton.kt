package com.openautolink.app.ui.components

import android.content.Context
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Shared draggable control. Geometry and persisted positions use parent pixels, not column deltas. */
@Composable
internal fun DraggableOverlayButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    initialOffsetDp: Offset = Offset(0f, 0f),
    positionKey: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
    onGlobalPosition: ((LayoutCoordinates) -> Unit)? = null,
    viewport: OverlayButtonViewport? = null,
) {
    val density = LocalDensity.current.density
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("overlay_positions", Context.MODE_PRIVATE) }
    val store = remember(prefs, positionKey) { positionKey?.let { OverlayButtonPositionStore(prefs, it) } }
    val initialPx = OverlayButtonPoint(initialOffsetDp.x * density, initialOffsetDp.y * density)
    var offset by remember(positionKey) { mutableStateOf(initialPx) }
    var geometry by remember(positionKey) { mutableStateOf<OverlayButtonGeometry?>(null) }
    var lastDragEndTime by remember { mutableStateOf(0L) }
    var wasDragged by remember { mutableStateOf(false) }

    FilledTonalButton(
        onClick = {
            val now = System.currentTimeMillis()
            if (wasDragged && now - lastDragEndTime < 300) {
                wasDragged = false
                return@FilledTonalButton
            }
            wasDragged = false
            onClick()
        },
        modifier = modifier
            // Physical/root coordinates must not reverse their X axis in RTL.
            .absoluteOffset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .onGloballyPositioned { coordinates ->
                onGlobalPosition?.invoke(coordinates)
                val parent = viewport
                if (parent != null && parent.width > 0f && parent.height > 0f &&
                    coordinates.size.width > 0 && coordinates.size.height > 0) {
                    val placed = coordinates.positionInRoot()
                    val measured = OverlayButtonGeometry(
                        parent.width, parent.height,
                        placed.x - parent.left - offset.x.roundToInt(),
                        placed.y - parent.top - offset.y.roundToInt(),
                        coordinates.size.width.toFloat(), coordinates.size.height.toFloat(),
                    )
                    // Relayout (bars, window, size, optional controls) is the trigger,
                    // not recomposition. The first layout also recovers legacy traps.
                    if (measured != geometry) {
                        offset = store?.readOffset(measured, initialPx) ?: measured.clampOffset(offset)
                        geometry = measured
                    }
                }
            }
            .pointerInput(geometry, store) {
                val bounds = geometry ?: return@pointerInput
                detectDragGestures(
                    onDragStart = { wasDragged = false },
                    onDragEnd = {
                        if (wasDragged) {
                            lastDragEndTime = System.currentTimeMillis()
                            store?.saveOffset(bounds, offset)
                        }
                    },
                    // A geometry-key change cancels the old handler. Do not
                    // persist its old coordinate basis over the reprojected position.
                    onDragCancel = { wasDragged = false },
                ) { change, dragAmount ->
                    change.consume()
                    wasDragged = true
                    offset = bounds.clampOffset(OverlayButtonPoint(offset.x + dragAmount.x, offset.y + dragAmount.y))
                }
            },
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = containerColor, contentColor = tint),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
    }
}

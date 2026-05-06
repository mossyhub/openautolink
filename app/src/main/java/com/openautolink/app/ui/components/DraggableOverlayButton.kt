package com.openautolink.app.ui.components

import android.content.Context
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A draggable semi-transparent icon button for use as a floating overlay.
 * Tap triggers [onClick]. Drag moves the button. Drag vs tap is distinguished
 * by tracking cumulative drag distance — only taps with < threshold movement fire onClick.
 * Position is persisted across restarts via SharedPreferences.
 * Positions are clamped to ±[maxBoundsX] / ±[maxBoundsY] so the button can be
 * dragged anywhere within the screen bounds (including left/top of the default
 * bottom-right placement) while still staying visible across display mode changes.
 */
@Composable
fun DraggableOverlayButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    initialOffsetDp: Offset = Offset(0f, 0f),
    positionKey: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
    onGlobalPosition: ((LayoutCoordinates) -> Unit)? = null,
    maxBoundsX: Float = Float.POSITIVE_INFINITY,
    maxBoundsY: Float = Float.POSITIVE_INFINITY,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("overlay_positions", Context.MODE_PRIVATE)
    }

    // Helper to clamp offset to bounds.
    // Minimum is negative to allow dragging left/up from the default bottom-right position.
    fun clampOffset(offset: Offset): Offset {
        return Offset(
            x = offset.x.coerceIn(-maxBoundsX, maxBoundsX),
            y = offset.y.coerceIn(-maxBoundsY, maxBoundsY)
        )
    }

    val initialPx = with(density) {
        if (positionKey != null && prefs.contains("${positionKey}_x")) {
            val savedOffset = Offset(prefs.getFloat("${positionKey}_x", 0f), prefs.getFloat("${positionKey}_y", 0f))
            clampOffset(savedOffset)
        } else {
            Offset(initialOffsetDp.x * this.density, initialOffsetDp.y * this.density)
        }
    }
    var offset by remember { mutableStateOf(initialPx) }
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
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .then(
                if (onGlobalPosition != null) Modifier.onGloballyPositioned(onGlobalPosition)
                else Modifier
            )
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { wasDragged = false },
                    onDragEnd = {
                        if (wasDragged) {
                            lastDragEndTime = System.currentTimeMillis()
                            if (positionKey != null) {
                                prefs.edit()
                                    .putFloat("${positionKey}_x", offset.x)
                                    .putFloat("${positionKey}_y", offset.y)
                                    .apply()
                            }
                        }
                    },
                    onDragCancel = { wasDragged = false },
                ) { change, dragAmount ->
                    change.consume()
                    wasDragged = true
                    offset = clampOffset(Offset(
                        x = offset.x + dragAmount.x,
                        y = offset.y + dragAmount.y,
                    ))
                }
            },
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = tint,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
        )
    }
}

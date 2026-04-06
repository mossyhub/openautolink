package com.openautolink.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// Video coordinate space — what the phone renders at
private const val VIDEO_W = 1920
private const val VIDEO_H = 1080
private const val MAX_INSET = 500

private val SAFE_LINE_COLOR = Color(0xFF64FFDA)
private val DANGER_COLOR = Color(0x18FF6E40)
private val HANDLE_BG = Color(0x8064FFDA)
private val HANDLE_GRIP = Color(0xFF004D40)
private const val HANDLE_W_DP = 24f

@Composable
fun SafeAreaEditorScreen(
    initialTop: Int,
    initialBottom: Int,
    initialLeft: Int,
    initialRight: Int,
    displayMode: String = "fullscreen_immersive",
    onDone: (top: Int, bottom: Int, left: Int, right: Int) -> Unit,
    onBack: () -> Unit,
) {
    var insetTop by remember { mutableIntStateOf(initialTop.coerceIn(0, MAX_INSET)) }
    var insetBottom by remember { mutableIntStateOf(initialBottom.coerceIn(0, MAX_INSET)) }
    var insetLeft by remember { mutableIntStateOf(initialLeft.coerceIn(0, MAX_INSET)) }
    var insetRight by remember { mutableIntStateOf(initialRight.coerceIn(0, MAX_INSET)) }
    var showManualInput by remember { mutableStateOf(false) }

    val label = if (insetTop == 0 && insetBottom == 0 && insetLeft == 0 && insetRight == 0) {
        "No safe area set"
    } else {
        "T:$insetTop  B:$insetBottom  L:$insetLeft  R:$insetRight"
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // No safeDrawing inset padding — editor must show the full physical display
            // so the user can see bezel cutoffs and set safe area to account for them.
            .testTag("safeAreaEditorScreen")
    ) {
        val density = LocalDensity.current
        val canvasW = maxWidth
        val canvasH = maxHeight
        val canvasWPx = with(density) { canvasW.toPx() }
        val canvasHPx = with(density) { canvasH.toPx() }

        // Fractions for each inset
        val topFrac = insetTop.toFloat() / VIDEO_H
        val bottomFrac = insetBottom.toFloat() / VIDEO_H
        val leftFrac = insetLeft.toFloat() / VIDEO_W
        val rightFrac = insetRight.toFloat() / VIDEO_W

        // --- Background: checkerboard simulating AA map ---
        Canvas(modifier = Modifier.fillMaxSize()) {
            val tileSize = 24f * density.density
            val cols = (size.width / tileSize).roundToInt() + 1
            val rows = (size.height / tileSize).roundToInt() + 1
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val color = if ((r + c) % 2 == 0) Color(0xFF141A25) else Color(0xFF1A2030)
                    drawRect(
                        color = color,
                        topLeft = Offset(c * tileSize, r * tileSize),
                        size = Size(
                            tileSize.coerceAtMost(size.width - c * tileSize),
                            tileSize.coerceAtMost(size.height - r * tileSize),
                        )
                    )
                }
            }
        }

        // --- Danger zones (shaded inset areas) ---
        Canvas(modifier = Modifier.fillMaxSize()) {
            val tPx = topFrac * size.height
            val bPx = bottomFrac * size.height
            val lPx = leftFrac * size.width
            val rPx = rightFrac * size.width
            if (tPx > 0) drawRect(DANGER_COLOR, Offset.Zero, Size(size.width, tPx))
            if (bPx > 0) drawRect(DANGER_COLOR, Offset(0f, size.height - bPx), Size(size.width, bPx))
            if (lPx > 0) drawRect(DANGER_COLOR, Offset(0f, tPx), Size(lPx, size.height - tPx - bPx))
            if (rPx > 0) drawRect(DANGER_COLOR, Offset(size.width - rPx, tPx), Size(rPx, size.height - tPx - bPx))
        }

        // --- Dashed safe area lines ---
        Canvas(modifier = Modifier.fillMaxSize()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
            val sw = 3f * density.density
            val tY = topFrac * size.height
            val bY = size.height - bottomFrac * size.height
            val lX = leftFrac * size.width
            val rX = size.width - rightFrac * size.width

            if (insetTop > 0) drawLine(SAFE_LINE_COLOR, Offset(0f, tY), Offset(size.width, tY), sw, pathEffect = dash)
            if (insetBottom > 0) drawLine(SAFE_LINE_COLOR, Offset(0f, bY), Offset(size.width, bY), sw, pathEffect = dash)
            if (insetLeft > 0) drawLine(SAFE_LINE_COLOR, Offset(lX, 0f), Offset(lX, size.height), sw, pathEffect = dash)
            if (insetRight > 0) drawLine(SAFE_LINE_COLOR, Offset(rX, 0f), Offset(rX, size.height), sw, pathEffect = dash)

            // Safe area rectangle border
            if (insetTop > 0 || insetBottom > 0 || insetLeft > 0 || insetRight > 0) {
                drawRect(
                    color = SAFE_LINE_COLOR.copy(alpha = 0.3f),
                    topLeft = Offset(lX, tY),
                    size = Size(rX - lX, bY - tY),
                    style = Stroke(width = 1.5f * density.density)
                )
            }
        }

        // --- Simulated AA UI elements that reposition with insets ---
        // Nav bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset {
                    IntOffset(
                        (leftFrac * canvasWPx).roundToInt(),
                        -(bottomFrac * canvasHPx).roundToInt()
                    )
                }
                .size(
                    with(density) { (canvasWPx * (1f - leftFrac - rightFrac)).toDp() },
                    36.dp
                )
                .background(Color(0xE6141420), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("🗺️", "📱", "🎵", "🔔", "⚙️").forEach { icon ->
                    Text(icon, fontSize = 16.sp)
                }
            }
        }

        // Status bar
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset {
                    IntOffset(
                        (leftFrac * canvasWPx).roundToInt(),
                        (topFrac * canvasHPx).roundToInt()
                    )
                }
                .size(
                    with(density) { (canvasWPx * (1f - leftFrac - rightFrac)).toDp() },
                    28.dp
                )
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("🔋 87%", "📶", "2:45").forEach { item ->
                Text(
                    item,
                    fontSize = 10.sp,
                    color = Color(0xFF888888),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        // "Now Playing" card — top-right inside safe area
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset {
                    IntOffset(
                        -(rightFrac * canvasWPx + 12 * density.density).roundToInt(),
                        (topFrac * canvasHPx + 40 * density.density).roundToInt()
                    )
                }
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xE81E1E30))
                .padding(10.dp)
        ) {
            Column {
                Text("🎵 Take Me Home", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("John Denver", fontSize = 10.sp, color = Color(0xFF888888))
            }
        }

        // --- Drag handles ---

        // Top handle
        var dragAccTop by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset {
                    val y = (topFrac * canvasHPx).roundToInt()
                    IntOffset(0, y)
                }
                .size(canvasW, HANDLE_W_DP.dp)
                .background(HANDLE_BG, RoundedCornerShape(4.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragAccTop = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragAccTop += dragAmount.y
                            val deltaPx = (dragAccTop / canvasHPx * VIDEO_H).roundToInt()
                            if (deltaPx != 0) {
                                insetTop = (insetTop + deltaPx).coerceIn(0, MAX_INSET)
                                dragAccTop = 0f
                            }
                        }
                    )
                }
                .testTag("dragTop")
        ) {
            GripLines(horizontal = true, modifier = Modifier.fillMaxSize())
        }

        // Bottom handle
        var dragAccBottom by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset {
                    val y = -(bottomFrac * canvasHPx).roundToInt()
                    IntOffset(0, y)
                }
                .size(canvasW, HANDLE_W_DP.dp)
                .background(HANDLE_BG, RoundedCornerShape(4.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragAccBottom = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragAccBottom -= dragAmount.y
                            val deltaPx = (dragAccBottom / canvasHPx * VIDEO_H).roundToInt()
                            if (deltaPx != 0) {
                                insetBottom = (insetBottom + deltaPx).coerceIn(0, MAX_INSET)
                                dragAccBottom = 0f
                            }
                        }
                    )
                }
                .testTag("dragBottom")
        ) {
            GripLines(horizontal = true, modifier = Modifier.fillMaxSize())
        }

        // Left handle
        var dragAccLeft by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset {
                    val x = (leftFrac * canvasWPx).roundToInt()
                    IntOffset(x, 0)
                }
                .size(HANDLE_W_DP.dp, canvasH)
                .background(HANDLE_BG, RoundedCornerShape(4.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragAccLeft = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragAccLeft += dragAmount.x
                            val deltaPx = (dragAccLeft / canvasWPx * VIDEO_W).roundToInt()
                            if (deltaPx != 0) {
                                insetLeft = (insetLeft + deltaPx).coerceIn(0, MAX_INSET)
                                dragAccLeft = 0f
                            }
                        }
                    )
                }
                .testTag("dragLeft")
        ) {
            GripLines(horizontal = false, modifier = Modifier.fillMaxSize())
        }

        // Right handle
        var dragAccRight by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset {
                    val x = -(rightFrac * canvasWPx).roundToInt()
                    IntOffset(x, 0)
                }
                .size(HANDLE_W_DP.dp, canvasH)
                .background(HANDLE_BG, RoundedCornerShape(4.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragAccRight = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragAccRight -= dragAmount.x
                            val deltaPx = (dragAccRight / canvasWPx * VIDEO_W).roundToInt()
                            if (deltaPx != 0) {
                                insetRight = (insetRight + deltaPx).coerceIn(0, MAX_INSET)
                                dragAccRight = 0f
                            }
                        }
                    )
                }
                .testTag("dragRight")
        ) {
            GripLines(horizontal = false, modifier = Modifier.fillMaxSize())
        }

        // --- Toolbar: top center, respects display mode system bars ---
        // Compute inset modifier matching the current display mode so buttons
        // aren't rendered behind the status bar.
        val toolbarInsetModifier = when (displayMode) {
            "system_ui_visible" -> Modifier.windowInsetsPadding(WindowInsets.systemBars)
            "status_bar_hidden" -> Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            "nav_bar_hidden" -> Modifier.windowInsetsPadding(WindowInsets.statusBars)
            else -> Modifier // fullscreen_immersive, custom_viewport — no insets
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .then(toolbarInsetModifier)
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xCC1E1E1E))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Cancel button
            FilledTonalButton(
                onClick = onBack,
                modifier = Modifier.testTag("cancelButton"),
            ) { Text("Cancel") }

            // Clear button
            FilledTonalButton(
                onClick = {
                    insetTop = 0; insetBottom = 0; insetLeft = 0; insetRight = 0
                },
                modifier = Modifier.testTag("clearButton"),
            ) { Text("Clear") }

            // Save button
            FilledTonalButton(
                onClick = { onDone(insetTop, insetBottom, insetLeft, insetRight) },
                modifier = Modifier.testTag("saveButton"),
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Save",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Save")
            }
        }

        // --- Dimension label: bottom center, tappable for manual input ---
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        ) {
            if (showManualInput) {
                SafeAreaManualInput(
                    top = insetTop, bottom = insetBottom,
                    left = insetLeft, right = insetRight,
                    onApply = { t, b, l, r ->
                        insetTop = t; insetBottom = b; insetLeft = l; insetRight = r
                        showManualInput = false
                    },
                    onDismiss = { showManualInput = false },
                )
            } else {
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC1E1E1E))
                        .clickable { showManualInput = true }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("dimensionLabel"),
                )
            }
        }

        // --- Per-edge value labels near each line ---
        if (insetTop > 0) {
            Text(
                text = "↓ $insetTop",
                color = SAFE_LINE_COLOR,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, (topFrac * canvasHPx + 4 * density.density).roundToInt()) }
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x4000201A))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        if (insetBottom > 0) {
            Text(
                text = "↑ $insetBottom",
                color = SAFE_LINE_COLOR,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset { IntOffset(0, -(bottomFrac * canvasHPx + 4 * density.density).roundToInt()) }
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x4000201A))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        if (insetLeft > 0) {
            Text(
                text = "→ $insetLeft",
                color = SAFE_LINE_COLOR,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset((leftFrac * canvasWPx + 4 * density.density).roundToInt(), 0) }
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x4000201A))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        if (insetRight > 0) {
            Text(
                text = "← $insetRight",
                color = SAFE_LINE_COLOR,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset { IntOffset(-(rightFrac * canvasWPx + 4 * density.density).roundToInt(), 0) }
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x4000201A))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun GripLines(horizontal: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    Canvas(modifier = modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val gripLen = 40f * density.density
        val spacing = 5f * density.density
        if (horizontal) {
            for (i in -1..1) {
                drawLine(
                    color = HANDLE_GRIP,
                    start = Offset(cx - gripLen / 2, cy + i * spacing),
                    end = Offset(cx + gripLen / 2, cy + i * spacing),
                    strokeWidth = 2f * density.density
                )
            }
        } else {
            for (i in -1..1) {
                drawLine(
                    color = HANDLE_GRIP,
                    start = Offset(cx + i * spacing, cy - gripLen / 2),
                    end = Offset(cx + i * spacing, cy + gripLen / 2),
                    strokeWidth = 2f * density.density
                )
            }
        }
    }
}

@Composable
private fun SafeAreaManualInput(
    top: Int, bottom: Int, left: Int, right: Int,
    onApply: (top: Int, bottom: Int, left: Int, right: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var tText by remember { mutableStateOf(top.toString()) }
    var bText by remember { mutableStateOf(bottom.toString()) }
    var lText by remember { mutableStateOf(left.toString()) }
    var rText by remember { mutableStateOf(right.toString()) }

    fun parse(s: String) = (s.toIntOrNull() ?: 0).coerceIn(0, MAX_INSET)

    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
        modifier = Modifier.testTag("safeAreaManualInput"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = tText, onValueChange = { tText = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Top") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.width(80.dp),
                )
                OutlinedTextField(
                    value = bText, onValueChange = { bText = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Bottom") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.width(80.dp),
                )
                OutlinedTextField(
                    value = lText, onValueChange = { lText = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Left") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.width(80.dp),
                )
                OutlinedTextField(
                    value = rText, onValueChange = { rText = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("Right") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onApply(parse(tText), parse(bText), parse(lText), parse(rText)) }),
                    modifier = Modifier.width(80.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onApply(parse(tText), parse(bText), parse(lText), parse(rText)) }) {
                    Text("Apply")
                }
                FilledTonalButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    }
}

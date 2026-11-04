package com.openautolink.app.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.cluster.PixelSize

/** Drawing coordinates are physical reference pixels, never a video/host qualification. */
data class LiveGridGeometry(val panel: PixelSize, val canvas: PixelRect, val output: PixelRect, val safe: PixelRect,
    val essentialCanvas: PixelRect = canvas) {
    init { require(panel.bounds.contains(canvas) && panel.bounds.contains(essentialCanvas) && canvas.contains(output) &&
        output.contains(safe) && essentialCanvas.contains(safe)) }
}
internal fun ClusterLayoutModel.gridGeometry() = LiveGridGeometry(key.panel, canvas, output, safe, essentialCanvas)

/** Transparent View layer only. Never receives a borrowed Surface or clears a display buffer. */
class LiveTransparentGridView(context: Context) : View(context) {
    var geometry: LiveGridGeometry? = null
        set(value) { field = value; invalidate() }
    var active: Boolean = false
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val watermark = OalGridStoreMark.read(context)
    init { setBackgroundColor(Color.TRANSPARENT); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val model = geometry?.takeIf { active } ?: return
        // Local editor uniformly fits; external window sets physical scaling via geometry/panel.
        val scale = minOf(width.toFloat() / model.panel.width, height.toFloat() / model.panel.height)
        val save = canvas.save()
        canvas.translate((width - model.panel.width * scale) / 2, (height - model.panel.height * scale) / 2)
        canvas.scale(scale, scale)
        drawLiveTransparentGrid(canvas, model, paint, watermark)
        canvas.restoreToCount(save)
    }
}

/** No drawColor, bitmap field or whole-panel clear: empty pixels retain alpha zero. */
internal fun drawLiveTransparentGrid(canvas: Canvas, model: LiveGridGeometry, p: Paint = Paint(Paint.ANTI_ALIAS_FLAG),
    watermark: android.graphics.Bitmap? = null) {
    val save = canvas.save()
    try {
        canvas.clipRect(model.canvas.left, model.canvas.top, model.canvas.right, model.canvas.bottom)
        canvas.clipRect(model.output.left, model.output.top, model.output.right, model.output.bottom)
        // Actual drawable clip after window-origin translation and authorized output/canvas clips.
        // Draw behind sparse outlines, never an opaque tile or whole-window opacity change.
        watermark?.let { logo ->
            val drawing = canvas.clipBounds
            if (!drawing.isEmpty && logo.width > 0 && logo.height > 0) {
                val ratio = logo.width.toFloat() / logo.height
                val width = minOf(drawing.width() * .55f, drawing.height() * .70f * ratio)
                val height = width / ratio
                val cx = drawing.exactCenterX(); val cy = drawing.exactCenterY()
                val destination = android.graphics.RectF(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2)
                val translucent = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 64 }
                canvas.drawBitmap(logo, null, destination, translucent)
            }
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Color.argb(180, 55, 208, 232)
        for (i in 1..9) {
            val x = model.output.left + model.output.width * i / 10f
            val y = model.output.top + model.output.height * i / 10f
            canvas.drawLine(x, model.output.top.toFloat(), x, model.output.bottom.toFloat(), p)
            canvas.drawLine(model.output.left.toFloat(), y, model.output.right.toFloat(), y, p)
        }
        fun outline(r: PixelRect, color: Int) {
            p.color = color; p.strokeWidth = 3f
            canvas.drawRect(r.left + 2f, r.top + 2f, r.right - 2f, r.bottom - 2f, p)
        }
        outline(model.output, Color.CYAN); outline(model.safe, Color.rgb(80, 216, 144))
    } finally { canvas.restoreToCount(save) }
}

/** One memory-only lease. Updates retain its original deadline; stale Stop cannot retire a successor. */
internal class LiveGridDraftRun(
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val schedule: (Long, () -> Unit) -> (() -> Unit) = { milliseconds, action ->
        val handler = Handler(Looper.getMainLooper()); val task = Runnable(action)
        handler.postDelayed(task, milliseconds); { handler.removeCallbacks(task) }
    },
    private val maxSeconds: Int = 15,
) {
    private data class Run(val owner: Any, val deadline: Long, val render: (LiveGridGeometry?) -> Unit, var cancel: (() -> Unit)? = null)
    private var run: Run? = null
    fun start(owner: Any, geometry: LiveGridGeometry, seconds: Int, render: (LiveGridGeometry?) -> Unit) {
        check(run == null) { "Stop the current grid first" }
        require(seconds in 1..maxSeconds)
        val next = Run(owner, now() + seconds * 1000L, render)
        run = next
        try {
            render(geometry)
            next.cancel = schedule(seconds * 1000L) { if (run === next) stop(owner) }
        } catch (failure: Exception) { stop(owner); throw failure }
    }
    fun update(owner: Any, geometry: LiveGridGeometry): Boolean {
        val exact = run?.takeIf { it.owner === owner } ?: return false
        if (now() >= exact.deadline) { stop(owner); return false }
        try { exact.render(geometry) } catch (failure: Exception) { stop(owner); throw failure }
        return true
    }
    fun stop(owner: Any) {
        val exact = run?.takeIf { it.owner === owner } ?: return
        run = null; exact.cancel?.invoke(); exact.render(null)
    }
    fun active(owner: Any): Boolean {
        val exact = run?.takeIf { it.owner === owner } ?: return false
        if (now() >= exact.deadline) { stop(owner); return false }
        return true
    }
}

/** Direct window binding, independent of encoded phone frames and legacy hosted map sessions. */
data class LiveGridPreviewBinding(
    val owner: Any,
    val destination: String,
    val start: (LiveGridGeometry, Int) -> Unit,
    val update: (LiveGridGeometry) -> Boolean,
    val stop: () -> Unit,
)

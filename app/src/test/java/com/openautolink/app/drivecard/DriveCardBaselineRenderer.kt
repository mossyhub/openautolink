package com.openautolink.app.drivecard

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import com.openautolink.app.secondary.SecondaryHostGeometry
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/** Original GM-inspired OAL drawing; physical fit still requires vehicle validation. ocbm DriveCard/ClusterLayout
 * inspiration remains credited in NOTICE (Unlicense); no donor telemetry or calibration.
 * Content bounds are an app policy, not proof of an OEM occlusion mask. */
internal class DriveCardBaselineRenderer(private val face: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val normal = Color.rgb(233, 234, 238)
    private val units = Color.rgb(173, 194, 221)
    private val blue = Color.rgb(82, 133, 197)
    private val orange = Color.rgb(255, 167, 40)

    fun draw(canvas: Canvas, geometry: SecondaryHostGeometry, state: DriveCardState,
        layout: DriveCardLayout? = null, imperial: Boolean = false) {
        if (geometry.width < 320 || geometry.height < 160) return
        val selected = layout ?: DriveCardLayout.DEFAULT
        val b = selected.box
        val width = ((b.right - b.left) * geometry.width).toFloat()
        val height = ((b.bottom - b.top) * geometry.height).toFloat()
        val save = canvas.save()
        canvas.clipRect(0, 0, geometry.width, geometry.height)
        canvas.translate((b.left * geometry.width).toFloat(), (b.top * geometry.height).toFloat())
        // Content placement stays saved independently of the continuing black dock.
        // The dock uses only this already-owned window and preserves its inherited clip.
        val shoulder = min(width, height) * .06f
        val slant = (width * selected.slant).toFloat()
        val backingWidth = ((1.0 - b.left) * geometry.width).toFloat()
        val backingHeight = ((1.0 - b.top) * geometry.height).toFloat()
        val leftLimit = ((.5 - b.left) * geometry.width).toFloat()
        val continuedLeft = slant * (1f - backingHeight / height)
        path.reset()
        path.moveTo(slant + shoulder, 0f)
        path.lineTo(backingWidth, 0f); path.lineTo(backingWidth, backingHeight)
        path.lineTo(continuedLeft.coerceAtLeast(leftLimit), backingHeight)
        if (slant > 0f && continuedLeft < leftLimit) {
            // Continue the saved slope only until the reserved left half, then run vertically.
            path.lineTo(leftLimit, height * (1f - leftLimit / slant))
        }
        path.lineTo((slant - shoulder * slant / height).coerceAtLeast(0f), shoulder)
        path.quadTo(slant, 0f, slant + shoulder, 0f)
        path.close()
        paint.shader = null; paint.style = Paint.Style.FILL; paint.color = Color.BLACK
        canvas.drawPath(path, paint)

        // Preserve the original content shoulder, clipping, transform and physical glyph anchors.
        path.reset()
        path.moveTo(slant + shoulder, 0f)
        path.lineTo(width, 0f); path.lineTo(width, height); path.lineTo(0f, height)
        path.lineTo((slant - shoulder * slant / height).coerceAtLeast(0f), shoulder)
        path.quadTo(slant, 0f, slant + shoulder, 0f)
        path.close()
        paint.color = Color.WHITE
        // Localized neutral light extinguishes before either content edge; the dock stays
        // opaque RGB-zero rather than a near-black panel or transparent map-revealing fade.
        paint.shader = RadialGradient(0f, 0f, min(width, height) * .8f,
            Color.rgb(58, 59, 62), Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        paint.shader = null
        canvas.clipPath(path)
        // Preserve glyph proportions and top anchor; the dock is taller than its content.
        val scale = min(width / 560f, height / 250f)
        canvas.translate(width - 560f * scale, 0f)
        canvas.scale(scale, scale)
        val dockHeight = height / scale
        val powerBaseline = dockHeight - 72f
        val arrivalBaseline = dockHeight - 24f

        val now = state.nowKmPerKwh?.takeIf { it.isFinite() && it > 0 }
        val recent = state.recentKmPerKwh?.takeIf { it.isFinite() && it > 0 }
        val rate = now ?: recent
        val charging = state.availability == DriveCardAvailability.CHARGING
        if (charging) {
            text(canvas, "Battery", 94f, 28f, 23f, units)
            text(canvas, "Charging", 94f, 106f, 46f, Color.WHITE, maxWidth = 435f)
            text(canvas, "Efficiency resumes while driving", 94f, 148f, 23f, units, maxWidth = 435f)
        } else {
            text(canvas, when { now != null -> "NOW"; recent != null -> "RECENT"; else -> "EFFICIENCY" },
                94f, 28f, 22f, units)
            text(canvas, rate?.let { figure(DriveCardUnits.efficiency(it, imperial)) } ?: "—",
                94f, 119f, if (rate != null) 92f else 60f, Color.WHITE, maxWidth = 214f)
            text(canvas, if (imperial) "mi/kWh" else "km/kWh", 94f, 146f, 25f, units)
            // Headline readiness must not hide a genuine first completed window.
            drawGraph(canvas, state.trace.map { DriveCardUnits.efficiency(it, imperial) })
            if (rate == null && state.trace.isEmpty()) {
                val unavailable = state.availability == DriveCardAvailability.UNAVAILABLE
                text(canvas, if (unavailable) "Vehicle data" else "Collecting", 310f, 80f, 21f, normal, maxWidth = 220f)
                text(canvas, if (unavailable) "unavailable" else "drive data", 310f, 108f, 21f, normal, maxWidth = 220f)
            }
            state.powerKw?.takeIf { it.isFinite() }?.let { power ->
                // Numeric source and direction, deliberately NOT a calibrated magnitude gauge.
                val basis = when (state.powerBasis) {
                    DriveCardPowerBasis.BATTERY -> "Battery"
                    DriveCardPowerBasis.MOTOR -> "Motor"
                    null -> "Power"
                }
                val direction = when {
                    power > 0 -> "Use"
                    power < 0 && state.powerBasis == DriveCardPowerBasis.BATTERY -> "Inflow"
                    power < 0 -> "Return"
                    else -> "Neutral"
                }
                text(canvas, basis, 98f, powerBaseline, 23f, normal)
                text(canvas, direction, 224f, powerBaseline, 21f, if (power > 0) orange else units, maxWidth = 100f)
                val number = String.format(Locale.US, if (abs(power) in 0.001..9.999) "%.1f" else "%.0f", abs(power))
                val signed = (if (power < 0) "−" else "") + number
                paint.typeface = face; paint.textSize = 25f
                val unitX = 530f - paint.measureText("kW")
                paint.textSize = 38f
                val numberSize = min(38f, 160f / paint.measureText(signed) * 38f)
                paint.textSize = numberSize
                val numberX = unitX - 8f - paint.measureText(signed)
                text(canvas, signed, numberX, powerBaseline, numberSize, Color.WHITE)
                text(canvas, "kW", unitX, powerBaseline, 25f, units)
            }
        }
        state.arrival?.takeIf { !charging && it.percent in 0..100 }?.let { arrival ->
            // Separate lower row: sourced forecast or qualified explicitly approximate local value.
            paint.color = units; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
            canvas.drawRect(98f, arrivalBaseline - 18f, 128f, arrivalBaseline - 1f, paint)
            canvas.drawRect(128f, arrivalBaseline - 13f, 132f, arrivalBaseline - 6f, paint)
            paint.style = Paint.Style.FILL; paint.color = normal
            canvas.drawRect(101f, arrivalBaseline - 15f, 101f + 24f * arrival.percent / 100f, arrivalBaseline - 4f, paint)
            text(canvas, "${if (arrival.approximate) "≈" else ""}${arrival.percent}% on arrival", 145f, arrivalBaseline, 28f, normal, maxWidth = 385f)
        }
        canvas.restoreToCount(save)
    }

    private fun drawGraph(canvas: Canvas, values: List<Double>) {
        val maximum = maxOf(1.0, values.filter { it.isFinite() && it > 0 }.maxOrNull() ?: return) * 1.12
        fun x(index: Int) = if (values.size == 1) 531f else 324f + 207f * index / (values.size - 1)
        fun y(value: Double) = 128f - (value / maximum * 95).toFloat()
        var start = 0
        while (start < values.size) {
            if (!values[start].isFinite() || values[start] <= 0) { start++; continue }
            var end = start
            while (end + 1 < values.size && values[end + 1].isFinite() && values[end + 1] > 0) end++
            // Each positive run owns its own fill. Never bridge regen/invalid history gaps.
            if (end > start) {
                path.reset(); path.moveTo(x(start), 132f)
                for (i in start..end) path.lineTo(x(i), y(values[i]))
                path.lineTo(x(end), 132f); path.close()
                paint.style = Paint.Style.FILL; paint.color = Color.WHITE
                paint.shader = LinearGradient(0f, 30f, 0f, 132f,
                    Color.argb(90, 63, 111, 216), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawPath(path, paint)
                paint.shader = null
                path.reset(); path.moveTo(x(start), y(values[start]))
                for (i in start + 1..end) path.lineTo(x(i), y(values[i]))
                paint.color = blue; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
                paint.strokeJoin = Paint.Join.ROUND; canvas.drawPath(path, paint)
            }
            // Isolated genuine observations stay visible without inventing a connecting line.
            if (end == start) {
                paint.style = Paint.Style.FILL; paint.color = blue
                canvas.drawCircle(x(start), y(values[start]), 3f, paint)
            }
            start = end + 1
        }
        val latest = values.lastOrNull()
        if (latest != null && latest.isFinite() && latest > 0) {
            paint.style = Paint.Style.FILL; paint.color = orange
            canvas.drawCircle(x(values.lastIndex), y(latest), 5f, paint)
        }
        paint.style = Paint.Style.FILL
    }
    private fun figure(value: Double) = if (value > 9.9) ">9.9" else String.format(Locale.US, "%.1f", value)
    private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, maxWidth: Float = Float.MAX_VALUE) {
        paint.style = Paint.Style.FILL; paint.color = color
        paint.typeface = if (size >= 80f) Typeface.create(face, Typeface.BOLD) else face
        paint.textSize = size
        val measured = paint.measureText(value)
        if (measured > maxWidth) paint.textSize = size * maxWidth / measured
        canvas.drawText(value, x, y, paint)
    }
}

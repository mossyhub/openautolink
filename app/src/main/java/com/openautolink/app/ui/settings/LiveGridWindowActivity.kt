package com.openautolink.app.ui.settings

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.openautolink.app.secondary.*

/** Separate normal transparent app window. Never starts CarAppService or owns navigation. */
class LiveGridWindowActivity : Activity() {
    private var admitted: LiveGridWindows.Request? = null
    private var grid: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayChanged(id: Int) { if (id == admitted?.display?.displayId) closeGrid() }
        override fun onDisplayRemoved(id: Int) { if (id == admitted?.display?.displayId) closeGrid() }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val request = LiveGridWindows.admit(this, intent.getLongExtra("gridRequest", 0), state == null)
        if (request == null) { finish(); return }
        admitted = request; request.activity = this
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.LEFT; dimAmount = 0f
            x = request.geometry.output.left; y = request.geometry.output.top
            width = request.geometry.output.width; height = request.geometry.output.height
            setFitInsetsTypes(0)
        }
        grid = object : View(this) {
            private val locationOnScreen = IntArray(2)
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val watermark = OalGridStoreMark.read(this@LiveGridWindowActivity)
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (!LiveGridWindows.current(request)) { closeGrid(); return }
                getLocationOnScreen(locationOnScreen)
                val save = canvas.save()
                // Actual window origin, not requested launch bounds; OEM may ignore the request.
                canvas.translate(-locationOnScreen[0].toFloat(), -locationOnScreen[1].toFloat())
                drawLiveTransparentGrid(canvas, request.geometry, paint, watermark)
                canvas.restoreToCount(save)
                request.drawn = true
            }
        }
        setContentView(grid)
        applyBounds()
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(listener, handler)
        handler.postDelayed({ closeGrid() }, (request.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        handler.post(object : Runnable {
            override fun run() {
                if (admitted !== request || !LiveGridWindows.current(request)) closeGrid()
                else handler.postDelayed(this, 250)
            }
        })
    }
    private fun applyBounds() {
        val request = admitted ?: return
        // PhoneWindow may reset floating dimensions while installing decor. Apply the exact
        // output origin AND size after content, attach/start, and each same-window edit.
        window.attributes = window.attributes.apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = request.geometry.output.left; y = request.geometry.output.top
            width = request.geometry.output.width; height = request.geometry.output.height
            setFitInsetsTypes(0)
        }
        window.setLayout(request.geometry.output.width, request.geometry.output.height)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); applyBounds() }
    override fun onStart() { super.onStart(); applyBounds() }
    internal fun refreshGrid() { applyBounds(); grid?.invalidate() }
    private fun closeGrid() { admitted?.let { LiveGridWindows.stop(it, this) }; finishAndRemoveTask() }
    override fun onStop() { super.onStop(); if (admitted != null) closeGrid() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(listener) }
        admitted?.let { LiveGridWindows.destroyed(it, this) }; admitted = null
        super.onDestroy()
    }
}

/** Main-thread one-shot admission; no persisted consent, no implicit relaunch. */
internal object LiveGridWindows {
    class Request(val sequence: Long, val owner: Any, val context: Context, val display: SecondaryDisplayDescriptor,
        var geometry: LiveGridGeometry, val admissionUntil: Long, val deadline: Long, val idle: () -> Boolean) {
        var activity: LiveGridWindowActivity? = null
        var drawn = false
    }
    private var sequence = 0L
    private var request: Request? = null
    private fun main() { check(Looper.myLooper() == Looper.getMainLooper()) }
    private fun targetMatches(context: Context, descriptor: SecondaryDisplayDescriptor): Boolean {
        val current = SecondaryDisplayPolicyInventory.get(context).snapshots.value.displays.singleOrNull { it.id == descriptor.id }
        if (current != descriptor || descriptor.reason != SecondaryReason.NONE || descriptor.route == SecondaryRoute.INVENTORY_ONLY) return false
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(descriptor.displayId) ?: return false
        val metrics = DisplayMetrics().also { @Suppress("DEPRECATION") display.getRealMetrics(it) }
        return descriptor.displayId != Display.DEFAULT_DISPLAY && display.isValid && display.state == Display.STATE_ON &&
            display.flags and Display.FLAG_PRIVATE == 0 && metrics.widthPixels == descriptor.width && metrics.heightPixels == descriptor.height
    }
    fun binding(context: Context, descriptor: SecondaryDisplayDescriptor, directionsArea: Boolean = false,
        idle: () -> Boolean): LiveGridPreviewBinding {
        val owner = Any()
        return LiveGridPreviewBinding(owner, "${descriptor.name} · direct transparent window",
            { geometry, seconds -> start(context, descriptor, idle, owner, geometry, seconds, if (directionsArea) 30 else 15) },
            { geometry -> update(owner, geometry) }, { stop(owner) })
    }
    private fun start(context: Context, descriptor: SecondaryDisplayDescriptor, idle: () -> Boolean,
        owner: Any, geometry: LiveGridGeometry, seconds: Int, maxSeconds: Int) {
        main(); require(seconds in 1..maxSeconds); check(request == null) { "Stop the current preview first." }
        check(idle()) { "Disconnect your phone to preview on driver display." }
        check(targetMatches(context, descriptor)) { "Display disconnected. Return to settings and choose a display." }
        require(geometry.panel.width == descriptor.width && geometry.panel.height == descriptor.height)
        val intent = Intent(context, LiveGridWindowActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        check(context.getSystemService(ActivityManager::class.java)?.isActivityStartAllowedOnDisplay(context, descriptor.displayId, intent) == true) {
            "Preview unavailable on this display. Return to settings and choose a display."
        }
        val now = SystemClock.elapsedRealtime()
        val next = Request(++sequence, owner, context.applicationContext, descriptor, geometry, now + 5000, now + seconds * 1000L, idle)
        request = next
        val bounds = android.graphics.Rect(geometry.output.left, geometry.output.top, geometry.output.right, geometry.output.bottom)
        try {
            context.startActivity(intent.putExtra("gridRequest", next.sequence), ActivityOptions.makeBasic()
                .setLaunchDisplayId(descriptor.displayId).setLaunchBounds(bounds).toBundle())
            Handler(Looper.getMainLooper()).postDelayed({ if (request === next) stop(owner) }, seconds * 1000L)
        } catch (failure: Exception) { stop(owner); throw failure }
    }
    fun admit(activity: Activity, token: Long, fresh: Boolean): Request? {
        main()
        val exact = request ?: return null
        @Suppress("DEPRECATION") val actual = activity.windowManager.defaultDisplay.displayId
        if (!fresh || token != exact.sequence || actual != exact.display.displayId || exact.activity != null ||
            SystemClock.elapsedRealtime() >= exact.admissionUntil || !current(exact)) {
            if (token == exact.sequence) stop(exact.owner)
            return null
        }
        return exact
    }
    fun current(exact: Request): Boolean = request === exact && SystemClock.elapsedRealtime() < exact.deadline &&
        exact.idle() && targetMatches(exact.context, exact.display)
    private fun update(owner: Any, geometry: LiveGridGeometry): Boolean {
        main(); val exact = request?.takeIf { it.owner === owner } ?: return false
        if (!current(exact) || geometry.panel != exact.geometry.panel || geometry.canvas != exact.geometry.canvas) { stop(owner); return false }
        exact.geometry = geometry; exact.activity?.refreshGrid(); return true
    }
    fun stop(owner: Any) {
        main(); val exact = request?.takeIf { it.owner === owner } ?: return
        request = null; exact.activity?.finishAndRemoveTask()
    }
    fun stop(exact: Request, activity: Activity) {
        main(); if (request !== exact || exact.activity !== activity) return
        request = null; exact.activity?.finishAndRemoveTask()
    }
    fun destroyed(exact: Request, activity: Activity) { main(); if (request === exact && exact.activity === activity) request = null }
}

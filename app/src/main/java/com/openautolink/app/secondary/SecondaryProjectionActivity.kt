package com.openautolink.app.secondary

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import com.openautolink.app.cluster.PixelRect
import kotlin.math.max
import kotlin.math.min

/** An opaque, noninteractive public-display canvas. No audio, input, overlays or navigation owner. */
open class SecondaryProjectionActivity : Activity(), SurfaceHolder.Callback {
    private val retainedWindow get() = this is com.openautolink.app.cluster.ClusterDirectVideoActivity
    private var textureView: android.view.TextureView? = null
    private var textureSurface: android.view.Surface? = null
    private val ownedTextures = java.util.IdentityHashMap<android.graphics.SurfaceTexture, android.view.Surface>()

    private var started = false
    private var token: String? = null
    private var admitted = false
    private var hidden = false
    private var surfaceView: SurfaceView? = null
    private var concealment: View? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var callbackSequence = 0L
    private var lastPublishObservation: String? = null

    /** Own public callbacks only. None of these facts identify the driver's OEM page. */
    private fun observeCallback(event: String, value: Int = 0, texture: android.graphics.SurfaceTexture? = textureView?.surfaceTexture) {
        if (!retainedWindow) return
        runCatching {
            val view = textureView
            val surface = textureSurface
            PageReturnDiagnostics.tryOffer(
                "DIRECT_CALLBACK_V1 pid=${android.os.Process.myPid()} activity=${System.identityHashCode(this)} seq=${++callbackSequence} elapsedMs=${android.os.SystemClock.elapsedRealtime()} event=$event value=$value started=$started admitted=$admitted hidden=$hidden attached=${view?.isAttachedToWindow} shown=${view?.isShown} window=${view?.windowVisibility} texture=${System.identityHashCode(texture)} surface=${System.identityHashCode(surface)} valid=${surface?.isValid} size=${view?.width}x${view?.height} physical=${display?.displayId} oemPage=unknown")
        }
    }
    private fun observePublish(reason: String) {
        if (lastPublishObservation == reason) return
        lastPublishObservation = reason
        observeCallback("publish_$reason")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.black)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.setDecorFitsSystemWindows(false)
        token = intent?.getStringExtra(SecondaryHostRegistry.TOKEN_EXTRA)
        // No id/epoch/Surface from Intent can establish authority. Token is process-only, one-use.
        admitted = SecondaryHostRegistry.bind(this, token)
        observeCallback("create")
        if (!admitted) { finish(); return }
        if (retainedWindow) { installRetainedCanvas(); return }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = false
            isFocusable = false
        }
        val canvas = SurfaceView(this).apply {
            isFocusable = false
            isClickable = false
            holder.setFormat(PixelFormat.OPAQUE)
            holder.addCallback(this@SecondaryProjectionActivity)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> publishMeasured() }
            setOnApplyWindowInsetsListener { _, insets -> publishMeasured(); insets }
        }
        surfaceView = canvas
        root.addView(canvas, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        canvas.requestApplyInsets()
    }
    override fun surfaceCreated(holder: SurfaceHolder) { publishMeasured() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        publishMeasured()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // OS owns this Surface; we cannot postpone platform destruction or release it ourselves.
        withdraw()
    }
    private fun publishMeasured() {
        val view = surfaceView ?: return
        // Layout/insets can arrive before surfaceCreated (and after destruction).
        // Missing OS surface is pending, never a renderer-ready receipt.
        val surface = view.holder.surface ?: return
        if (!admitted || hidden || !view.isAttachedToWindow || !surface.isValid ||
            surfaceWidth <= 0 || surfaceHeight <= 0 || view.width != surfaceWidth || view.height != surfaceHeight) return
        val insets = view.rootWindowInsets ?: return
        val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val position = IntArray(2).also { view.getLocationInWindow(it) }
        val decor = window.decorView
        val left = max(0, safe.left - position[0])
        val top = max(0, safe.top - position[1])
        val right = min(view.width, decor.width - safe.right - position[0])
        val bottom = min(view.height, decor.height - safe.bottom - position[1])
        if (right <= left || bottom <= top) { withdraw(); return }
        SecondaryHostRegistry.present(this, token, surface,
            SecondaryHostGeometry(view.width, view.height, PixelRect(left, top, right, bottom)))
    }
    internal fun concealSurface() {
        hidden = true
        // INVISIBLE destroys SurfaceView's native window before the renderer's asynchronous
        // black-swap/detach. Cover the old image in the window instead, keeping its borrowed
        // Surface alive until the exact GPU retirement receipt lets the registry finish us.
        if (concealment != null) return
        val parent = surfaceView?.parent as? FrameLayout ?: return
        concealment = View(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = false
            isFocusable = false
        }.also { parent.addView(it, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)) }
    }
    private fun withdraw() {
        if (!admitted) return
        concealSurface()
        SecondaryHostRegistry.withdraw(this, token)
    }
    private fun installRetainedCanvas() {

        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0f; if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0) }
        val view = object : android.view.TextureView(this) {
            override fun onWindowVisibilityChanged(visibility: Int) {
                super.onWindowVisibilityChanged(visibility)
                observeCallback("window_visibility", visibility)
                if (visibility != View.VISIBLE) SecondaryHostRegistry.suspend(this@SecondaryProjectionActivity, token, source = "window_visibility")
                else if (started) { SecondaryHostRegistry.resume(this@SecondaryProjectionActivity, token); publishTexture() }
            }
            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                observeCallback("view_attached")
            }
            override fun onDetachedFromWindow() {
                observeCallback("view_detached")
                SecondaryHostRegistry.suspend(this@SecondaryProjectionActivity, token, source = "view_detached")
                super.onDetachedFromWindow()
            }
        }
        textureView = view
        view.isOpaque = true
        view.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                observeCallback("texture_available", texture = texture)
                if (ownedTextures.containsKey(texture)) { publishTexture(); return }
                textureSurface = android.view.Surface(texture)
                ownedTextures[texture] = requireNotNull(textureSurface)
                publishTexture()
            }
            override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                observeCallback("texture_size", texture = texture)
                publishTexture()
            }
            override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean {
                observeCallback("texture_destroyed", texture = texture)
                val exact = ownedTextures.remove(texture) ?: return true
                if (textureSurface === exact) textureSurface = null
                // Registry retains only exact outstanding release debt, not one terminal
                // future callback (and dead Activity) per historical page cycle.
                SecondaryHostRegistry.releaseTexture(token, exact) {
                    try { exact.release() } finally { texture.release() }
                }
                return false
            }
        }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> publishTexture() }
        view.setOnApplyWindowInsetsListener { _, insets -> publishTexture(); insets }
        setContentView(view)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        view.requestApplyInsets()
    }
    private fun publishTexture() {
        val view = textureView ?: run { observePublish("no_view"); return }
        val surface = textureSurface ?: run { observePublish("no_surface"); return }
        if (!admitted || !started || hidden || !view.isAttachedToWindow || !view.isShown || view.windowVisibility != View.VISIBLE ||
            !surface.isValid || view.width <= 0 || view.height <= 0) { observePublish("not_ready"); return }
        val texture = view.surfaceTexture ?: run { observePublish("no_texture"); return }
        if (!view.isAvailable) { observePublish("unavailable"); return }
        val local = IntArray(2).also { view.getLocationInWindow(it) }
        val decor = window.decorView
        if (local[0] < 0 || local[1] < 0 || local[0] + view.width > decor.width || local[1] + view.height > decor.height) { observePublish("outside_decor"); return }
        texture.setDefaultBufferSize(view.width, view.height)
        val full = PixelRect(0, 0, view.width, view.height)
        val api = view.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val supplied = api?.takeIf { it.left + it.top + it.right + it.bottom > 0 }?.let {
            runCatching { PixelRect(max(0,it.left-local[0]), max(0,it.top-local[1]),
                min(view.width,decor.width-it.right-local[0]), min(view.height,decor.height-it.bottom-local[1])) }.getOrNull()
        }
        val safe = supplied?.takeIf { full.contains(it) } ?: directDefaultSafe(view.width, view.height)
        val position = IntArray(2).also { view.getLocationOnScreen(it) }
        PageReturnDiagnostics.tryOffer(
            "DIRECT_WINDOW_V1 physical=${display?.displayId} actual=${view.width}x${view.height} origin=${position[0]},${position[1]} safeSource=${if (supplied == null) "important_content_default" else "public_window_insets"} oemMask=unknown")
        observePublish("submitted")
        SecondaryHostRegistry.present(this, token, surface, SecondaryHostGeometry(view.width, view.height, safe))
    }
    override fun onStart() {
        super.onStart(); started = true
        observeCallback("start")
        if (retainedWindow && admitted) { hidden = false; SecondaryHostRegistry.resume(this, token); publishTexture() }
    }
    override fun onStop() {
        started = false
        observeCallback("stop")
        if (retainedWindow) { if (admitted) SecondaryHostRegistry.suspend(this, token, source = "activity_stop") } else withdraw()
        super.onStop()
    }
    override fun onDestroy() {
        observeCallback("destroy")
        if (retainedWindow) SecondaryHostRegistry.destroyed(this, token) else withdraw()
        surfaceView?.holder?.removeCallback(this)
        surfaceView = null
        concealment = null
        super.onDestroy()
    }
    override fun onRestart() { super.onRestart(); observeCallback("restart") }
    override fun onResume() { super.onResume(); observeCallback("resume") }
    override fun onPause() { super.onPause(); observeCallback("pause") }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        observeCallback("window_focus", if (hasFocus) 1 else 0)
    }
    companion object {
        /** Owner-selected important content: 558,70–1694,668 in the full 2000x810 window.
         * Normalize that confirmed pixel rectangle across drawable sizes, not codec/crop space.
         * This is a fallback, not an OEM calibration or a claim that instruments were measured.
         */
        internal fun directDefaultSafe(width: Int, height: Int): PixelRect {
            val w = max(1, width); val h = max(1, height)
            val left = kotlin.math.round(w * (558.0 / 2000)).toInt().coerceIn(0, w - 1)
            val top = kotlin.math.round(h * (70.0 / 810)).toInt().coerceIn(0, h - 1)
            val right = kotlin.math.round(w * (1694.0 / 2000)).toInt().coerceIn(left + 1, w)
            val bottom = kotlin.math.round(h * (668.0 / 810)).toInt().coerceIn(top + 1, h)
            return PixelRect(left, top, right, bottom)
        }
    }
}

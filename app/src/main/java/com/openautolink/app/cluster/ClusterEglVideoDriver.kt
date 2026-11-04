package com.openautolink.app.cluster

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.openautolink.app.secondary.VideoMapping
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Shader inputs are drawable fit + one codec crop, then the platform SurfaceTexture matrix. */
internal class ClusterGlPlan private constructor(
    val clearRect: PixelRect, val vertices: FloatArray, val textureCoordinates: FloatArray,
) {
    companion object {
        fun create(mapping: VideoMapping, decoderVisible: PixelRect): ClusterGlPlan {
            require(mapping.codedSize.bounds.contains(decoderVisible))
            val crop = mapping.codecCrop
            require(decoderVisible.contains(crop)) { "Decoder output omits required cluster source pixels" }
            val size = mapping.target.panel
            val dst = mapping.destination
            val l = (dst.left * 2 / size.width - 1).toFloat()
            val r = (dst.right * 2 / size.width - 1).toFloat()
            val t = (1 - dst.top * 2 / size.height).toFloat()
            val b = (1 - dst.bottom * 2 / size.height).toFloat()
            val u0 = (crop.left - decoderVisible.left).toFloat() / decoderVisible.width
            val u1 = (crop.right - decoderVisible.left).toFloat() / decoderVisible.width
            // SurfaceTexture's matrix performs the Android buffer orientation/half-texel transform.
            val v0 = 1f - (crop.top - decoderVisible.top).toFloat() / decoderVisible.height
            val v1 = 1f - (crop.bottom - decoderVisible.top).toFloat() / decoderVisible.height
            return ClusterGlPlan(mapping.target.output,
                floatArrayOf(l, b, r, b, l, t, r, t), floatArrayOf(u0, v1, u1, v1, u0, v0, u1, v0))
        }
    }
}

/** All GL/codec calls run on the renderer worker. Only the texture-ready bit crosses threads. */
internal class ClusterEglVideoDriver(private var host: Surface, private val mapping: VideoMapping,
    private val retainDecoderWhileHidden: Boolean = false) : ClusterRenderDriver {
    private var eglConfig: android.opengl.EGLConfig? = null
    private var parked = false
    private var hasImage = false
    private var redraw = false
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var textureId = 0
    private var texture: SurfaceTexture? = null
    private var input: Surface? = null
    private var codec: MediaCodec? = null
    private var ready = AtomicBoolean(false)
    private var callbackThread: HandlerThread? = null
    private var callbackHandler: Handler? = null
    private var plan = ClusterGlPlan.create(mapping, mapping.codedSize.bounds)
    private val info = MediaCodec.BufferInfo()
    private val matrix = FloatArray(16)
    private var closed = false
    private var trace: OptionalOutputDiagnostics? = null
    override fun diagnostics(trace: OptionalOutputDiagnostics) { this.trace = trace }
    private var decoderTimeouts = 0L
    private var malformedFormats = 0L
    private var lastOutputPts = -1L
    private var nextStatsNs = System.nanoTime() + 5_000_000_000L
    private var outputVisible = mapping.codedSize.bounds

    override fun initialize() {
        check(host.isValid) { "Cluster host Surface is invalid" }
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        val version = IntArray(2)
        egl(EGL14.eglInitialize(display, version, 0, version, 1), "initialize")
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        egl(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or (if (retainDecoderWhileHidden) EGL14.EGL_PBUFFER_BIT else 0),
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 0, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0), "choose config")
        check(count[0] > 0)
        eglConfig = configs[0]
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "Cluster EGL context failed" }
        window = EGL14.eglCreateWindowSurface(display, configs[0], host, intArrayOf(EGL14.EGL_NONE), 0)
        check(window != EGL14.EGL_NO_SURFACE) { "Cluster EGL window failed" }
        current()
        val actual = IntArray(1)
        egl(EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, actual, 0), "query width")
        require(actual[0] == mapping.target.panel.width) { "Unqualified cluster surface width" }
        egl(EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, actual, 0), "query height")
        require(actual[0] == mapping.target.panel.height) { "Unqualified cluster surface height" }
        program = createProgram()
        black()
        trace?.event("egl_attached", "panel=${mapping.target.panel} output=${mapping.target.output}")
    }

    override fun configure(config: ClusterCodecConfig) {
        check(!closed && config.codedSize == mapping.codedSize)
        stopDecoder()
        current()
        black()
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0); textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        glCheck("external texture")
        // A new bit/listener identity per configuration fences late callbacks from a retired codec.
        val signal = AtomicBoolean(false)
        ready = signal
        if (callbackThread == null) {
            callbackThread = HandlerThread("oal-cluster-texture").apply { start() }
            callbackHandler = Handler(requireNotNull(callbackThread!!.looper))
        }
        texture = SurfaceTexture(textureId).also {
            it.setDefaultBufferSize(config.codedSize.width, config.codedSize.height)
            it.setOnFrameAvailableListener({ signal.set(true) }, callbackHandler)
        }
        input = Surface(texture)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.codedSize.width, config.codedSize.height)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(config.sps))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(config.pps))
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, ClusterEncodedFrame.MAX_BYTES)
        // A qualified name is authority, not a preference: failure stays local, never by-type retry.
        trace?.event("codec_create", "decoder=${OptionalOutputDiagnostics.codecName(config.decoderName)} coded=${config.codedSize}")
        codec = if (config.decoderName != null) MediaCodec.createByCodecName(config.decoderName)
            else MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try { codec!!.configure(format, input, null, 0) }
        catch (failure: Exception) { trace?.failure("codec_configure", failure); throw failure }
        try { codec!!.start() }
        catch (failure: Exception) { trace?.failure("codec_start", failure); throw failure }
        trace?.event("codec_configured", "decoder=${OptionalOutputDiagnostics.codecName(codec!!.name)} coded=${config.codedSize} crop=${mapping.codecCrop}")
        trace?.event("scaling", "destination=${mapping.destination} safe=${mapping.phoneSafeInsets} stable=${mapping.stableContentInsets}")
        plan = ClusterGlPlan.create(mapping, mapping.codedSize.bounds)
    }

    override fun submit(frame: ClusterEncodedFrame): Boolean {
        val decoder = codec ?: return false
        val index = decoder.dequeueInputBuffer(0)
        if (index < 0) { decoderTimeouts++; return false }
        val buffer = requireNotNull(decoder.getInputBuffer(index))
        require(buffer.capacity() >= frame.bytes.size) { "Cluster access unit exceeds codec input buffer" }
        buffer.clear(); buffer.put(frame.bytes)
        decoder.queueInputBuffer(index, 0, frame.bytes.size, frame.presentationTimeUs,
            if (frame.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        return true
    }

    override fun poll(): ClusterRenderProgress {
        val decoder = codec ?: return ClusterRenderProgress(0, 0)
        var decoded = 0L
        // Bound each drain; transport and destruction commands cannot be starved by output callbacks.
        for (i in 0 until ClusterVideoRenderer.MAX_FRAMES) {
            when (val index = decoder.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> { decoderTimeouts++; break }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = decoder.outputFormat
                    try {
                    trace?.event("output_format", "width=${f.getInteger(MediaFormat.KEY_WIDTH)} height=${f.getInteger(MediaFormat.KEY_HEIGHT)} cropPresent=${f.containsKey("crop-left")},${f.containsKey("crop-top")},${f.containsKey("crop-right")},${f.containsKey("crop-bottom")}")
                    require(f.getInteger(MediaFormat.KEY_WIDTH) == mapping.codedSize.width &&
                        f.getInteger(MediaFormat.KEY_HEIGHT) == mapping.codedSize.height) { "Unexpected cluster decoder dimensions" }
                    fun edge(key: String, fallback: Int) = if (f.containsKey(key)) f.getInteger(key) else fallback
                    outputVisible = PixelRect(edge("crop-left", 0), edge("crop-top", 0),
                        edge("crop-right", mapping.codedSize.width - 1) + 1, edge("crop-bottom", mapping.codedSize.height - 1) + 1)
                    trace?.event("decoder_crop", "visible=$outputVisible required=${mapping.codecCrop}")
                    plan = ClusterGlPlan.create(mapping, outputVisible)
                    } catch (failure: Exception) { malformedFormats++; trace?.failure("output_format", failure); throw failure }
                }
                else -> if (index >= 0) {
                    val render = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0
                    decoder.releaseOutputBuffer(index, render)
                    if (render) { decoded++; lastOutputPts = info.presentationTimeUs }
                    check(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) { "Cluster decoder EOF" }
                }
            }
        }
        var presented = 0L
        if (ready.getAndSet(false)) {
            current()
            val image = requireNotNull(texture)
            image.updateTexImage(); image.getTransformMatrix(matrix)
            hasImage = true
            redraw = true
        }
        if (redraw && !parked) { draw(); redraw = false; presented = 1 }
        if (System.nanoTime() >= nextStatsNs) {
            nextStatsNs = System.nanoTime() + 5_000_000_000L
            trace?.event("decoder_summary", "decoderTimeouts=$decoderTimeouts malformedFormats=$malformedFormats coded=${mapping.codedSize} visible=$outputVisible lastOutputPtsUs=$lastOutputPts")
        }
        return ClusterRenderProgress(decoded, presented, lastOutputPts)
    }

    override fun clearOutput() {
        // Partial initialization may never have acquired a drawable window. There is then no
        // imagery from this driver to retire; otherwise a successful swap is mandatory.
        if (context == EGL14.EGL_NO_CONTEXT || window == EGL14.EGL_NO_SURFACE || parked) return
        // OS withdrawal means the native window no longer exists: it cannot contain visible
        // stale imagery and drawing into it is invalid. This does NOT acknowledge EGL/codec
        // retirement; detach must still destroy every owned resource successfully.
        if (!host.isValid) {
            trace?.event("clear_withdrawn", "surfaceValid=false")
            return
        }
        try {
            current(); black()
            trace?.event("clear_presented", "surfaceValid=${host.isValid} proof=opaque_black_draw_swap")
        }
        catch (failure: ClusterGraphicsFailure) {
            // Preserve the failed operation, including a destruction race after isValid.
            trace?.failure("clear_output", failure)
            if (host.isValid) throw failure
            trace?.event("clear_withdrawn", "surfaceValid=false")
        }
    }

    override fun stopDecoder() {
        hasImage = false; redraw = false
        // release() is the ownership boundary even when stop() reports an invalid codec state.
        codec?.let { decoder ->
            try { decoder.stop() } catch (failure: IllegalStateException) { trace?.failure("codec_stop", failure) /* release still required */ }
            decoder.release()
            codec = null
        }
        ready = AtomicBoolean(false)
        texture?.setOnFrameAvailableListener(null)
        callbackHandler?.removeCallbacksAndMessages(null)
        input?.release(); input = null
        texture?.release(); texture = null
        if (textureId != 0 && context != EGL14.EGL_NO_CONTEXT) {
            // If the OS destroyed the window, context destruction below owns GL object
            // reclamation. Never make that dead window current merely to delete a texture.
            if (parked || host.isValid) {
                try { current(); GLES20.glDeleteTextures(1, intArrayOf(textureId), 0); textureId = 0 }
                catch (failure: ClusterGraphicsFailure) {
                    trace?.failure("delete_texture", failure)
                    if (parked || host.isValid) throw failure
                }
            }
        }
    }

    override fun detach(): Boolean {
        if (closed) return true
        // Also fence direct driver retirement, not only renderer-mediated closeOutput().
        // Clear failure deliberately retains EGL ownership for host withdrawal/retry.
        try { stopDecoder() } finally { clearOutput() }
        callbackThread?.let {
            it.quitSafely(); it.join(250)
            check(!it.isAlive) { "Cluster texture callback retirement timed out" }
        }
        callbackThread = null; callbackHandler = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE && (parked || host.isValid)) {
                try {
                    current()
                    if (program != 0) { GLES20.glDeleteProgram(program); program = 0 }
                    GLES20.glFinish(); glCheck("detach finish")
                } catch (failure: ClusterGraphicsFailure) {
                    trace?.failure("detach_objects", failure)
                    if (parked || host.isValid) throw failure
                }
            }
            egl(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT), "unbind host")
            if (window != EGL14.EGL_NO_SURFACE) {
                egl(EGL14.eglDestroySurface(display, window), "destroy host EGL surface")
                window = EGL14.EGL_NO_SURFACE
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                egl(EGL14.eglDestroyContext(display, context), "destroy context")
                context = EGL14.EGL_NO_CONTEXT
                program = 0; textureId = 0
            }
            egl(EGL14.eglTerminate(display), "terminate")
            display = EGL14.EGL_NO_DISPLAY
            EGL14.eglReleaseThread()
        }
        closed = true
        trace?.event("gpu_retired", "surfaceValid=${host.isValid} codecRetained=${codec != null} textureRetained=${texture != null} callbackRetained=${callbackThread != null}")
        // The borrowed host Surface is deliberately never released here.
        return true
    }

    override fun parkWindow(): Boolean {
        check(retainDecoderWhileHidden && !closed)
        if (parked) return true
        clearOutput()
        current(); GLES20.glFinish(); glCheck("park finish")
        val parking = EGL14.eglCreatePbufferSurface(display, eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(parking != EGL14.EGL_NO_SURFACE) { "Cluster parking pbuffer failed" }
        try {
            egl(EGL14.eglMakeCurrent(display, parking, parking, context), "park current")
            egl(EGL14.eglDestroySurface(display, window), "park destroy host")
        } catch (failure: Exception) {
            EGL14.eglMakeCurrent(display, window, window, context)
            EGL14.eglDestroySurface(display, parking)
            throw failure
        }
        window = parking; parked = true
        trace?.event("window_parked", "decoderRetained=${codec != null} hostGpuDetached=true")
        return true
    }
    override fun resumeWindow(surface: Surface): Boolean {
        check(retainDecoderWhileHidden && parked && surface.isValid && !closed)
        val next = EGL14.eglCreateWindowSurface(display, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(next != EGL14.EGL_NO_SURFACE) { "Cluster successor window failed" }
        try {
            val size = IntArray(1)
            egl(EGL14.eglQuerySurface(display, next, EGL14.EGL_WIDTH, size, 0), "resume width")
            require(size[0] == mapping.target.panel.width)
            egl(EGL14.eglQuerySurface(display, next, EGL14.EGL_HEIGHT, size, 0), "resume height")
            require(size[0] == mapping.target.panel.height)
            egl(EGL14.eglMakeCurrent(display, next, next, context), "resume current")
            egl(EGL14.eglDestroySurface(display, window), "resume destroy parking")
        } catch (failure: Exception) {
            EGL14.eglMakeCurrent(display, window, window, context)
            EGL14.eglDestroySurface(display, next)
            throw failure
        }
        window = next; host = surface; parked = false
        if (hasImage) { draw(); redraw = false } else black()
        trace?.event("window_resumed", "decoderRetained=${codec != null} retainedImage=$hasImage")
        return true
    }
    private fun current() {
        check(!closed)
        egl(EGL14.eglMakeCurrent(display, window, window, context), "make current")
    }
    private fun clearOwned() {
        val size = mapping.target.panel
        val rect = plan.clearRect
        GLES20.glViewport(0, 0, size.width, size.height)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(rect.left, size.height - rect.bottom, rect.width, rect.height)
        GLES20.glColorMask(true, true, true, true)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }
    private fun black() {
        clearOwned(); swap()
    }
    private fun draw() {
        clearOwned()
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "video"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "textureMatrix"), 1, false, matrix, 0)
        val position = GLES20.glGetAttribLocation(program, "position")
        val uv = GLES20.glGetAttribLocation(program, "uv")
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(uv)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, floats(plan.vertices))
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, floats(plan.textureCoordinates))
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position); GLES20.glDisableVertexAttribArray(uv)
        swap()
    }
    private fun swap() {
        glCheck("draw/clear")
        egl(EGL14.eglSwapBuffers(display, window), "swap")
    }
    private fun floats(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    private fun createProgram(): Int {
        val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 position; attribute vec2 uv; uniform mat4 textureMatrix; varying vec2 tex; void main(){ gl_Position=vec4(position,0.,1.); tex=(textureMatrix*vec4(uv,0.,1.)).xy; }")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES video; varying vec2 tex; void main(){ gl_FragColor=vec4(texture2D(video,tex).rgb,1.); }")
        val result = GLES20.glCreateProgram()
        try {
            GLES20.glAttachShader(result, vertex); GLES20.glAttachShader(result, fragment)
            GLES20.glLinkProgram(result)
            val linked = IntArray(1); GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] != 0) { GLES20.glGetProgramInfoLog(result) }
            return result
        } catch (failure: Exception) { GLES20.glDeleteProgram(result); throw failure }
        finally { GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment) }
    }
    private fun shader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
        val compiled = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader); GLES20.glDeleteShader(shader)
            error("Cluster shader compile: $error")
        }
        return shader
    }
    private fun egl(ok: Boolean, operation: String) {
        if (!ok) throw ClusterGraphicsFailure("egl_${operation.replace(' ', '_')}", EGL14.eglGetError())
    }
    private fun glCheck(operation: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) throw ClusterGraphicsFailure("gl_${operation.replace('/', '_').replace(' ', '_')}", error)
    }
}

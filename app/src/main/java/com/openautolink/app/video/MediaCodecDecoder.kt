package com.openautolink.app.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.openautolink.app.diagnostics.DiagnosticLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * MediaCodec-based video decoder that renders OAL video frames to a Surface.
 *
 * Uses synchronous MediaCodec mode:
 * - onFrame() is called from the transport IO thread — queues input buffers directly
 * - A dedicated output drain thread dequeues and renders output buffers
 *
 * Lifecycle:
 * 1. attach(surface) — bind output surface
 * 2. onFrame(codecConfig) — configure codec from SPS/PPS
 * 3. onFrame(idr) — first keyframe starts decode
 * 4. onFrame(p-frame) — ongoing decode
 * 5. detach() — release codec and surface binding
 */
class MediaCodecDecoder(
    private val codecPreference: String = "h264",
    private val scalingMode: String = "letterbox" // "letterbox" or "crop"
) : VideoDecoder {

    companion object {
        private const val TAG = "MediaCodecDecoder"
        private const val INPUT_TIMEOUT_US = 16000L // 16ms — one frame period at 60fps
        private const val INPUT_TIMEOUT_BEHIND_US = 5000L // 5ms — shorter when catching up
        private const val OUTPUT_TIMEOUT_US = 1000L // 1ms timeout for output drain
        private const val STATS_INTERVAL_MS = 500L
    }

    private val _decoderState = MutableStateFlow(DecoderState.IDLE)
    override val decoderState: StateFlow<DecoderState> = _decoderState.asStateFlow()

    private val _stats = MutableStateFlow(VideoStats())
    override val stats: StateFlow<VideoStats> = _stats.asStateFlow()

    @Volatile private var codec: MediaCodec? = null
    @Volatile private var surface: Surface? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    private var codecConfigData: ByteArray? = null
    private var cachedIdrFrame: VideoFrame? = null  // IDR that arrived before surface was attached
    @Volatile private var receivedIdr = false
    private val mimeType: String = CodecSelector.codecToMime(codecPreference)

    // Output drain thread
    private var drainThread: Thread? = null
    private val drainRunning = AtomicBoolean(false)

    // Stats tracking
    private val framesDecoded = AtomicLong(0)
    private val framesDropped = AtomicLong(0)
    private var codecResetCount = 0
    private var lastStatsTime = 0L
    private var lastStatsFrames = 0L
    @Volatile private var currentFps = 0f
    @Volatile private var firstFrameRendered = false
    @Volatile private var decodeStartTimeMs = 0L

    private val _needsKeyframeFlow = MutableStateFlow(false)
    override val needsKeyframe: StateFlow<Boolean> = _needsKeyframeFlow.asStateFlow()
    @Volatile private var _needsKeyframe = false
    private var pendingWidth: Int? = null
    private var pendingHeight: Int? = null

    // Drop-only stats tracking — updates stats even when only dropping frames
    private var lastDropStatsTime = 0L
    private var lastDropStatsCount = 0L
    private val DROP_STATS_INTERVAL_MS = 1000L

    // Bitrate tracking — rolling window
    private var bitrateWindowStartMs = 0L
    private var bitrateWindowBytes = 0L
    private var currentBitrateKbps = 0f
    private val BITRATE_WINDOW_MS = 1000L

    // Late frame tracking — PTS monotonicity
    @Volatile private var lastQueuedPtsMs = -1L
    private var consecutiveDrops = 0

    override fun attach(surface: Surface, width: Int, height: Int) {
        val surfaceChanged = this.surface !== surface
        this.surface = surface
        this.surfaceWidth = width
        this.surfaceHeight = height
        Log.i(TAG, "Surface attached: ${width}x${height} (surfaceChanged=$surfaceChanged)")

        // Only reconfigure codec if the Surface object itself changed (e.g., after surfaceDestroyed).
        // Size-only changes don't require codec reset — MediaCodec renders to whatever size the surface is.
        if (surfaceChanged) {
            codecConfigData?.let { config ->
                configureCodec(config)
                // Codec configured from cached SPS/PPS, but the IDR that arrived with it
                // was likely lost (surface wasn't attached when it arrived during replay).
                // Check if we have a cached IDR to replay before requesting a fresh one.
                val cachedIdr = cachedIdrFrame
                if (cachedIdr != null && codec != null) {
                    Log.i(TAG, "Replaying cached IDR (${cachedIdr.data.size} bytes) after surface attach")
                    DiagnosticLog.i("video", "Replaying cached IDR (${cachedIdr.data.size} bytes) after surface attach")
                    cachedIdrFrame = null
                    handleKeyframe(cachedIdr)
                } else {
                    _needsKeyframe = false
                    _needsKeyframeFlow.value = true  // Signal caller to request IDR
                }
            }
        }
    }

    override fun detach() {
        Log.i(TAG, "Detaching surface")
        releaseCodec()
        surface = null
        surfaceWidth = 0
        surfaceHeight = 0
    }

    override fun onFrame(frame: VideoFrame) {
        // Track video dimensions from frame headers
        if (frame.width > 0 && frame.height > 0) {
            pendingWidth = frame.width
            pendingHeight = frame.height
        }

        // Track bitrate from frame sizes
        trackBitrate(frame.data.size)

        when {
            frame.isCodecConfig && frame.isKeyframe -> {
                // Combined SPS/PPS + IDR frame — configure codec, then queue the keyframe.
                // But verify the data actually contains IDR NALs — the bridge replay may
                // incorrectly flag SPS/PPS-only data as keyframe.
                handleCodecConfig(frame)
                if (NalParser.containsIdr(frame.data)) {
                    handleKeyframe(frame)
                } else {
                    Log.w(TAG, "Frame flagged as codec_config+keyframe but no IDR NAL found (${frame.data.size} bytes)")
                }
            }
            frame.isCodecConfig -> handleCodecConfig(frame)
            frame.isEndOfStream -> handleEndOfStream()
            frame.isKeyframe -> handleKeyframe(frame)
            else -> handleRegularFrame(frame)
        }
    }

    override fun requestKeyframe(): Boolean {
        if (_needsKeyframe) return false
        _needsKeyframe = true
        _needsKeyframeFlow.value = true
        return true
    }

    override fun pause() {
        Log.i(TAG, "Pausing decoder")
        releaseCodec()
        _decoderState.value = DecoderState.PAUSED
    }

    override fun resume() {
        Log.i(TAG, "Resuming decoder")
        _decoderState.value = DecoderState.IDLE
        receivedIdr = false
        _needsKeyframe = true
        _needsKeyframeFlow.value = true
        codecConfigData?.let { config ->
            if (surface != null) configureCodec(config)
        }
    }

    override fun release() {
        releaseCodec()
        cachedIdrFrame = null
        _decoderState.value = DecoderState.IDLE
    }

    private fun handleCodecConfig(frame: VideoFrame) {
        Log.i(TAG, "Codec config received: ${frame.data.size} bytes (flags=0x${frame.flags.toString(16)})")
        DiagnosticLog.i("video", "Codec config received: ${frame.data.size} bytes")

        // New SPS/PPS means a new stream — any cached IDR from a previous session
        // is now invalid and would produce corrupt video if replayed. Clear it.
        if (cachedIdrFrame != null) {
            Log.i(TAG, "Clearing stale cached IDR (new codec config received)")
            cachedIdrFrame = null
        }

        // Skip reconfigure if the codec is already running.
        // H.265 SPS/PPS varies slightly between frames (timing metadata changes),
        // but the resolution and codec profile stay the same. Reconfiguring on every
        // SPS change creates an infinite reset loop where receivedIdr never becomes true.
        // Only reconfigure if we don't have a codec yet (first config) or codec errored out.
        if (codec != null && _decoderState.value == DecoderState.DECODING) {
            Log.d(TAG, "Codec already active — skipping reconfigure")
            // Still save the latest config data for potential future codec reset
            codecConfigData = frame.data.copyOf()
            return
        }

        codecConfigData = frame.data.copyOf()
        receivedIdr = false
        _needsKeyframe = true  // Request IDR after codec reconfigure
        _needsKeyframeFlow.value = true

        if (surface != null) {
            configureCodec(frame.data)
        }
    }

    private fun handleKeyframe(frame: VideoFrame) {
        if (codec == null) {
            if (CodecSelector.isNalCodec(codecPreference)) {
                // H.264/H.265: try to extract SPS/PPS from the IDR frame.
                val nalTypes = NalParser.collectH264NalTypes(frame.data)
                if (nalTypes.contains(NalParser.H264_NAL_SPS) && nalTypes.contains(NalParser.H264_NAL_IDR)) {
                    Log.i(TAG, "IDR contains inline SPS/PPS — extracting codec config")
                    handleCodecConfig(frame)
                    // Fall through to queue as keyframe below
                } else if (surface == null && codecConfigData != null) {
                    // IDR arrived but surface not attached yet — cache it for replay
                    // when surface becomes available. This happens during Save & Connect
                    // from Settings where SurfaceView doesn't exist.
                    Log.i(TAG, "IDR received but no surface — caching for later replay (${frame.data.size} bytes)")
                    DiagnosticLog.i("video", "IDR cached (no surface): ${frame.data.size} bytes")
                    cachedIdrFrame = frame
                    return
                } else {
                    Log.w(TAG, "IDR received but codec not configured (no inline SPS), dropping")
                    framesDropped.incrementAndGet()
                    return
                }
            } else {
                // VP9/AV1: no SPS/PPS needed — configure codec from frame dimensions
                Log.i(TAG, "Non-NAL keyframe, configuring codec directly (${frame.width}x${frame.height})")
                val w = if (frame.width > 0) frame.width else pendingWidth ?: surfaceWidth
                val h = if (frame.height > 0) frame.height else pendingHeight ?: surfaceHeight
                if (surface != null && w > 0 && h > 0) {
                    configureCodecDirect(w, h)
                } else if (surface == null) {
                    Log.i(TAG, "Keyframe received but no surface — caching for later replay")
                    cachedIdrFrame = frame
                    return
                } else {
                    Log.w(TAG, "Keyframe but no surface or dimensions, dropping")
                    framesDropped.incrementAndGet()
                    return
                }
            }
        }
        Log.i(TAG, "IDR keyframe received: ${frame.data.size} bytes")
        receivedIdr = true
        _needsKeyframe = false
        _needsKeyframeFlow.value = false
        cachedIdrFrame = null  // Clear cache — we have a live IDR now
        queueFrame(frame)
    }

    private fun handleRegularFrame(frame: VideoFrame) {
        if (!receivedIdr) {
            framesDropped.incrementAndGet()
            updateDropStats()
            return
        }
        if (codec == null) {
            framesDropped.incrementAndGet()
            updateDropStats()
            return
        }
        queueFrame(frame)
    }

    private fun handleEndOfStream() {
        Log.i(TAG, "End of stream received")
        releaseCodec()
        _decoderState.value = DecoderState.IDLE
    }

    private fun trackBitrate(frameBytes: Int) {
        val now = System.currentTimeMillis()
        if (bitrateWindowStartMs == 0L) {
            bitrateWindowStartMs = now
            bitrateWindowBytes = 0
        }
        bitrateWindowBytes += frameBytes
        val elapsed = now - bitrateWindowStartMs
        if (elapsed >= BITRATE_WINDOW_MS) {
            currentBitrateKbps = (bitrateWindowBytes * 8f) / elapsed // bits/ms = kbps
            if (currentBitrateKbps < 2000f && receivedIdr) {
                // Below 2 Mbps at 1080p60 is very low — flag it
                Log.w(TAG, "Low encoder bitrate: ${currentBitrateKbps.toInt()} kbps")
            }
            bitrateWindowStartMs = now
            bitrateWindowBytes = 0
        }
    }

    private fun configureCodec(configData: ByteArray) {
        releaseCodec()
        _decoderState.value = DecoderState.CONFIGURING

        try {
            val decoderName = CodecSelector.findDecoder(mimeType)
            if (decoderName == null) {
                Log.e(TAG, "No decoder available for $mimeType")
                DiagnosticLog.e("video", "No decoder available for $mimeType")
                _decoderState.value = DecoderState.ERROR
                return
            }

            // Parse SPS to get actual video dimensions from codec config
            val spsDims = NalParser.parseSpsResolution(configData)
            val videoWidth = spsDims?.first ?: pendingWidth ?: surfaceWidth
            val videoHeight = spsDims?.second ?: pendingHeight ?: surfaceHeight
            Log.i(TAG, "Configuring codec with video dims: ${videoWidth}x${videoHeight} (surface: ${surfaceWidth}x${surfaceHeight})")

            val format = MediaFormat.createVideoFormat(mimeType, videoWidth, videoHeight)
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(configData))
            // Low-latency hints for real-time video stream
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            try { format.setInteger("priority", 0) } catch (_: Exception) {} // 0 = realtime priority

            val mc = MediaCodec.createByCodecName(decoderName)
            mc.configure(format, surface, null, 0)
            mc.start()
            if (scalingMode == "crop") {
                try { mc.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING) }
                catch (_: Exception) {}
            }
            codec = mc
            firstFrameRendered = false
            decodeStartTimeMs = System.currentTimeMillis()

            // Start output drain thread
            startDrainThread(mc)

            _decoderState.value = DecoderState.DECODING
            updateStats(decoderName)
            Log.i(TAG, "Codec configured: $decoderName ($mimeType, scaling=$scalingMode)")
            DiagnosticLog.i("video", "Codec selected: $decoderName ($mimeType)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure codec", e)
            DiagnosticLog.e("video", "Failed to configure codec: ${e.message}")
            _decoderState.value = DecoderState.ERROR
        }
    }

    /**
     * Configure codec without SPS/PPS (for VP9/AV1 which are self-describing).
     * MediaCodec auto-detects codec parameters from the bitstream.
     */
    private fun configureCodecDirect(width: Int, height: Int) {
        releaseCodec()
        _decoderState.value = DecoderState.CONFIGURING

        try {
            val decoderName = CodecSelector.findDecoder(mimeType)
            if (decoderName == null) {
                Log.e(TAG, "No decoder available for $mimeType")
                DiagnosticLog.e("video", "No decoder available for $mimeType")
                _decoderState.value = DecoderState.ERROR
                return
            }

            Log.i(TAG, "Configuring codec direct: ${width}x${height} ($mimeType)")

            val format = MediaFormat.createVideoFormat(mimeType, width, height)
            // No csd-0 — VP9/AV1 decoders extract params from the bitstream
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            try { format.setInteger("priority", 0) } catch (_: Exception) {}

            val mc = MediaCodec.createByCodecName(decoderName)
            mc.configure(format, surface, null, 0)
            mc.start()
            if (scalingMode == "crop") {
                try { mc.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING) }
                catch (_: Exception) {}
            }
            codec = mc
            firstFrameRendered = false
            decodeStartTimeMs = System.currentTimeMillis()

            startDrainThread(mc)

            _decoderState.value = DecoderState.DECODING
            updateStats(decoderName)
            Log.i(TAG, "Codec configured direct: $decoderName ($mimeType, scaling=$scalingMode)")
            DiagnosticLog.i("video", "Codec selected: $decoderName ($mimeType)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure codec direct", e)
            DiagnosticLog.e("video", "Failed to configure codec: ${e.message}")
            _decoderState.value = DecoderState.ERROR
        }
    }

    /**
     * Queue a frame into the MediaCodec input.
     * Called from the transport IO thread.
     * - Keyframes always wait up to INPUT_TIMEOUT_US for a buffer (critical for decode)
     * - P-frames use shorter timeout when decoder is behind (consecutive drops)
     * - Stale P-frames (PTS not advancing) are dropped immediately
     */
    private fun queueFrame(frame: VideoFrame) {
        val mc = codec ?: return

        // Drop stale P-frames (PTS went backwards — reordered or duplicate)
        if (!frame.isKeyframe && lastQueuedPtsMs >= 0 && frame.ptsMs > 0 && frame.ptsMs < lastQueuedPtsMs) {
            framesDropped.incrementAndGet()
            return
        }

        // Adaptive timeout: keyframes always wait, P-frames use shorter timeout when behind
        // but never drop to 0 — that creates a vicious cycle where ALL frames are dropped
        val timeout = if (frame.isKeyframe) {
            INPUT_TIMEOUT_US
        } else if (consecutiveDrops >= 5) {
            // Decoder is significantly behind — use shorter timeout but still try
            INPUT_TIMEOUT_BEHIND_US
        } else {
            INPUT_TIMEOUT_US
        }

        try {
            val inputIndex = mc.dequeueInputBuffer(timeout)
            if (inputIndex < 0) {
                framesDropped.incrementAndGet()
                consecutiveDrops++
                if (consecutiveDrops == 5 || (consecutiveDrops > 5 && consecutiveDrops % 30 == 0)) {
                    Log.w(TAG, "Decoder behind: $consecutiveDrops consecutive frame drops")
                }
                return
            }

            val inputBuffer = mc.getInputBuffer(inputIndex) ?: return
            if (frame.data.size > inputBuffer.capacity()) {
                Log.w(TAG, "Frame too large: ${frame.data.size} > ${inputBuffer.capacity()}")
                mc.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                framesDropped.incrementAndGet()
                return
            }

            inputBuffer.clear()
            inputBuffer.put(frame.data)

            val flags = if (frame.isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            mc.queueInputBuffer(inputIndex, 0, frame.data.size, frame.ptsMs * 1000, flags)
            lastQueuedPtsMs = frame.ptsMs
            consecutiveDrops = 0
        } catch (e: MediaCodec.CodecException) {
            Log.e(TAG, "Codec error queuing frame", e)
            DiagnosticLog.e("video", "Codec error queuing frame: ${e.message}, recoverable=${e.isRecoverable}")
            if (!e.isRecoverable) {
                _decoderState.value = DecoderState.ERROR
                _needsKeyframe = true
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Codec in bad state", e)
        }
    }

    /**
     * Start a dedicated thread that continuously drains and renders output buffers.
     */
    private fun startDrainThread(mc: MediaCodec) {
        drainRunning.set(true)
        drainThread = Thread({
            val bufferInfo = MediaCodec.BufferInfo()
            while (drainRunning.get()) {
                try {
                    val outputIndex = mc.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)
                    when {
                        outputIndex >= 0 -> {
                            // Render to surface — this is live UI state, render immediately
                            mc.releaseOutputBuffer(outputIndex, true)
                            val decoded = framesDecoded.incrementAndGet()
                            if (!firstFrameRendered) {
                                firstFrameRendered = true
                                val elapsed = System.currentTimeMillis() - decodeStartTimeMs
                                DiagnosticLog.i("video", "First frame rendered in ${elapsed}ms")
                            }
                            updateFps(decoded)
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = mc.outputFormat
                            Log.i(TAG, "Output format changed: $format")
                            val w = format.getInteger(MediaFormat.KEY_WIDTH, 0)
                            val h = format.getInteger(MediaFormat.KEY_HEIGHT, 0)
                            if (w > 0 && h > 0) {
                                _stats.value = _stats.value.copy(width = w, height = h)
                            }
                        }
                        // INFO_TRY_AGAIN_LATER — just loop
                    }
                } catch (e: MediaCodec.CodecException) {
                    Log.e(TAG, "Codec error in drain loop", e)
                    DiagnosticLog.e("video", "Codec error in drain loop: ${e.message}")
                    _decoderState.value = DecoderState.ERROR
                    _needsKeyframe = true
                    break
                } catch (e: IllegalStateException) {
                    // Codec was released
                    break
                }
            }
        }, "VideoDecoder-drain").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY  // Drain thread should never be starved
            start()
        }
    }

    private fun stopDrainThread() {
        drainRunning.set(false)
        drainThread?.join(1000)
        drainThread = null
    }

    private fun releaseCodec() {
        stopDrainThread()
        try {
            codec?.stop()
        } catch (_: Exception) {}
        try {
            codec?.release()
        } catch (_: Exception) {}
        if (codec != null) codecResetCount++
        codec = null
        receivedIdr = false
        lastQueuedPtsMs = -1
        consecutiveDrops = 0
    }

    /**
     * Update stats periodically when only frames are being dropped (no decodes).
     * Without this, telemetry shows stale dropped count during IDR starvation.
     */
    private fun updateDropStats() {
        val now = System.currentTimeMillis()
        if (lastDropStatsTime == 0L) {
            lastDropStatsTime = now
            lastDropStatsCount = framesDropped.get()
            return
        }
        val elapsed = now - lastDropStatsTime
        if (elapsed >= DROP_STATS_INTERVAL_MS) {
            lastDropStatsTime = now
            lastDropStatsCount = framesDropped.get()
            updateStats(null)
        }
    }

    private fun updateFps(decoded: Long) {
        val now = System.currentTimeMillis()
        if (lastStatsTime == 0L) {
            lastStatsTime = now
            lastStatsFrames = decoded
            return
        }

        val elapsed = now - lastStatsTime
        if (elapsed >= STATS_INTERVAL_MS) {
            val framesDelta = decoded - lastStatsFrames
            currentFps = framesDelta * 1000f / elapsed
            lastStatsTime = now
            lastStatsFrames = decoded
            updateStats(null)
        }
    }

    private fun updateStats(codecName: String?) {
        val current = _stats.value
        val isHw = codecName?.let {
            try { android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
                .codecInfos.firstOrNull { info -> info.name == it }
                ?.isHardwareAccelerated == true
            } catch (_: Exception) { false }
        } ?: current.isHardware
        _stats.value = current.copy(
            fps = currentFps,
            framesReceived = (framesDecoded.get() + framesDropped.get()),
            framesDecoded = framesDecoded.get(),
            framesDropped = framesDropped.get(),
            codec = codecName ?: current.codec,
            decoderName = codecName ?: current.decoderName,
            isHardware = isHw,
            width = pendingWidth ?: current.width,
            height = pendingHeight ?: current.height,
            codecResets = codecResetCount,
            bitrateKbps = currentBitrateKbps,
        )
    }
}

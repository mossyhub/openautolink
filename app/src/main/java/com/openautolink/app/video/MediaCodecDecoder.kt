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
    private var mimeType: String = CodecSelector.codecToMime(codecPreference)
    private var detectedCodec: String = codecPreference

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

    // Render gate: don't render output until after a valid keyframe has been
    // queued to the decoder. Prevents green/blocky frames from P-frames
    // decoded before a valid IDR reference is established.
    // The bridge-side fix (no stale IDR replay) ensures the first IDR the app
    // receives is always fresh from the phone, so a single-IDR gate suffices.
    @Volatile private var renderingEnabled = false

    override fun attach(surface: Surface, width: Int, height: Int) {
        val surfaceChanged = this.surface !== surface
        this.surface = surface
        this.surfaceWidth = width
        this.surfaceHeight = height
        Log.i(TAG, "Surface attached: ${width}x${height} (surfaceChanged=$surfaceChanged, codecActive=${codec != null}, receivedIdr=$receivedIdr, renderingEnabled=$renderingEnabled)")
        DiagnosticLog.i("video", "Surface attached: ${width}x${height} surfaceChanged=$surfaceChanged codecActive=${codec != null} renderGate=$renderingEnabled")

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

        // Auto-detect codec from NAL stream data — the bridge controls what codec
        // the phone uses, so the actual stream may differ from the app's preference.
        val streamCodec = detectCodecFromConfig(frame.data)
        if (streamCodec != null && streamCodec != detectedCodec) {
            val newMime = CodecSelector.codecToMime(streamCodec)
            Log.i(TAG, "Codec auto-detected from stream: $streamCodec ($newMime) — was $detectedCodec ($mimeType)")
            DiagnosticLog.i("video", "Codec auto-detected: $streamCodec (was $detectedCodec)")
            detectedCodec = streamCodec
            mimeType = newMime
            // Force full reconfigure with new codec
            releaseCodec()
        }

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
            if (CodecSelector.isNalCodec(detectedCodec)) {
                // H.264/H.265: try to extract SPS/PPS/VPS from the IDR frame.
                val hasInlineConfig = if (detectedCodec == "h265" || detectedCodec == "hevc") {
                    NalParser.isH265CodecConfig(frame.data)
                } else {
                    val nalTypes = NalParser.collectH264NalTypes(frame.data)
                    nalTypes.contains(NalParser.H264_NAL_SPS)
                }
                if (hasInlineConfig) {
                    Log.i(TAG, "IDR contains inline SPS/PPS — extracting codec config")
                    handleCodecConfig(frame)
                    if (surface == null) {
                        // Surface not attached — cache IDR for instant replay on reattach
                        Log.i(TAG, "IDR with inline config but no surface — caching (${frame.data.size} bytes)")
                        DiagnosticLog.i("video", "IDR cached (no surface, inline config): ${frame.data.size} bytes")
                        cachedIdrFrame = frame
                        return
                    }
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
        Log.i(TAG, "IDR keyframe received: ${frame.data.size} bytes, renderGate: false->true")
        DiagnosticLog.i("video", "IDR received: ${frame.data.size}B, codecActive=${codec != null}, enabling render")
        receivedIdr = true
        _needsKeyframe = false
        _needsKeyframeFlow.value = false
        // Always keep the latest IDR cached — if the surface is destroyed (user
        // navigates away), we can replay it instantly when the surface returns
        // instead of requesting a fresh keyframe from the bridge and waiting.
        cachedIdrFrame = frame
        // Queue the IDR BEFORE enabling rendering — this ensures the decoder
        // processes the IDR as its first frame. If we enable rendering first,
        // the drain thread could render a stale output buffer before the IDR
        // is decoded, producing green/blocky artifacts.
        queueFrame(frame)
        renderingEnabled = true
        updateStats(null)  // Immediately update waitingForKeyframe state for UI
    }

    private fun handleRegularFrame(frame: VideoFrame) {
        if (!receivedIdr || !renderingEnabled) {
            // Drop P-frames entirely when:
            // - No IDR received yet (can't decode without reference)
            // - Rendering not enabled (between stale and fresh IDR after codec reset)
            // CRITICAL: these frames must NOT be queued to the decoder — even with
            // output rendering suppressed, they corrupt the decoder's reference
            // picture buffer when they don't match the current IDR.
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

    /**
     * Detect codec type from codec config data by examining NAL unit types.
     * H.264: first NAL is SPS (type 7) or PPS (type 8)
     * H.265: first NAL is VPS (type 32), SPS (type 33), or PPS (type 34)
     * Returns "h264", "h265", or null if unknown.
     */
    private fun detectCodecFromConfig(data: ByteArray): String? {
        // Try H.265 first — VPS (32) is unique to H.265
        val h265Type = NalParser.findFirstH265NalType(data)
        if (h265Type in listOf(NalParser.H265_NAL_VPS, NalParser.H265_NAL_SPS, NalParser.H265_NAL_PPS)) {
            // Verify it's really H.265 by checking the raw byte — H.264 SPS (7) maps to
            // H.265 NAL type 3 which is not VPS/SPS/PPS, so VPS (32) is unambiguous.
            // But H.264 SPS byte 0x67 → h265NalType = (0x67 >> 1) & 0x3F = 0x33 = 51, not 32/33/34.
            // H.264 PPS byte 0x68 → h265NalType = (0x68 >> 1) & 0x3F = 0x34 = 52, not 32/33/34.
            // So if h265Type is exactly 32, 33, or 34, it's definitely H.265.
            if (h265Type == NalParser.H265_NAL_VPS) return "h265"
            // For SPS/PPS, double-check with H.264 parser to disambiguate
            val h264Type = NalParser.findFirstH264NalType(data)
            if (h264Type == NalParser.H264_NAL_SPS || h264Type == NalParser.H264_NAL_PPS) {
                return "h264"
            }
            return "h265"
        }
        // Try H.264
        val h264Type = NalParser.findFirstH264NalType(data)
        if (h264Type == NalParser.H264_NAL_SPS || h264Type == NalParser.H264_NAL_PPS) {
            return "h264"
        }
        return null
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

            // Parse codec config to get actual video dimensions
            // H.264: parse SPS NAL. H.265: parse from frame headers (SPS parsing is complex)
            val spsDims = if (mimeType == CodecSelector.MIME_H264) {
                NalParser.parseSpsResolution(configData)
            } else null
            val videoWidth = spsDims?.first ?: pendingWidth ?: surfaceWidth
            val videoHeight = spsDims?.second ?: pendingHeight ?: surfaceHeight

            // Ensure we have valid dimensions — H.265 initial config may arrive before
            // frame headers provide width/height. Use a reasonable default to avoid 0x0.
            val configWidth = if (videoWidth > 0) videoWidth else 1920
            val configHeight = if (videoHeight > 0) videoHeight else 1080
            Log.i(TAG, "Configuring codec with video dims: ${configWidth}x${configHeight} " +
                    "(parsed=${spsDims != null}, pending=${pendingWidth}x${pendingHeight}, surface: ${surfaceWidth}x${surfaceHeight})")

            val format = MediaFormat.createVideoFormat(mimeType, configWidth, configHeight)
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(configData))
            // Low-latency hints for real-time video stream
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            try { format.setInteger("priority", 0) } catch (_: Exception) {} // 0 = realtime priority

            val mc = MediaCodec.createByCodecName(decoderName)
            mc.configure(format, surface, null, 0)
            mc.start()
            // Always use SCALE_TO_FIT — Qualcomm c2.qti decoders stretch
            // non-uniformly with the default (SCALE_TO_FIT_WITH_CROPPING),
            // making circles into ovals. SCALE_TO_FIT preserves aspect ratio.
            try { mc.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT) }
            catch (_: Exception) {}
            codec = mc
            firstFrameRendered = false
            decodeStartTimeMs = System.currentTimeMillis()
            renderingEnabled = false  // Don't render until first IDR is queued

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
            try { mc.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT) }
            catch (_: Exception) {}
            codec = mc
            firstFrameRendered = false
            decodeStartTimeMs = System.currentTimeMillis()
            renderingEnabled = true  // VP9/AV1: no reference dependency, render immediately

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
                            if (!renderingEnabled) {
                                // Don't render until first IDR is decoded — prevents
                                // green/blocky frames from stale P-frames
                                mc.releaseOutputBuffer(outputIndex, false)
                            } else {
                                // Render to surface — this is live UI state, render immediately
                                mc.releaseOutputBuffer(outputIndex, true)
                            }
                            val decoded = framesDecoded.incrementAndGet()
                            if (!firstFrameRendered && renderingEnabled) {
                                firstFrameRendered = true
                                val elapsed = System.currentTimeMillis() - decodeStartTimeMs
                                Log.i(TAG, "First frame RENDERED in ${elapsed}ms (renderGate=$renderingEnabled)")
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
        val wasActive = codec != null
        stopDrainThread()
        try {
            codec?.stop()
        } catch (_: Exception) {}
        try {
            codec?.release()
        } catch (_: Exception) {}
        if (wasActive) {
            codecResetCount++
            DiagnosticLog.i("video", "Codec released (reset #$codecResetCount), renderGate -> false")
        }
        codec = null
        receivedIdr = false
        renderingEnabled = false
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
        val codecFormat = when (mimeType) {
            CodecSelector.MIME_H264 -> "H.264"
            CodecSelector.MIME_H265 -> "H.265"
            CodecSelector.MIME_VP9 -> "VP9"
            else -> mimeType
        }
        _stats.value = current.copy(
            fps = currentFps,
            framesReceived = (framesDecoded.get() + framesDropped.get()),
            framesDecoded = framesDecoded.get(),
            framesDropped = framesDropped.get(),
            codec = codecName ?: current.codec,
            codecFormat = codecFormat,
            decoderName = codecName ?: current.decoderName,
            isHardware = isHw,
            width = pendingWidth ?: current.width,
            height = pendingHeight ?: current.height,
            codecResets = codecResetCount,
            bitrateKbps = currentBitrateKbps,
            waitingForKeyframe = _needsKeyframe,
        )
    }
}

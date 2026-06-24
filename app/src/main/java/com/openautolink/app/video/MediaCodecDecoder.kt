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
        // Minimum IDR size to be considered a real picture (not encoder startup seed).
        // Real IDRs at any supported resolution are 50KB+. Seed IDRs are ~900 bytes.
        private const val MIN_REAL_IDR_BYTES = 4096
        // After accepting a seed IDR, silently decode P-frames for this long before
        // rendering. Gives the decoder time to accumulate picture content from P-frames
        // so the first visible frame is mostly complete rather than green.
        private const val SEED_WARMUP_MS = 2500L
        // Freeze cap (issue #35): after a cached-IDR replay we set awaitingFreshIdr
        // and drop P-frames until a FRESH real IDR arrives. If the keyframe
        // re-request doesn't pull one forward (e.g. the phone ignores a same-state
        // VideoFocusIndication after a phone-call/GM-UI takeover), that wait can be
        // up to the phone's natural GOP (~2 min) — a 2-minute frozen screen. Cap it:
        // once awaitingFreshIdr has held this long, stop dropping and feed P-frames
        // anyway. The P-chain references content we missed, so expect a few hundred
        // ms of blockiness as it rebuilds — far better than a multi-minute freeze.
        private const val AWAITING_FRESH_IDR_CAP_MS = 3000L
    }

    private val _decoderState = MutableStateFlow(DecoderState.IDLE)
    override val decoderState: StateFlow<DecoderState> = _decoderState.asStateFlow()

    private val _stats = MutableStateFlow(VideoStats())
    override val stats: StateFlow<VideoStats> = _stats.asStateFlow()

    @Volatile private var codec: MediaCodec? = null
    /** Serializes releaseCodec invocations. Without this, the drain thread,
     *  the frame-input coroutine, the surface-detach path, and the
     *  SessionManager teardown can all race into release() concurrently and
     *  produce a storm of stop()/release() calls on the same MediaCodec
     *  instance — observed in production as 20+ "Codec released (reset #1)"
     *  log lines at the same millisecond, which on some Qualcomm decoders
     *  leaves the codec in an unrecoverable state. */
    private val codecReleaseLock = Any()
    @Volatile private var activeDecoderName: String? = null
    @Volatile private var surface: Surface? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    private var codecConfigData: ByteArray? = null
    private var cachedIdrFrame: VideoFrame? = null  // IDR that arrived before surface was attached
    @Volatile private var receivedIdr = false
    private var mimeType: String = CodecSelector.codecToMime(codecPreference)
    private var detectedCodec: String = codecPreference

    /**
     * Set the negotiated codec type from the AA protocol (video setup response).
     * Must be called before video frames arrive.
     * @param aaCodecType aasdk MediaCodecType: 3=H.264, 5=H.264_BP, 7=H.265
     */
    fun setNegotiatedCodec(aaCodecType: Int) {
        val newCodec = when (aaCodecType) {
            7 -> "h265"
            3, 5 -> "h264"
            else -> return
        }
        if (newCodec != detectedCodec) {
            val newMime = CodecSelector.codecToMime(newCodec)
            Log.i(TAG, "Negotiated codec from AA protocol: $newCodec ($newMime) — was $detectedCodec ($mimeType)")
            detectedCodec = newCodec
            mimeType = newMime
        }
    }

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

    // Seed IDR warmup: when a tiny seed IDR is accepted, we decode P-frames
    // silently for SEED_WARMUP_MS before enabling rendering. This avoids
    // showing green (uninitialized decoder buffer) while still getting
    // near-instant video once the warmup completes.
    @Volatile private var seedIdrTimeMs = 0L

    // Render gate: don't render output until after a valid keyframe has been
    // queued to the decoder. Prevents green/blocky frames from P-frames
    // decoded before a valid IDR reference is established.
    // The bridge-side fix (no stale IDR replay) ensures the first IDR the app
    // receives is always fresh from the phone, so a single-IDR gate suffices.
    @Volatile private var renderingEnabled = false
    // After replaying a cached IDR on surface re-attach, P-frames will
    // reference content we never decoded (we missed everything between
    // the cached IDR and now). Feed them to the decoder → blocky artifacts.
    // Instead, drop all P-frames until a fresh real IDR arrives from the
    // phone, showing a frozen-but-clean cached frame in the meantime.
    @Volatile private var awaitingFreshIdr = false
    // Wall-clock (elapsedRealtime) when awaitingFreshIdr was last raised, so
    // handleRegularFrame can cap the P-frame-drop window (issue #35). 0 = not set.
    @Volatile private var awaitingFreshIdrSinceMs = 0L
    // Skip first few frames after codec init to avoid green hue from resolution
    // transition. When codec is configured at 1920x1080 but video is 2560x1440,
    // the first frames decode with wrong color plane alignment until the codec
    // internally adjusts via output format change.
    @Volatile private var outputFormatReceived = false

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
                    // Cached IDR gives us a fast unblank, but it's only the
                    // anchor frame for a P-frame chain we no longer have
                    // (we missed everything between when the IDR was sent
                    // and now). Drop all P-frames until a fresh real IDR
                    // arrives — shows a frozen-but-clean cached frame
                    // instead of progressively-corrupting blocky garbage.
                    awaitingFreshIdr = true
                    awaitingFreshIdrSinceMs = System.currentTimeMillis()
                    _needsKeyframe = false
                    _needsKeyframeFlow.value = true
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
        // Keep codecConfigData and cachedIdrFrame across detach. This path
        // fires on transient backgrounding (nav-away to AAOS home, OEM
        // overlays, etc.) where the AA session is still alive and the phone
        // does NOT re-send SPS/PPS — only an IDR in response to our
        // VideoFocusIndication. Wiping the cached config would leave the
        // decoder unconfigurable on return, producing permanent black until
        // the phone's next periodic IDR (~2 min) coincides with re-attach.
        // Phone-switch / session-teardown wipes happen via [release] instead.
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
            frame.isCodecConfig -> {
                handleCodecConfig(frame)
                // H.265 phones often send VPS+SPS+PPS+IDR in one buffer.
                // C++ scans all NALs now, but as defense-in-depth: if the
                // codec is ready and the buffer contains an IDR, process it.
                if (codec != null && !receivedIdr && NalParser.containsIdr(frame.data)) {
                    Log.i(TAG, "Config frame contains embedded IDR — processing as keyframe")
                    DiagnosticLog.i("video", "Config frame has embedded IDR, processing")
                    handleKeyframe(frame)
                }
            }
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
        awaitingFreshIdr = false
        awaitingFreshIdrSinceMs = 0L
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
        awaitingFreshIdr = false
        awaitingFreshIdrSinceMs = 0L
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
                } else if (codecConfigData != null) {
                    // IDR arrived but codec not ready yet (still configuring) or
                    // surface not attached. Cache it for replay when codec/surface is ready.
                    Log.i(TAG, "IDR received but codec not ready — caching for replay (${frame.data.size} bytes)")
                    DiagnosticLog.i("video", "IDR cached (codec configuring): ${frame.data.size} bytes")
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
        // Guard against tiny "seed" IDRs from the phone's H.265 encoder startup.
        // These ~900-byte placeholder IDRs decode to green (uninitialized chroma planes).
        // Strategy: accept the seed IDR so P-frames can flow and build up picture content,
        // but suppress rendering for SEED_WARMUP_MS. After warmup, enough P-frame blocks
        // have accumulated that the picture is mostly complete. A real IDR (when it arrives
        // at the phone's natural GOP interval) will clean up any remaining artifacts.
        if (frame.data.size < MIN_REAL_IDR_BYTES) {
            Log.w(TAG, "Seed IDR detected (${frame.data.size} bytes < $MIN_REAL_IDR_BYTES) — silent decode for ${SEED_WARMUP_MS}ms before rendering")
            DiagnosticLog.w("video", "Seed IDR: ${frame.data.size}B, warmup ${SEED_WARMUP_MS}ms")
            queueFrame(frame)  // Feed to decoder — establishes reference for P-frames
            receivedIdr = true  // Allow P-frames to flow to decoder
            renderingEnabled = false  // Don't show green seed output
            seedIdrTimeMs = System.currentTimeMillis()  // Start warmup timer
            // Don't cache the seed IDR — if surface is recreated, we don't want
            // to replay a green frame. Better to wait for a real one.
            // Keep requesting a real IDR — the phone may eventually respond
            if (!_needsKeyframe) {
                _needsKeyframe = true
                _needsKeyframeFlow.value = true
            }
            return
        }

        Log.i(TAG, "IDR keyframe received: ${frame.data.size} bytes, renderGate: false->true")
        DiagnosticLog.i("video", "IDR received: ${frame.data.size}B, codecActive=${codec != null}, enabling render")
        receivedIdr = true
        _needsKeyframe = false
        _needsKeyframeFlow.value = false
        // Fresh IDR from the phone re-anchors the reference chain — safe to
        // resume feeding P-frames (clears the cached-IDR-replay guard).
        if (awaitingFreshIdr) {
            Log.i(TAG, "Fresh IDR arrived — clearing awaitingFreshIdr, P-frames will flow")
            DiagnosticLog.i("video", "Fresh IDR cleared awaitingFreshIdr")
            awaitingFreshIdr = false
            awaitingFreshIdrSinceMs = 0L
        }
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
        if (!receivedIdr) {
            // No IDR received yet — P-frames can't decode without a reference.
            // These MUST be dropped, not queued — they corrupt the decoder's
            // reference picture buffer.
            framesDropped.incrementAndGet()
            updateDropStats()
            return
        }
        if (awaitingFreshIdr) {
            // After replaying a cached IDR on surface re-attach, we show a
            // frozen-but-clean frame. P-frames arriving now reference content
            // we never decoded (missed during backgrounding). Feeding them
            // would produce progressively-worse blockiness. Drop until a
            // fresh real IDR from the phone re-anchors the reference chain.
            //
            // BUT cap the wait (issue #35): if the fresh IDR never comes (e.g.
            // the phone ignores our same-state keyframe re-request after a
            // phone-call / GM-UI takeover), dropping forever leaves the screen
            // frozen until the phone's next natural GOP IDR (~2 min). Once the
            // gate has held longer than AWAITING_FRESH_IDR_CAP_MS, give up
            // waiting and feed P-frames anyway — accept a few hundred ms of
            // blockiness as the reference chain rebuilds over a multi-minute freeze.
            val heldMs = if (awaitingFreshIdrSinceMs > 0)
                System.currentTimeMillis() - awaitingFreshIdrSinceMs else 0L
            if (heldMs >= AWAITING_FRESH_IDR_CAP_MS) {
                Log.w(TAG, "awaitingFreshIdr held ${heldMs}ms (cap ${AWAITING_FRESH_IDR_CAP_MS}ms) — no fresh IDR arrived, feeding P-frames anyway")
                DiagnosticLog.w("video", "Fresh-IDR wait capped at ${heldMs}ms — resuming P-frames (expect brief blockiness)")
                awaitingFreshIdr = false
                awaitingFreshIdrSinceMs = 0L
                // fall through to normal P-frame handling below
            } else {
                framesDropped.incrementAndGet()
                updateDropStats()
                return
            }
        }
        if (codec == null) {
            framesDropped.incrementAndGet()
            updateDropStats()
            return
        }
        // After a seed IDR, P-frames flow to the decoder (silent decode) and
        // rendering is enabled after SEED_WARMUP_MS. The drain thread handles
        // render suppression via the renderingEnabled flag — output buffers are
        // dequeued and released without rendering, keeping the decoder pipeline
        // active so picture content accumulates in the reference buffers.
        if (!renderingEnabled && seedIdrTimeMs > 0) {
            val elapsed = System.currentTimeMillis() - seedIdrTimeMs
            if (elapsed >= SEED_WARMUP_MS) {
                Log.i(TAG, "Seed warmup complete (${elapsed}ms) — enabling render")
                DiagnosticLog.i("video", "Seed warmup done (${elapsed}ms), render enabled")
                renderingEnabled = true
                seedIdrTimeMs = 0  // Clear — warmup is done
                _needsKeyframe = false
                _needsKeyframeFlow.value = false
            }
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
            val candidates = CodecSelector.decoderCandidates(mimeType)
            if (candidates.isEmpty()) {
                Log.e(TAG, "No decoder available for $mimeType")
                DiagnosticLog.e("video", "No decoder available for $mimeType")
                _decoderState.value = DecoderState.ERROR
                return
            }

            // Parse codec config to get actual video dimensions
            val spsDims = when (mimeType) {
                CodecSelector.MIME_H264 -> NalParser.parseSpsResolution(configData)
                CodecSelector.MIME_H265 -> NalParser.parseH265SpsResolution(configData)
                else -> null
            }
            // Use parsed SPS dimensions if available, then pending dimensions from frame headers.
            // Do NOT fall back to surfaceWidth/Height — surface is the display size
            // (portrait phone = 1080x2340) which corrupts H.265 decoder init.
            val videoWidth = spsDims?.first ?: pendingWidth ?: 0
            val videoHeight = spsDims?.second ?: pendingHeight ?: 0

            // Ensure we have valid dimensions — H.265 initial config may arrive before
            // frame headers provide width/height. Use a reasonable default to avoid 0x0.
            // CRITICAL: Do NOT use surfaceWidth/Height as fallback — the surface is the
            // display size (e.g. 1080x2340 portrait) which is wrong for the video
            // (e.g. 1920x1080). Wrong dimensions cause green/corrupt first frames.
            val configWidth = if (videoWidth > 0) videoWidth else 1920
            val configHeight = if (videoHeight > 0) videoHeight else 1080
            Log.i(TAG, "Configuring codec with video dims: ${configWidth}x${configHeight} " +
                    "(parsed=${spsDims != null}, pending=${pendingWidth}x${pendingHeight}, surface: ${surfaceWidth}x${surfaceHeight})")

            // Try each candidate (HW first, SW fallback) with up to 2 retries.
            // Mirrors GM ProjectionRenderer.initializeCodec (CODEC_START_ATTEMPT_ALLOWANCE)
            // and persist.hw.decoder.aa SW fallback. Cheap reliability insurance.
            var lastError: Throwable? = null
            var configured = false
            outer@ for (decoderName in candidates) {
                for (attempt in 1..2) {
                    var mc: MediaCodec? = null
                    try {
                        val format = MediaFormat.createVideoFormat(mimeType, configWidth, configHeight)
                        format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(configData))
                        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                        try { format.setInteger("priority", 0) } catch (_: Exception) {}

                        mc = MediaCodec.createByCodecName(decoderName)
                        mc.configure(format, surface, null, 0)
                        try { mc.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT) }
                        catch (_: Exception) {}
                        mc.start()
                        try { mc.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT) }
                        catch (_: Exception) {}
                        codec = mc
                        activeDecoderName = decoderName
                        configured = true
                        Log.i(TAG, "Codec configured: $decoderName ($mimeType, attempt=$attempt)")
                        DiagnosticLog.i("video", "Codec selected: $decoderName ($mimeType)")
                        break@outer
                    } catch (e: Throwable) {
                        lastError = e
                        Log.w(TAG, "Codec init failed ($decoderName attempt $attempt): ${e.message}")
                        try { mc?.reset() } catch (_: Throwable) {}
                        try { mc?.release() } catch (_: Throwable) {}
                    }
                }
                Log.w(TAG, "All retries exhausted for $decoderName, trying next candidate")
            }

            if (!configured) {
                Log.e(TAG, "Failed to configure any decoder for $mimeType", lastError)
                DiagnosticLog.e("video", "All decoders failed for $mimeType: ${lastError?.message}")
                _decoderState.value = DecoderState.ERROR
                return
            }

            firstFrameRendered = false
            // Set outputFormatReceived based on whether we have reliable dimensions.
            // When SPS was successfully parsed, dimensions match the stream — no
            // resolution transition will occur, so INFO_OUTPUT_FORMAT_CHANGED may
            // not fire on some Qualcomm H.265 decoders. Waiting for it would block
            // rendering indefinitely, showing green (uninitialized surface).
            // Only gate on format change when using fallback dimensions.
            outputFormatReceived = (spsDims != null)
            decodeStartTimeMs = System.currentTimeMillis()
            renderingEnabled = false  // Don't render until first IDR is queued

            // Start output drain thread
            startDrainThread(codec!!)

            _decoderState.value = DecoderState.DECODING
            updateStats(activeDecoderName)

            // Replay cached IDR if one arrived while codec was configuring
            val cachedIdr = cachedIdrFrame
            if (cachedIdr != null) {
                Log.i(TAG, "Replaying cached IDR after codec configure (${cachedIdr.data.size} bytes)")
                cachedIdrFrame = null
                handleKeyframe(cachedIdr)
            }
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
            val candidates = CodecSelector.decoderCandidates(mimeType)
            if (candidates.isEmpty()) {
                Log.e(TAG, "No decoder available for $mimeType")
                DiagnosticLog.e("video", "No decoder available for $mimeType")
                _decoderState.value = DecoderState.ERROR
                return
            }

            Log.i(TAG, "Configuring codec direct: ${width}x${height} ($mimeType)")

            var lastError: Throwable? = null
            var configured = false
            outer@ for (decoderName in candidates) {
                for (attempt in 1..2) {
                    var mc: MediaCodec? = null
                    try {
                        val format = MediaFormat.createVideoFormat(mimeType, width, height)
                        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                        try { format.setInteger("priority", 0) } catch (_: Exception) {}

                        mc = MediaCodec.createByCodecName(decoderName)
                        mc.configure(format, surface, null, 0)
                        mc.start()
                        try { mc.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT) }
                        catch (_: Exception) {}
                        codec = mc
                        activeDecoderName = decoderName
                        configured = true
                        Log.i(TAG, "Codec configured direct: $decoderName ($mimeType, attempt=$attempt)")
                        DiagnosticLog.i("video", "Codec selected: $decoderName ($mimeType)")
                        break@outer
                    } catch (e: Throwable) {
                        lastError = e
                        Log.w(TAG, "Codec direct init failed ($decoderName attempt $attempt): ${e.message}")
                        try { mc?.reset() } catch (_: Throwable) {}
                        try { mc?.release() } catch (_: Throwable) {}
                    }
                }
            }

            if (!configured) {
                Log.e(TAG, "Failed to configure any decoder for $mimeType (direct)", lastError)
                DiagnosticLog.e("video", "All decoders failed for $mimeType: ${lastError?.message}")
                _decoderState.value = DecoderState.ERROR
                return
            }

            firstFrameRendered = false
            decodeStartTimeMs = System.currentTimeMillis()
            renderingEnabled = true  // VP9/AV1: no reference dependency, render immediately

            startDrainThread(codec!!)

            _decoderState.value = DecoderState.DECODING
            updateStats(activeDecoderName)
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
                            // Don't render until:
                            // 1. IDR received (renderingEnabled) — prevents green from stale P-frames
                            // 2. Output format received (outputFormatReceived) — prevents green from
                            //    codec resolution transition (configured 1920x1080 but video is 2560x1440)
                            val shouldRender = renderingEnabled && outputFormatReceived
                            if (!shouldRender) {
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
                            outputFormatReceived = true
                            // Use crop rect for actual video dimensions (KEY_WIDTH includes padding)
                            val cropLeft = format.getInteger("crop-left", 0)
                            val cropRight = format.getInteger("crop-right", -1)
                            val cropTop = format.getInteger("crop-top", 0)
                            val cropBottom = format.getInteger("crop-bottom", -1)
                            val w = if (cropRight >= 0) cropRight - cropLeft + 1
                                    else format.getInteger(MediaFormat.KEY_WIDTH, 0)
                            val h = if (cropBottom >= 0) cropBottom - cropTop + 1
                                    else format.getInteger(MediaFormat.KEY_HEIGHT, 0)
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
        synchronized(codecReleaseLock) {
            val current = codec ?: return  // already released; nothing to do
            stopDrainThread()
            try {
                current.stop()
            } catch (_: Exception) {}
            try {
                current.release()
            } catch (_: Exception) {}
            codecResetCount++
            DiagnosticLog.i("video", "Codec released (reset #$codecResetCount), renderGate -> false")
            codec = null
            activeDecoderName = null
            receivedIdr = false
            renderingEnabled = false
            awaitingFreshIdr = false
            awaitingFreshIdrSinceMs = 0L
            seedIdrTimeMs = 0
            lastQueuedPtsMs = -1
            consecutiveDrops = 0
        }
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

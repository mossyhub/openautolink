// SPDX-License-Identifier: GPL-3.0-only
// Narrow Android backend; CSD/NAL and PCM wire rules adapted from pinned DiPlay.
package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat as PlatformAudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.openautolink.carplaylab.media.CarPlayDisplayPolicy
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import com.openautolink.carplaylab.media.CarPlayDisplaySelection
import com.shilapi.xcertplay.airplay.*
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** H264/platform-decoded output and optional platform-encoded, permission-gated microphone. */
class AndroidMediaSink(
    context: Context,
    private val surface: Surface,
    private val display: CarPlayDisplaySelection = CarPlayDisplayPolicy.select(CarPlayDisplayPreferences()),
    private val onStatus: (String) -> Unit = {},
    private val onSurfaceLost: () -> Unit = {},
    private val telemetry: com.openautolink.carplaylab.media.ReceiverTelemetry = com.openautolink.carplaylab.media.ReceiverTelemetry(0),
    private val videoSelection: CarPlayVideoSelection = CarPlayVideoSelection(display.display.width,
        display.display.height,display.display.fps,display.decoderName),
    private val videoCodecFactory: (String) -> MediaCodec = MediaCodec::createByCodecName,
) : MediaSink, Closeable {
    private val closed = AtomicBoolean()
    init { AndroidMicrophoneSupport.initialize(context) }
    // One exact session/type/audioType/generation, including stalled platform retirees.
    private val microphones = MicrophoneOwnerSlot<MicrophoneStreamId, MicrophoneUplink>(
        { it.isRetired }, { it.isClosed })
    private val microphoneOwners = ConcurrentHashMap.newKeySet<MicrophoneUplink>()
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener({ change ->
            // This is OS focus, not a telephony transport claim.
            tracks.values.forEach { it.volume(if (change < 0) 0f else 1f) }
            report("media stage=audio-focus-change")
        }, Handler(Looper.getMainLooper())).build()
    private val tracks = ConcurrentHashMap<AudioStreamId, AudioOutputTrack>()
    private var focusHeld = false
    private var recovery: (() -> Unit)? = null
    private var diagnostic: ((String) -> Unit)? = null
    private val videoQueue = ArrayBlockingQueue<VideoJob>(24)
    private val rendered = AtomicLong()
    private val writtenBytes = AtomicLong()
    private val audioOwners = AtomicLong()
    private val videoRetired = AtomicBoolean()
    val isVideoRetired: Boolean get() = videoRetired.get()
    private val currentVideoCodec = java.util.concurrent.atomic.AtomicReference<MediaCodec?>()
    @Volatile private var announcedCodec = VideoCodec.H264
    private val videoThread = Thread(::runVideo, "lab-video").apply { isDaemon = true; start() }
    private sealed class VideoJob {
        class Config(val codec: VideoCodec, val bytes: ByteArray) : VideoJob()
        class Frame(val bytes: ByteArray) : VideoJob()
        object Reset : VideoJob()
    }
    private fun report(message: String) { runCatching { onStatus(message) } }
    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) { if (type == 110) recovery = handler }
    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) { if (type == 110) diagnostic = handler }
    @Synchronized override fun onVideoCodec(type: Int, codec: VideoCodec) {
        require(type == 110 && videoSelection.decoderName(codec) != null)
        if (!closed.get()) announcedCodec = codec
    }
    @Synchronized override fun onVideoConfig(type: Int, codecData: ByteArray) {
        if (closed.get() || type != 110) return
        val (actual, data) = ScreenCodec.detectConfig(codecData)
        require(actual == announcedCodec) { "Video codec/config mismatch" }
        videoQueue.clear(); videoQueue.offer(VideoJob.Config(actual,data.copyOf()))
    }
    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        if (closed.get() || type != 110) return
        if (!videoQueue.offer(VideoJob.Frame(naluBytes))) {
            videoQueue.clear(); videoQueue.offer(VideoJob.Reset)
            report("media stage=video-queue-overflow")
        }
    }
    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (type == 110 && !active) { videoQueue.clear(); videoQueue.offer(VideoJob.Reset) }
    }
    override fun supportsAudio(format: AudioFormat): Boolean = AndroidAudioDecoderSupport.supports(format)
    override fun supportsMicrophone(format: AudioFormat): Boolean = !closed.get() &&
        MicrophoneSupport.source(format.audioType) != null &&
        MicrophoneSupport.bits(format)?.let { it in AndroidMicrophoneSupport.capability.bits(format.audioType) } == true
    @Synchronized override fun onMicrophoneStarted(id: MicrophoneStreamId, config: MicrophoneConfig) {
        if (closed.get() || id.session.isClosed || id.type != 100 || id.audioType != config.audioType ||
            !AndroidMicrophoneSupport.permissionGranted()) { config.key.fill(0); return }
        microphoneOwners.removeIf { it.isRetired }
        val accepted = microphones.start(id) { MicrophoneUplink(config, ::report).apply {
            microphoneOwners.add(this); start()
        } }
        if (!accepted) { config.key.fill(0); report("media stage=microphone-owner-busy") }
    }
    @Synchronized override fun onMicrophoneStopped(id: MicrophoneStreamId) {
        microphones.stop(id)
    }
    @Synchronized override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        if (closed.get() || !supportsAudio(format)) return
        require(format.channels in 1..2)
        tracks.entries.removeIf { it.value.isRetired }
        tracks[id]?.let { old ->
            old.close()
            if (!old.isRetired) { report("media stage=audio-retirement-pending"); return }
            tracks.remove(id, old)
        }
        if (tracks.size >= 8) { report("media stage=audio-owner-limit"); return }
        if (!focusHeld) {
            focusHeld = manager?.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            report(if (focusHeld) "media stage=audio-focus-granted" else "media stage=audio-focus-denied")
        }
        telemetry.audioStreamStarted()
        val stream = audioOwners.incrementAndGet() // Local owner sequence, never a phone connection ID.
        val qualification = "stream=$stream type=${id.type} audioType=${format.audioType} codec=${format.codec}"
        report("media stage=audio-setup $qualification rate=${format.sampleRate} clock=${format.rtpClockRate} channels=${format.channels} spf=${format.samplesPerFrame}")
        tracks[id] = AudioOutputTrack(format, firstSample, writtenBytes, telemetry, ::report, qualification)
    }
    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        tracks[id]?.submit(rtp, sample)
    }
    @Synchronized override fun onAudioStopped(id: AudioStreamId) {
        tracks[id]?.let { old -> old.close(); if (old.isRetired) tracks.remove(id, old) }
        if (tracks.values.none { !it.isClosed }) { manager?.abandonAudioFocusRequest(focus); focusHeld = false }
    }
    private fun runVideo() {
        var codec: MediaCodec? = null
        var config: VideoJob.Config? = null
        var activeCodec = VideoCodec.H264
        var anchored = false
        var lastRecoveryNs = 0L
        fun release() { currentVideoCodec.set(null); runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null; anchored = false }
        fun recover() {
            anchored = false
            val now = System.nanoTime()
            if (now - lastRecoveryNs > 1_000_000_000L) { lastRecoveryNs = now; recovery?.invoke() }
        }
        fun configure(job: VideoJob.Config) {
            release()
            activeCodec = job.codec
            val fmt = CarPlayVideoQualification.mediaFormat(job.codec,job.bytes,videoSelection)
            if (closed.get()) return
            val candidate = videoCodecFactory(checkNotNull(videoSelection.decoderName(job.codec)))
            try {
                val caps = candidate.codecInfo.getCapabilitiesForType(CarPlayVideoQualification.mime(job.codec))
                require(CarPlayVideoQualification.supports(caps,fmt,videoSelection.fps)) { "Incoming format unsupported by exact decoder" }
                if (closed.get()) { candidate.release(); return }
                candidate.configure(fmt, surface, null, 0)
                candidate.setOnFrameRenderedListener({ _, _, nanoTime ->
                    if (!closed.get() && currentVideoCodec.get() === candidate) {
                        telemetry.frameRendered(nanoTime)
                        val count = rendered.incrementAndGet()
                        if (count == 1L) {
                            diagnostic?.invoke("first frame rendered")
                            report("media stage=first-frame-rendered count=1")
                        } else if (count % 300 == 0L) report("media stage=frames-rendered count=$count")
                    }
                }, Handler(Looper.getMainLooper()))
                if (closed.get()) { candidate.release(); return }
                candidate.start(); codec = candidate; currentVideoCodec.set(candidate)
                telemetry.decoderConfigured(candidate.name)
                report("media stage=${if(job.codec==VideoCodec.H264) "h264" else "h265"}-configured")
            } catch (failure: Exception) { runCatching { candidate.release() }; throw failure }
        }
        fun drain(current: MediaCodec) {
            val info = MediaCodec.BufferInfo()
            // Busy decoders must not monopolize this owner and starve replacement/retirement.
            repeat(32) {
                if (closed.get()) return
                val index = current.dequeueOutputBuffer(info, 0)
                if (index >= 0) current.releaseOutputBuffer(index, info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0)
                else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Allowlist dimensions only: outputFormat.toString() can contain codec data.
                    val output = current.outputFormat
                    val width = output.getInteger(MediaFormat.KEY_WIDTH)
                    val height = output.getInteger(MediaFormat.KEY_HEIGHT)
                    fun dimension(key: String, fallback: Int) = if (output.containsKey(key)) output.getInteger(key) else fallback
                    telemetry.outputFormat(width, height,
                        dimension("crop-right", width - 1) - dimension("crop-left", 0) + 1,
                        dimension("crop-bottom", height - 1) - dimension("crop-top", 0) + 1)
                    CarPlayDisplayPolicy.outputDiagnostic(display.display, width, height,
                        dimension("crop-left", 0), dimension("crop-top", 0),
                        dimension("crop-right", width - 1), dimension("crop-bottom", height - 1))
                        // Preserve the display/crop numeric allowlist, but label the incoming codec,
                        // not the policy's legacy AVC-only diagnostic default.
                        ?.let { report(it.replace("codec=H264 ","codec=${activeCodec.name} ")) }
                } else return
            }
        }
        try {
            while (!closed.get()) {
                if (!surface.isValid) { report("media stage=surface-lost"); onSurfaceLost(); return }
                try {
                    when (val job = videoQueue.poll(10, TimeUnit.MILLISECONDS)) {
                        is VideoJob.Config -> { config = job; configure(job); recover() }
                        is VideoJob.Reset -> { release(); recover() }
                        is VideoJob.Frame -> {
                            val bytes = MediaCodecSupport.toAnnexB(job.bytes)
                            val idr = MediaCodecSupport.isRandomAccess(bytes, activeCodec)
                            if (!anchored && !idr) { recover(); continue }
                            if (codec == null) config?.let { configure(it) }
                            val current = codec ?: continue
                            drain(current)
                            var index = current.dequeueInputBuffer(0)
                            val deadline = System.nanoTime() + 100_000_000L
                            while (index < 0 && !closed.get() && System.nanoTime() < deadline) {
                                drain(current); index = current.dequeueInputBuffer(5000)
                            }
                            if (index < 0) { release(); recover(); continue }
                            val input = checkNotNull(current.getInputBuffer(index)); input.clear()
                            if (bytes.isEmpty() || bytes.size > input.remaining()) { release(); recover(); continue }
                            input.put(bytes); current.queueInputBuffer(index, 0, bytes.size, System.nanoTime() / 1000, 0)
                            anchored = true
                        }
                        null -> Unit
                    }
                    codec?.let { drain(it) }
                } catch (_: Exception) { release(); report("media stage=video-decoder-failed"); recover() }
            }
        } catch (_: InterruptedException) { /* shutdown */ }
        finally { release(); videoRetired.set(true) }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) telemetry.retire()
        currentVideoCodec.set(null)
        videoThread.interrupt(); videoQueue.clear()
        if (Thread.currentThread() !== videoThread) {
            try { videoThread.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        if (!videoRetired.get()) report("media stage=video-retirement-pending")
        synchronized(this) {
            tracks.values.forEach { it.close() }
            tracks.entries.removeIf { it.value.isRetired }
            microphones.close()
            microphoneOwners.removeIf { it.isRetired }
            manager?.abandonAudioFocusRequest(focus); focusHeld = false
        }
        recovery = null; diagnostic = null
    }

    /** Exact retirement, not the bounded best-effort close join. Timed-out owners remain retryable. */
    fun awaitRetired(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val retired = synchronized(this) {
                tracks.entries.removeIf { it.value.isRetired }
                microphoneOwners.removeIf { it.isRetired }
                closed.get() && videoRetired.get() && !videoThread.isAlive &&
                    tracks.isEmpty() && microphoneOwners.isEmpty()
            }
            if (retired) return true
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            TimeUnit.NANOSECONDS.sleep(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(10)))
        }
    }
}

/** Codec create/configure/feed/drain/stop/release and AudioTrack writes share one bounded owner. */
private class AudioOutputTrack(
    private val format: AudioFormat,
    private val firstSample: Int,
    private val writtenBytes: AtomicLong,
    private val telemetry: com.openautolink.carplaylab.media.ReceiverTelemetry,
    private val report: (String) -> Unit,
    private val qualification: String,
) : Closeable {
    private val packets = AtomicLong()
    private val queueDrops = AtomicLong()
    private var decoderDrops = 0L
    private var streamWritten = 0L
    private var zeroWrites = 0L
    private var partialWrites = 0L
    private var writeErrors = 0L
    private var droppedBytes = 0L
    private var statsAtNs = System.nanoTime()
    private fun stats(ended: Boolean = false) {
        val now = System.nanoTime()
        if (!ended && now - statsAtNs < 5_000_000_000L) return
        statsAtNs = now
        val underruns = runCatching { track?.underrunCount }.getOrNull()
        val route = runCatching { track?.routedDevice?.type }.getOrNull()
        runCatching { report("media stage=audio-stats $qualification packets=${packets.get()} queue=${queue.size} " +
            "queueDrops=${queueDrops.get()} decoderDrops=$decoderDrops written=$streamWritten zero=$zeroWrites " +
            "partial=$partialWrites errors=$writeErrors droppedBytes=$droppedBytes underruns=$underruns route=$route ended=$ended") }
    }
    private data class Packet(val bytes: ByteArray, val sample: Int)
    private val closed = AtomicBoolean()
    private val retired = AtomicBoolean()
    private val queue = ArrayBlockingQueue<Packet>(64)
    @Volatile private var track: AudioTrack? = null
    @Volatile private var requestedVolume = 1f
    val isClosed: Boolean get() = closed.get()
    val isRetired: Boolean get() = retired.get() && !thread.isAlive
    private val thread = Thread(::run, "lab-audio-output").apply { isDaemon = true; start() }
    fun submit(rtp: ByteArray, sample: Int) {
        if (closed.get()) return
        val bytes = AudioOutputSupport.payload(format,rtp) ?: return
        packets.incrementAndGet()
        if (queue.offer(Packet(bytes,sample))) telemetry.audioPacketAccepted()
        else { queueDrops.incrementAndGet(); report("media stage=audio-queue-overflow") }
    }
    fun volume(value: Float) { requestedVolume = value }
    private fun run() {
        var current: AudioTrack? = null
        var codec: MediaCodec? = null
        fun releaseTrack() {
            track = null
            runCatching { current?.stop() }; runCatching { current?.release() }; current = null
        }
        fun createTrack(rate: Int, channels: Int, encoding: Int) {
            require(rate in 8000..48000 && channels in 1..2 && encoding == PlatformAudioFormat.ENCODING_PCM_16BIT)
            releaseTrack()
            val mask = if (channels == 1) PlatformAudioFormat.CHANNEL_OUT_MONO else PlatformAudioFormat.CHANNEL_OUT_STEREO
            val minimum = AudioTrack.getMinBufferSize(rate,mask,encoding)
            require(minimum > 0)
            val built = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                .setUsage(if (format.audioType == "default" && format.payloadType == 101)
                    AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(PlatformAudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(mask).setEncoding(encoding).build())
                .setBufferSizeInBytes(maxOf(minimum * 2,rate * channels * 2 / 10))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            current = built; track = built
            require(built.state == AudioTrack.STATE_INITIALIZED)
            report("media stage=audio-track $qualification rate=$rate channels=$channels capacity=${built.bufferSizeInFrames}")
            if (!closed.get()) { built.setVolume(requestedVolume); built.play() }
        }
        fun writePcm(pcm: ByteArray) {
            val built = checkNotNull(current)
            var offset = 0
            val deadline = System.nanoTime() + 250_000_000L
            while (offset < pcm.size && !closed.get()) {
                built.setVolume(requestedVolume)
                // Nonblocking writes allow the owner to observe retirement without cross-thread codec calls.
                val count = built.write(pcm,offset,pcm.size-offset,AudioTrack.WRITE_NON_BLOCKING)
                if (count < 0) writeErrors++
                check(count >= 0)
                if (count == 0) {
                    zeroWrites++
                    if (System.nanoTime() >= deadline) { droppedBytes += pcm.size - offset; report("media stage=audio-write-backpressure"); return }
                    Thread.sleep(2); continue
                }
                if (count < pcm.size - offset) partialWrites++
                streamWritten += count
                telemetry.audioWritten(count)
                offset += count
                val total = writtenBytes.getAndAdd(count.toLong())
                if (total == 0L || total / 1_048_576 != (total + count) / 1_048_576)
                    report("media stage=audiotrack-written bytes=${total + count}; human audibility unverified")
            }
        }
        fun drain(decoder: MediaCodec) {
            val info = MediaCodec.BufferInfo()
            // Bound each drain so shutdown and the next input always receive service.
            repeat(32) {
                if (closed.get()) return
                val index = decoder.dequeueOutputBuffer(info,0)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = decoder.outputFormat
                        createTrack(output.getInteger(MediaFormat.KEY_SAMPLE_RATE),output.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                            if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            else PlatformAudioFormat.ENCODING_PCM_16BIT)
                    }
                    index >= 0 -> try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            if (current == null) createTrack(format.sampleRate,format.channels,PlatformAudioFormat.ENCODING_PCM_16BIT)
                            val output = checkNotNull(decoder.getOutputBuffer(index))
                            require(info.offset >= 0 && info.size <= output.capacity()-info.offset)
                            output.position(info.offset); output.limit(info.offset+info.size)
                            val pcm = ByteArray(info.size); output.get(pcm); writePcm(pcm)
                        }
                    } finally { decoder.releaseOutputBuffer(index,false) }
                    else -> return
                }
            }
        }
        try {
            val config = AudioOutputSupport.configuration(format)
            if (config == null) createTrack(format.sampleRate,format.channels,PlatformAudioFormat.ENCODING_PCM_16BIT)
            else {
                if (closed.get()) return
                val decoder = MediaCodec.createByCodecName(checkNotNull(AndroidAudioDecoderSupport.decoderName(format)))
                // Assign immediately: configure/start failures still release the exact candidate in finally.
                codec = decoder
                decoder.configure(AndroidAudioDecoderSupport.mediaFormat(format,config),null,null,0)
                if (closed.get()) return
                decoder.start()
                report("media stage=audio-decoder-configured codec=${format.codec}")
            }
            while (!closed.get()) {
                val packet = queue.poll(10,TimeUnit.MILLISECONDS)
                val decoder = codec
                stats()
                if (packet != null) {
                    if (decoder == null) writePcm(packet.bytes)
                    else {
                        drain(decoder)
                        var index = decoder.dequeueInputBuffer(0)
                        val deadline = System.nanoTime() + 50_000_000L
                        while (index < 0 && !closed.get() && System.nanoTime() < deadline) {
                            drain(decoder); index = decoder.dequeueInputBuffer(5000)
                        }
                        if (closed.get()) break
                        if (index < 0) { decoderDrops++; report("media stage=audio-decoder-backpressure"); continue }
                        val input = checkNotNull(decoder.getInputBuffer(index)); input.clear()
                        require(packet.bytes.size <= input.remaining())
                        input.put(packet.bytes)
                        val delta = (packet.sample.toLong()-firstSample.toLong()) and 0xffff_ffffL
                        decoder.queueInputBuffer(index,0,packet.bytes.size,delta*1_000_000L/format.rtpClockRate,0)
                    }
                }
                decoder?.let { drain(it) }
            }
        } catch (_: InterruptedException) { /* owner retirement */
        } catch (_: Exception) { if (!closed.get()) report("media stage=audio-output-failed codec=${format.codec}")
        } finally {
            stats(ended = true)
            closed.set(true); queue.clear()
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            releaseTrack(); retired.set(true)
        }
    }
    override fun close() {
        closed.set(true); queue.clear(); thread.interrupt()
        if (Thread.currentThread() !== thread) {
            try { thread.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        // Platform create/configure can stall despite interrupt: keep this owner counted until finally.
        if (!retired.get()) report("media stage=audio-retirement-pending")
    }
}
